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

### 트렌드 기본 데이터

`V27__seed_trend_contents.sql`이 적용되면 콘텐츠 계정을 작성자로 사용하는 트렌드 6건이 생성된다.
트렌드 ID는 프론트의 `targetId`와 저장 API가 같은 콘텐츠를 가리키도록 1~6으로 고정한다.

| trendId | 제목 | 태그(관련도) |
| ---: | --- | --- |
| 1 | 미니멀 무드 | 미니멀(100), 뉴트럴(10), 와이드핏(1) |
| 2 | 스트릿 무드 | 스트릿(100), 오버사이즈(10), 캐주얼(1) |
| 3 | 러블리 무드 | 러블리(100), 페미닌(10) |
| 4 | 캐주얼 무드 | 캐주얼(100), 데일리룩(10) |
| 5 | 오피스 포멀 무드 | 포멀(100), 오피스룩(10) |
| 6 | 어떤 자리에서도 손색없는 캐주얼+포멀조합 | 캐주얼(100), 포멀(100) |

- 트렌드 작성자: Content 계정(`fitback.demo+content@gmail.com`)
- 관련도는 트렌드와 태그가 얼마나 밀접한지 나타내며, 관련 룩북의 노출 순서를 계산할 때 사용한다.
- V27은 Content 계정이 없거나 트렌드 ID 1~6이 이미 사용 중이면 잘못된 데이터를 만들지 않고 중단된다.

### 트렌드 관련 룩북 샘플

Content 계정으로 트렌드별 룩북 샘플을 3개씩 생성했다. 원본은 사용자가 상의·하의·아우터 영역을
잘라 올린 것처럼 구성했다. 매칭 이미지는 원본과 다른 모델·자세·배경의 자연스러운 착용 사진과
상품 상세 페이지에서 사용하는 모델 착용 사진을 함께 사용했다.

| trendId | lookbookId | 태그 | 이미지 범위 |
| ---: | ---: | --- | --- |
| 1 | 7 | 미니멀, 뉴트럴 | 상의 |
| 1 | 13 | 미니멀, 와이드핏 | 하의 |
| 1 | 14 | 미니멀, 뉴트럴 | 상의 |
| 2 | 8 | 스트릿, 오버사이즈 | 아우터 |
| 2 | 15 | 스트릿, 오버사이즈 | 하의 |
| 2 | 16 | 스트릿, 오버사이즈 | 아우터 |
| 3 | 9 | 러블리, 페미닌 | 상의 |
| 3 | 17 | 러블리, 페미닌 | 하의 |
| 3 | 18 | 러블리, 페미닌 | 상의 |
| 4 | 10 | 캐주얼, 데일리룩 | 하의 |
| 4 | 19 | 캐주얼, 데일리룩 | 상의 |
| 4 | 20 | 캐주얼, 데일리룩 | 아우터 |
| 5 | 11 | 포멀, 오피스룩 | 아우터 |
| 5 | 21 | 포멀, 오피스룩 | 상의 |
| 5 | 22 | 포멀, 오피스룩 | 하의 |
| 6 | 12 | 캐주얼, 포멀 | 하의 |
| 6 | 23 | 캐주얼, 포멀 | 상의 |
| 6 | 24 | 캐주얼, 포멀 | 아우터 |

- 원본과 매칭 이미지는 모두 FIT-BACK 이미지 업로드 API를 거쳐 S3에 저장했다.
- 추가 룩북 12건의 원본·매칭 이미지 URL 24개가 모두 HTTP 200으로 열리는 것을 확인했다.
- `GET /api/v1/trends/{trendId}/lookbooks?pageSize=3`에서 트렌드별 룩북 3개가 모두 조회된다.
- 실제 조회 순서는 트렌드 1부터 `14·7·13`, `16·15·8`, `18·17·9`, `20·19·10`,
  `22·21·11`, `24·23·12`다.

### 계정별 상호작용 결과

| 구분 | lookbookId | 좋아요 | 룩북 저장 | trendId | 트렌드 저장 |
| --- | ---: | --- | --- | ---: | --- |
| Demo-0 | 4 | true | true | 6 | true |
| Demo-1 | 4 | true | true | 5 | true |

### 트렌드 룩북 상호작용 결과

각 데모 계정이 트렌드마다 서로 다른 룩북에 좋아요와 마이 클로젯 저장을 남겼다.

| 구분 | lookbookId | 좋아요 | 룩북 저장 |
| --- | --- | --- | --- |
| Demo-0 | 13, 15, 17, 19, 21, 23 | 모두 true | 모두 true |
| Demo-1 | 14, 16, 18, 20, 22, 24 | 모두 true | 모두 true |

