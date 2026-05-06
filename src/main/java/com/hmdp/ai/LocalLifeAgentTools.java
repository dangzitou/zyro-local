package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopRecommendationQuery;
import com.hmdp.dto.ShopRecommendationDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.UserInfo;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.ai.rag.BaiduMapGeoService;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopRecommendationService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.UserHolder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class LocalLifeAgentTools {

    private static final int DEFAULT_LIMIT = 5;

    private final IShopService shopService;
    private final IVoucherService voucherService;
    private final IBlogService blogService;
    private final IShopRecommendationService shopRecommendationService;
    private final IUserInfoService userInfoService;
    private final BaiduMapGeoService baiduMapGeoService;
    private final AgentTraceContext traceContext;

    public LocalLifeAgentTools(IShopService shopService, IVoucherService voucherService,
                               IBlogService blogService, IShopRecommendationService shopRecommendationService,
                               IUserInfoService userInfoService, BaiduMapGeoService baiduMapGeoService,
                               AgentTraceContext traceContext) {
        this.shopService = shopService;
        this.voucherService = voucherService;
        this.blogService = blogService;
        this.shopRecommendationService = shopRecommendationService;
        this.userInfoService = userInfoService;
        this.baiduMapGeoService = baiduMapGeoService;
        this.traceContext = traceContext;
    }

    /**
     * 先给模型一个轻量候选列表，适合“先搜再追问详情”的场景。
     */
    @Tool(name = "search_shops", description = "Search shops by keyword and optional category, return concise shop cards.")
    public List<ShopCard> searchShops(
            @ToolParam(required = false, description = "Keyword such as 火锅, 咖啡, 烧烤") String keyword,
            @ToolParam(required = false, description = "Shop type id if the user already specified a category") Integer typeId,
            @ToolParam(required = false, description = "Page number starting from 1") Integer current) {
        int pageNo = current == null ? 1 : Math.max(1, current);
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(keyword), "name", keyword)
                .eq(typeId != null, "type_id", typeId)
                .page(new Page<>(pageNo, DEFAULT_LIMIT));
        List<ShopCard> result = page.getRecords().stream()
                .map(this::toShopCard)
                .collect(Collectors.toList());
        traceContext.record("search_shops(keyword=" + safe(keyword) + ", typeId=" + safe(typeId) + ", current=" + pageNo + ") -> " + result.size() + " hit(s)");
        return result;
    }

    /**
     * 单店详情工具只回答强事实字段，避免一次把无关数据都塞给模型。
     */
    @Tool(name = "get_shop_detail", description = "Fetch one shop detail for factual fields such as price, score, address and opening hours.")
    public ShopDetail getShopDetail(@ToolParam(description = "Shop id from previous search results") Long shopId) {
        Shop shop = shopService.getById(shopId);
        ShopDetail detail = shop == null ? null : new ShopDetail(
                shop.getId(),
                shop.getName(),
                shop.getArea(),
                shop.getAddress(),
                shop.getAvgPrice(),
                normalizeScore(shop.getScore()),
                shop.getComments(),
                shop.getOpenHours()
        );
        traceContext.record("get_shop_detail(shopId=" + shopId + ") -> " + (detail == null ? "not_found" : "ok"));
        return detail;
    }

    /**
     * 优惠券工具承接“有没有券、哪张更划算”这类动态业务问题。
     */
    @Tool(name = "get_shop_coupons", description = "Fetch active coupons of a shop. Useful when the user asks about deals or discounts.")
    public List<CouponCard> getShopCoupons(@ToolParam(description = "Shop id from previous search or recommendation results") Long shopId) {
        List<CouponCard> result = loadVouchers(shopId).stream()
                .sorted(Comparator.comparing(Voucher::getPayValue, Comparator.nullsLast(Long::compareTo)))
                .limit(DEFAULT_LIMIT)
                .map(voucher -> new CouponCard(
                        voucher.getId(),
                        voucher.getTitle(),
                        voucher.getSubTitle(),
                        voucher.getPayValue(),
                        voucher.getActualValue(),
                        voucher.getRules()
                ))
                .collect(Collectors.toList());
        traceContext.record("get_shop_coupons(shopId=" + shopId + ") -> " + result.size() + " coupon(s)");
        return result;
    }

    /**
     * 博客工具提供的是热度和口碑信号，不属于知识库里的静态事实。
     */
    @Tool(name = "get_hot_blogs", description = "Fetch recent hot review blogs for trend and social proof signals.")
    public List<BlogCard> getHotBlogs(@ToolParam(required = false, description = "Number of blog cards to return, default 5") Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, 10));
        List<BlogCard> result = blogService.query()
                .orderByDesc("liked")
                .orderByDesc("create_time")
                .last("LIMIT " + size)
                .list()
                .stream()
                .map(blog -> new BlogCard(
                        blog.getId(),
                        StrUtil.blankToDefault(blog.getTitle(), "探店笔记"),
                        abbreviate(blog.getContent(), 120),
                        blog.getLiked(),
                        blog.getShopId()
                ))
                .collect(Collectors.toList());
        traceContext.record("get_hot_blogs(limit=" + size + ") -> " + result.size() + " blog(s)");
        return result;
    }

    /**
     * Nearby intent often depends on the current logged-in user's saved profile location.
     * This tool gives the model one explicit way to fetch that context before recommending shops.
     */
    @Tool(name = "get_current_user_location", description = "Fetch the current logged-in user's saved city, address and coordinates for nearby recommendations.")
    public UserLocationCard getCurrentUserLocation() {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null || currentUser.getId() == null) {
            traceContext.record("get_current_user_location() -> unauthenticated");
            return new UserLocationCard(false, null, null, null, null, "unauthenticated");
        }
        UserInfo userInfo = userInfoService.getById(currentUser.getId());
        if (userInfo == null) {
            traceContext.record("get_current_user_location(userId=" + currentUser.getId() + ") -> missing_profile");
            return new UserLocationCard(false, null, null, null, null, "missing_profile");
        }
        Double longitude = userInfo.getLocationX();
        Double latitude = userInfo.getLocationY();
        if ((longitude == null || latitude == null)
                && baiduMapGeoService != null
                && StrUtil.isNotBlank(userInfo.getAddress())) {
            BaiduMapGeoService.GeoPoint point = baiduMapGeoService.geocodeAddress(userInfo.getCity(), userInfo.getAddress());
            if (point != null) {
                longitude = point.lng();
                latitude = point.lat();
                userInfo.setLocationX(longitude);
                userInfo.setLocationY(latitude);
                userInfoService.updateById(userInfo);
            }
        }
        boolean available = StrUtil.isNotBlank(userInfo.getAddress()) && longitude != null && latitude != null;
        traceContext.record("get_current_user_location(userId=" + currentUser.getId() + ") -> available=" + available
                + ", city=" + safe(userInfo.getCity()) + ", address=" + safe(userInfo.getAddress())
                + ", longitude=" + safe(longitude) + ", latitude=" + safe(latitude));
        return new UserLocationCard(
                available,
                userInfo.getCity(),
                userInfo.getAddress(),
                longitude,
                latitude,
                available ? "ok" : "incomplete_profile"
        );
    }

    /**
     * 推荐工具直接走业务排序逻辑，把“推荐权”收在后端，而不是交给模型主观比较。
     */
    @Tool(name = "recommend_shops", description = "Recommend shops with business-side ranking based on keyword, budget, location and coupon preference.")
    public List<ShopRecommendationDTO> recommendShops(
            String keyword,
            Integer typeId,
            Long maxBudget,
            Double x,
            Double y,
            Boolean couponOnly,
            Integer limit) {
        return recommendShops(keyword, typeId, maxBudget, null, null, x, y, couponOnly, limit);
    }

    @Tool(name = "recommend_shops_v2", description = "Recommend shops with business-side ranking based on keyword, budget, explicit city/location and coupon preference.")
    public List<ShopRecommendationDTO> recommendShops(
            @ToolParam(required = false, description = "Keyword such as 火锅, 咖啡, 烧烤") String keyword,
            @ToolParam(required = false, description = "Shop type id if available") Integer typeId,
            @ToolParam(required = false, description = "Maximum budget in RMB") Long maxBudget,
            @ToolParam(required = false, description = "Explicit city mentioned by user") String city,
            @ToolParam(required = false, description = "Explicit商圈/地标/区域 hint mentioned by user") String locationHint,
            @ToolParam(required = false, description = "Longitude of the user") Double x,
            @ToolParam(required = false, description = "Latitude of the user") Double y,
            @ToolParam(required = false, description = "Whether only shops with coupons are acceptable") Boolean couponOnly,
            @ToolParam(required = false, description = "Number of recommendation items to return, default 5") Integer limit) {
        List<ShopRecommendationDTO> result = shopRecommendationService.recommendShops(keyword, typeId, maxBudget, city, locationHint, x, y, couponOnly, limit);
        traceContext.record("recommend_shops(keyword=" + safe(keyword) + ", typeId=" + safe(typeId) + ", budget=" + safe(maxBudget)
                + ", city=" + safe(city) + ", locationHint=" + safe(locationHint)
                + ", couponOnly=" + safe(couponOnly) + ", limit=" + safe(limit) + ") -> " + result.size() + " recommendation(s)");
        return result;
    }

    @Tool(name = "recommend_nearby_shops", description = "Recommend nearby shops with an explicit max distance radius chosen by the model.")
    public List<ShopRecommendationDTO> recommendNearbyShops(
            @ToolParam(required = false, description = "Keyword such as 火锅, 咖啡, 烧烤") String keyword,
            @ToolParam(required = false, description = "Shop type id if available") Integer typeId,
            @ToolParam(required = false, description = "Maximum budget in RMB") Long maxBudget,
            @ToolParam(required = false, description = "Explicit city mentioned by user") String city,
            @ToolParam(required = false, description = "Explicit商圈/地标/区域 hint mentioned by user") String locationHint,
            @ToolParam(description = "Longitude of the user") Double x,
            @ToolParam(description = "Latitude of the user") Double y,
            @ToolParam(required = false, description = "Maximum nearby radius in meters, such as 500, 2000, 5000, 10000") Double maxDistanceMeters,
            @ToolParam(required = false, description = "Whether only shops with coupons are acceptable") Boolean couponOnly,
            @ToolParam(required = false, description = "Number of recommendation items to return, default 5") Integer limit) {
        ShopRecommendationQuery query = new ShopRecommendationQuery();
        query.setKeyword(keyword);
        query.setTypeId(typeId);
        query.setMaxBudget(maxBudget);
        query.setCity(city);
        query.setLocationHint(locationHint);
        query.setX(x);
        query.setY(y);
        query.setMaxDistanceMeters(maxDistanceMeters);
        query.setCouponOnly(couponOnly);
        query.setLimit(limit);
        List<ShopRecommendationDTO> result = shopRecommendationService.recommendShops(query);
        traceContext.record("recommend_nearby_shops(keyword=" + safe(keyword)
                + ", typeId=" + safe(typeId)
                + ", budget=" + safe(maxBudget)
                + ", city=" + safe(city)
                + ", locationHint=" + safe(locationHint)
                + ", x=" + safe(x)
                + ", y=" + safe(y)
                + ", maxDistanceMeters=" + safe(maxDistanceMeters)
                + ", couponOnly=" + safe(couponOnly)
                + ", limit=" + safe(limit) + ") -> " + result.size() + " recommendation(s)");
        return result;
    }

    public List<ShopRecommendationDTO> recommendShops(ShopRecommendationQuery query) {
        List<ShopRecommendationDTO> result = shopRecommendationService.recommendShops(query);
        traceContext.record("recommend_shops(keyword=" + safe(query.getKeyword())
                + ", typeId=" + safe(query.getTypeId())
                + ", budget=" + safe(query.getMaxBudget())
                + ", city=" + safe(query.getCity())
                + ", locationHint=" + safe(query.getLocationHint())
                + ", maxDistanceMeters=" + safe(query.getMaxDistanceMeters())
                + ", subcategory=" + safe(query.getSubcategory())
                + ", excludedCategories=" + safe(query.getExcludedCategories())
                + ", negativePreferences=" + safe(query.getNegativePreferences())
                + ", couponOnly=" + safe(query.getCouponOnly())
                + ", limit=" + safe(query.getLimit()) + ") -> " + result.size() + " recommendation(s)");
        return result;
    }

    /**
     * 统一把 Result 包装拆成 Voucher 领域对象，方便工具层后续复用。
     */
    private List<Voucher> loadVouchers(Long shopId) {
        Result result = voucherService.queryVoucherOfShop(shopId);
        Object data = result.getData();
        if (!(data instanceof List<?> rawList)) {
            return Collections.emptyList();
        }
        List<Voucher> vouchers = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (item instanceof Voucher voucher) {
                vouchers.add(voucher);
                continue;
            }
            vouchers.add(JSONUtil.toBean(JSONUtil.parseObj(item), Voucher.class));
        }
        return vouchers;
    }

    private ShopCard toShopCard(Shop shop) {
        return new ShopCard(
                shop.getId(),
                shop.getName(),
                shop.getArea(),
                shop.getAddress(),
                shop.getAvgPrice(),
                normalizeScore(shop.getScore()),
                shop.getOpenHours()
        );
    }

    private Double normalizeScore(Integer score) {
        return score == null ? null : score / 10.0D;
    }

    private String safe(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    public record ShopCard(Long shopId, String name, String area, String address, Long avgPrice,
                           Double score, String openHours) {
    }

    public record ShopDetail(Long shopId, String name, String area, String address, Long avgPrice,
                             Double score, Integer comments, String openHours) {
    }

    public record CouponCard(Long voucherId, String title, String subTitle, Long payValue,
                             Long actualValue, String rules) {
    }

    public record BlogCard(Long blogId, String title, String snippet, Integer liked, Long shopId) {
    }

    public record UserLocationCard(Boolean available,
                                   String city,
                                   String address,
                                   Double longitude,
                                   Double latitude,
                                   String status) {
    }
}
