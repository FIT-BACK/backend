package com.fitback.backend.domain.image.entity;

public enum ImagePurpose {
    ANALYSIS_ORIGINAL,
    LOOKBOOK_ORIGINAL,
    LOOKBOOK_MATCHED,
    PROFILE,
    ANALYSIS,
    LOOKBOOK;

    public boolean isAnalysis() {
        return this == ANALYSIS_ORIGINAL || this == ANALYSIS;
    }

    public boolean isLookbook() {
        return this == LOOKBOOK_ORIGINAL || this == LOOKBOOK_MATCHED || this == LOOKBOOK;
    }
}
