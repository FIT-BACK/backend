INSERT INTO tag (tag_name, tag_type, created_at, updated_at)
SELECT '미니멀', 'DETAIL', CURRENT_TIMESTAMP(6), NULL
WHERE NOT EXISTS (
    SELECT 1 FROM tag WHERE tag_name = '미니멀'
);

INSERT INTO tag (tag_name, tag_type, created_at, updated_at)
SELECT '와이드핏', 'SILHOUETTE', CURRENT_TIMESTAMP(6), NULL
WHERE NOT EXISTS (
    SELECT 1 FROM tag WHERE tag_name = '와이드핏'
);

INSERT INTO tag (tag_name, tag_type, created_at, updated_at)
SELECT '베이지톤', 'COLOR', CURRENT_TIMESTAMP(6), NULL
WHERE NOT EXISTS (
    SELECT 1 FROM tag WHERE tag_name = '베이지톤'
);
