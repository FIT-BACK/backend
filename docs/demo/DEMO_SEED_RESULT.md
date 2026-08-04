# 데모데이 시드 데이터 결과

## 목적

- 라이브 업로드 또는 외부 네트워크 문제 발생 시 사용할 사전 분석·추천 결과
- 실제 AI 모델이 아닌 prototype 분석 태그 기반 결과
- 추천 상품 표시는 Shopify live lookup으로 검증

## 생성 결과

### 데모 계정 분석 결과

| 구분 | imageId | reportId | recommendationCount | liveFieldCount | selectedItemCount | saved | listed | productDetailDataStatus |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- | --- |
| Demo-0 | 5418186b-e702-44bc-9219-0e15e2578b72 | 11 | 27 | 18 | 5 | true | true | LIVE |
| Demo-1 | c0e0134d-01c3-4a75-ac79-0547b1a593bb | 12 | 26 | 19 | 5 | true | true | LIVE |

### 콘텐츠 계정 생성 결과

| imageId | reportId | lookbookId | recommendedProductId | recommendationCount | reportSaved | 최종 좋아요 수 |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| 4ddaf294-993e-4027-94e4-88827961f2d5 | 35 | 4 | 162 | 29 | true | 2 |

- 분석 태그: `스트릿`, `캐주얼`, `streetwear`, `oversized`, `black`
- 매칭 비율: 70
- 룩북 이미지: 분석 원본 이미지와 별도로 업로드한 룩북 매칭 이미지 사용
- 생성 확인 시각: 2026-08-05 00:13 KST

### 계정별 상호작용 결과

| 구분 | lookbookId | 좋아요 | 룩북 저장 | 트렌드 저장 |
| --- | ---: | --- | --- | --- |
| Demo-0 | 4 | true | true | false |
| Demo-1 | 4 | true | true | false |

운영 서버의 트렌드 목록이 비어 있고 트렌드 생성 API도 없어 이번 실행에서는 트렌드 저장 데이터를 만들지 않았다.
트렌드 콘텐츠가 추가되면 같은 스크립트를 다시 실행해 계정별 저장 상태를 생성할 수 있다.

## 항목 설명

| 항목 | 설명 |
| --- | --- |
| recommendationCount | 추천 결과에 포함된 전체 상품 수 |
| liveFieldCount | 이미지, 상품명, 가격, 구매 링크가 모두 있는 추천 상품 수 |
| selectedItemCount | 분석 리포트 저장 시 카테고리별로 선택한 상품 수 |
| saved | 분석 리포트 저장 성공 여부 |
| listed | 저장한 분석 리포트가 목록에 표시되는지 여부 |
| productDetailDataStatus | 첫 번째 추천 상품의 실시간 상세 조회 상태 |

## 데모 시나리오와 API 관계

| 데모 시나리오 | 확인 API | 확인하는 이유 |
| --- | --- | --- |
| 저장한 분석 결과 다시 보기 | `GET /api/v1/analyses`, `GET /api/v1/analyses/{reportId}` | 업로드를 다시 하지 않아도 마이페이지의 분석 목록에서 기존 결과와 추천 상품을 열 수 있는지 확인 |
| 콘텐츠 계정이 만든 룩북 보기 | `GET /api/v1/lookbooks/{lookbookId}` | 원본 착장과 매칭 착장, 작성자, 태그가 룩북 상세 화면에 필요한 형태로 제공되는지 확인 |
| 데모 사용자의 룩북 좋아요 보기 | `GET /api/v1/lookbooks/{lookbookId}` | 같은 룩북이라도 로그인한 사용자에 따라 `isLiked`가 다르게 계산되는지 확인 |
| 마이 클로젯에 저장한 룩북 보기 | `GET /api/v1/closet-saves?target_type=LOOKBOOK` | 좋아요와 별개로 저장한 룩북이 계정별 마이 클로젯에 유지되는지 확인 |
| 추천 상품의 현재 정보 보기 | `GET /api/v1/products/{productId}` | DB의 상품 식별 정보로 Shopify의 현재 이미지, 상품명, 가격, 구매 링크를 조회할 수 있는지 확인 |

## Swagger 확인 방법

운영 Swagger: `https://d1ra74et9h0ohu.cloudfront.net/swagger-ui.html`

### 로그인 및 인증 설정

분석 결과, 좋아요 여부, 마이 클로젯 저장 상태는 계정마다 다르므로 확인할 계정의 토큰을 먼저 설정한다.

1. 확인할 계정으로 `POST /api/v1/auth/login`을 실행한다.
2. 응답의 `data.accessToken`을 복사한다.
3. Swagger 우측 상단 `Authorize`에 `Bearer {accessToken}` 형식으로 입력한다.

### Demo-0 저장 분석 결과

**확인 목적:** Demo-0이 미리 저장한 분석 결과를 목록에서 다시 선택하고 추천 결과 화면까지 열 수 있는지 확인한다.

1. `GET /api/v1/analyses`를 호출한다. 이 API는 현재 계정이 저장한 분석 리포트 목록을 반환한다.
2. `reportId=11`이 표시되는지 확인한다. 목록에 있어야 프론트의 저장된 분석 화면에서 해당 리포트를 선택할 수 있다.
3. `GET /api/v1/analyses/11`을 호출한다. 이 API는 분석 이미지, 태그, 매칭 비율과 추천 상품 묶음을 반환한다.
4. `saved=true`인지 확인한다. 현재 계정이 리포트를 저장한 상태라는 의미다.
5. `recommendationGroups[].items[]`가 존재하는지 확인한다. 데모에서 분석 결과에 이어 추천 상품을 표시하기 위해 필요하다.

### Demo-1 저장 분석 결과

