# OpenAI Gold Dataset Preparation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare the committed canonical-tag catalog snapshot and a human-labeling handoff required to run the existing OpenAI baseline without inventing gold labels.

**Architecture:** The existing runner remains unchanged. A static JSON snapshot mirrors the approved V25 canonical taxonomy, while a separate document defines the external image dataset layout and the admissibility rules humans must satisfy before any `gold-labels.json` is created.

**Tech Stack:** JSON, Markdown, Gradle/JUnit validation.

## Global Constraints

- Do not add images, gold labels, API keys, or production data to Git.
- Do not change the OpenAI prompt, taxonomy, matching, API, or recommendation evaluation.
- Use only exact `(type, name)` pairs seeded by `V25__seed_tag_master_taxonomy.sql`.
- Stop before executing the OpenAI baseline if approved human-labelled images are unavailable.

---

### Task 1: Commit the canonical catalog snapshot

**Files:**
- Create: `scripts/poc/ai-tag-evaluation/canonical-catalog.v25.json`
- Verify: `src/main/resources/db/migration/V25__seed_tag_master_taxonomy.sql`

**Interfaces:**
- Consumes: the runner's JSON array of `{ "type": "<TagType>", "name": "<approved name>" }` entries.
- Produces: `AI_TAG_EVALUATION_CATALOG` input containing all 43 V25 canonical tags exactly once.

- [x] **Step 1: Transcribe the V25 canonical tags in migration order**

Create the JSON array using exactly the 43 names and five types in the V25 taxonomy insert.

- [x] **Step 2: Validate the snapshot syntax and V25 membership**

Run: `jq -e 'length == 43 and ([.[].type] | unique | length == 5)' scripts/poc/ai-tag-evaluation/canonical-catalog.v25.json`

Expected: the command exits with status 0.

- [ ] **Step 3: Commit the snapshot with the labeling handoff**

```bash
git add scripts/poc/ai-tag-evaluation/canonical-catalog.v25.json docs/OPENAI_TAG_BASELINE_EVALUATION.md docs/OPENAI_TAG_GOLD_DATASET_LABELING.md docs/superpowers/plans/2026-08-08-openai-gold-dataset.md
git commit -m "docs: OpenAI gold dataset 준비 기준 추가"
```

### Task 2: Document the human-labeling gate

**Files:**
- Create: `docs/OPENAI_TAG_GOLD_DATASET_LABELING.md`
- Modify: `docs/OPENAI_TAG_BASELINE_EVALUATION.md`

**Interfaces:**
- Consumes: the strict schema in `scripts/poc/ai-tag-evaluation/gold-labels.schema.json` and the V25 snapshot from Task 1.
- Produces: an external dataset whose JSON includes only `imageId`, `imagePath`, and exact canonical expected tags.

- [x] **Step 1: State the required minimum image coverage**

Document one unambiguous approved image for each of `TOP`, `PANTS`, `SKIRT`, `DRESS`, and `OUTER` as the minimum runnable set; exclude shoes and recommendation labels from this baseline.

- [x] **Step 2: State the acceptance and human-review rules**

Require a single principal garment, rights-cleared image provenance, visible-only attributes, exact V25 pairs, independent reviewer agreement, and exclusion of uncertain images.

- [x] **Step 3: Link the snapshot and external-data command**

Update the evaluation documentation so `AI_TAG_EVALUATION_CATALOG` points to the committed V25 snapshot and `AI_TAG_EVALUATION_DATASET` remains outside Git.

- [x] **Step 4: Run the focused runner tests**

Run: `./gradlew test --tests '*OpenAiTagEvaluationMainTest'`

Expected: all focused tests pass.

## Self-Review

1. The plan does not create an image or infer a gold tag.
2. The catalog contains only V25 names and types and is the runner's required array shape.
3. The documentation identifies the human-labeling blocker and leaves OpenAI execution contingent on that external dataset.
