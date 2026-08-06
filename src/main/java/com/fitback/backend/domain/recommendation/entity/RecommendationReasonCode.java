package com.fitback.backend.domain.recommendation.entity;

public enum RecommendationReasonCode {
    FULL_ATTRIBUTE_MATCH,
    PARTIAL_ATTRIBUTE_MATCH,
    NO_ATTRIBUTE_MATCH,
    NO_SCORABLE_TAGS,
    HIGH_SIMILARITY;

    public static String requireValid(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        try {
            return valueOf(value).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported reasonCode: " + value, exception);
        }
    }
}
