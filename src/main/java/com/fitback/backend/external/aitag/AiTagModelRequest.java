package com.fitback.backend.external.aitag;

import java.util.Map;

public record AiTagModelRequest(String prompt, Map<String, Object> jsonSchema) {

    public AiTagModelRequest {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        jsonSchema = Map.copyOf(jsonSchema);
    }
}
