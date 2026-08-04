-- 회원 관심 스타일(SCR-03/12)에서 쓰는 무드 태그 시딩.
-- 기존 tag 테이블은 AI 분석 태그 수정용(V16)으로만 채워져 있어 미니멀 외에는
-- 존재하지 않았음 — tag_type은 기존 값 중 가장 근접한 DETAIL을 재사용.
INSERT INTO tag (tag_name, tag_type, created_at, updated_at)
SELECT '스트릿', 'DETAIL', CURRENT_TIMESTAMP(6), NULL
WHERE NOT EXISTS (
    SELECT 1 FROM tag WHERE tag_name = '스트릿'
);

INSERT INTO tag (tag_name, tag_type, created_at, updated_at)
SELECT '러블리', 'DETAIL', CURRENT_TIMESTAMP(6), NULL
WHERE NOT EXISTS (
    SELECT 1 FROM tag WHERE tag_name = '러블리'
);

INSERT INTO tag (tag_name, tag_type, created_at, updated_at)
SELECT '캐주얼', 'DETAIL', CURRENT_TIMESTAMP(6), NULL
WHERE NOT EXISTS (
    SELECT 1 FROM tag WHERE tag_name = '캐주얼'
);

INSERT INTO tag (tag_name, tag_type, created_at, updated_at)
SELECT '포멀', 'DETAIL', CURRENT_TIMESTAMP(6), NULL
WHERE NOT EXISTS (
    SELECT 1 FROM tag WHERE tag_name = '포멀'
);
