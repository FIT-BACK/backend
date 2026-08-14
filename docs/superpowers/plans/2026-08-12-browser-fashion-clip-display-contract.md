# Browser Fashion-CLIP 표시 계약 구현 계획

> **에이전트 작업자용:** 필수 하위 스킬: `superpowers:subagent-driven-development`(권장) 또는 `superpowers:executing-plans`를 사용해 작업별로 구현한다. 단계 추적에는 체크박스(`- [ ]`) 형식을 사용한다.

**목표:** 기존 추천 POST의 `browserReranking` handoff에 snapshot 표시 metadata를 확장하고, browser가 선택 결과를 가격순으로 정렬하기 전에 relevance top-K를 선택하도록 한다.

**아키텍처:** backend는 이미 선택한 `ExternalProductCandidate`를 한 번만 매핑하고 기존 `ProductPriceResponse`와 member-bound opaque `CandidateTokenService` token을 재사용한다. browser는 해당 snapshot metadata를 로컬에서 검증·결합하고 전체 handoff pool에 대해 기존 normalized Fashion-CLIP cosine과 양의 70/30 점수를 계산한다. relevance 기준으로 `min(10, count)`를 선택한 뒤 비교 가능한 동일 통화 가격에 한해 shortlist만 가격순으로 정렬하고, 비교할 수 없는 가격은 relevance 순서를 유지한다.

**기술 스택:** Java 21, Spring Boot/Gradle, JUnit 5/Mockito, browser-native ES module, Node test runner, Vite, ONNX Runtime Web.

## 전체 제약조건

- 추천 POST 응답 1회에 `browserReranking` metadata를 포함하며 resolve API나 후속 metadata 호출을 추가하지 않는다.
- `candidateId`는 기존 member-bound opaque token을 유지한다. Shopify GID, merchant identity, provider 내부 ID, 영속 `productId`는 browser 계약에 포함하지 않는다.
- `finalScore = imageSimilarity * 0.70 + tagSimilarity * 0.30`을 사용한다. 현재 normalized Fashion-CLIP cosine을 사용하고 threshold와 browser score 영속화는 적용하지 않는다.
- Backend handoff source는 기존 `ExternalProductCandidate`다. Shopify lookup/API 호출, migration, Modal 변경, AI tag/OpenAI 변경, 운영 배포를 추가하지 않는다.
- Backend handoff 최대값은 30이다. Browser relevance shortlist 크기는 `min(10, rerankedCandidateCount)`다.
- Browser fallback 문구는 `browser-reranking unavailable`이며, 2xx가 아닌 응답, handoff 누락, model 실패, 이미지 fetch/decode 실패에서도 backend 추천 결과는 계속 표시한다.
- Handoff metadata는 응답 시점 snapshot이며 live Shopify 가격 보장으로 설명하지 않는다.

---

### 작업 1: Handoff 계약에 backend snapshot metadata 추가

**파일:**
- 생성: `src/main/java/com/fitback/backend/domain/recommendation/dto/BrowserRerankingCandidate.java`
- 수정: `src/main/java/com/fitback/backend/domain/recommendation/service/BrowserRerankingHandoffService.java`
- 수정: `src/main/java/com/fitback/backend/domain/recommendation/dto/RecommendationCreateResponse.java`
- 수정: `src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationService.java`
- 추가/수정: `src/main/java/com/fitback/backend/domain/recommendation/service/RecommendationTagMatcher.java`, `RecommendationScorer.java`
- 수정: `docs/API_SPEC.md`, `README.md`

**인터페이스:**
- `BrowserRerankingCandidate`는 `candidateId`, `imageUrl`, `tagSimilarity`, `name`, nullable `sellerName`, nullable `ProductPriceResponse price`, nullable `purchaseUrl`을 제공한다.
- `BrowserRerankingHandoffService.create(long, ProductCategory, List<TagInput>, List<ExternalProductCandidate>)`는 전달된 검색 snapshot에서 최대 30개 후보를 매핑하며 `ProductCatalogPort`를 호출하지 않는다.

- [ ] 기존 token, image URL, tag similarity 검증을 유지하면서 표시 필드 7개를 추가한다. `sellerName`과 `purchaseUrl`은 존재할 때 `candidate.offer()`에서 매핑하고, 가격은 `ProductCandidateMapper.price(offer)`를 통해 매핑해 모든 가격 필드가 null이면 `price: null`이 되게 한다.
- [ ] Handoff service에 `ProductCandidateMapper`를 주입하고 materialization 전 기존 선택 후보 목록을 대상으로 `RecommendationService`에서 호출한다. 영속 점수 계산과 선택은 변경하지 않는다.
- [ ] 기존 backend tag score와 handoff `tagSimilarity`가 바뀌지 않도록 tag matcher 추출의 의미를 현재 scorer와 동일하게 유지한다.
- [ ] 정확한 JSON 구조를 문서화하고 metadata가 live Shopify data가 아닌 handoff 시점 snapshot임을 명시한다. Browser resolve/lookup 호출이 없다는 점도 기록한다.

