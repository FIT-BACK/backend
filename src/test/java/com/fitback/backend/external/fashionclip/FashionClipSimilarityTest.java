package com.fitback.backend.external.fashionclip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FashionClipSimilarityTest {

    @Test
    void returnsOneForIdenticalVectors() {
        assertThat(FashionClipSimilarity.cosineSimilarity(new double[]{1.0, 2.0}, new double[]{1.0, 2.0}))
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1.0e-12));
    }

    @Test
    void returnsZeroForOrthogonalVectors() {
        assertThat(FashionClipSimilarity.cosineSimilarity(new double[]{1.0, 0.0}, new double[]{0.0, 1.0}))
                .isEqualTo(0.0);
    }

    @Test
    void returnsMinusOneForOppositeVectors() {
        assertThat(FashionClipSimilarity.cosineSimilarity(new double[]{1.0, 2.0}, new double[]{-1.0, -2.0}))
                .isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1.0e-12));
    }

    @Test
    void rejectsDifferentDimensions() {
        assertThatThrownBy(() -> FashionClipSimilarity.cosineSimilarity(
                new double[]{1.0}, new double[]{1.0, 2.0}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("embedding dimensions must match");
    }

    @Test
    void rejectsZeroVector() {
        assertThatThrownBy(() -> FashionClipSimilarity.cosineSimilarity(
                new double[]{0.0, 0.0}, new double[]{1.0, 0.0}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("zero vector is not supported");
    }

    @Test
    void rejectsNullAndAbnormalInputs() {
        assertThatThrownBy(() -> FashionClipSimilarity.cosineSimilarity(null, new double[]{1.0}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("embeddings must not be null");
        assertThatThrownBy(() -> FashionClipSimilarity.cosineSimilarity(new double[0], new double[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("embeddings must not be empty");
        assertThatThrownBy(() -> FashionClipSimilarity.cosineSimilarity(
                new double[]{Double.NaN}, new double[]{1.0}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("embedding values must be finite");
    }

}
