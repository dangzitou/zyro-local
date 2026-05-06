package com.hmdp.config;

import lombok.Data;

@Data
public class BaiduMapProperties {

    /**
     * Whether Baidu Map geocoding is enabled for explicit location understanding.
     */
    private Boolean enabled = false;

    /**
     * Baidu Map Web API key.
     */
    private String ak;

    /**
     * Geocoding endpoint.
     */
    private String geocodingUrl = "https://api.map.baidu.com/geocoding/v3/";

    /**
     * Reverse geocoding endpoint.
     */
    private String reverseGeocodingUrl = "https://api.map.baidu.com/reverse_geocoding/v3/";

    /**
     * Place suggestion endpoint.
     */
    private String suggestionUrl = "https://api.map.baidu.com/place/v2/suggestion";

    /**
     * Place search endpoint.
     */
    private String placeSearchUrl = "https://api.map.baidu.com/place/v2/search";
}
