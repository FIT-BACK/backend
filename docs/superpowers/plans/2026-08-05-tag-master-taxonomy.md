# Tag Master Taxonomy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store and expose the approved 43-tag taxonomy with its clothing applicability contract.

**Architecture:** Keep `tag` as the canonical tag aggregate and map its multi-valued clothing applicability through an enum element collection backed by `tag_target_clothing`. Flyway V24 seeds the complete catalog idempotently, preserves existing tag identifiers, and renames the legacy prototype color `베이지톤` to `베이지`; the public tag API returns the canonical type and ordered applicability list.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, Flyway, MySQL 8.4, H2, JUnit 5, AssertJ

## Global Constraints

- Canonical types are exactly `STYLE`, `SILHOUETTE`, `MATERIAL`, `DETAIL`, and `COLOR`.
- Clothing values are exactly `TOP`, `PANTS`, `SKIRT`, `DRESS`, `OUTER`, and `ALL`.
- The canonical catalog contains exactly 43 approved names: 5 STYLE, 12 SILHOUETTE, 8 MATERIAL, 10 DETAIL, and 8 COLOR.
- Existing tag IDs and tag foreign-key references must be preserved.
- Draft PR #223 must drop its partial V24 MATERIAL migration and consume this taxonomy after #224 lands.

---

### Task 1: Model clothing applicability and expose it in the tag API

**Files:**
- Create: `src/main/java/com/fitback/backend/domain/tag/entity/TagTargetClothing.java`
- Modify: `src/main/java/com/fitback/backend/domain/tag/entity/Tag.java`
- Modify: `src/main/java/com/fitback/backend/domain/tag/repository/TagRepository.java`
- Modify: `src/main/java/com/fitback/backend/domain/tag/dto/TagResponse.java`
- Test: `src/test/java/com/fitback/backend/domain/tag/service/TagServiceTest.java`
- Test: `src/test/java/com/fitback/backend/domain/tag/controller/TagControllerTest.java`

**Interfaces:**
- Consumes: persisted `tag_target_clothing(tag_id, target_clothing)` rows.
- Produces: `TagTargetClothing { TOP, PANTS, SKIRT, DRESS, OUTER, ALL }` and API items shaped as `tagId`, `tagName`, `tagType`, `targetClothing`.

- [x] **Step 1: Extend service/controller tests with the final response contract**

```java
assertThat(response.items()).singleElement().satisfies(item -> {
    assertThat(item.tagType()).isEqualTo(TagType.SILHOUETTE);
    assertThat(item.targetClothing()).containsExactly(TagTargetClothing.PANTS);
});
```

- [x] **Step 2: Run the focused tests and verify compilation fails on the missing fields**

Run: `./gradlew test --tests '*TagServiceTest' --tests '*TagControllerTest'`

Expected: compilation failure for `tagType`/`targetClothing`.

- [x] **Step 3: Add the enum element collection and DTO mapping**

```java
@ElementCollection(fetch = FetchType.LAZY)
@CollectionTable(name = "tag_target_clothing", joinColumns = @JoinColumn(name = "tag_id"))
@Enumerated(EnumType.STRING)
@Column(name = "target_clothing", nullable = false, length = 20)
private Set<TagTargetClothing> targetClothing = new LinkedHashSet<>();
```

Keep `Tag.create(String, TagType)` for existing callers and add `Tag.create(String, TagType, Collection<TagTargetClothing>)`. Fetch the collection through `@EntityGraph` and return an immutable enum-order list.

- [x] **Step 4: Run focused tests**

Run: `./gradlew test --tests '*TagServiceTest' --tests '*TagControllerTest'`

Expected: `BUILD SUCCESSFUL`.

### Task 2: Seed the final catalog and validate the MySQL contract

**Files:**
- Create: `src/main/resources/db/migration/V24__seed_tag_master_taxonomy.sql`
- Modify: `scripts/ci/test_mysql_migrations.sh`
- Modify: `src/main/java/com/fitback/backend/domain/analysis/service/PrototypeAiTagAnalyzer.java`
- Test: `src/test/java/com/fitback/backend/domain/analysis/service/PrototypeAiTagAnalyzerTest.java`

**Interfaces:**
- Consumes: V16/V22/V23 legacy tags and the unique `tag.tag_name` constraint.
- Produces: 43 mapped canonical tags and prototype names `미니멀`, `와이드핏`, `베이지`.

- [x] **Step 1: Change the prototype test from `베이지톤` to `베이지`**

```java
when(tagRepository.findAllByTagNameIn(List.of("미니멀", "와이드핏", "베이지")))
        .thenReturn(List.of(minimal, wideFit, beige));
```

- [x] **Step 2: Add a failing MySQL assertion for 43 tags and every type/applicability count**

Run: `bash scripts/ci/test_mysql_migrations.sh`

Expected: failure because V24 and `tag_target_clothing` do not exist.

- [x] **Step 3: Add V24**

V24 creates `tag_target_clothing` with primary key `(tag_id, target_clothing)`, index `IX_TAG_TARGET_CLOTHING_TARGET`, and cascading FK `FK_TAG_TARGET_CLOTHING_TAG`. It renames `베이지톤` to `베이지` when only the legacy name exists; when both names exist it merges duplicate references, moves remaining references to `베이지`, and removes the legacy row. It upserts every approved name/type without replacing canonical IDs, deletes/rebuilds mappings only for the 43 canonical names, and inserts one `ALL` row for universally applicable tags or the exact category rows for scoped tags.

- [x] **Step 4: Run the full MySQL migration gate**

Run: `bash scripts/ci/test_mysql_migrations.sh`

Expected: `MySQL migration tests passed.`

### Task 3: Synchronize API and persistence documentation

**Files:**
- Modify: `docs/API_SPEC.md`
- Modify: `docs/ERD.md`
- Modify: `docs/DEPLOYMENT.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 1 API shape and Task 2 schema.
- Produces: reviewer-facing canonical taxonomy, migration ordering, and deployment validation notes.

- [x] **Step 1: Document the complete `GET /api/v1/tags` response and value enums**

```json
{"tagId": 1, "tagName": "와이드핏", "tagType": "SILHOUETTE", "targetClothing": ["PANTS"]}
```

- [x] **Step 2: Document `tag_target_clothing`, its constraints, the 43-tag counts, and V24**

- [x] **Step 3: Check documentation and source diffs**

Run: `git diff --check`

Expected: no output and exit code 0.

### Task 4: Verify the complete change

**Files:**
- Verify: all files above

**Interfaces:**
- Consumes: completed implementation and documentation.
- Produces: fresh evidence for PR review.

- [x] **Step 1: Run the required project build**

Run: `./gradlew clean build`

Expected: `BUILD SUCCESSFUL` with zero failing tests.

- [x] **Step 2: Re-run the MySQL gate after the build**

Run: `bash scripts/ci/test_mysql_migrations.sh`

Expected: `MySQL migration tests passed.`

- [x] **Step 3: Review scope and whitespace**

Run: `git status --short && git diff --check && git diff --stat origin/develop...HEAD`

Expected: only #224 files are modified and `git diff --check` exits 0.
