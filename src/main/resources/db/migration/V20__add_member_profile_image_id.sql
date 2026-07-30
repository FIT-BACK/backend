ALTER TABLE member
    ADD COLUMN profile_image_id VARCHAR(36)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
    ADD CONSTRAINT FK_MEMBER_PROFILE_IMAGE_OWNER
        FOREIGN KEY (profile_image_id, member_id)
        REFERENCES image (image_id, owner_id);
