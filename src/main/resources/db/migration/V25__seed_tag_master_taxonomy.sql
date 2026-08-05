CREATE TABLE IF NOT EXISTS tag_target_clothing (
    tag_id BIGINT NOT NULL,
    target_clothing VARCHAR(20) NOT NULL,
    PRIMARY KEY (tag_id, target_clothing),
    CONSTRAINT FK_TAG_TARGET_CLOTHING_TAG
        FOREIGN KEY (tag_id) REFERENCES tag (tag_id) ON DELETE CASCADE,
    CONSTRAINT CK_TAG_TARGET_CLOTHING_VALUE
        CHECK (target_clothing IN ('TOP', 'PANTS', 'SKIRT', 'DRESS', 'OUTER', 'ALL')),
    INDEX IX_TAG_TARGET_CLOTHING_TARGET (target_clothing)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

UPDATE tag legacy
LEFT JOIN tag canonical ON canonical.tag_name = '베이지'
SET legacy.tag_name = '베이지', legacy.tag_type = 'COLOR'
WHERE legacy.tag_name = '베이지톤'
  AND canonical.tag_id IS NULL;

DELETE legacy_link
FROM member_tag legacy_link
JOIN tag legacy ON legacy.tag_id = legacy_link.tag_id AND legacy.tag_name = '베이지톤'
JOIN tag canonical ON canonical.tag_name = '베이지'
JOIN member_tag canonical_link
  ON canonical_link.member_id = legacy_link.member_id
 AND canonical_link.tag_id = canonical.tag_id;

UPDATE member_tag link
JOIN tag legacy ON legacy.tag_id = link.tag_id AND legacy.tag_name = '베이지톤'
JOIN tag canonical ON canonical.tag_name = '베이지'
SET link.tag_id = canonical.tag_id;

UPDATE report_tag canonical_link
JOIN tag canonical
  ON canonical.tag_id = canonical_link.tag_id
 AND canonical.tag_name = '베이지'
JOIN tag legacy ON legacy.tag_name = '베이지톤'
JOIN report_tag legacy_link
  ON legacy_link.report_id = canonical_link.report_id
 AND legacy_link.tag_id = legacy.tag_id
SET canonical_link.source = CASE
        WHEN canonical_link.source = 'USER' OR legacy_link.source = 'USER' THEN 'USER'
        ELSE 'AI'
    END,
    canonical_link.is_confirmed = canonical_link.is_confirmed OR legacy_link.is_confirmed;

DELETE legacy_link
FROM report_tag legacy_link
JOIN tag legacy ON legacy.tag_id = legacy_link.tag_id AND legacy.tag_name = '베이지톤'
JOIN tag canonical ON canonical.tag_name = '베이지'
JOIN report_tag canonical_link
  ON canonical_link.report_id = legacy_link.report_id
 AND canonical_link.tag_id = canonical.tag_id;

UPDATE report_tag link
JOIN tag legacy ON legacy.tag_id = link.tag_id AND legacy.tag_name = '베이지톤'
JOIN tag canonical ON canonical.tag_name = '베이지'
SET link.tag_id = canonical.tag_id;

DELETE legacy_link
FROM product_tag legacy_link
JOIN tag legacy ON legacy.tag_id = legacy_link.tag_id AND legacy.tag_name = '베이지톤'
JOIN tag canonical ON canonical.tag_name = '베이지'
JOIN product_tag canonical_link
  ON canonical_link.product_id = legacy_link.product_id
 AND canonical_link.tag_id = canonical.tag_id;

UPDATE product_tag link
JOIN tag legacy ON legacy.tag_id = link.tag_id AND legacy.tag_name = '베이지톤'
JOIN tag canonical ON canonical.tag_name = '베이지'
SET link.tag_id = canonical.tag_id;

DELETE legacy_link
FROM lookbook_tag legacy_link
JOIN tag legacy ON legacy.tag_id = legacy_link.tag_id AND legacy.tag_name = '베이지톤'
JOIN tag canonical ON canonical.tag_name = '베이지'
JOIN lookbook_tag canonical_link
  ON canonical_link.lookbook_id = legacy_link.lookbook_id
 AND canonical_link.tag_id = canonical.tag_id;

UPDATE lookbook_tag link
JOIN tag legacy ON legacy.tag_id = link.tag_id AND legacy.tag_name = '베이지톤'
JOIN tag canonical ON canonical.tag_name = '베이지'
SET link.tag_id = canonical.tag_id;

DELETE legacy_link
FROM trend_tag legacy_link
JOIN tag legacy ON legacy.tag_id = legacy_link.tag_id AND legacy.tag_name = '베이지톤'
JOIN tag canonical ON canonical.tag_name = '베이지'
JOIN trend_tag canonical_link
  ON canonical_link.trend_id = legacy_link.trend_id
 AND canonical_link.tag_id = canonical.tag_id;

UPDATE trend_tag link
JOIN tag legacy ON legacy.tag_id = link.tag_id AND legacy.tag_name = '베이지톤'
JOIN tag canonical ON canonical.tag_name = '베이지'
SET link.tag_id = canonical.tag_id;

DELETE legacy
FROM tag legacy
JOIN tag canonical ON canonical.tag_name = '베이지'
WHERE legacy.tag_name = '베이지톤';

INSERT INTO tag (tag_name, tag_type, created_at, updated_at)
SELECT taxonomy.tag_name, taxonomy.tag_type, CURRENT_TIMESTAMP(6), NULL
FROM (
    SELECT '미니멀' AS tag_name, 'STYLE' AS tag_type
    UNION ALL SELECT '스트릿', 'STYLE'
    UNION ALL SELECT '러블리', 'STYLE'
    UNION ALL SELECT '캐주얼', 'STYLE'
    UNION ALL SELECT '포멀', 'STYLE'
    UNION ALL SELECT '와이드핏', 'SILHOUETTE'
    UNION ALL SELECT '슬림핏', 'SILHOUETTE'
    UNION ALL SELECT '오버사이즈', 'SILHOUETTE'
    UNION ALL SELECT '레귤러핏', 'SILHOUETTE'
    UNION ALL SELECT 'A라인', 'SILHOUETTE'
    UNION ALL SELECT 'H라인', 'SILHOUETTE'
    UNION ALL SELECT '크롭', 'SILHOUETTE'
    UNION ALL SELECT '로우라이즈', 'SILHOUETTE'
    UNION ALL SELECT '하이라이즈', 'SILHOUETTE'
    UNION ALL SELECT '숏기장', 'SILHOUETTE'
    UNION ALL SELECT '미디기장', 'SILHOUETTE'
    UNION ALL SELECT '롱기장', 'SILHOUETTE'
    UNION ALL SELECT '데님', 'MATERIAL'
    UNION ALL SELECT '니트', 'MATERIAL'
    UNION ALL SELECT '코튼', 'MATERIAL'
    UNION ALL SELECT '린넨', 'MATERIAL'
    UNION ALL SELECT '가죽', 'MATERIAL'
    UNION ALL SELECT '트위드', 'MATERIAL'
    UNION ALL SELECT '시폰', 'MATERIAL'
    UNION ALL SELECT '우븐/시어', 'MATERIAL'
    UNION ALL SELECT '브이넥', 'DETAIL'
    UNION ALL SELECT '터틀넥', 'DETAIL'
    UNION ALL SELECT '라운드넥', 'DETAIL'
    UNION ALL SELECT '러플/프릴', 'DETAIL'
    UNION ALL SELECT '지퍼', 'DETAIL'
    UNION ALL SELECT '벨트', 'DETAIL'
    UNION ALL SELECT '포켓', 'DETAIL'
    UNION ALL SELECT '슬릿', 'DETAIL'
    UNION ALL SELECT '단추', 'DETAIL'
    UNION ALL SELECT '턱', 'DETAIL'
    UNION ALL SELECT '화이트', 'COLOR'
    UNION ALL SELECT '블랙', 'COLOR'
    UNION ALL SELECT '베이지', 'COLOR'
    UNION ALL SELECT '네이비', 'COLOR'
    UNION ALL SELECT '그레이', 'COLOR'
    UNION ALL SELECT '브라운', 'COLOR'
    UNION ALL SELECT '카키', 'COLOR'
    UNION ALL SELECT '파스텔/메탈릭', 'COLOR'
) taxonomy
LEFT JOIN tag existing ON existing.tag_name = taxonomy.tag_name
WHERE existing.tag_id IS NULL;

UPDATE tag
SET tag_type = 'STYLE'
WHERE tag_name IN ('미니멀', '스트릿', '러블리', '캐주얼', '포멀');

UPDATE tag
SET tag_type = 'SILHOUETTE'
WHERE tag_name IN (
    '와이드핏', '슬림핏', '오버사이즈', '레귤러핏', 'A라인', 'H라인',
    '크롭', '로우라이즈', '하이라이즈', '숏기장', '미디기장', '롱기장'
);

UPDATE tag
SET tag_type = 'MATERIAL'
WHERE tag_name IN ('데님', '니트', '코튼', '린넨', '가죽', '트위드', '시폰', '우븐/시어');

UPDATE tag
SET tag_type = 'DETAIL'
WHERE tag_name IN (
    '브이넥', '터틀넥', '라운드넥', '러플/프릴', '지퍼',
    '벨트', '포켓', '슬릿', '단추', '턱'
);

UPDATE tag
SET tag_type = 'COLOR'
WHERE tag_name IN (
    '화이트', '블랙', '베이지', '네이비', '그레이', '브라운', '카키', '파스텔/메탈릭'
);

DELETE target
FROM tag_target_clothing target
JOIN tag ON tag.tag_id = target.tag_id
WHERE tag.tag_name IN (
    '미니멀', '스트릿', '러블리', '캐주얼', '포멀',
    '와이드핏', '슬림핏', '오버사이즈', '레귤러핏', 'A라인', 'H라인',
    '크롭', '로우라이즈', '하이라이즈', '숏기장', '미디기장', '롱기장',
    '데님', '니트', '코튼', '린넨', '가죽', '트위드', '시폰', '우븐/시어',
    '브이넥', '터틀넥', '라운드넥', '러플/프릴', '지퍼',
    '벨트', '포켓', '슬릿', '단추', '턱',
    '화이트', '블랙', '베이지', '네이비', '그레이', '브라운', '카키', '파스텔/메탈릭'
);

INSERT INTO tag_target_clothing (tag_id, target_clothing)
SELECT tag_id, 'ALL'
FROM tag
WHERE tag_name IN (
    '미니멀', '스트릿', '러블리', '캐주얼', '포멀',
    '데님', '니트', '코튼', '린넨', '가죽', '트위드', '시폰', '우븐/시어',
    '러플/프릴', '지퍼', '벨트', '포켓', '슬릿', '단추',
    '화이트', '블랙', '베이지', '네이비', '그레이', '브라운', '카키', '파스텔/메탈릭'
);

INSERT INTO tag_target_clothing (tag_id, target_clothing)
SELECT tag_id, 'TOP'
FROM tag
WHERE tag_name IN (
    '슬림핏', '오버사이즈', '레귤러핏', '크롭', '브이넥', '터틀넥', '라운드넥'
);

INSERT INTO tag_target_clothing (tag_id, target_clothing)
SELECT tag_id, 'PANTS'
FROM tag
WHERE tag_name IN (
    '와이드핏', '슬림핏', '레귤러핏', '로우라이즈', '하이라이즈',
    '숏기장', '미디기장', '롱기장', '턱'
);

INSERT INTO tag_target_clothing (tag_id, target_clothing)
SELECT tag_id, 'SKIRT'
FROM tag
WHERE tag_name IN (
    '슬림핏', '레귤러핏', 'A라인', 'H라인', '로우라이즈', '하이라이즈',
    '숏기장', '미디기장', '롱기장', '턱'
);

INSERT INTO tag_target_clothing (tag_id, target_clothing)
SELECT tag_id, 'DRESS'
FROM tag
WHERE tag_name IN (
    '슬림핏', '오버사이즈', '레귤러핏', 'A라인', 'H라인', '크롭',
    '브이넥', '터틀넥', '라운드넥'
);

INSERT INTO tag_target_clothing (tag_id, target_clothing)
SELECT tag_id, 'OUTER'
FROM tag
WHERE tag_name IN (
    '슬림핏', '오버사이즈', '레귤러핏', 'A라인', '크롭',
    '브이넥', '터틀넥', '라운드넥'
);
