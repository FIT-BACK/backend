# Image Enum Release C Implementation Plan

> **상태 (2026-08-01): 완료·병합됨.** [Issue #97](https://github.com/FIT-BACK/backend/issues/97)과 [PR #170](https://github.com/FIT-BACK/backend/pull/170)의 실행 기록이다. 아래 본문은 배포 당시 계획을 보존하며 현재 이미지 lifecycle 계약은 [IMAGE_STORAGE_POLICY.md](../../IMAGE_STORAGE_POLICY.md)와 구현 코드를 기준으로 한다.
>
> **For Codex:** Execute only after Release B is successfully deployed and recorded as the immediate rollback target.

**Goal:** Remove all legacy image lifecycle values and narrow the database contract to the public logical enums while preserving rollback to Release B.

**Architecture:** V19 performs one final idempotent catch-up, gates on zero legacy rows before any constraint change, then replaces the dual checks with new-only checks. Java legacy constants, startup reconciliation, and compatibility branches are removed. Release B remains rollback-compatible because its writer already emits only new values.

**Tech Stack:** Java 21, Spring Data JPA, Flyway SQL, MySQL 8.4

---

### Task 1: Contract the database safely

**Files:**
- Add: `src/main/resources/db/migration/V19__contract_image_lifecycle_values.sql`
- Modify: `scripts/ci/test_mysql_migrations.sh`

1. Simulate a legacy row written during the B rollback window.
2. Run final catch-up mapping.
3. Use a temporary-table CHECK gate to require zero legacy rows before DDL.
4. Replace purpose/status checks with new-only values.
5. Verify legacy and unknown writes fail while Release B's new writer values succeed.

### Task 2: Remove compatibility code

**Files:**
- Modify: `src/main/java/com/fitback/backend/domain/image/entity/ImagePurpose.java`
- Modify: `src/main/java/com/fitback/backend/domain/image/entity/ImageStatus.java`
- Modify: `src/main/java/com/fitback/backend/domain/image/repository/ImageRepository.java`
- Modify: `src/main/java/com/fitback/backend/domain/image/service/ImageCleanupService.java`
- Delete: `src/main/java/com/fitback/backend/domain/image/service/ImageLifecycleReconciliationRunner.java`
- Update related image and lookbook tests

1. Keep only `ANALYSIS|LOOKBOOK|PROFILE`.
2. Keep only `PENDING_UPLOAD|READY|ACTIVE|DELETING|DELETE_FAILED|DELETED|REJECTED`.
3. Remove the Release B startup catch-up and legacy cleanup branches.
4. Update fixtures to use logical purpose values.

### Task 3: Document, verify, and release

**Files:**
- Modify: `docs/ERD.md`
- Modify: `docs/IMAGE_STORAGE_POLICY.md`
- Modify: `docs/DEPLOYMENT.md`

1. Record Release B production SHA `82543ae` and successful CD run `30499561977`.
2. Run MySQL migration verification and the required clean build.
3. Merge the Issue #97 PR to `develop`.
4. Merge `develop` to `main`, verify Production CD and readiness, then close #97.
