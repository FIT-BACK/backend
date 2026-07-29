UPDATE image
SET purpose = CASE
        WHEN purpose = 'ANALYSIS_ORIGINAL' THEN 'ANALYSIS'
        WHEN purpose IN ('LOOKBOOK_ORIGINAL', 'LOOKBOOK_MATCHED') THEN 'LOOKBOOK'
        ELSE purpose
    END,
    status = CASE
        WHEN status = 'PENDING' THEN 'PENDING_UPLOAD'
        ELSE status
    END
WHERE purpose IN (
        'ANALYSIS_ORIGINAL',
        'LOOKBOOK_ORIGINAL',
        'LOOKBOOK_MATCHED'
    )
   OR status = 'PENDING';

CREATE TEMPORARY TABLE image_lifecycle_contract_gate (
    legacy_count BIGINT NOT NULL,
    CONSTRAINT CK_IMAGE_LEGACY_ZERO CHECK (legacy_count = 0)
);

INSERT INTO image_lifecycle_contract_gate (legacy_count)
SELECT COUNT(*)
FROM image
WHERE purpose IN (
        'ANALYSIS_ORIGINAL',
        'LOOKBOOK_ORIGINAL',
        'LOOKBOOK_MATCHED'
    )
   OR status = 'PENDING';

DROP TEMPORARY TABLE image_lifecycle_contract_gate;

ALTER TABLE image
    DROP CHECK CK_IMAGE_PURPOSE,
    DROP CHECK CK_IMAGE_STATUS,
    ADD CONSTRAINT CK_IMAGE_PURPOSE
        CHECK (purpose IN ('ANALYSIS', 'LOOKBOOK', 'PROFILE')),
    ADD CONSTRAINT CK_IMAGE_STATUS
        CHECK (status IN (
            'PENDING_UPLOAD', 'READY', 'ACTIVE', 'DELETING',
            'DELETE_FAILED', 'DELETED', 'REJECTED'
        ));
