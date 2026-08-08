-- 값이 클수록 해당 태그가 트렌드를 대표하며 관련 룩북 정렬 점수에 더 크게 반영된다.
ALTER TABLE trend_tag
    ADD COLUMN relevance_weight INT NOT NULL DEFAULT 1,
    ADD CONSTRAINT CK_TREND_TAG_RELEVANCE_WEIGHT CHECK (relevance_weight > 0);
