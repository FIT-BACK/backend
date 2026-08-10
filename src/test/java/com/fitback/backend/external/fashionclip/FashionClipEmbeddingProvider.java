package com.fitback.backend.external.fashionclip;

import java.util.ArrayList;
import java.util.List;

@FunctionalInterface
public interface FashionClipEmbeddingProvider {

    double[] embed(byte[] imageBytes, String contentType);

    /**
     * Embeds inputs in order: result index {@code i} corresponds to input index {@code i}.
     * Providers with native batching should override this method; the default preserves
     * compatibility for existing single-image implementations.
     */
    default List<double[]> embedBatch(List<FashionClipImageInput> inputs) {
        validateBatchInputs(inputs);
        List<double[]> embeddings = new ArrayList<>(inputs.size());
        for (FashionClipImageInput input : inputs) {
            embeddings.add(embed(input.imageBytes(), input.contentType()));
        }
        return validateBatchResult(inputs.size(), embeddings);
    }

    static void validateBatchInputs(List<FashionClipImageInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("embedding batch must not be null or empty");
        }
        if (inputs.stream().anyMatch(input -> input == null)) {
            throw new IllegalArgumentException("embedding batch must not contain null elements");
        }
    }

    static List<double[]> validateBatchResult(int expectedCount, List<double[]> embeddings) {
        if (embeddings == null) {
            throw new IllegalArgumentException("embedding batch result must not be null");
        }
        if (embeddings.size() != expectedCount) {
            throw new IllegalArgumentException("embedding count must match image input count");
        }
        if (embeddings.stream().anyMatch(embedding -> embedding == null)) {
            throw new IllegalArgumentException("embedding batch result must not contain null elements");
        }
        return List.copyOf(embeddings);
    }
}
