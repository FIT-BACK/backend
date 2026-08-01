# Shopify 상품 공급자 PoC 및 채택 결정

> **문서 성격 (2026-08-01): 역사적 의사결정 기록(ADR).** 아래 호출 결과와 외부 조건은
> 2026-07-30 PoC 시점의 증거이며 현재 Shopify 응답·정책·운영 설정을 보장하지 않는다.
> 현재 코드는 [`application.properties`](../src/main/resources/application.properties)의
> `fixture`/`false`를 기본값으로 사용하고,
> [`ShoppingProviderConfig.java`](../src/main/java/com/fitback/backend/external/shopping/config/ShoppingProviderConfig.java)에서
> `SHOPPING_PROVIDER=shopify`와 `SHOPIFY_ENABLED=true`가 함께 설정된 경우 Shopify adapter를
> 선택한다. 실행 방법은 [README.md](../README.md)를 함께 확인한다.

> 상태: 최소 프로토타입에 한해 채택, 정식 운영 채택은 보류
>
> 검증 일시: 2026-07-30 08:04 KST
>
> 관련 이슈: #92

## 1. 결정

Shopify Global Catalog를 FIT-BACK 최소 프로토타입의 외부 상품 공급자 primary로 사용한다.
다만 정식 운영 공급자로 확정하지 않으며, 기본 runtime은 계속 `fixture`를 사용한다.
프로토타입에서만 `SHOPPING_PROVIDER=shopify`, `SHOPIFY_ENABLED=true`를 함께 설정해 명시적으로
활성화한다.

저장 및 표시 원칙은 다음과 같다.

- DB에는 `provider`, `externalProductId`, `externalVariantId`, `merchantId`만 저장한다.
- 상품명, 가격, 통화, 이미지, 재고, 구매 URL은 조회 시 `lookup_catalog`으로 다시 가져온다.
- 검색 결과와 이미지 파일을 FIT-BACK 서버 또는 S3에 캐시·복사하지 않는다.
- provider 장애 시 오래된 Shopify 표시값으로 대체하지 않는다.
- 로컬·테스트와 외부 공급자 비활성 환경은 결정적 `fixture`를 사용한다.
- 구매 URL은 사용자를 판매자 장바구니로 이동시키는 링크일 뿐이다. FIT-BACK이 구매를
  완료하거나 결제금을 청구하는 기능으로 취급하지 않는다.

이 결정은 Shopify가 `lookup_catalog`을 기존 ID의 최신 데이터 조회 용도로 설명하고, 검색
결과 캐시 및 이미지 다운로드·재사용을 금지하는 공식 사용 지침과 일치한다.

## 2. 공식 계약 근거

| 확인 항목 | 공식 근거 | FIT-BACK 적용 |
|---|---|---|
| 엔드포인트·인증 | Global Catalog MCP는 `https://catalog.shopify.com/api/ucp/mcp`를 사용하며 agent profile만으로 익명 호출 가능 | API key 없이 검증, profile URL은 설정으로 관리 |
| 검색·상세 갱신 | `search_catalog`은 상품 발견, `lookup_catalog`은 알려진 product/variant ID의 최신 데이터 조회 | 검색 후 ID만 materialize하고 모든 read에서 lookup |
| 이미지 | 상품 listing과 함께 실시간 표시해야 하며 서버 다운로드·재사용 금지 | 공급자 CDN URL만 응답, S3 복사 금지 |
| 검색 결과 | 가격·재고·표시 선호가 반영되므로 캐시 금지 | 검색 응답과 표시 snapshot을 영구 저장하지 않음 |
| 호출 제한 | 익명 tier가 가장 낮은 rate limit이며 keyless access는 상향 요청 불가 | 소량 프로토타입만 허용, 429는 공급자 오류로 변환 |
| 결제 | 익명 tier는 catalog/cart/checkout 도구에 접근할 수 있지만 `complete_checkout`은 불가 | 구매 URL 외부 이동만 제공, 대리 결제 미구현 |

공식 문서:

