package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.ai.rag.BaiduMapGeoService;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopRecommendationQuery;
import com.hmdp.dto.ShopRecommendationDTO;
import com.hmdp.ai.rag.RestaurantSemanticRetrievalService;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopRecommendationService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopSubCategoryService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@Service
public class ShopRecommendationServiceImpl implements IShopRecommendationService {

    private static final int MAX_CANDIDATES = 30;
    private static final int MAX_CANDIDATES_WITH_LOCATION = 200;
    private static final int MAX_GEO_CANDIDATES = 120;
    private static final int MIN_LOCATION_CANDIDATES = 12;
    private static final int ENRICHMENT_MULTIPLIER = 4;
    private static final int MIN_ENRICHMENT_CANDIDATES = 12;
    private static final int MAX_ENRICHMENT_CANDIDATES = 24;
    private static final double GEO_SEARCH_RADIUS_METERS = 8000D;
    private static final double NEARBY_DISTANCE_LIMIT_METERS = 50000D;

    @Resource
    private IShopService shopService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IBlogService blogService;

    @Resource
    private IShopSubCategoryService shopSubCategoryService;

    @Resource
    private RestaurantSemanticRetrievalService restaurantSemanticRetrievalService;

    @Resource
    private BaiduMapGeoService baiduMapGeoService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 推荐链路分成三步：
     * 1. 先做候选召回，尽量把“附近 + 类目 + 关键词”相关的店捞出来。
     * 2. 再做轻量粗排，避免对过多候选触发券/博客查询造成 N+1 压力。
     * 3. 最后只富化前排候选，产出可直接给 Agent 组织答案的推荐卡片。
     */
    @Override
    public List<ShopRecommendationDTO> recommendShops(ShopRecommendationQuery query) {
        if (query == null) {
            return Collections.emptyList();
        }
        return recommendShopsInternal(query);
    }

    @Override
    public List<ShopRecommendationDTO> recommendShops(String keyword, Integer typeId, Long maxBudget,
                                                      String city, String locationHint,
                                                      Double x, Double y, Boolean couponOnly, Integer limit) {
        ShopRecommendationQuery query = new ShopRecommendationQuery();
        query.setKeyword(keyword);
        query.setTypeId(typeId);
        query.setMaxBudget(maxBudget);
        query.setCity(city);
        query.setLocationHint(locationHint);
        query.setX(x);
        query.setY(y);
        query.setCouponOnly(couponOnly);
        query.setLimit(limit);
        return recommendShopsInternal(query);
    }

