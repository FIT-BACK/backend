package com.fitback.backend.domain.image.entity;

public enum ImageStatus {
    PENDING,
    PENDING_UPLOAD,
    READY,
    ACTIVE,
    DELETING,
    DELETE_FAILED,
    DELETED,
    REJECTED;

    public boolean isPendingUpload() {
        return this == PENDING || this == PENDING_UPLOAD;
    }
}
