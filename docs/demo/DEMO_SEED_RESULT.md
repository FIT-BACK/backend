# 데모데이 시드 데이터 결과

## 목적

- 라이브 업로드 또는 외부 네트워크 문제 발생 시 사용할 사전 분석/추천 결과
- 실제 AI 모델이 아닌 prototype 분석 태그(`미니멀`, `와이드핏`, `베이지톤`) 기반 결과
- 추천 상품 표시는 Shopify live lookup으로 검증

## 생성 결과

| 구분 | imageId | reportId | recommendationCount | liveFieldCount | selectedItemCount | saved | listed | productDetailDataStatus |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- | --- |
| Demo-0 | 5418186b-e702-44bc-9219-0e15e2578b72 | 11 | 27 | 18 | 5 | true | true | LIVE |
| Demo-1 | c0e0134d-01c3-4a75-ac79-0547b1a593bb | 12 | 26 | 19 | 5 | true | true | LIVE |

### 항목 설명

| 항목 | 설명 |
| --- | --- |
| recommendationCount | 추천 결과에 포함된 전체 상품 수 |
| liveFieldCount | 이미지, 상품명, 가격, 구매 링크가 모두 있는 추천 상품 수 |
| selectedItemCount | 분석 리포트 저장 시 카테고리별로 선택한 상품 수 |
| saved | 분석 리포트 저장 성공 여부 |
| listed | 저장한 분석 리포트가 목록에 표시되는지 여부 |
| productDetailDataStatus | 첫 번째 추천 상품의 실시간 상세 조회 상태 |

## 확인 방법

1. 운영 Swagger에 접속한다.
2. 데모 계정으로 로그인한다.
3. `Authorization`에 `Bearer {accessToken}`을 설정한다.
4. `GET /api/v1/analyses`에서 해당 계정의 리포트가 목록에 표시되는지 확인한다.
5. Demo-0은 `GET /api/v1/analyses/11`, Demo-1은 `GET /api/v1/analyses/12`를 조회한다.
6. 추천 항목의 `productId`로 `GET /api/v1/products/{productId}`를 조회한다.

## 정상 기준

- 분석 결과의 `imageUrl`, `tags`, `matchPercentage`가 존재한다.
- 분석 결과가 저장 상태이며 분석 리포트 목록에 표시된다.
- 추천 결과의 `recommendationGroups[].items[]`가 존재한다.
- 상품 상세의 `imageUrl`, `name`, `price.amount`, `purchaseUrl`이 존재한다.
- 상품 상세의 `dataStatus`가 `LIVE`다.

## 주의사항

- 데모 계정 비밀번호, accessToken, refreshToken, Presigned POST 값은 기록하지 않는다.
- Shopify live lookup에 의존하므로 데모 직전에 상품 상세 조회 상태를 다시 확인한다.