    private List<ShopRecommendationDTO> recommendShopsInternal(ShopRecommendationQuery query) {
        Double effectiveX = query.getX();
        Double effectiveY = query.getY();
        boolean explicitGeoResolved = false;
        if (StrUtil.isNotBlank(query.getLocationHint()) && (effectiveX == null || effectiveY == null)) {
            BaiduMapGeoService.GeoPoint geoPoint = baiduMapGeoService.geocode(query.getCity(), query.getLocationHint());
            if (geoPoint != null) {
                effectiveX = geoPoint.lng();
                effectiveY = geoPoint.lat();
                explicitGeoResolved = true;
            }
        }
        final Double finalX = effectiveX;
        final Double finalY = effectiveY;
        int resultLimit = query.getLimit() == null ? 5 : Math.max(1, Math.min(query.getLimit(), 10));
        boolean useLocation = finalX != null && finalY != null;
        double distanceLimitMeters = query.getMaxDistanceMeters() == null || query.getMaxDistanceMeters() <= 0D
                ? NEARBY_DISTANCE_LIMIT_METERS
                : Math.min(query.getMaxDistanceMeters(), NEARBY_DISTANCE_LIMIT_METERS);
        String normalizedKeyword = normalizeKeyword(buildEffectiveKeyword(query));
        Set<Long> categoryMatchedShopIds = loadMatchedSubCategoryShopIds(normalizedKeyword, query.getTypeId());
        Set<Long> excludedShopIds = loadExcludedCategoryShopIds(query.getExcludedCategories(), query.getTypeId());
        String cityFilter = explicitGeoResolved && useLocation ? null : query.getCity();
        String locationFilterHint = explicitGeoResolved && useLocation ? null : query.getLocationHint();
        log.info("recommendation_query keyword={}, typeId={}, budget={}, city={}, locationHint={}, effectiveX={}, effectiveY={}, explicitGeoResolved={}",
                normalizedKeyword, query.getTypeId(), query.getMaxBudget(), cityFilter, locationFilterHint, finalX, finalY, explicitGeoResolved);
        List<Shop> candidates = loadCandidates(normalizedKeyword, query.getTypeId(), query.getMaxBudget(), cityFilter, locationFilterHint, finalX, finalY, useLocation, categoryMatchedShopIds);
        if (candidates == null || candidates.isEmpty()) {
            log.info("recommendation_candidates total=0 after_recall");
            return Collections.emptyList();
        }
        if (useLocation) {
            candidates = prioritizeCandidatesByDistance(candidates, finalX, finalY, distanceLimitMeters);
            log.info("recommendation_candidates distance_prioritized={} radiusMeters={}", candidates.size(), distanceLimitMeters);
        }
        if (candidates == null || candidates.isEmpty()) {
            log.info("recommendation_candidates total=0 after_nearby_filter");
            return Collections.emptyList();
        }
        log.info("recommendation_candidates total={}", candidates.size());

        // 这里只做轻量过滤和粗排，避免所有候选都去查优惠券和博客。
        List<Shop> shortlisted = candidates.stream()
                .filter(shop -> matchesBudget(shop, query.getMaxBudget())
                        && matchesKeyword(shop, normalizedKeyword, categoryMatchedShopIds)
                        && !matchesExcludedCategories(shop, excludedShopIds, query.getExcludedCategories())
                        && !matchesNegativePreferences(shop, query.getNegativePreferences()))
                .sorted(Comparator.comparingDouble((Shop shop) -> baseCandidateScore(shop, normalizedKeyword, finalX, finalY, categoryMatchedShopIds)
                        + preferenceScore(shop, query)).reversed())
                .limit(resolveEnrichmentLimit(resultLimit, query.getCouponOnly()))
                .toList();
        log.info("recommendation_shortlist count={}", shortlisted.size());

        List<ShopRecommendationDTO> recommendations = new ArrayList<ShopRecommendationDTO>();
        for (Shop shop : shortlisted) {
            List<Voucher> vouchers = loadVouchers(shop.getId());
            if (Boolean.TRUE.equals(query.getCouponOnly()) && vouchers.isEmpty()) {
                continue;
            }
            if (matchesExcludedCategories(shop, excludedShopIds, query.getExcludedCategories())
                    || matchesNegativePreferences(shop, query.getNegativePreferences())) {
                continue;
            }
            List<Blog> blogs = loadShopBlogs(shop.getId());
            ShopRecommendationDTO dto = buildRecommendation(shop, vouchers, blogs, finalX, finalY, normalizedKeyword);
            if (useLocation && dto.getDistanceMeters() != null && dto.getDistanceMeters() > distanceLimitMeters) {
                log.debug("recommendation_drop shopId={} reason=distance_over_limit distanceMeters={}",
                        shop.getId(), dto.getDistanceMeters());
                continue;
            }
            dto.setRecommendationScore(dto.getRecommendationScore()
                    + categoryMatchScore(shop, normalizedKeyword, categoryMatchedShopIds)
                    + preferenceScore(shop, query));
            recommendations.add(dto);
        }

        recommendations.sort(Comparator.comparing(ShopRecommendationDTO::getRecommendationScore).reversed());
        log.info("recommendation_final count={}", recommendations.size());
        if (recommendations.size() > resultLimit) {
            return new ArrayList<ShopRecommendationDTO>(recommendations.subList(0, resultLimit));
        }
        return recommendations;
    }

    /**
     * 富化候选数量不跟召回量完全一致，而是按最终返回条数放大几倍。
     * 这样既能保留排序余量，也能控制数据库查询成本。
     */
    private long resolveEnrichmentLimit(int resultLimit, Boolean couponOnly) {
        long limit = Math.max((long) resultLimit * ENRICHMENT_MULTIPLIER, MIN_ENRICHMENT_CANDIDATES);
        if (Boolean.TRUE.equals(couponOnly)) {
            limit = Math.max(limit, 18L);
        }
        return Math.min(limit, MAX_ENRICHMENT_CANDIDATES);
    }

