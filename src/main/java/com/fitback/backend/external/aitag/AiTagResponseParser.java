package com.fitback.backend.external.aitag;

import com.fitback.backend.domain.tag.entity.TagType;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class AiTagResponseParser {

    private final ObjectMapper objectMapper;

    public AiTagResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AiTagModelOutput parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode garments = root.path("garments");
            if (!garments.isArray()) {
                throw new IllegalArgumentException("garments must be an array");
            }
            List<AiTagGarment> results = new ArrayList<>();
            for (JsonNode garment : garments) {
                results.add(new AiTagGarment(
                        GarmentPiece.valueOf(garment.path("piece").asText()),
                        predictions(garment.path("canonicalTags")),
                        suggestions(garment.path("suggestedTags"))
                ));
            }
            return new AiTagModelOutput(results);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid AI tag response", exception);
        }
    }

    private List<AiTagPrediction> predictions(JsonNode tags) {
        if (!tags.isArray()) {
            throw new IllegalArgumentException("canonicalTags must be an array");
        }
        List<AiTagPrediction> predictions = new ArrayList<>();
        for (JsonNode tag : tags) {
            predictions.add(new AiTagPrediction(
                        TagType.valueOf(tag.path("type").asText()),
                        tag.path("name").asText()
            ));
        }
        return predictions;
    }

    private List<AiTagSuggestion> suggestions(JsonNode tags) {
        if (!tags.isArray()) {
            throw new IllegalArgumentException("suggestedTags must be an array");
        }
        List<AiTagSuggestion> suggestions = new ArrayList<>();
        for (JsonNode tag : tags) {
            suggestions.add(new AiTagSuggestion(
                        TagType.valueOf(tag.path("type").asText()),
                        tag.path("name").asText(),
                        tag.path("confidence").asDouble(),
                        tag.path("evidence").asText()
            ));
        }
        return suggestions;
    }
}
