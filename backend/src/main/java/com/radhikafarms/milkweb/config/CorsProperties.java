package com.radhikafarms.milkweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the list of frontend origins permitted to call the API cross-origin.
 *
 * <p>Bound from {@code milkweb.cors.allowed-origins}. The list is empty by default so
 * that a misconfigured deployment does not silently fall back to allowing every
 * origin; callers that need CORS must set the property explicitly.
 */
@Component
@ConfigurationProperties(prefix = "milkweb.cors")
public class CorsProperties {

    private final List<String> allowedOrigins = new ArrayList<>();

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins.clear();
        if (allowedOrigins != null) {
            this.allowedOrigins.addAll(allowedOrigins);
        }
    }
}