이 데이터는 홈이나 트렌드 상세에서 로그인 계정에 따라 `isLiked`와 저장 상태가 다르게 표시되는지
확인하기 위해 사용한다.

Demo-0은 트렌드 `6`, Demo-1은 트렌드 `5`를 마이 클로젯에 저장했다. 아래 명령은 이미 저장된
룩북과 트렌드를 중복 생성하지 않고 현재 상태를 다시 확인한다.

```bash
bash .local/demo/run_seed.sh --prepare-interactions
```

실제 결과는 `.local/demo/prepared-data.json`의 `interactions.accounts[].trendId`와
`trendSaved`에서 확인한다.

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
| 홈 트렌드 목록과 상세 보기 | `GET /api/v1/trends`, `GET /api/v1/trends/{trendId}` | V27로 생성한 트렌드 1~6의 제목, 이미지, 설명, 태그가 화면에 필요한 형태로 제공되는지 확인 |
| 트렌드 관련 룩북 보기 | `GET /api/v1/trends/{trendId}/lookbooks` | 트렌드 태그와 공개 룩북 태그의 관련도에 따라 룩북이 조회되는지 확인 |
| 마이 클로젯에 저장한 트렌드 보기 | `POST /api/v1/closet-saves`, `GET /api/v1/closet-saves?target_type=TREND` | 선택한 트렌드가 현재 계정의 저장 목록에 유지되고 상세의 `isSaved`에 반영되는지 확인 |
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

### 트렌드 기본 데이터 조회

**확인 목적:** V27로 준비한 트렌드 1~6이 홈 목록과 상세 화면에서 조회 가능한지 확인한다.

1. 인증하지 않은 상태로 `GET /api/v1/trends`를 호출한다. 비로그인 사용자는 전체 트렌드를 최신순으로 조회한다.
2. 응답의 `items`에서 `trendId=1`부터 `trendId=6`까지 존재하는지 확인한다. 첫 응답에 모두 없다면 `nextCursor`로 다음 목록을 조회한다.
3. `GET /api/v1/trends/{trendId}`를 1~6에 대해 호출한다.
4. 각 상세 응답에 제목, `imageUrl`, `description`, `tags`가 존재하는지 확인한다.
5. `GET /api/v1/trends/{trendId}/lookbooks?pageSize=3`를 호출한다.
6. 트렌드별로 위 표에 기록된 룩북 3개가 조회되는지 확인한다.
7. 조회된 룩북의 `originalImageUrl`, `matchedImageUrl`이 모두 존재하는지 확인한다.

### Demo-0·Demo-1 트렌드 저장

**확인 목적:** 트렌드 카드의 저장 동작이 계정별 마이 클로젯과 트렌드 상세 상태에 반영되는지 확인한다.

각 데모 계정으로 로그인한 뒤 아래 항목을 확인한다.

1. `GET /api/v1/closet-saves?target_type=TREND`를 호출한다.
2. Demo-0은 `targetId=6`, Demo-1은 `targetId=5`인 항목이 있는지 확인한다.
3. Demo-0은 `GET /api/v1/trends/6`, Demo-1은 `GET /api/v1/trends/5`를 호출한다.
4. 각 상세 응답의 `isSaved=true`인지 확인한다.
5. 저장 데이터를 다시 준비해야 하면 `bash .local/demo/run_seed.sh --prepare-interactions`를 실행한다.

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
- Demo-0·Demo-1이 각자 선택한 트렌드 룩북 6개에 좋아요 및 저장 상태로 표시된다.
- 트렌드 1~6의 목록과 상세 정보가 조회된다.
- 트렌드 1~6의 관련 룩북 첫 페이지에서 트렌드별 룩북 3개가 조회된다.
- 트렌드 룩북의 원본은 선택한 옷 부위 중심으로, 매칭 이미지는 자연스러운 룩북 또는 상품 상세
  착용 사진으로 표시된다.
- 로그인한 계정에서 저장한 트렌드가 마이 클로젯에 표시되고 상세의 `isSaved=true`로 반환된다.
- 상품 상세에 `imageUrl`, `name`, `price.amount`, `purchaseUrl`이 존재한다.
- 상품 상세의 `dataStatus`가 `LIVE`다.

## 주의사항

- 데모 계정 비밀번호, accessToken, refreshToken, Presigned POST 값은 기록하지 않는다.
- Shopify live lookup에 의존하므로 데모 직전에 상품 상세 조회 상태를 다시 확인한다.
- 문서의 계정별 트렌드 저장 결과는 스크립트를 실제 실행한 뒤 기록된 값만 사용한다.
