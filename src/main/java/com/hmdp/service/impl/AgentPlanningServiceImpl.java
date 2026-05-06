package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.ai.LocationTextParser;
import com.hmdp.ai.OpenAiCompatibleStreamBridge;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AgentExecutionPlan;
import com.hmdp.entity.ShopSubCategory;
import com.hmdp.service.IAgentPlanningService;
import com.hmdp.service.IShopSubCategoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AgentPlanningServiceImpl implements IAgentPlanningService {

    private static final String PLANNER_PROMPT = """
            You are the planner for Zyro's local-life agent.
            Do not answer the user directly.
            Only output one structured execution plan object that matches the target schema exactly.

            The plan must contain these fields only:
            - intent
            - useKnowledge
            - useTools
            - retrievalQuery
            - responseStyle
            - reasoningFocus
            - city
            - locationHint
            - nearby
            - category
            - subcategory
            - pricePreference
            - qualityPreference
            - partySize
            - budgetMax
            - excludedCategories
            - negativePreferences
            - preferredTools

            Rules:
            1. intent must be one of: recommendation, factual_lookup, social_discovery, general
            2. useKnowledge decides whether background knowledge retrieval is needed
            3. useTools decides whether business tools are needed
            4. retrievalQuery should be a short searchable query in Chinese
            5. responseStyle should usually be concise
            6. reasoningFocus should describe the main reasoning objective briefly
            7. city is the explicit city name when available, otherwise null
            8. locationHint is a business area / landmark / district hint when available
            9. nearby is true when the user expresses nearby intent
            10. category should be the positive broad category, such as 餐厅 / 海鲜 / 火锅 / 咖啡 / 酒吧
            11. subcategory should be the positive fine-grained category when inferable, otherwise null
            12. pricePreference should be one of cheap / moderate / premium when inferable
            13. qualityPreference should summarize positive preferences like 高评分 / 好吃 / 安静 / 适合约会 / 出餐快 / 清淡 / 性价比高
            14. partySize should be an integer when the user mentions人数, otherwise null
            15. budgetMax should be a numeric RMB budget when inferable, otherwise null
            16. excludedCategories should contain explicitly rejected categories, such as 火锅
            17. negativePreferences should contain explicitly rejected preferences, such as 太辣 / 太贵
            18. If the user says "不要火锅，想吃清淡的", then category/subcategory should represent the wanted direction, and 火锅 should go into excludedCategories instead of category.
            19. preferredTools can only contain:
                - get_current_user_location
                - search_shops
                - get_shop_detail
                - get_shop_coupons
                - get_hot_blogs
                - recommend_shops
                - recommend_nearby_shops
            20. Recommendation-style local-life questions should prefer recommend_shops.
            21. When the user clearly asks for nearby results and coordinates will matter, prefer recommend_nearby_shops so the model can choose a distance radius itself.
            22. Dynamic business facts such as shop details, prices, coupons, ratings and opening hours should prefer tools.
            23. Output only the structured plan object.
            """;

    private static final Pattern BUDGET_PATTERN =
            Pattern.compile("(?:人均|预算|不超过|控制在|价格)?\\s*(\\d{2,4})\\s*(?:元|块)?\\s*(?:以内|以下|之内)?");

    private static final String[] RECOMMENDATION_CUES = {
            "推荐", "附近", "周边", "一带", "适合", "吃什么", "喝什么", "去哪里",
            "有没有", "有啥", "便宜点", "不贵", "平价", "随便吃点", "约会", "聚餐"
    };

    private static final String[] FACT_LOOKUP_CUES = {
            "营业时间", "几点开门", "几点关门", "评分", "地址", "电话", "优惠", "优惠券", "折扣"
    };

    private static final String[] CURRENT_LOCATION_CUES = {
            "我现在地址", "我现在在哪", "我在哪", "我的位置", "当前位置", "当前地址", "我的地址"
    };

    private static final String[] FAST_FOOD_TERMS = {
            "快餐", "简餐", "轻食", "盖饭", "套餐", "汉堡", "炸鸡", "小吃"
    };

    private static final String[] COFFEE_TERMS = {
            "咖啡", "cafe", "coffee"
    };

    private static final String[] DRINK_TERMS = {
            "奶茶", "饮品", "茶饮", "果茶"
    };

    private static final String[] NEGATION_MARKERS = {
            "不要", "别", "不想吃", "不吃", "排除", "不要太", "别太"
    };

    private final AiProperties aiProperties;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final LocationTextParser locationTextParser;
    private final IShopSubCategoryService shopSubCategoryService;
    private final OpenAiCompatibleStreamBridge openAiCompatibleStreamBridge;

    @Autowired
    public AgentPlanningServiceImpl(AiProperties aiProperties,
                                    ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                                    LocationTextParser locationTextParser,
                                    ObjectProvider<IShopSubCategoryService> shopSubCategoryServiceProvider,
                                    ObjectProvider<OpenAiCompatibleStreamBridge> streamBridgeProvider) {
        this.aiProperties = aiProperties;
        this.chatClientBuilderProvider = chatClientBuilderProvider;
        this.locationTextParser = locationTextParser;
        this.shopSubCategoryService = shopSubCategoryServiceProvider.getIfAvailable();
        this.openAiCompatibleStreamBridge = streamBridgeProvider.getIfAvailable();
    }

    public AgentPlanningServiceImpl(AiProperties aiProperties,
                                    ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
                                    LocationTextParser locationTextParser) {
        this(aiProperties, chatClientBuilderProvider, locationTextParser, new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(IShopSubCategoryService.class), new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(OpenAiCompatibleStreamBridge.class));
    }

    public AgentPlanningServiceImpl(AiProperties aiProperties,
                                    ObjectProvider<ChatClient.Builder> chatClientBuilderProvider) {
        this(aiProperties, chatClientBuilderProvider, new LocationTextParser(null));
    }

    @Override
    public AgentExecutionPlan plan(String message) {
        if (StrUtil.isBlank(message)) {
            return fallbackPlan("");
        }
        if (!aiProperties.isEnabled() || !Boolean.TRUE.equals(aiProperties.getPlannerEnabled())) {
            return fallbackPlan(message);
        }
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            return fallbackPlan(message);
        }

        try {
            String content;
            if (openAiCompatibleStreamBridge != null && openAiCompatibleStreamBridge.available()) {
                content = openAiCompatibleStreamBridge.chat(PLANNER_PROMPT, message);
            } else {
                content = builder.clone()
                        .build()
                        .prompt()
                        .system(PLANNER_PROMPT)
                        .user(message)
                        .stream()
                        .content()
                        .reduce(new StringBuilder(), StringBuilder::append)
                        .map(StringBuilder::toString)
                        .block();
            }
            AgentExecutionPlan plan = parsePlan(content);
            return sanitize(plan, message);
        } catch (Exception e) {
            log.warn("Planning failed, fallback to heuristic plan", e);
            return fallbackPlan(message);
        }
    }

    private AgentExecutionPlan sanitize(AgentExecutionPlan plan, String message) {
        if (plan == null) {
            return fallbackPlan(message);
        }

        AgentExecutionPlan heuristic = fallbackPlan(message);
        plan.setIntent(normalizeIntent(plan.getIntent(), heuristic.getIntent()));
        plan.setUseKnowledge(plan.getUseKnowledge() != null ? plan.getUseKnowledge() : heuristic.getUseKnowledge());
        plan.setUseTools(plan.getUseTools() != null ? plan.getUseTools() : heuristic.getUseTools());
        plan.setRetrievalQuery(StrUtil.blankToDefault(plan.getRetrievalQuery(), heuristic.getRetrievalQuery()));
        plan.setResponseStyle(StrUtil.blankToDefault(plan.getResponseStyle(), "concise"));
        plan.setReasoningFocus(StrUtil.blankToDefault(plan.getReasoningFocus(), heuristic.getReasoningFocus()));
        plan.setCity(StrUtil.blankToDefault(plan.getCity(), heuristic.getCity()));
        plan.setLocationHint(StrUtil.blankToDefault(plan.getLocationHint(), heuristic.getLocationHint()));
        plan.setNearby(plan.getNearby() != null ? plan.getNearby() : heuristic.getNearby());
        plan.setCategory(mergeCategory(plan.getCategory(), heuristic.getCategory(), plan.getExcludedCategories()));
        plan.setSubcategory(StrUtil.blankToDefault(plan.getSubcategory(), heuristic.getSubcategory()));
        plan.setPricePreference(StrUtil.blankToDefault(plan.getPricePreference(), heuristic.getPricePreference()));
        plan.setQualityPreference(StrUtil.blankToDefault(plan.getQualityPreference(), heuristic.getQualityPreference()));
        plan.setPartySize(plan.getPartySize() != null ? plan.getPartySize() : heuristic.getPartySize());
        plan.setBudgetMax(plan.getBudgetMax() != null ? plan.getBudgetMax() : heuristic.getBudgetMax());
        plan.setExcludedCategories(mergeList(plan.getExcludedCategories(), heuristic.getExcludedCategories()));
        plan.setNegativePreferences(mergeList(plan.getNegativePreferences(), heuristic.getNegativePreferences()));
        plan.setPreferredTools(normalizePreferredTools(plan, heuristic));

        if (Boolean.TRUE.equals(plan.getNearby())
                && plan.getPreferredTools().stream().noneMatch("recommend_shops"::equals)) {
            plan.getPreferredTools().add(0, "recommend_shops");
        }
        if ("recommendation".equals(plan.getIntent())) {
            plan.setUseTools(Boolean.TRUE);
        }
        if (!Boolean.TRUE.equals(plan.getUseTools())) {
            plan.setPreferredTools(Collections.emptyList());
        }
        return plan;
    }

    private AgentExecutionPlan fallbackPlan(String message) {
        String normalized = normalize(message);
        LocationTextParser.ParsedLocation parsedLocation = locationTextParser.parse(message);
        MatchedCategory matchedCategory = detectMatchedCategory(message);
        List<String> excludedCategories = detectExcludedCategories(message);
        List<String> negativePreferences = detectNegativePreferences(message);

        AgentExecutionPlan plan = new AgentExecutionPlan();
        plan.setIntent(resolveIntent(normalized, parsedLocation, matchedCategory));
        plan.setUseKnowledge(resolveUseKnowledge(plan.getIntent(), normalized));
        plan.setUseTools(Boolean.TRUE);
        plan.setRetrievalQuery(buildRetrievalQuery(message, parsedLocation, matchedCategory, excludedCategories));
        plan.setResponseStyle("concise");
        plan.setReasoningFocus(resolveReasoningFocus(plan.getIntent(), matchedCategory));
        plan.setCity(parsedLocation.city());
        plan.setLocationHint(parsedLocation.locationHint());
        plan.setNearby(isNearbyIntent(normalized, parsedLocation));
        plan.setCategory(resolvePositiveCategory(matchedCategory, excludedCategories));
        plan.setSubcategory(resolveSubcategory(matchedCategory, excludedCategories));
        plan.setPricePreference(extractPricePreference(normalized));
        plan.setQualityPreference(extractQualityPreference(message, matchedCategory));
        plan.setPartySize(extractPartySize(normalized));
        plan.setBudgetMax(extractBudget(message));
        plan.setExcludedCategories(excludedCategories);
        plan.setNegativePreferences(negativePreferences);
        plan.setPreferredTools(resolvePreferredTools(plan));
        return plan;
    }

    private String resolveIntent(String normalized,
                                 LocationTextParser.ParsedLocation parsedLocation,
                                 MatchedCategory matchedCategory) {
        if (containsAny(normalized, "博客", "笔记", "探店", "热门", "review", "blog")) {
            return "social_discovery";
        }
        if (containsAny(normalized, FACT_LOOKUP_CUES)) {
            return "factual_lookup";
        }
        if (containsAny(normalized, RECOMMENDATION_CUES)) {
            return "recommendation";
        }
        boolean hasLocation = StrUtil.isNotBlank(parsedLocation.city()) || StrUtil.isNotBlank(parsedLocation.locationHint());
        boolean hasDiningSignal = matchedCategory.present()
                || containsAny(normalized, "吃饭", "馆子", "店", "餐厅", "咖啡", "奶茶")
                || extractBudget(normalized) != null
                || extractPartySize(normalized) != null
                || StrUtil.isNotBlank(extractQualityPreference(normalized, matchedCategory));
        return hasLocation && hasDiningSignal ? "recommendation" : "general";
    }

    private Boolean resolveUseKnowledge(String intent, String normalized) {
        if ("recommendation".equals(intent)) {
            return containsAny(normalized, "攻略", "为什么", "原理", "规则", "说明");
        }
        return Boolean.TRUE;
    }

    private String buildRetrievalQuery(String message,
                                       LocationTextParser.ParsedLocation parsedLocation,
                                       MatchedCategory matchedCategory,
                                       List<String> excludedCategories) {
        List<String> parts = new ArrayList<String>();
        if (StrUtil.isNotBlank(parsedLocation.city())) {
            parts.add(parsedLocation.city());
        }
        if (StrUtil.isNotBlank(parsedLocation.locationHint())) {
            parts.add(parsedLocation.locationHint());
        }
        if (matchedCategory.present()) {
            parts.add(matchedCategory.primaryTerm());
        }
        if (!excludedCategories.isEmpty()) {
            parts.add("排除" + String.join(" ", excludedCategories));
        }
        String compact = String.join(" ", parts).trim();
        return StrUtil.isNotBlank(compact) ? compact : message;
    }

    private String resolveReasoningFocus(String intent, MatchedCategory matchedCategory) {
        if ("recommendation".equals(intent)) {
            return "compare_candidates";
        }
        if ("factual_lookup".equals(intent)) {
            return "grounded_lookup";
        }
        if ("social_discovery".equals(intent)) {
            return "social_proof";
        }
        return "grounded_facts";
    }

    private String resolvePositiveCategory(MatchedCategory matchedCategory, List<String> excludedCategories) {
        if (!matchedCategory.present()) {
            return null;
        }
        String primary = matchedCategory.primaryTerm();
        return excludedCategories.contains(primary) ? "餐厅" : matchedCategory.broadCategory();
    }

    private String resolveSubcategory(MatchedCategory matchedCategory, List<String> excludedCategories) {
        if (!matchedCategory.present()) {
            return null;
        }
        return excludedCategories.contains(matchedCategory.primaryTerm()) ? null : matchedCategory.primaryTerm();
    }

    private String extractPricePreference(String normalized) {
        if (containsAny(normalized, "不贵", "便宜", "平价", "实惠", "学生党")) {
            return "cheap";
        }
        if (containsAny(normalized, "高端", "高级", "贵一点", "宴请")) {
            return "premium";
        }
        return null;
    }

    private String extractQualityPreference(String message, MatchedCategory matchedCategory) {
        List<String> tags = new ArrayList<String>();
        if (message.contains("好吃")) {
            tags.add("好吃");
        }
        if (message.contains("评价") || message.contains("评分")) {
            tags.add("高评分");
        }
        if (message.contains("环境")) {
            tags.add("环境好");
        }
        if (message.contains("安静")) {
            tags.add("安静");
        }
        if (message.contains("约会")) {
            tags.add("适合约会");
        }
        if (message.contains("一个人")) {
            tags.add("适合一个人");
        }
        if (containsAny(message, "快一点", "出餐快", "效率高")) {
            tags.add("出餐快");
        }
        if (containsAny(message, "性价比", "划算")) {
            tags.add("性价比高");
        }
        if (containsAny(message, "清淡", "不油腻")) {
            tags.add("清淡");
        }
        if (matchedCategory.present() && "小吃快餐".equals(matchedCategory.canonicalName())) {
            tags.add("出餐快");
        }
        return tags.isEmpty() ? null : String.join(",", new LinkedHashSet<String>(tags));
    }

    private Integer extractPartySize(String normalized) {
        if (containsAny(normalized, "两个人", "2个人", "二人")) {
            return 2;
        }
        if (containsAny(normalized, "三个人", "3个人", "三人")) {
            return 3;
        }
        if (containsAny(normalized, "一个人", "1个人", "单人")) {
            return 1;
        }
        return null;
    }

    private Long extractBudget(String message) {
        Matcher matcher = BUDGET_PATTERN.matcher(message);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        String normalized = normalize(message);
        if (containsAny(normalized, "不贵", "便宜", "平价", "实惠", "学生党")) {
            return 100L;
        }
        return null;
    }

    private boolean isNearbyIntent(String normalized, LocationTextParser.ParsedLocation parsedLocation) {
        return containsAny(normalized, "附近", "周边", "一带", "离", "近一点")
                || StrUtil.isNotBlank(parsedLocation.locationHint());
    }

    private List<String> resolvePreferredTools(AgentExecutionPlan plan) {
        boolean useCurrentUserLocationTool = shouldFetchCurrentUserLocation(plan);
        if ("recommendation".equals(plan.getIntent())) {
            List<String> tools = new ArrayList<String>();
            if (useCurrentUserLocationTool) {
                tools.add("get_current_user_location");
            }
            if (Boolean.TRUE.equals(plan.getNearby())) {
                tools.add("recommend_nearby_shops");
            }
            tools.add("recommend_shops");
            if (Boolean.TRUE.equals(plan.getUseKnowledge())) {
                tools.add("search_shops");
            }
            tools.add("get_shop_detail");
            if (containsAny(StrUtil.blankToDefault(plan.getRetrievalQuery(), ""), "券", "优惠", "折扣")) {
                tools.add("get_shop_coupons");
            }
            return tools;
        }
        if ("factual_lookup".equals(plan.getIntent())) {
            if (useCurrentUserLocationTool) {
                return new ArrayList<String>(List.of("get_current_user_location", "search_shops", "get_shop_detail", "get_shop_coupons"));
            }
            return new ArrayList<String>(List.of("search_shops", "get_shop_detail", "get_shop_coupons"));
        }
        if ("social_discovery".equals(plan.getIntent())) {
            return new ArrayList<String>(List.of("get_hot_blogs"));
        }
        return new ArrayList<String>(List.of("search_shops", "get_shop_detail"));
    }

    private List<String> mergeList(List<String> primary, List<String> fallback) {
        Set<String> merged = new LinkedHashSet<String>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (fallback != null) {
            merged.addAll(fallback);
        }
        return new ArrayList<String>(merged);
    }

    private String mergeCategory(String primary, String fallback, List<String> excludedCategories) {
        String chosen = StrUtil.blankToDefault(primary, fallback);
        if (StrUtil.isBlank(chosen)) {
            return null;
        }
        return excludedCategories != null && excludedCategories.contains(chosen) ? "餐厅" : chosen;
    }

    private List<String> normalizePreferredTools(AgentExecutionPlan plan, AgentExecutionPlan heuristic) {
        List<String> tools = plan.getPreferredTools() == null ? new ArrayList<String>() : new ArrayList<String>(plan.getPreferredTools());
        if (tools.isEmpty()) {
            tools.addAll(heuristic.getPreferredTools());
        }
        if ("recommendation".equals(plan.getIntent()) && Boolean.TRUE.equals(plan.getNearby()) && !tools.contains("recommend_nearby_shops")) {
            tools.add(0, "recommend_nearby_shops");
        }
        if ("recommendation".equals(plan.getIntent()) && !tools.contains("recommend_shops")) {
            tools.add(0, "recommend_shops");
        }
        if (shouldFetchCurrentUserLocation(plan) && !tools.contains("get_current_user_location")) {
            tools.add(0, "get_current_user_location");
        }
        return new ArrayList<String>(new LinkedHashSet<String>(tools));
    }

    private boolean shouldFetchCurrentUserLocation(AgentExecutionPlan plan) {
        if (plan == null) {
            return false;
        }
        boolean missingExplicitLocation = StrUtil.isBlank(plan.getLocationHint()) && StrUtil.isBlank(plan.getCity());
        if (Boolean.TRUE.equals(plan.getNearby()) && missingExplicitLocation) {
            return true;
        }
        return containsAny(StrUtil.blankToDefault(plan.getRetrievalQuery(), ""), CURRENT_LOCATION_CUES);
    }

    private String normalizeIntent(String primary, String fallback) {
        if (StrUtil.isBlank(primary)) {
            return fallback;
        }
        return switch (primary) {
            case "recommendation", "factual_lookup", "social_discovery", "general" -> primary;
            default -> fallback;
        };
    }

    private MatchedCategory detectMatchedCategory(String message) {
        if (shopSubCategoryService != null) {
            List<ShopSubCategory> categories = shopSubCategoryService.matchCategories(message, 1);
            for (ShopSubCategory category : categories) {
                if (!isNegatedCategory(message, category)) {
                    return new MatchedCategory("餐厅", category.getName(), canonicalCategoryTerm(category));
                }
            }
        }
        String normalized = normalize(message);
        if (containsAny(normalized, FAST_FOOD_TERMS)) {
            return new MatchedCategory("餐厅", "小吃快餐", "快餐");
        }
        if (containsAny(normalized, COFFEE_TERMS)) {
            return new MatchedCategory("餐厅", "咖啡馆", "咖啡");
        }
        if (containsAny(normalized, DRINK_TERMS)) {
            return new MatchedCategory("餐厅", "甜品饮品", "奶茶");
        }
        if (containsAny(normalized, "清淡")) {
            return new MatchedCategory("餐厅", "家常菜", "清淡");
        }
        return MatchedCategory.empty();
    }

    private List<String> detectExcludedCategories(String message) {
        Set<String> categories = new LinkedHashSet<String>();
        if (shopSubCategoryService != null) {
            for (ShopSubCategory category : shopSubCategoryService.matchCategories(message, 1)) {
                if (isNegatedCategory(message, category)) {
                    categories.add(canonicalCategoryTerm(category));
                }
            }
        }
        String normalized = normalize(message);
        if (containsNegated(normalized, "火锅")) {
            categories.add("火锅");
        }
        if (containsNegated(normalized, "咖啡")) {
            categories.add("咖啡");
        }
        if (containsNegated(normalized, "奶茶")) {
            categories.add("奶茶");
        }
        return new ArrayList<String>(categories);
    }

    private List<String> detectNegativePreferences(String message) {
        Set<String> preferences = new LinkedHashSet<String>();
        String normalized = normalize(message);
        if (containsAny(normalized, "别太贵", "不要太贵", "不想太贵")) {
            preferences.add("太贵");
        }
        if (containsAny(normalized, "不想吃辣", "不要辣", "别太辣")) {
            preferences.add("太辣");
        }
        if (containsAny(normalized, "不要太吵", "别太吵")) {
            preferences.add("太吵");
        }
        return new ArrayList<String>(preferences);
    }

    private boolean isNegatedCategory(String message, ShopSubCategory category) {
        for (String term : categoryTerms(category)) {
            if (containsNegated(message, term)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsNegated(String source, String term) {
        if (StrUtil.isBlank(source) || StrUtil.isBlank(term)) {
            return false;
        }
        int index = source.indexOf(term);
        if (index < 0) {
            return false;
        }
        int start = Math.max(0, index - 6);
        String prefix = source.substring(start, index);
        return containsAny(prefix, NEGATION_MARKERS);
    }

    private String canonicalCategoryTerm(ShopSubCategory category) {
        if (category == null) {
            return null;
        }
        String code = StrUtil.blankToDefault(category.getCode(), "");
        if ("fastfood".equalsIgnoreCase(code)) {
            return "快餐";
        }
        if ("coffee".equalsIgnoreCase(code)) {
            return "咖啡";
        }
        if ("dessert".equalsIgnoreCase(code)) {
            return "奶茶";
        }
        return category.getName();
    }

    private List<String> categoryTerms(ShopSubCategory category) {
        Set<String> terms = new LinkedHashSet<String>();
        if (category == null) {
            return Collections.emptyList();
        }
        terms.add(category.getName());
        if (StrUtil.isNotBlank(category.getAliases())) {
            for (String alias : category.getAliases().split(",")) {
                if (StrUtil.isNotBlank(alias)) {
                    terms.add(alias.trim());
                }
            }
        }
        return new ArrayList<String>(terms);
    }

    private AgentExecutionPlan parsePlan(String content) {
        if (StrUtil.isBlank(content)) {
            return null;
        }
        String json = extractJsonObject(content);
        if (StrUtil.isBlank(json)) {
            throw new IllegalStateException("Planner did not return JSON: " + content);
        }
        return JSONUtil.toBean(json, AgentExecutionPlan.class);
    }

    private String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return content.substring(start, end + 1);
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "").toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String source, String... candidates) {
        for (String candidate : candidates) {
            if (source.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private record MatchedCategory(String broadCategory, String canonicalName, String primaryTerm) {
        private static MatchedCategory empty() {
            return new MatchedCategory(null, null, null);
        }

        private boolean present() {
            return StrUtil.isNotBlank(primaryTerm);
        }
    }
}
