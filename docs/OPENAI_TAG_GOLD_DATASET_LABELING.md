# OpenAI 태그 Gold Dataset 라벨링 기준

## 현재 상태

저장소에는 재사용 가능한 안전한 실제 이미지 또는 fixture 이미지가 없다. 따라서 이 문서는
이미지나 gold label을 추가하지 않으며, 승인·라벨링된 외부 데이터셋이 준비되기 전까지
OpenAI baseline 실행은 중단한다.

## 필요한 최소 데이터셋

runner의 JSON Schema상 최소값은 case 1건이지만, 의류 범주별 결과를 해석할 수 있는 최소
baseline은 아래 5건이다. 각 이미지는 명확한 주 의류 1점만 포함하고, canonical tag을 1개 이상
가져야 한다.

| 수 | 주 의류 범주 | 요구사항 |
| --- | --- | --- |
| 1 | TOP | 상의 1점이 전체적으로 보이는 승인 이미지 |
| 1 | PANTS | 바지 1점이 전체적으로 보이는 승인 이미지 |
| 1 | SKIRT | 치마 1점이 전체적으로 보이는 승인 이미지 |
| 1 | DRESS | 원피스 1점이 전체적으로 보이는 승인 이미지 |
| 1 | OUTER | 아우터 1점이 전체적으로 보이는 승인 이미지 |

SHOES는 tag master의 복종 적용 범주가 아니므로 이 최소셋에서 제외한다. 추천 rank와
reasonCode도 이 데이터셋의 정답에 포함하지 않는다.

## 이미지·정답 수용 기준

1. 이미지의 사용 권리와 출처를 사람이 확인한다. 개인정보·식별 가능한 인물·민감정보가 포함된
   이미지는 사용하지 않는다.
2. 주 의류 이외의 의류, 가림, 강한 조명 왜곡, 저해상도, 또는 판정하기 어려운 속성이 있으면
   제외한다. 보이지 않는 소재·핏·디테일·스타일을 추론해 라벨하지 않는다.
3. `expectedCanonicalTags`에는
   [`canonical-catalog.v25.json`](../scripts/poc/ai-tag-evaluation/canonical-catalog.v25.json)의
   정확한 `(type, name)` 쌍만 넣는다. 대소문자·띄어쓰기·슬래시를 포함해 이름을 바꾸거나
   free-form tag을 추가하지 않는다.
4. 서로 독립적인 두 명의 라벨러가 각각 모든 tag을 작성하고, 불일치 사례는 검토자가 근거를
   확인해 합의하지 못하면 제외한다. 라벨 결정 근거와 이미지 사용 승인 기록은 Git 밖의 안전한
   위치에 보관한다.
5. 확정된 case만 Schema의 필드(`imageId`, `imagePath`, `expectedCanonicalTags`)로 옮긴다.
   라벨러·출처·검토 상태 같은 운영 메타데이터는 strict schema에 추가하지 않는다.

## 외부 데이터셋 레이아웃과 실행 전 확인

gold data는 Git 밖의 단일 디렉터리에 둔다.

```text
<secure-dataset>/
├── gold-labels.json
└── images/
    ├── top-01.jpg
    ├── pants-01.jpg
    ├── skirt-01.jpg
    ├── dress-01.jpg
    └── outer-01.jpg
```

`gold-labels.json`은 `gold-labels.schema.json`을 따르고, 각 `imagePath`는 이 디렉터리를
기준으로 한 JPEG, PNG 또는 WebP 상대 경로여야 한다. 별도 image catalog나 production DB는
사용하지 않는다. 모든 라벨이 합의된 뒤에만 `openAiTagEvaluation`을 실행한다.
