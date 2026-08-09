package com.fitback.backend.external.fashionclip;

public final class FashionClipSimilarity {

    private FashionClipSimilarity() {
    }

    public static double cosineSimilarity(double[] left, double[] right) {
        validate(left, right);
        double dotProduct = 0.0d;
        double leftNormSquared = 0.0d;
        double rightNormSquared = 0.0d;
        for (int index = 0; index < left.length; index++) {
            double leftValue = left[index];
            double rightValue = right[index];
            if (!Double.isFinite(leftValue) || !Double.isFinite(rightValue)) {
                throw new IllegalArgumentException("embedding values must be finite");
            }
            dotProduct += leftValue * rightValue;
            leftNormSquared += leftValue * leftValue;
            rightNormSquared += rightValue * rightValue;
        }
        if (leftNormSquared == 0.0d || rightNormSquared == 0.0d) {
            throw new IllegalArgumentException("zero vector is not supported");
        }
        return dotProduct / (Math.sqrt(leftNormSquared) * Math.sqrt(rightNormSquared));
    }

    private static void validate(double[] left, double[] right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("embeddings must not be null");
        }
        if (left.length == 0 || right.length == 0) {
            throw new IllegalArgumentException("embeddings must not be empty");
        }
        if (left.length != right.length) {
            throw new IllegalArgumentException("embedding dimensions must match");
        }
    }
}