- [About Catalogs](https://shopify.dev/docs/agents/catalog)
- [Global Catalog MCP](https://shopify.dev/docs/agents/catalog/global-catalog)
- [Auth and rate limiting](https://shopify.dev/docs/agents/profiles/auth-and-rate-limiting)
- [Agent profiles and UCP negotiation](https://shopify.dev/docs/agents/profiles)

## 3. 고정 데이터셋과 실행 방법

공통 조건:

```text
endpoint: https://catalog.shopify.com/api/ucp/mcp
address_country: KR
language: ko
currency: KRW
pagination.limit: 3
authorization: 없음
agent profile: Shopify 공식 2026-04-08 test profile
```

고정 질의:

1. `minimal beige wide fit pants`
2. `women black blazer`
3. `white sneakers`

질의당 `search_catalog` 1회씩 호출하고, 첫 번째 결과의 variant ID를 `lookup_catalog` 1회로
재조회했다. 총 호출 수는 4회다. 원본 응답에는 시점에 따라 바뀌는 가격·구매 URL이 포함되므로
response snapshot과 실행 스크립트는 커밋하지 않는다.

## 4. 2026-07-30 최소 PoC 결과

| 호출 | HTTP | 응답시간 | 결과 |
|---|---:|---:|---|
| beige wide-fit pants 검색 | 200 | 577 ms | 3건 |
| women black blazer 검색 | 200 | 448 ms | 3건 |
| white sneakers 검색 | 200 | 440 ms | 3건 |
| 첫 variant lookup | 200 | 310 ms | 동일 product/variant 1건 |

관찰 결과:

- 9/9 결과에서 product ID, variant ID, 상품명, 가격, 통화, 이미지, 구매 URL, 재고,
  merchant ID를 확인했다.
- 첫 검색 결과를 variant ID로 lookup했을 때 product/variant ID와 가격 `56,900 KRW`가
  동일했다.
- 검색과 lookup 사이에 장바구니 URL의 `_gsid` query 값이 바뀌었다. 따라서 구매 URL을
  저장하지 않고 매 조회 시 갱신해야 한다는 현재 구현 방향이 타당하다.
- 요청 통화를 KRW로 지정했지만 9건 중 6건만 KRW였고 나머지는 USD 2건, EUR 1건이었다.
- `language=ko`로 요청했지만 상품명은 모두 영어였다.
- 모든 결과가 `available=true`였으나 이번 고정 호출은 `ships_to=KR` 필터와 실제 배송·관세
  검증을 포함하지 않았다.
- 4회 호출에서는 429나 JSON-RPC quota 오류가 발생하지 않았다. 이는 부하 한도 근거가
  아니며 의도적으로 rate-limit 유발 검증은 수행하지 않았다.

## 5. 후보 평가

| 항목 | 판정 | 근거 |
|---|---|---|
| 가입·인증 준비 | PASS | 익명 catalog 호출에 API key 불필요 |
| search 호출성 | PASS | 고정 질의 3/3 HTTP 200 |
| lookup 호출성 | PASS | variant ID로 동일 identity 조회 |
| 안정 식별자 | PASS | product/variant/merchant GID 제공 |
| 상품명·가격·통화 | PASS_WITH_LIMIT | 필드는 존재하지만 KRW 강제가 보장되지 않음 |
| 이미지·구매 경로 | PASS_WITH_POLICY | 필드는 존재하지만 실시간 표시만 허용 |
| 한국 적합도 | LIMITED | 한국어 제목과 KRW, 한국 배송 가능 여부가 일관되지 않음 |
| 개인정보 | PASS_FOR_PROTOTYPE | 국가·언어·통화만 전송, 사용자·결제정보 미전송 |
| rate limit | PENDING_SUPPORT_RESPONSE | 익명 tier가 최저라는 원칙만 공개되고 정확한 수치 미확인 |
| 비용·상업 조건 | PENDING_SUPPORT_RESPONSE | keyless PoC는 별도 유료 리소스 없이 실행했으나 정식 운영 가격표·보장 한도 미확인 |
| 저장·재표시 정책 | PASS | identity-only + live lookup + no image copy로 공식 지침 준수 |

지원 답변이 필요한 두 항목은 구현 blocker로 사용하지 않는다. 답변을 24시간 이상 기다리지
않고 익명 저용량 프로토타입과 `fixture` fallback으로 진행한다. 정확한 quota 또는 상업 조건이
확인되기 전에는 대량 트래픽, 자동 재시도 폭증, 검색 결과 저장을 활성화하지 않는다.

## 6. 운영 경계와 후속 조건

최소 프로토타입 허용 범위:

- 익명 `search_catalog`과 `lookup_catalog`
- 명시적 feature toggle
- product/variant/merchant identity 저장
- 가격·통화 원문 표시
- 공급자 장애를 사용자에게 부분 실패로 전달

정식 운영 채택 전 필수 확인:

1. FIT-BACK 소유 도메인에 production agent profile 게시
2. Dev Dashboard 또는 지원 채널에서 예상 QPS 기준 quota·상향 절차·비용 확인
3. `ships_to=KR` 필터 적용 후 한국 배송 가능 결과 비율 재측정
4. 비-KRW 통화 UX와 배송비·관세 고지 확정
5. 429 backoff, circuit breaker, 관측 지표 기준 확정

이 조건을 충족하지 못하면 Shopify는 프로토타입 후보로만 유지하고, 정식 운영 primary 선정은
다른 공급자 PoC와 함께 다시 결정한다.