    /**
     * 候选召回优先级：
     * 1. 有定位时先走 Redis GEO，优先捞“真的在附近”的门店。
     * 2. GEO 不够时，再补数据库模糊查询结果。
     * 3. 关键词太窄时，最后再补一层类目级候选，避免完全无召回。
     */
    private List<Shop> loadCandidates(String keyword, Integer typeId, Long maxBudget, String city, String locationHint, Double x, Double y,
                                      boolean useLocation, Set<Long> categoryMatchedShopIds) {
        Map<Long, Shop> merged = new LinkedHashMap<Long, Shop>();
        if (useLocation && typeId != null) {
            List<Shop> nearbyCandidates = loadNearbyCandidates(typeId, x, y);
            log.info("recommendation_recall nearby_geo={}", nearbyCandidates.size());
            mergeCandidates(merged, nearbyCandidates);
        }
        int targetSize = useLocation ? MAX_CANDIDATES_WITH_LOCATION : MAX_CANDIDATES;
        if (useLocation) {
            List<Shop> coordinateCandidates = loadCoordinateDbCandidates(typeId, maxBudget, x, y, targetSize);
            log.info("recommendation_recall coordinate_db={}", coordinateCandidates.size());
            mergeCandidates(merged, coordinateCandidates);
        }
        if (merged.size() < targetSize && StrUtil.isNotBlank(keyword)) {
            List<Shop> semanticCandidates = loadSemanticCandidates(keyword, typeId, city, locationHint, x, y, targetSize);
            log.info("recommendation_recall semantic={}", semanticCandidates.size());
            mergeCandidates(merged, semanticCandidates);
        }
        if (merged.size() < (useLocation ? MIN_LOCATION_CANDIDATES : targetSize)) {
            List<Shop> primaryDbCandidates = loadPrimaryDbCandidates(keyword, typeId, maxBudget, city, locationHint, targetSize);
            log.info("recommendation_recall primary_db={}", primaryDbCandidates.size());
            mergeCandidates(merged, primaryDbCandidates);
        }
        if (merged.size() < targetSize && StrUtil.isNotBlank(keyword)) {
            List<Shop> subCategoryCandidates = loadSubCategoryCandidates(categoryMatchedShopIds, typeId, maxBudget, city, locationHint);
            log.info("recommendation_recall sub_category={}", subCategoryCandidates.size());
            mergeCandidates(merged, subCategoryCandidates);
        }
        if (merged.size() < targetSize && StrUtil.isNotBlank(keyword)) {
            List<Shop> supplementDbCandidates = loadSupplementDbCandidates(typeId, maxBudget, city, locationHint, targetSize);
            log.info("recommendation_recall supplement_db={}", supplementDbCandidates.size());
            mergeCandidates(merged, supplementDbCandidates);
        }
        log.info("recommendation_recall merged={}", merged.size());
        return new ArrayList<Shop>(merged.values());
    }

    /**
     * 用 LinkedHashMap 去重，既保留先召回结果的优先级，也避免同一门店被多次富化。
     */
    private void mergeCandidates(Map<Long, Shop> merged, List<Shop> candidates) {
        for (Shop shop : candidates) {
            if (shop != null && shop.getId() != null) {
                merged.putIfAbsent(shop.getId(), shop);
            }
        }
    }

