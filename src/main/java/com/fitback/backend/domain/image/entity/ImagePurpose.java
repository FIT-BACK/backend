package com.fitback.backend.domain.image.entity;

public enum ImagePurpose {
    PROFILE,
    ANALYSIS,
    LOOKBOOK;

    public boolean isAnalysis() {
        return this == ANALYSIS;
    }

    public boolean isLookbook() {
        return this == LOOKBOOK;
    }
}
