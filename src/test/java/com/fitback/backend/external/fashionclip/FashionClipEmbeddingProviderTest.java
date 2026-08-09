package com.fitback.backend.external.fashionclip;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class FashionClipEmbeddingProviderTest {

    @Test
    void defaultBatchPreservesInputAndEmbeddingOrder() {
        FashionClipEmbeddingProvider provider = (bytes, contentType) ->
                new double[]{bytes[0]};
        List<FashionClipImageInput> inputs = List.of(
                new FashionClipImageInput(new byte[]{1}, "image/jpeg"),
                new FashionClipImageInput(new byte[]{2}, "image/png"));

        List<double[]> embeddings = provider.embedBatch(inputs);

        org.assertj.core.api.Assertions.assertThat(embeddings).extracting(embedding -> embedding[0])
                .containsExactly(1.0, 2.0);
    }

    @Test
    void rejectsInvalidBatchInputsAndResultsAtTheContractBoundary() {
        FashionClipEmbeddingProvider provider = (bytes, contentType) -> new double[]{1.0};
        assertThatThrownBy(() -> provider.embedBatch(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("embedding batch must not be null or empty");
        assertThatThrownBy(() -> provider.embedBatch(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("embedding batch must not be null or empty");
        assertThatThrownBy(() -> provider.embedBatch(java.util.Arrays.asList((FashionClipImageInput) null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("embedding batch must not contain null elements");
        assertThatThrownBy(() -> new FashionClipImageInput(new byte[0], "image/jpeg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("imageBytes must not be null or empty");
        assertThatThrownBy(() -> new FashionClipImageInput(new byte[]{1}, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("contentType must not be null or blank");
        assertThatThrownBy(() -> new FashionClipImageInput(new byte[]{1}, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("contentType must be image/jpeg, image/png, or image/webp");
        assertThatThrownBy(() -> FashionClipEmbeddingProvider.validateBatchResult(
                2, List.of(new double[]{1.0})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("embedding count must match image input count");
        assertThatThrownBy(() -> FashionClipEmbeddingProvider.validateBatchResult(
                1, java.util.Arrays.asList((double[]) null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("embedding batch result must not contain null elements");
    }
}
