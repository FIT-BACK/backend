# Shopify Product Provider PoC Plan

> **상태 (2026-08-01): PoC 완료·병합됨.** [Issue #92](https://github.com/FIT-BACK/backend/issues/92)와 [PR #167](https://github.com/FIT-BACK/backend/pull/167)의 실행 기록이다. 아래 본문은 당시 계획을 보존하며 현재 기본 공급자와 활성화 조건은 [SHOPIFY_PRODUCT_PROVIDER_DECISION.md](../../SHOPIFY_PRODUCT_PROVIDER_DECISION.md) 및 구현 설정을 기준으로 한다.

> **For Codex:** Execute this docs-only decision task with the smallest live dataset and repository-required verification.

**Goal:** Close Issue #92 with a reproducible Shopify Global Catalog usefulness check and a conservative production adoption decision.

**Architecture:** Keep `ProductCatalogPort` as the provider boundary. Persist Shopify provider/product/variant/merchant identifiers only; resolve title, price, currency, image, availability, and purchase URL through live `lookup_catalog`. Keep fixture as the default fallback until production traffic limits and commercial terms are confirmed.

**Tech Stack:** Shopify Global Catalog MCP, Markdown ADR, existing Java adapter contract

---

### Task 1: Run the bounded live PoC

1. Use three fixed fashion queries with `KR`/`ko`/`KRW` context.
2. Request at most three results per query.
3. Resolve one returned variant through `lookup_catalog`.
4. Record HTTP status, latency, field completeness, identity consistency, and Korean-market limitations.

### Task 2: Record policy and decision evidence

**Files:**
- Add: `docs/SHOPIFY_PRODUCT_PROVIDER_DECISION.md`

1. Link Shopify's official catalog, profile, and rate-limit documentation.
2. Record the no-cache/no-image-copy rules and anonymous-tier limits.
3. Mark undisclosed quota and commercial terms as `PENDING_SUPPORT_RESPONSE` without blocking the prototype.
4. Adopt Shopify only as the prototype primary provider, with fixture as the deterministic fallback.

### Task 3: Verify and close

1. Remove the local-only PoC runner and ensure no response snapshot is committed.
2. Run `git diff --check` and the repository-required clean build.
3. Commit, push, open a PR to `develop`, wait for required CI, and merge without waiting for reviewer approval.
