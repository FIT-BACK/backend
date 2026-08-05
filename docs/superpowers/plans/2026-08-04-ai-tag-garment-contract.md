# AI Tag Garment Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align issue #219 adapters with issue #220 runtime configuration, extract canonical and free-form tags per garment piece across all five tag dimensions, and consume the tag master taxonomy delivered by #225.

**Architecture:** Provider-neutral model output becomes a list of `TOP`, `BOTTOM`, and `SHOES` garment results. This AI result grouping remains separate from #225's `TagTargetClothing` values (`TOP`, `PANTS`, `SKIRT`, `DRESS`, `OUTER`, `ALL`); it neither converts `BOTTOM` to `PANTS` nor adds `SHOES` to the taxonomy. OpenAI and Bedrock keep one shared prompt/schema and the existing `AiTagAnalyzer` persistence boundary flattens and deduplicates canonical tags because the current analysis database contract does not store garment ownership. Runtime configuration uses one validated request timeout for both providers and the exact #220 property names.

**Tech Stack:** Java 21, Spring Boot 4.1 configuration properties, OpenAI Responses API, AWS SDK Bedrock Runtime, Jackson, JUnit 5, AssertJ

## Global Constraints

- Analyzer selection is `FITBACK_AI_TAG_ANALYZER=unavailable|prototype|openai|bedrock`.
- Common timeout is `FITBACK_AI_REQUEST_TIMEOUT`, defaults to `PT30S`, and must be a positive whole-second duration.
- OpenAI variables are `FITBACK_AI_OPENAI_MODEL` and `FITBACK_AI_OPENAI_API_KEY`.
- Bedrock variables are `FITBACK_AI_BEDROCK_MODEL_ID` and existing `AWS_REGION`; runtime credentials come from the EC2 instance role.
- Spring properties are `fitback.ai.request-timeout`, `fitback.ai.openai.api-key`, `fitback.ai.openai.model`, `fitback.ai.bedrock.model-id`, and `fitback.ai.bedrock.region`.
- The OpenAI key must not be written to workflow payloads, release `.env`, documents, tests, or logs.
- Deployment workflow and Parameter Store mutations remain issue #220 scope; this plan only binds #219 application code and records live verification evidence.

---

### Task 1: Bind adapters to the shared runtime contract

**Files:**
- Modify: `src/main/java/com/fitback/backend/external/aitag/config/AiTagProperties.java`
- Modify: `src/main/java/com/fitback/backend/external/aitag/config/AiTagProviderConfig.java`
- Modify: `src/main/java/com/fitback/backend/external/aitag/openai/OpenAiTagModelClient.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/fitback/backend/external/aitag/config/AiTagPropertiesTest.java`

**Interfaces:**
- Consumes: the exact environment and Spring property names from issue #220.
- Produces: `AiTagProperties.requestTimeout(): Duration`, `OpenAi(apiKey, model)`, and `Bedrock(region, modelId)` used by both provider clients.

- [ ] **Step 1: Write configuration tests for the default and invalid timeouts**

```java
@Test
void defaultsRequestTimeoutToThirtySeconds() {
    AiTagProperties properties = new AiTagProperties(null, null, null, null);
    assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
}

@Test
void rejectsSubSecondRequestTimeout() {
    assertThatThrownBy(() -> new AiTagProperties(
            null, Duration.ofMillis(500), null, null
    )).hasMessage("fitback.ai.request-timeout must be a positive whole-second duration");
}
```

- [ ] **Step 2: Run the focused test and confirm the old constructor fails**

Run: `./gradlew test --tests '*AiTagPropertiesTest'`

Expected: compilation failure because `AiTagProperties` has no shared timeout component.

- [ ] **Step 3: Replace provider-specific timeouts and environment bindings**