**확인 목적:** Demo-1에서도 Demo-0과 분리된 본인 분석 결과를 목록과 상세 화면에서 조회할 수 있는지 확인한다.

1. `GET /api/v1/analyses`를 호출한다.
2. `reportId=12`가 표시되는지 확인한다. Demo-1 소유의 저장 리포트가 Demo-1 목록에 연결됐다는 의미다.
3. `GET /api/v1/analyses/12`를 호출한다.
4. `saved=true`이고 `recommendationGroups[].items[]`가 존재하는지 확인한다. 저장 상태와 추천 결과 재진입을 함께 검증한다.

### 콘텐츠 계정 분석·룩북

**확인 목적:** 콘텐츠 계정이 만든 스트릿·캐주얼 분석 결과가 룩북으로 이어지고, 공개 룩북 상세 화면에 필요한 정보가 모두 제공되는지 확인한다.

1. 콘텐츠 계정으로 `GET /api/v1/analyses`를 호출한다. 이 API는 콘텐츠 계정이 저장한 분석 리포트 목록을 반환한다.
2. 목록에 `reportId=35`가 있는지 확인한다. 콘텐츠 계정도 분석 결과를 저장했으며 화면에서 ID를 외우지 않고 다시 찾을 수 있다는 의미다.
3. `GET /api/v1/analyses/35`를 호출한다. 이 리포트는 룩북 원본 착장의 분석·추천 결과다.
4. `originalImageId=4ddaf294-993e-4027-94e4-88827961f2d5`인지 확인한다. 준비한 분석 이미지와 리포트가 올바르게 연결됐는지 판단하는 값이다.
5. `matchPercentage=70`, `recommendationStatus=CURRENT`, `saved=true`인지 확인한다. 추천이 최신 상태이며 재조회 가능한 저장 결과라는 의미다.
6. `tags`에 `스트릿`, `캐주얼`, `streetwear`, `oversized`, `black`이 있는지 확인한다. 고정 prototype 태그가 아니라 데모용으로 확정한 태그가 추천에 사용됐는지 검증한다.
7. `GET /api/v1/lookbooks/4`를 호출한다. 이 API는 룩북 상세 화면에 사용할 이미지, 작성자, 태그와 좋아요 상태를 반환한다.
8. `originalImageUrl`과 `matchedImageUrl`이 모두 존재하는지 확인한다. 룩북의 원본 착장과 매칭 착장을 함께 표시하기 위해 필요하다.
9. `authorNickname=fitback_creator`, 태그가 `스트릿`, `캐주얼`인지 확인한다. 콘텐츠 계정과 룩북 분류가 올바른지 판단한다.
10. `likeCount=2`, `isOwner=true`인지 확인한다. 두 데모 계정의 좋아요가 반영됐고 현재 로그인 계정이 작성자임을 의미한다.

### Demo-0·Demo-1 룩북 상호작용

**확인 목적:** 콘텐츠 계정이 만든 동일한 룩북에 대해 Demo-0과 Demo-1의 좋아요 및 마이 클로젯 저장 상태가 각각 유지되는지 확인한다.

각 데모 계정으로 로그인한 뒤 아래 항목을 확인한다.

1. `GET /api/v1/lookbooks/4`를 호출한다.
2. `isLiked=true`인지 확인한다. 현재 로그인한 데모 계정이 이 룩북에 좋아요를 누른 상태라는 의미다.
3. `isOwner=false`인지 확인한다. 룩북 작성자는 콘텐츠 계정이고 데모 계정은 상호작용만 한 사용자임을 검증한다.
4. `GET /api/v1/closet-saves?target_type=LOOKBOOK`을 호출한다. 이 API는 현재 계정이 마이 클로젯에 저장한 룩북만 반환한다.
5. `items`에 `targetId=4`인 항목이 있는지 확인한다. 좋아요 여부와 별개로 룩북 저장 관계가 유지되고 있다는 의미다.

### 추천 상품 상세

**확인 목적:** 분석 리포트에 저장된 상품 ID를 이용해 데모 시점의 최신 Shopify 상품 정보를 가져올 수 있는지 확인한다.

1. `GET /api/v1/products/162`를 호출한다. 이 API는 내부 상품 ID를 Shopify 상품 정보와 연결해 상세 데이터를 반환한다.
2. `imageUrl`, `name`, `price.amount`, `purchaseUrl`이 존재하는지 확인한다. 프론트의 추천 상품 카드와 구매 이동에 필요한 값이다.
3. `dataStatus=LIVE`인지 확인한다. DB의 오래된 표시용 값이 아니라 Shopify에서 실시간으로 조회한 결과라는 의미다.

## 정상 기준

- 분석 결과에 `imageUrl`, `tags`, `matchPercentage`가 존재한다.
- 분석 결과가 저장 상태이며 분석 리포트 목록에 표시된다.
- 추천 결과에 `recommendationGroups[].items[]`가 존재한다.
- 콘텐츠 룩북에 원본 이미지와 매칭 이미지가 모두 표시된다.
- Demo-0·Demo-1 모두 콘텐츠 룩북에 좋아요 및 저장 상태로 표시된다.
- 상품 상세에 `imageUrl`, `name`, `price.amount`, `purchaseUrl`이 존재한다.
- 상품 상세의 `dataStatus`가 `LIVE`다.

## 주의사항

- 데모 계정 비밀번호, accessToken, refreshToken, Presigned POST 값은 기록하지 않는다.
- Shopify live lookup에 의존하므로 데모 직전에 상품 상세 조회 상태를 다시 확인한다.
- 트렌드 저장 확인은 운영 트렌드 콘텐츠가 준비된 뒤 진행한다.
