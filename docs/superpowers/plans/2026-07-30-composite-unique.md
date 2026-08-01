# Composite Unique Migration Implementation Plan

> **상태 (2026-08-01): 완료·병합됨.** [Issue #87](https://github.com/FIT-BACK/backend/issues/87)과 [PR #166](https://github.com/FIT-BACK/backend/pull/166)의 실행 기록이다. 아래 본문과 체크박스는 작성 당시 계획을 보존하며 현재 완료 여부를 나타내지 않는다. 최신 스키마 계약은 [ERD.md](../../ERD.md)와 Flyway migration을 기준으로 한다.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영 `closet_save`와 `trend_tag`의 중복을 결정적으로 정리하고 Entity와 같은 복합 UNIQUE 제약을 보장한다.

**Architecture:** 이미 운영 배포된 V13의 closet 제약을 변경하지 않고 후속 V17에서 두 테이블을 재조정한다. 각 복합 키에서 가장 작은 surrogate ID를 보존하고 나머지를 삭제한 뒤, 제약이 없는 경우에만 동일한 이름의 UNIQUE를 추가한다.

**Tech Stack:** Flyway SQL, MySQL 8.4, Bash migration contract test

## Global Constraints

- 적용된 V13 migration checksum을 변경하지 않는다.
- 기존 관계 중 가장 작은 ID를 보존한다.
- migration은 제약이 이미 있는 운영 DB에서도 안전해야 한다.

---

### Task 1: 중복 회귀 테스트

**Files:**
- Modify: `scripts/ci/test_mysql_migrations.sh`

**Interfaces:**
- Consumes: legacy baseline `trend_tag`
- Produces: 중복 0건과 두 UNIQUE 제약 검증

- [x] 중복 fixture와 실패 검증을 추가한다.
- [x] `bash scripts/ci/test_mysql_migrations.sh`가 trend 제약 누락으로 실패하는지 확인한다.

### Task 2: V17 reconciliation migration

**Files:**
- Create: `src/main/resources/db/migration/V17__reconcile_composite_unique_constraints.sql`
- Modify: `docs/ERD.md`
- Modify: `docs/DEPLOYMENT.md`

**Interfaces:**
- Consumes: `closet_save(save_id, member_id, target_type, target_id)`, `trend_tag(trend_tag_id, trend_id, tag_id)`
- Produces: `UK_CLOSET_SAVE_MEMBER_ID_TARGET_TYPE_TARGET_ID`, `UK_TREND_TAG_TREND_ID_TAG_ID`

- [x] 작은 surrogate ID를 남기는 self-join DELETE를 구현한다.
- [x] information_schema 확인 후 누락된 UNIQUE만 추가한다.
- [x] ERD와 운영 migration 목록을 동기화한다.
- [x] MySQL migration test와 `./gradlew clean build`를 통과한다.

### Task 3: PR 완료

- [ ] `git diff --check` 후 커밋·push한다.
- [ ] #87을 닫는 develop 대상 PR을 만들고 CI 성공 후 병합한다.