```properties
fitback.ai.request-timeout=${FITBACK_AI_REQUEST_TIMEOUT:PT30S}
fitback.ai.openai.api-key=${FITBACK_AI_OPENAI_API_KEY:}
fitback.ai.openai.model=${FITBACK_AI_OPENAI_MODEL:}
fitback.ai.bedrock.region=${AWS_REGION:}
fitback.ai.bedrock.model-id=${FITBACK_AI_BEDROCK_MODEL_ID:}
```

`AiTagProperties` rejects zero, negative, and fractional-second durations; provider beans fail closed on blank model, blank OpenAI key, or blank Bedrock region/model. OpenAI uses the shared timeout for connect and request timeout. Bedrock uses the shared timeout for both `apiCallTimeout` and `apiCallAttemptTimeout`.

- [ ] **Step 4: Run the focused properties and production configuration tests**

Run: `./gradlew test --tests '*AiTagPropertiesTest' --tests '*ProductionProfileConfigurationTest'`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit the configuration unit**

```bash
git add src/main/java/com/fitback/backend/external/aitag/config/AiTagProperties.java src/main/java/com/fitback/backend/external/aitag/config/AiTagProviderConfig.java src/main/java/com/fitback/backend/external/aitag/openai/OpenAiTagModelClient.java src/main/resources/application.properties src/test/java/com/fitback/backend/external/aitag/config/AiTagPropertiesTest.java src/test/java/com/fitback/backend/global/health/ProductionProfileConfigurationTest.java
git commit -m "fix: AI 분석기 배포 설정 계약 정렬"
```

### Task 2: Return canonical and suggested tags per garment piece

**Files:**
- Create: `src/main/java/com/fitback/backend/external/aitag/GarmentPiece.java`
- Create: `src/main/java/com/fitback/backend/external/aitag/AiTagGarment.java`
- Modify: `src/main/java/com/fitback/backend/external/aitag/AiTagModelOutput.java`
- Modify: `src/main/java/com/fitback/backend/external/aitag/AiTagModelResult.java`
- Modify: `src/main/java/com/fitback/backend/external/aitag/AiTagRequestFactory.java`
- Modify: `src/main/java/com/fitback/backend/external/aitag/AiTagResponseParser.java`
- Modify: `src/main/java/com/fitback/backend/external/aitag/CanonicalAiTagAnalyzer.java`
- Modify: `src/main/java/com/fitback/backend/external/aitag/openai/OpenAiTagModelClient.java`
- Modify: `src/main/java/com/fitback/backend/external/aitag/bedrock/BedrockAiTagModelClient.java`
- Test: `src/test/java/com/fitback/backend/external/aitag/AiTagRequestFactoryTest.java`
- Test: `src/test/java/com/fitback/backend/external/aitag/AiTagResponseParserTest.java`
- Test: `src/test/java/com/fitback/backend/external/aitag/CanonicalAiTagAnalyzerTest.java`
- Test: `src/test/java/com/fitback/backend/external/aitag/openai/OpenAiTagModelClientTest.java`

**Interfaces:**
- Consumes: canonical `Tag` catalog with `SILHOUETTE`, `COLOR`, `DETAIL`, `STYLE`, and `MATERIAL` types.
- Produces: `AiTagGarment(GarmentPiece piece, List<AiTagPrediction> canonicalTags, List<AiTagSuggestion> suggestedTags)` and `AiTagModelResult.garments()`.

- [ ] **Step 1: Change schema/parser tests to require garment ownership**

```json
{
  "garments": [
    {
      "piece": "TOP",
      "canonicalTags": [{"type": "STYLE", "name": "캐주얼"}],
      "suggestedTags": [{"type": "COLOR", "name": "오프화이트", "confidence": 0.91, "evidence": "상의의 밝은 크림색 표면"}]
    },
    {
      "piece": "SHOES",
      "canonicalTags": [{"type": "MATERIAL", "name": "가죽"}],
      "suggestedTags": []
    }
  ]
}
```

The request test asserts that `garments` is required, `piece` is limited to `TOP/BOTTOM/SHOES`, and the prompt names every one of the five dimensions.