    /**
     * Redis GEO 用来承接“附近”语义。
     * 返回后会把距离回填到 Shop 上，后面排序和回答都能直接复用。
     */
    private List<Shop> loadNearbyCandidates(Integer typeId, Double x, Double y) {
        if (typeId == null || x == null || y == null || stringRedisTemplate == null) {
            return Collections.emptyList();
        }
        try {
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                    SHOP_GEO_KEY + typeId,
                    GeoReference.fromCoordinate(x, y),
                    new Distance(GEO_SEARCH_RADIUS_METERS),
                    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(MAX_GEO_CANDIDATES)
            );
            if (results == null || results.getContent().isEmpty()) {
                return Collections.emptyList();
            }
            List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoHits = results.getContent();
            List<Long> ids = new ArrayList<Long>(geoHits.size());
            Map<Long, Double> distanceById = new LinkedHashMap<Long, Double>();
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> hit : geoHits) {
                Long shopId = Long.parseLong(hit.getContent().getName());
                ids.add(shopId);
                distanceById.put(shopId, hit.getDistance() == null ? null : hit.getDistance().getValue());
            }
            if (ids.isEmpty()) {
                return Collections.emptyList();
            }
            String orderedIds = StrUtil.join(",", ids);
            List<Shop> shops = shopService.query()
                    .in("id", ids)
                    .last("ORDER BY FIELD(id," + orderedIds + ")")
                    .list();
            for (Shop shop : shops) {
                shop.setDistance(distanceById.get(shop.getId()));
            }
            return shops;
        } catch (Exception e) {
            // Nearby GEO is an accelerator, not a hard dependency.
            // When Redis is unavailable, we still let semantic retrieval and DB recall continue.
            return Collections.emptyList();
        }
    }

    /**
     * 关键词主召回负责“精准一点”的命中，适合火锅、咖啡、烧烤这类明确意图。
     */
    private List<Shop> loadPrimaryDbCandidates(String keyword, Integer typeId, Long maxBudget, String city, String locationHint, int limit) {
        boolean genericRestaurantIntent = isGenericRestaurantKeyword(keyword);
        var query = shopService.query()
                .like(StrUtil.isNotBlank(keyword) && !genericRestaurantIntent, "name", keyword)
                .eq(typeId != null, "type_id", typeId)
                .le(maxBudget != null, "avg_price", maxBudget);
        applyLocationFilter(query, city, locationHint);
        return query.last("LIMIT " + limit).list();
    }

    /**
     * 餐厅语义召回：
     * 先从 MySQL 建好的餐厅向量索引里按语义召回，再通过阿里云 rerank 做精排。
     */
    private List<Shop> loadSemanticCandidates(String keyword, Integer typeId, String city, String locationHint, Double x, Double y, int limit) {
        if (restaurantSemanticRetrievalService == null || !restaurantSemanticRetrievalService.isReady()) {
            return Collections.emptyList();
        }
        List<RestaurantSemanticRetrievalService.SemanticRestaurantHit> hits =
                restaurantSemanticRetrievalService.search(keyword, typeId, city, locationHint, x, y, Math.max(limit, 5));
        if (hits.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> ids = hits.stream().map(RestaurantSemanticRetrievalService.SemanticRestaurantHit::shopId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String orderedIds = StrUtil.join(",", ids);
        return shopService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + orderedIds + ")")
                .list();
    }

    /**
     * 细分类候选召回。
     * 用户说“海鲜”“瑜伽”“美甲”时，不再依赖 Java 里写死关键词，而是查 MySQL 字典和关联表。
     */
    private Set<Long> loadMatchedSubCategoryShopIds(String keyword, Integer typeId) {
        if (shopSubCategoryService == null || StrUtil.isBlank(keyword)) {
            return Collections.emptySet();
        }
        List<Long> shopIds = shopSubCategoryService.findMatchedShopIds(keyword, typeId, MAX_CANDIDATES_WITH_LOCATION);
        if (shopIds == null || shopIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<Long>(shopIds);
    }

    /**
     * 细分类候选召回。
     * 用户说“海鲜”“瑜伽”“美甲”时，不再依赖 Java 里写死关键词，而是查 MySQL 字典和关联表。
     */
    private List<Shop> loadSubCategoryCandidates(Set<Long> shopIds, Integer typeId, Long maxBudget, String city, String locationHint) {
        if (shopIds == null || shopIds.isEmpty()) {
            return Collections.emptyList();
        }
        String orderedIds = StrUtil.join(",", shopIds);
        var query = shopService.query()
                .in("id", shopIds)
                .eq(typeId != null, "type_id", typeId)
                .le(maxBudget != null, "avg_price", maxBudget);
        applyLocationFilter(query, city, locationHint);
        return query.last("ORDER BY FIELD(id," + orderedIds + ")").list();
    }

    /**
     * 补召回不带关键词过滤，目的是在数据稀疏时兜底，不让“附近推荐”直接空掉。
     */
    private List<Shop> loadSupplementDbCandidates(Integer typeId, Long maxBudget, String city, String locationHint, int limit) {
        var query = shopService.query()
                .eq(typeId != null, "type_id", typeId)
                .le(maxBudget != null, "avg_price", maxBudget);
        applyLocationFilter(query, city, locationHint);
        return query.last("LIMIT " + limit).list();
    }

    /**
     * 当显式地点已经被解析成坐标时，直接走数据库坐标筛选做兜底。
     * 这样即便语义召回没有把“体育西附近”的店放进前几名，也不会完全空掉。
     */
    private List<Shop> loadCoordinateDbCandidates(Integer typeId, Long maxBudget, Double x, Double y, int limit) {
        if (x == null || y == null) {
            return Collections.emptyList();
        }
        List<Shop> shops = shopService.query()
                .eq(typeId != null, "type_id", typeId)
                .le(maxBudget != null, "avg_price", maxBudget)
                .list();
        return shops.stream()
                .peek(shop -> {
                    if (shop.getX() != null && shop.getY() != null) {
                        shop.setDistance(distanceMeters(x, y, shop.getX(), shop.getY()));
                    }
                })
                .filter(shop -> shop.getDistance() != null && shop.getDistance() <= NEARBY_DISTANCE_LIMIT_METERS)
                .sorted(Comparator.comparing(Shop::getDistance))
                .limit(limit)
                .toList();
    }

    /**
     * 用户明确说“附近”且我们已经拿到坐标时，先做一层硬距离过滤。
     * 这样可以避免全国候选因为评分高而挤进 shortlist，最后又在结果阶段被整体清空。
     */
    private List<Shop> prioritizeCandidatesByDistance(List<Shop> candidates, Double x, Double y, double radiusMeters) {
        if (candidates == null || candidates.isEmpty() || x == null || y == null) {
            return candidates == null ? Collections.emptyList() : candidates;
        }
        List<Shop> filtered = new ArrayList<Shop>(candidates.size());
        for (Shop shop : candidates) {
            if (shop == null) {
                continue;
            }
            Double distance = shop.getDistance();
            if (distance == null && shop.getX() != null && shop.getY() != null) {
                distance = distanceMeters(x, y, shop.getX(), shop.getY());
                shop.setDistance(distance);
            }
            if (distance != null && distance <= radiusMeters) {
                filtered.add(shop);
            }
        }
        filtered.sort(Comparator.comparing(Shop::getDistance, Comparator.nullsLast(Double::compareTo)));
        return filtered;
    }

    private List<Voucher> loadVouchers(Long shopId) {
        Result result = voucherService.queryVoucherOfShop(shopId);
        Object data = result.getData();
        if (!(data instanceof List)) {
            return Collections.emptyList();
        }
        List<?> rawList = (List<?>) data;
        List<Voucher> vouchers = new ArrayList<Voucher>(rawList.size());
        for (Object item : rawList) {
            vouchers.add(JSONUtil.toBean(JSONUtil.parseObj(item), Voucher.class));
        }
        return vouchers;
    }

    private List<Blog> loadShopBlogs(Long shopId) {
        return blogService.query()
                .eq("shop_id", shopId)
                .orderByDesc("liked")
                .orderByDesc("create_time")
                .last("LIMIT 3")
                .list();
    }

    /**
     * DTO 是给 Agent 用的“可解释推荐卡片”，不仅有基础字段，还带推荐理由和排序分。
     */
    private ShopRecommendationDTO buildRecommendation(Shop shop, List<Voucher> vouchers, List<Blog> blogs,
                                                      Double x, Double y, String keyword) {
        ShopRecommendationDTO dto = new ShopRecommendationDTO();
        dto.setShopId(shop.getId());
        dto.setName(shop.getName());
        dto.setArea(shop.getArea());
        dto.setAddress(shop.getAddress());
        dto.setAvgPrice(shop.getAvgPrice());
        dto.setScore(shop.getScore() == null ? null : shop.getScore() / 10.0);
        dto.setCouponCount(vouchers.size());
        dto.setCouponSummary(buildCouponSummary(vouchers));
        dto.setBlogSummary(buildBlogSummary(blogs));
        if (shop.getDistance() != null) {
            dto.setDistanceMeters(Math.round(shop.getDistance() * 10D) / 10D);
        } else if (x != null && y != null && shop.getX() != null && shop.getY() != null) {
            dto.setDistanceMeters(distanceMeters(x, y, shop.getX(), shop.getY()));
        }
        dto.setReasonTags(buildReasonTags(shop, dto, vouchers, blogs, keyword));
        dto.setRecommendationScore(calculateScore(shop, dto, blogs, keyword));
        return dto;
    }

    private String buildCouponSummary(List<Voucher> vouchers) {
        if (vouchers.isEmpty()) {
            return "No active coupons";
        }
        Voucher best = vouchers.stream()
                .min(Comparator.comparing(Voucher::getPayValue))
                .orElse(vouchers.get(0));
        return "Coupons=" + vouchers.size() + ", bestPrice=" + best.getPayValue();
    }

    private String buildBlogSummary(List<Blog> blogs) {
        if (blogs == null || blogs.isEmpty()) {
            return "No recent hot content";
        }
        Blog top = blogs.get(0);
        String title = StrUtil.blankToDefault(top.getTitle(), "shop review");
        return "Hot post " + title + ", likes=" + top.getLiked();
    }

    private List<String> buildReasonTags(Shop shop, ShopRecommendationDTO dto, List<Voucher> vouchers, List<Blog> blogs, String keyword) {
        List<String> tags = new ArrayList<String>();
        if (dto.getScore() != null && dto.getScore() >= 4.5D) {
            tags.add("high_rating");
        }
        if (dto.getDistanceMeters() != null && dto.getDistanceMeters() <= 1000D) {
            tags.add("nearby");
        }
        if (!vouchers.isEmpty()) {
            tags.add("has_coupon");
        }
        if (blogs != null && !blogs.isEmpty()) {
            tags.add("recently_hot");
        }
        if (dto.getAvgPrice() != null && dto.getAvgPrice() <= 80L) {
            tags.add("good_value");
        }
        if (textualMatchScore(shop, keyword) > 0D) {
            tags.add("keyword_match");
        }
        return tags;
    }

    /**
     * 最终排序是多信号叠加：
     * 评分、评论量、优惠、距离、内容新鲜度、关键词匹配都会参与打分。
     */
    private Double calculateScore(Shop shop, ShopRecommendationDTO dto, List<Blog> blogs, String keyword) {
        double ratingScore = shop.getScore() == null ? 0D : shop.getScore() / 10.0D;
        double commentScore = shop.getComments() == null ? 0D : Math.min(shop.getComments() / 200.0D, 1.5D);
        double couponScore = dto.getCouponCount() == null ? 0D : Math.min(dto.getCouponCount() * 0.2D, 1D);
        double distanceScore = 0D;
        if (dto.getDistanceMeters() != null) {
            distanceScore = Math.max(0D, 1.5D - dto.getDistanceMeters() / 2000.0D);
        }
        double keywordScore = textualMatchScore(shop, keyword);
        double freshnessScore = 0D;
        if (blogs != null && !blogs.isEmpty()) {
            Blog latest = blogs.stream()
                    .filter(blog -> blog.getCreateTime() != null)
                    .max(Comparator.comparing(Blog::getCreateTime))
                    .orElse(null);
            if (latest != null) {
                long days = ChronoUnit.DAYS.between(latest.getCreateTime(), LocalDateTime.now());
                freshnessScore = Math.max(0.2D, Math.exp(-0.03D * Math.max(days, 0)));
            }
            int likedSum = blogs.stream().map(Blog::getLiked).filter(v -> v != null).mapToInt(Integer::intValue).sum();
            freshnessScore += Math.min(likedSum / 200.0D, 1D);
        }
        return ratingScore + commentScore + couponScore + distanceScore + freshnessScore + keywordScore;
    }

    private boolean matchesBudget(Shop shop, Long maxBudget) {
        return maxBudget == null || shop.getAvgPrice() == null || shop.getAvgPrice() <= maxBudget;
    }

    /**
     * 粗排分数只依赖轻量字段，保证召回阶段足够快。
     */
    private double baseCandidateScore(Shop shop, String keyword, Double x, Double y, Set<Long> categoryMatchedShopIds) {
        double score = textualMatchScore(shop, keyword);
        score += categoryMatchScore(shop, keyword, categoryMatchedShopIds);
        score += shop.getScore() == null ? 0D : shop.getScore() / 10.0D;
        score += shop.getComments() == null ? 0D : Math.min(shop.getComments() / 300.0D, 1.2D);
        Double distance = shop.getDistance();
        if (distance == null && x != null && y != null && shop.getX() != null && shop.getY() != null) {
            distance = distanceMeters(x, y, shop.getX(), shop.getY());
        }
        if (distance != null) {
            score += Math.max(0D, 1.6D - distance / 1800.0D);
        }
        return score;
    }

    private boolean matchesKeyword(Shop shop, String keyword, Set<Long> categoryMatchedShopIds) {
        if (isGenericRestaurantKeyword(keyword)) {
            return true;
        }
        return StrUtil.isBlank(keyword)
                || textualMatchScore(shop, keyword) > 0D
                || categoryMatchScore(shop, keyword, categoryMatchedShopIds) > 0D;
    }

    /**
     * 关键词命中不只看店名，也看商圈和地址。
     * 这样“天河区咖啡”“枫叶路火锅”这类自然表达也能命中。
     */
    private double textualMatchScore(Shop shop, String keyword) {
        if (shop == null || StrUtil.isBlank(keyword)) {
            return 0D;
        }
        if (isGenericRestaurantKeyword(keyword)) {
            return 0D;
        }
        List<String> variants = buildKeywordVariants(keyword);
        double score = 0D;
        if (containsAnyVariant(shop.getName(), variants)) {
            score += 1.8D;
        }
        if (containsAnyVariant(shop.getArea(), variants)) {
            score += 0.8D;
        }
        if (containsAnyVariant(shop.getAddress(), variants)) {
            score += 0.6D;
        }
        String exactKeyword = keyword.toLowerCase(Locale.ROOT).trim();
        if (containsText(shop.getName(), exactKeyword)) {
            score += 0.8D;
        }
        return score;
    }

    /**
     * 把用户自然语言里的“推荐、店铺、门店”之类噪音词剥掉，再拆出品类词和地址词。
     */
    private List<String> buildKeywordVariants(String keyword) {
        Set<String> variants = new LinkedHashSet<String>();
        String normalized = keyword.toLowerCase(Locale.ROOT).trim();
        if (StrUtil.isBlank(normalized)) {
            return Collections.emptyList();
        }
        variants.add(normalized);
        String relaxed = normalized.replace("店铺", "")
                .replace("商家", "")
                .replace("门店", "")
                .replace("推荐", "")
                .replace("店", "")
                .trim();
        if (StrUtil.isNotBlank(relaxed)) {
            variants.add(relaxed);
        }
        String addressPart = relaxed;
        for (String hint : categoryHints(keyword)) {
            String lowerHint = hint.toLowerCase(Locale.ROOT);
            if (relaxed.contains(lowerHint)) {
                variants.add(lowerHint);
                addressPart = addressPart.replace(lowerHint, " ");
            }
        }
        addressPart = addressPart.trim();
        if (addressPart.length() >= 2) {
            variants.add(addressPart);
        }
        return new ArrayList<String>(variants);
    }

    private boolean containsAnyVariant(String source, List<String> variants) {
        if (StrUtil.isBlank(source) || variants == null || variants.isEmpty()) {
            return false;
        }
        for (String variant : variants) {
            if (containsText(source, variant)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsText(String source, String keyword) {
        return StrUtil.isNotBlank(source) && StrUtil.isNotBlank(keyword)
                && source.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /**
     * 当用户给的是“海鲜餐厅、约会餐厅”这类长句时，
     * 这里委托细分类服务做 DB 字典匹配，避免把具体品类长期写死在代码里。
     */
    private double categoryMatchScore(Shop shop, String keyword, Set<Long> categoryMatchedShopIds) {
        if (shop == null || StrUtil.isBlank(keyword)) {
            return 0D;
        }
        if (categoryMatchedShopIds != null && categoryMatchedShopIds.contains(shop.getId())) {
            return 1.6D;
        }
        if (shopSubCategoryService == null) {
            return 0D;
        }
        return shopSubCategoryService.categoryMatchScore(shop, keyword);
    }

    private List<String> categoryHints(String keyword) {
        if (shopSubCategoryService == null || StrUtil.isBlank(keyword)) {
            return Collections.emptyList();
        }
        return shopSubCategoryService.extractMatchedTerms(keyword);
    }

    private String buildEffectiveKeyword(ShopRecommendationQuery query) {
        List<String> parts = new ArrayList<String>();
        if (StrUtil.isNotBlank(query.getKeyword())) {
            parts.add(query.getKeyword());
        }
        if (StrUtil.isNotBlank(query.getSubcategory())
                && parts.stream().noneMatch(part -> part.contains(query.getSubcategory()))) {
            parts.add(query.getSubcategory());
        }
        if (StrUtil.isNotBlank(query.getQualityPreference())) {
            parts.add(query.getQualityPreference());
        }
        return String.join(" ", parts).trim();
    }

    private Set<Long> loadExcludedCategoryShopIds(List<String> excludedCategories, Integer typeId) {
        if (shopSubCategoryService == null || excludedCategories == null || excludedCategories.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> shopIds = new LinkedHashSet<Long>();
        for (String excludedCategory : excludedCategories) {
            if (StrUtil.isBlank(excludedCategory)) {
                continue;
            }
            shopIds.addAll(shopSubCategoryService.findMatchedShopIds(excludedCategory, typeId, MAX_CANDIDATES_WITH_LOCATION));
        }
        return shopIds;
    }

    private boolean matchesExcludedCategories(Shop shop, Set<Long> excludedShopIds, List<String> excludedCategories) {
        if (shop == null) {
            return false;
        }
        if (excludedShopIds != null && excludedShopIds.contains(shop.getId())) {
            return true;
        }
        if (shopSubCategoryService == null || excludedCategories == null || excludedCategories.isEmpty()) {
            return false;
        }
        for (String excludedCategory : excludedCategories) {
            if (shopSubCategoryService.categoryMatchScore(shop, excludedCategory) > 0D) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesNegativePreferences(Shop shop, List<String> negativePreferences) {
        if (shop == null || negativePreferences == null || negativePreferences.isEmpty()) {
            return false;
        }
        String text = normalizeKeyword(StrUtil.nullToEmpty(shop.getName()) + " "
                + StrUtil.nullToEmpty(shop.getArea()) + " "
                + StrUtil.nullToEmpty(shop.getAddress()));
        for (String negativePreference : negativePreferences) {
            if (StrUtil.isBlank(negativePreference)) {
                continue;
            }
            if ("太辣".equals(negativePreference) && (text.contains("辣") || text.contains("麻辣"))) {
                return true;
            }
        }
        return false;
    }

    /* legacy preference helpers kept for reference during semantic refactor
    private double preferenceScore(Shop shop, ShopRecommendationQuery query) {
        if (shop == null || query == null) {
            return 0D;
        }
        double score = 0D;
        if (StrUtil.isNotBlank(query.getSubcategory())) {
            score += shopSubCategoryService == null ? 0D : shopSubCategoryService.categoryMatchScore(shop, query.getSubcategory());
        }
        if (StrUtil.isNotBlank(query.getQualityPreference())) {
            String preference = query.getQualityPreference();
            if (preference.contains("性价比高") && shop.getAvgPrice() != null && shop.getAvgPrice() <= 80L) {
                score += 0.8D;
            }
            if (preference.contains("出餐快") && shopSubCategoryService != null
                    && shopSubCategoryService.categoryMatchScore(shop, "快餐") > 0D) {
                score += 1.2D;
            }
            if (preference.contains("清淡") && containsText(shop.getName(), "素")) {
                score += 0.6D;
            }
        }
        return score;
    }

    private String normalizeKeyword(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return "";
        }
        return keyword.replace("附近", "")
                .replace("周边", "")
                .replace("推荐", "")
                .replace("店铺", "")
                .replace("商家", "")
                .trim();
    }

    */

    private double preferenceScore(Shop shop, ShopRecommendationQuery query) {
        if (shop == null || query == null) {
            return 0D;
        }
        double score = 0D;
        if (StrUtil.isNotBlank(query.getSubcategory()) && shopSubCategoryService != null) {
            score += shopSubCategoryService.categoryMatchScore(shop, query.getSubcategory());
        }
        if (StrUtil.isNotBlank(query.getQualityPreference())) {
            String preference = query.getQualityPreference();
            if (preference.contains("性价比高") && shop.getAvgPrice() != null && shop.getAvgPrice() <= 80L) {
                score += 0.8D;
            }
            if (preference.contains("出餐快") && shopSubCategoryService != null
                    && shopSubCategoryService.categoryMatchScore(shop, "快餐") > 0D) {
                score += 1.2D;
            }
            if (preference.contains("清淡")
                    && (containsText(shop.getName(), "素") || containsText(shop.getName(), "清"))) {
                score += 0.6D;
            }
            if (preference.contains("适合约会") && shop.getScore() != null && shop.getScore() >= 45) {
                score += 0.5D;
            }
            if (preference.contains("高评分") && shop.getScore() != null && shop.getScore() >= 46) {
                score += 0.6D;
            }
        }
        return score;
    }

    private String normalizeKeyword(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return "";
        }
        return keyword.replace("附近", "")
                .replace("周边", "")
                .replace("推荐", "")
                .replace("店铺", "")
                .replace("商家", "")
                .replace("，", " ")
                .replace("。", " ")
                .replace("！", " ")
                .replace("？", " ")
                .replace(",", " ")
                .replace(".", " ")
                .replace("!", " ")
                .replace("?", " ")
                .trim();
    }

    private boolean isGenericRestaurantKeyword(String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return false;
        }
        String normalized = keyword.toLowerCase(Locale.ROOT).trim();
        return normalized.contains("餐厅")
                || normalized.contains("美食")
                || normalized.contains("吃饭")
                || normalized.contains("饭店");
    }

    private void applyLocationFilter(com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper<Shop> query,
                                     String city,
                                     String locationHint) {
        if (StrUtil.isBlank(city) && StrUtil.isBlank(locationHint)) {
            return;
        }
        if (StrUtil.isNotBlank(city)) {
            query.and(wrapper -> wrapper.like("area", city).or().like("address", city));
        }
        if (StrUtil.isNotBlank(locationHint)) {
            query.and(wrapper -> wrapper.like("area", locationHint).or().like("address", locationHint));
        }
    }

    private Double distanceMeters(Double userX, Double userY, Double shopX, Double shopY) {
        double earthRadius = 6371000D;
        double lat1 = Math.toRadians(userY);
        double lat2 = Math.toRadians(shopY);
        double deltaLat = Math.toRadians(shopY - userY);
        double deltaLng = Math.toRadians(shopX - userX);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return Math.round(earthRadius * c * 10D) / 10D;
    }
}
