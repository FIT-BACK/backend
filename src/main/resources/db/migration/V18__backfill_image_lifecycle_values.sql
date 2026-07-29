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