- [ ] **Step 2: Run request/parser/analyzer/client tests and confirm flat-output failures**

Run: `./gradlew test --tests '*AiTagRequestFactoryTest' --tests '*AiTagResponseParserTest' --tests '*CanonicalAiTagAnalyzerTest' --tests '*OpenAiTagModelClientTest'`

Expected: tests fail because the current response root contains flat `canonicalTags` and `suggestedTags`.

- [ ] **Step 3: Add garment result records and the nested strict schema**

```java
public enum GarmentPiece { TOP, BOTTOM, SHOES }

public record AiTagGarment(
        GarmentPiece piece,
        List<AiTagPrediction> canonicalTags,
        List<AiTagSuggestion> suggestedTags
) {
    public AiTagGarment {
        if (piece == null) throw new IllegalArgumentException("garment piece must not be null");
        canonicalTags = List.copyOf(canonicalTags);
        suggestedTags = List.copyOf(suggestedTags);
    }
}
```

The schema root contains only `garments`; each garment requires `piece`, `canonicalTags`, and `suggestedTags`. The prompt tells the model to identify visible top, bottom, and shoes separately and inspect all five dimensions without inferring invisible material.

- [ ] **Step 4: Parse nested results and flatten only at the persistence boundary**

```java
List<TagKey> predictedKeys = result.garments().stream()
        .flatMap(garment -> garment.canonicalTags().stream())
        .map(prediction -> new TagKey(prediction.type(), prediction.name()))
        .distinct()
        .toList();
```

The parser rejects an empty garment list, duplicate garment pieces, empty tag arrays on every garment combined, unknown pieces, and more than three garment entries. `CanonicalAiTagAnalyzer` still returns the flat canonical `List<Tag>` required by `AnalysisService` and does not persist free-form suggestions.

- [ ] **Step 5: Run the focused adapter tests**

Run: `./gradlew test --tests '*AiTagRequestFactoryTest' --tests '*AiTagResponseParserTest' --tests '*CanonicalAiTagAnalyzerTest' --tests '*OpenAiTagModelClientTest'`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit the garment contract unit**

```bash
git add src/main/java/com/fitback/backend/external/aitag src/test/java/com/fitback/backend/external/aitag
git commit -m "feat: 가먼트피스별 AI 태그 추출 추가"
```

### Task 3: Preserve garment grouping in blind evaluation

**Files:**
- Modify: `src/test/java/com/fitback/backend/external/aitag/AiTagBlindEvaluationMain.java`
- Modify: `src/test/java/com/fitback/backend/external/aitag/AiTagBlindEvaluationMainTest.java`
- Modify: `docs/AI_TAG_BLIND_EVALUATION.md`

**Interfaces:**
- Consumes: `AiTagModelResult.garments()` from Task 2.
- Produces: blind result JSON with a `garments` array for each provider slot, without secrets or image bytes.

- [ ] **Step 1: Test blind-result serialization**

```java
assertThat(evaluation)
        .containsKey("garments")
        .doesNotContainKeys("canonicalTags", "suggestedTags");
```

- [ ] **Step 2: Run the blind evaluation unit test and confirm it fails on flat keys**

Run: `./gradlew test --tests '*AiTagBlindEvaluationMainTest'`

Expected: assertion failure because the current output writes two flat arrays.

- [ ] **Step 3: Write nested output and document the persistence boundary**

```java
evaluation.put("garments", result.garments());
```

The document uses the #220 environment names, explains that blind output keeps garment grouping, and states that the current analysis report persists only the deduplicated canonical union.

- [ ] **Step 4: Run the blind evaluation unit test**

Run: `./gradlew test --tests '*AiTagBlindEvaluationMainTest'`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit the evaluation contract unit**

