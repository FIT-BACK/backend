package com.fitback.backend.domain.product.service.model;

import java.net.URI;

final class ModelValidation {

    private ModelValidation() {
    }

    static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    static String validateNullableText(String value, String fieldName) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    static URI requireHttpUri(URI value, String fieldName) {
        if (value == null || !value.isAbsolute()) {
            throw new IllegalArgumentException(fieldName + " must be an absolute URI");
        }
        String scheme = value.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(fieldName + " must use http or https");
        }
        return value;
    }

    static URI validateNullableHttpUri(URI value, String fieldName) {
        return value == null ? null : requireHttpUri(value, fieldName);
    }
}
