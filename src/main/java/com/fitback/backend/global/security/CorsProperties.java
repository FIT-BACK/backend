package com.fitback.backend.global.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
        List<String> allowedOrigins
) {

    public CorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("app.cors.allowed-origins must not be empty");
        }
        if (allowedOrigins.stream().anyMatch(origin -> origin == null || origin.isBlank())) {
            throw new IllegalArgumentException("app.cors.allowed-origins must not contain blank values");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
    }
}