```bash
git add src/test/java/com/fitback/backend/external/aitag/AiTagBlindEvaluationMain.java src/test/java/com/fitback/backend/external/aitag/AiTagBlindEvaluationMainTest.java docs/AI_TAG_BLIND_EVALUATION.md
git commit -m "docs: 가먼트별 블라인드 평가 계약 반영"
```

### Task 4: Consume the #225 tag master taxonomy

**Files:**
- Verify: `src/main/resources/db/migration/V25__seed_tag_master_taxonomy.sql`
- Verify: `src/main/java/com/fitback/backend/domain/tag/entity/TagType.java`
- Verify: `src/main/java/com/fitback/backend/domain/tag/entity/TagTargetClothing.java`
- Verify: `src/main/java/com/fitback/backend/domain/tag/repository/TagRepository.java`

**Interfaces:**
- Consumes: #225's 43 canonical tags and mapped target clothing values from V25.
- Produces: an AI canonical catalog containing only mapped master tags. No migration is added by #223.

- [ ] **Step 1: Verify the merged taxonomy contract**

Confirm V25 is the only tag master seed and that its clothing values are exactly `TOP`, `PANTS`,
`SKIRT`, `DRESS`, `OUTER`, and `ALL`. `TagType` remains `SILHOUETTE`, `COLOR`, `DETAIL`,
`STYLE`, and `MATERIAL`.

- [ ] **Step 2: Keep the AI garment result grouping separate**

`GarmentPiece` remains `TOP`, `BOTTOM`, and `SHOES`. Do not convert `BOTTOM` to `PANTS` and do
not add `SHOES` to `TagTargetClothing`; there is no approved mapping for that conversion.

- [ ] **Step 3: Use only mapped master tags**

`CanonicalAiTagAnalyzer` reads the catalog through `TagRepository.findAllByOrderByIdAsc()`, whose
#225 query joins `targetClothing`, so unmapped legacy tags do not enter the model schema.

- [ ] **Step 4: Run the existing migration gate**

Run: `bash scripts/ci/test_mysql_migrations.sh`

Expected: `MySQL migration tests passed.`

### Task 5: Verify, publish, and separate the live production check

**Files:**
- Verify: all files changed by Tasks 1-3 and the merged #225 migration contract

**Interfaces:**
- Consumes: issue #219 acceptance criteria and the issue #220 deployment boundary.
- Produces: a clean branch and draft PR to `develop`, plus a live-state statement for `/fitback/prod/openai-api-key` that exposes neither its value nor version content.

- [ ] **Step 1: Run focused code and migration checks**

Run: `./gradlew test --tests 'com.fitback.backend.external.aitag.*' --tests '*ProductionProfileConfigurationTest'`

Expected: `BUILD SUCCESSFUL`.

Run: `bash scripts/ci/test_mysql_migrations.sh`

Expected: `MySQL migration tests passed.`

- [ ] **Step 2: Run repository-required verification**

Run: `./gradlew clean build`

Expected: `BUILD SUCCESSFUL`.

Run: `git diff --check`

Expected: no output and exit code 0.

- [ ] **Step 3: Verify production state without reading or printing secret content**

Confirm only whether `/fitback/prod/openai-api-key` exists as `SecureString`, whether the selected EC2 instance role can read the chosen prefix, and whether the most recent deployment injected OpenAI mode without adding the key to workflow payload or release `.env`. Track this independent operational work in issue #222. If authenticated AWS state is unavailable, report `NOT_VERIFIED` instead of inferring success from repository code.

- [ ] **Step 4: Push the issue branch and open a draft PR**

```bash
git push -u origin 'feature/#219-ai-tag-garment-contract'
gh pr create --repo FIT-BACK/backend --base develop --head 'feature/#219-ai-tag-garment-contract' --draft --title 'Feat: AI 태그 분석기 가먼트·배포 계약 보완' --body-file /tmp/fitback-pr219-body.md
```

The PR body links `close #219`, lists focused/MySQL/full-build results, states that #220 deployment files were not changed, and records production key/deployment verification as confirmed or `NOT_VERIFIED`.
