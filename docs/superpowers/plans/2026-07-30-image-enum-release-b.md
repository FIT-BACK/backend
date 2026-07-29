# Image Enum Release B Implementation Plan

> **For Codex:** Execute Release B independently from Release C and preserve rollback compatibility with Release A.

**Goal:** Write only the new image purpose/status values, backfill existing rows, and catch up legacy rows created during an A rollback without narrowing the dual constraints.

**Architecture:** Keep legacy enum constants for dual-read compatibility. A versioned migration converts existing rows before B starts. A transactional startup runner performs the same idempotent reconciliation on every application start, because an unchanged Flyway repeatable migration would not rerun after an A rollback. Release C remains a separate deployment.

**Tech Stack:** Java 21, Spring Boot ApplicationRunner, Spring Data JPA native update, Flyway SQL, MySQL 8.4

---

### Task 1: Switch the writer to new values

**Files:**
- Modify: `src/main/java/com/fitback/backend/domain/image/entity/Image.java`
- Modify: `src/main/java/com/fitback/backend/domain/image/service/ImageUploadService.java`
- Test: `src/test/java/com/fitback/backend/domain/image/entity/ImageLifecycleTest.java`
- Test: `src/test/java/com/fitback/backend/domain/image/service/ImageUploadServiceTest.java`

1. Make new uploads persist `PENDING_UPLOAD`.
2. Map API purposes directly to `ANALYSIS`, `LOOKBOOK`, and `PROFILE`.
3. Keep legacy enum constants and dual-read helpers for rollback.

### Task 2: Backfill and reconcile every B start

**Files:**
- Add: `src/main/resources/db/migration/V18__backfill_image_lifecycle_values.sql`
- Modify: `src/main/java/com/fitback/backend/domain/image/repository/ImageRepository.java`
- Add: `src/main/java/com/fitback/backend/domain/image/service/ImageLifecycleReconciliationRunner.java`
- Test: `src/test/java/com/fitback/backend/domain/image/service/ImageLifecycleReconciliationRunnerTest.java`
- Modify: `scripts/ci/test_mysql_migrations.sh`

1. Backfill all legacy purpose/status values in V18.
2. Add one idempotent native update that applies the same mapping.
3. Run it transactionally on every startup before readiness.
4. Simulate an A rollback legacy write and verify the B startup catch-up.
5. Verify both old and new values remain accepted by the V4 dual constraints.

### Task 3: Document, verify, and release

**Files:**
- Modify: `docs/ERD.md`
- Modify: `docs/IMAGE_STORAGE_POLICY.md`
- Modify: `docs/DEPLOYMENT.md`

1. Record Release A deployment evidence and Release B rollback boundary.
2. Run focused tests, MySQL migration verification, and the required clean build.
3. Merge the Issue #96 PR to `develop`.
4. Merge `develop` to `main`, verify production CD and readiness, and keep that SHA as Release C's rollback target.
