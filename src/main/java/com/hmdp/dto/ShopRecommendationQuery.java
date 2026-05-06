package com.hmdp.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ShopRecommendationQuery {
    private String keyword;
    private Integer typeId;
    private Long maxBudget;
    private String city;
    private String locationHint;
    private Double x;
    private Double y;
    private Double maxDistanceMeters;
    private Boolean couponOnly;
    private Integer limit;
    private String subcategory;
    private String qualityPreference;
    private Integer partySize;
    private List<String> excludedCategories = new ArrayList<>();
    private List<String> negativePreferences = new ArrayList<>();
}