### 작업 2: Backend 매핑과 회귀 검증 추가

**파일:**
- 수정: `src/test/java/com/fitback/backend/domain/recommendation/service/BrowserRerankingHandoffServiceTest.java`
- 수정: `src/test/java/com/fitback/backend/domain/recommendation/service/RecommendationServiceTest.java`
- 수정: `src/test/java/com/fitback/backend/domain/recommendation/controller/RecommendationControllerIntegrationTest.java`

- [ ] metadata가 완전한 후보 1개, seller/purchase URL/모든 가격이 null인 후보, opaque token 전달, 입력 31개가 30개로 제한되는 경우를 테스트한다.
- [ ] 기존 영속 `similarityScore`/`finalScore` assertion은 유지하면서 controller JSON에 metadata가 포함되는지 검증한다.
- [ ] Browser 변경을 시작하기 전에 backend 집중 테스트를 실행한다.

### 작업 3: Browser 최종 relevance 선택과 가격 표시 정렬 구현

**파일:**
- 수정: `scripts/poc/fashion-clip-browser/src/math.js`
- 수정: `scripts/poc/fashion-clip-browser/src/main.js`
- 수정: `scripts/poc/fashion-clip-browser/index.html`
- 수정: `scripts/poc/fashion-clip-browser/README.md`

**인터페이스:**
- `validateBrowserRerankingHandoff`는 `candidateId`를 해석하지 않고 검증된 snapshot metadata를 반환한다.
- `sortRerankingResults(results)`는 `finalScore DESC`, 다음으로 원래 handoff index ASC 순서를 유지한다.
- `sortDisplayResults(results)`를 추가한다. 모든 후보의 amount가 유한하고 서로 비교 가능하며 통화가 같을 때만 shortlist를 price ASC로 정렬하고, 그 외에는 shortlist 전체의 안정적인 relevance 순서를 유지한다. 비교 가능한 동일 가격은 `finalScore DESC`, 다음으로 원래 index ASC를 사용한다.

- [ ] Nullable seller, price, purchase URL을 검증하고, 잘못된 non-null 표시값은 기본값이나 URL을 만들지 않고 거부한다.
- [ ] 검증된 handoff pool 전체에 이미지 획득/inference를 실행하고, normalized cosine과 변경되지 않은 양의 70/30 최종 점수를 계산한다. relevance 순위를 만든 뒤 첫 `min(10, count)`를 선택하고 그 shortlist에만 표시 정렬을 적용한다.
- [ ] Text-safe DOM 조작으로 name, seller, image URL, nullable price, nullable purchase link, image similarity, tag similarity, final score를 표시한다. Opaque token은 필수 UI 필드로 렌더링하지 않는다.
- [ ] Backend POST 1회, metadata API 없음, browser score 제출 없음, 영속화 없음, 기존 unavailable fallback 경로를 유지한다.
- [ ] Relevance top-K, 동일 통화 가격 오름차순, 통화 혼합/가격 누락 fallback, 동일 가격 relevance tie, final score 회귀, opaque ID, 모든 fallback class를 위한 browser test를 갱신한다.

### 작업 4: 검증, 리뷰, Draft PR 게시

**파일:**
- 검증에서 범위 내 결함을 찾지 않는 한 추가 source 파일 없음.

- [ ] Backend clean build, browser `npm test`, `npm run build`, `npm audit`, `git diff --check`를 새 출력으로 실행한다.
- [ ] 기존 환경만 사용해 허가된 local fixture/Shopify E2E를 1회 시도한다. 후보 수, metadata 완전성, relevance top-10, 가격 표시 순서, reranking latency를 기록하거나, 조작 없이 `NOT_RUN`/`USER_INPUT_REQUIRED`로 보고한다.
- [ ] 금지된 API, 식별자, score 영속화, 무관한 변경이 없는지 최종 diff를 검토한다. Code review subagent를 요청하고 중요한 지적을 해결한다.
- [ ] 저장소 commit 규칙에 맞게 최소 범위 diff를 commit하고 issue branch를 push한 뒤, `develop` 대상 Draft PR을 `Closes #332`, 검증 근거, snapshot 표현, 명시적 제외 범위와 함께 생성한다.
