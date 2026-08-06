-- 프론트 트렌드 콘텐츠 ID와 저장 API의 targetId 계약을 맞추기 위한 기본 데이터.
-- 콘텐츠 계정은 데모 데이터 준비 과정에서 미리 생성되어 있어야 한다.
SET @trend_author_id := (
    SELECT member_id
    FROM member
    WHERE email = 'fitback.demo+content@gmail.com'
    LIMIT 1
);

INSERT INTO tag (tag_name, tag_type, created_at, updated_at)
SELECT trend_tag.tag_name, 'STYLE', CURRENT_TIMESTAMP(6), NULL
FROM (
    SELECT '뉴트럴' AS tag_name
    UNION ALL SELECT '페미닌'
    UNION ALL SELECT '데일리룩'
    UNION ALL SELECT '오피스룩'
) trend_tag
LEFT JOIN tag existing ON existing.tag_name = trend_tag.tag_name
WHERE existing.tag_id IS NULL;

INSERT INTO tag_target_clothing (tag_id, target_clothing)
SELECT tag.tag_id, 'ALL'
FROM tag
WHERE tag.tag_name IN ('뉴트럴', '페미닌', '데일리룩', '오피스룩')
  AND NOT EXISTS (
      SELECT 1
      FROM tag_target_clothing target
      WHERE target.tag_id = tag.tag_id
        AND target.target_clothing = 'ALL'
  );

-- ID 1~6은 프론트 trendArticles.ts의 targetId와 동일하게 유지한다.
INSERT INTO trend_content (
    trend_id,
    title,
    image_url,
    description,
    created_by,
    created_at,
    updated_at
) VALUES
    (
        1,
        '미니멀 무드',
        'https://i.pinimg.com/1200x/6f/fe/1e/6ffe1ea673bc95190a513535c00678b9.jpg',
        '군더더기 없는 깔끔한 실루엣과 뉴트럴 컬러로 완성하는 미니멀 스타일. 이번 시즌 가장 핫한 트렌드예요.',
        @trend_author_id,
        CURRENT_TIMESTAMP(6),
        NULL
    ),
    (
        2,
        '스트릿 무드',
        'https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg',
        '오버사이즈 실루엣과 레이어드 룩으로 완성하는 스트릿 스타일링.',
        @trend_author_id,
        CURRENT_TIMESTAMP(6),
        NULL
    ),
    (
        3,
        '러블리 무드',
        'https://picsum.photos/seed/trend-lovely/600/400',
        '부드러운 실루엣과 파스텔 컬러로 완성하는 러블리 스타일링.',
        @trend_author_id,
        CURRENT_TIMESTAMP(6),
        NULL
    ),
    (
        4,
        '캐주얼 무드',
        'https://picsum.photos/seed/trend-casual/600/400',
        '데일리로 편하게 입기 좋은 캐주얼 코디 모음.',
        @trend_author_id,
        CURRENT_TIMESTAMP(6),
        NULL
    ),
    (
        5,
        '오피스 포멀 무드',
        'https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg',
        '단정하면서도 트렌디한 오피스룩 세팅 가이드 영상.',
        @trend_author_id,
        CURRENT_TIMESTAMP(6),
        NULL
    ),
    (
        6,
        '어떤 자리에서도 손색없는 캐주얼+포멀조합',
        'https://i.pinimg.com/1200x/55/60/18/55601858d817ccd0c52f8a611b7faed0.jpg',
        '캐주얼함은 유지하면서 포멀한 자리에서도 손색없는 밸런스를 잡은 조합.',
        @trend_author_id,
        CURRENT_TIMESTAMP(6),
        NULL
    );

-- 대표 스타일 태그에 더 큰 가중치를 주어 관련 룩북을 우선 노출한다.
INSERT INTO trend_tag (trend_id, tag_id, relevance_weight, created_at)
SELECT mapping.trend_id, tag.tag_id, mapping.relevance_weight, CURRENT_TIMESTAMP(6)
FROM (
    SELECT 1 AS trend_id, '미니멀' AS tag_name, 100 AS relevance_weight
    UNION ALL SELECT 1, '뉴트럴', 10
    UNION ALL SELECT 1, '와이드핏', 1
    UNION ALL SELECT 2, '스트릿', 100
    UNION ALL SELECT 2, '오버사이즈', 10
    UNION ALL SELECT 2, '캐주얼', 1
    UNION ALL SELECT 3, '러블리', 100
    UNION ALL SELECT 3, '페미닌', 10
    UNION ALL SELECT 4, '캐주얼', 100
    UNION ALL SELECT 4, '데일리룩', 10
    UNION ALL SELECT 5, '포멀', 100
    UNION ALL SELECT 5, '오피스룩', 10
    UNION ALL SELECT 6, '캐주얼', 100
    UNION ALL SELECT 6, '포멀', 100
) mapping
JOIN tag ON tag.tag_name = mapping.tag_name;
