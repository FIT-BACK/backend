# Shopify Identity-Only Persistence Implementation Plan

> **상태 (2026-08-01): 완료·병합됨.** [PR #154](https://github.com/FIT-BACK/backend/pull/154)의 실행 기록이다. 아래 본문과 체크박스는 작성 당시 계획을 보존하며 현재 완료 여부를 나타내지 않는다. 최신 공급자 경계는 [SHOPIFY_PRODUCT_PROVIDER_DECISION.md](../../SHOPIFY_PRODUCT_PROVIDER_DECISION.md)와 구현 코드를 기준으로 한다.
>
> **For Codex:** Execute this plan in the current PR branch and keep verification proportional to the requested scope.

**Goal:** Persist only Shopify provider identity while resolving product name, price, image, and purchase URL from live `lookup_catalog` calls.

**Architecture:** Reuse the existing `ProductStorageMode.IDENTITY_ONLY` contract. Shopify materialization stores provider/product/variant/merchant identity and `UNKNOWN` availability only. Product detail, saved-product lists, and recommendation result reads hydrate identity-only products through `ProductDetailService`; provider failures never fall back to a Shopify snapshot.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, JUnit 5, Mockito, MockMvc

---

### Task 1: Persist Shopify identity only

**Files:**
- Modify: `src/main/java/com/fitback/backend/domain/product/entity/Product.java`
- Modify: `src/main/java/com/fitback/backend/domain/product/service/ProductPersistenceService.java`
- Modify: `src/main/java/com/fitback/backend/domain/product/service/ProductMaterializationService.java`
- Modify: `src/main/java/com/fitback/backend/external/shopping/shopify/ShopifyGlobalCatalogAdapter.java`
- Test: `src/test/java/com/fitback/backend/domain/product/service/ProductMaterializationServiceTest.java`
- Test: `src/test/java/com/fitback/backend/external/shopping/shopify/ShopifyProductFlowIntegrationTest.java`

1. Add an identity-only Product factory and persistence method.
2. Route `requiresLiveLookup` materialization through identity-only persistence.
3. Mark Shopify price/image persistence unsupported and remove snapshot TTL use.
4. Assert the Shopify row contains identity fields only.

### Task 2: Hydrate identity-only reads live

**Files:**
- Modify: `src/main/java/com/fitback/backend/domain/product/service/ProductDetailService.java`
- Modify: `src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationQueryService.java`
- Test: `src/test/java/com/fitback/backend/domain/product/service/ProductDetailServiceTest.java`
- Test: `src/test/java/com/fitback/backend/domain/recommendation/service/RecommendationQueryServiceTest.java`

1. Return successful identity-only lookups directly without refreshing the Product row.
2. Do not mark or serve snapshot fallback data for identity-only rows.
3. Hydrate recommendation items through product detail lookup and report partial provider failures.
4. Keep saved-product list hydration through its existing ProductDetailService path.

### Task 3: Document and close the PR

**Files:**
- Modify: `README.md`
- Modify: `docs/API_SPEC.md`

1. Record Shopify identity-only persistence and live lookup behavior.
2. Run focused product/Shopify tests, then the repository-required clean build.
3. Commit, push, wait for required GitHub checks, and merge PR #154 without waiting for reviewer approval as explicitly requested.
