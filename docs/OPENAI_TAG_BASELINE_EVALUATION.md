# OpenAI 태그 baseline 평가

`openAiTagEvaluation`은 FIT-BACK production API나 데이터베이스를 호출하거나 변경하지 않는다.
승인된 이미지와 gold label을 로컬 경로에서 읽어 OpenAI Responses API만 직접 호출하며, 요청에는
기존 클라이언트와 동일하게 `store=false`가 적용된다. 기존 `aiTagBlindEvaluation`은 수정하거나
대체하지 않는다.

## Gold label 준비

저장소에는 커밋 가능한 안전 이미지 fixture가 없으므로
`scripts/poc/ai-tag-evaluation/gold-labels.template.json`은 의도적으로 비어 있으며 JSON Schema의
실행 가능한 dataset 예시는 아니다. 실제 이미지와
gold label은 Git 밖의 단일 디렉터리에 둔다. 형식은
[`gold-labels.schema.json`](../scripts/poc/ai-tag-evaluation/gold-labels.schema.json)을 따른다.
이미지를 고르고 gold label을 확정하는 기준과 최소 범주 coverage는
[`OPENAI_TAG_GOLD_DATASET_LABELING.md`](OPENAI_TAG_GOLD_DATASET_LABELING.md)를 따른다.

```json
{
  "cases": [
    {
      "imageId": "<stable-image-id>",
      "imagePath": "images/<image-file>.jpg",
      "expectedCanonicalTags": [
        {"type": "<TagType>", "name": "<approved-catalog-name>"}
      ]
    }
  ]
}
```

`imagePath`는 dataset JSON 파일의 디렉터리를 기준으로 한 상대 경로이며 JPEG, PNG, WebP만
허용한다. `type`은 `SILHOUETTE`, `COLOR`, `DETAIL`, `STYLE`, `MATERIAL` 중 하나다. 모든
기대 태그 `(type, name)`는 실행 시 전달하는 catalog JSON에 있어야 한다. gold label의 빈
`cases`는 실행할 수 없으며, 이미지 파일과 임의 정답을 저장소에 추가해서는 안 된다.

## 실행

평가용 catalog는 V25 승인 taxonomy를 고정한
[`canonical-catalog.v25.json`](../scripts/poc/ai-tag-evaluation/canonical-catalog.v25.json)을
사용한다. API key와 실제 이미지 경로는 쉘의 보안된 환경변수 또는 비밀 관리 도구로만 제공하며,
명령·로그·결과를 Git에 추가하지 않는다.

```bash
AI_TAG_EVALUATION_DATASET=/secure/path/gold-labels.json \
AI_TAG_EVALUATION_CATALOG=scripts/poc/ai-tag-evaluation/canonical-catalog.v25.json \
FITBACK_AI_OPENAI_API_KEY=... \
FITBACK_AI_OPENAI_MODEL=gpt-5.6-luna \
FITBACK_AI_REQUEST_TIMEOUT=PT30S \
./gradlew openAiTagEvaluation
```

기본 결과 파일은 `build/openai-tag-evaluation/openai-tag-evaluation.json`이며,
`AI_TAG_EVALUATION_OUTPUT_DIR`로 변경할 수 있다.

평가 runner는 동일한 prompt/request를 유지한 채 provider HTTP `500`, `502`, `503`, `504`에만
최대 2회 추가 시도한다. 첫 retry는 `250–500ms`, 두 번째 retry는 `500–1000ms`의 짧은
exponential backoff+jitter를 사용한다. 4xx/429, timeout·transport, response parsing·schema·canonical
실패는 자동 retry하지 않는다. production `OpenAiTagModelClient` 호출 경로에는 이 정책이 적용되지 않는다.

## 결과 해석

- `summary.micro`와 `summary.macro`는 canonical tag set 기준 precision, recall, F1이다. 실패한
  호출은 예측이 없는 사례로 계산해, 결과가 과대평가되지 않게 한다.
- `exactMatchCases`와 `exactMatchRate`는 기대 태그 set과 예측 tag set이 완전히 같은 사례의 수와 비율이다.
- 각 사례의 `falseNegatives`는 누락 태그, `falsePositives`는 과잉 태그다.
- summary와 각 사례의 `falseNegatives`, `falsePositives`, `unknownCanonicalTags`는 총 개수와
  `(type, name)`별 빈도를 기록한다. unknown 출력은 동시에 false positive로 집계한다.
- `latency`는 성공한 OpenAI 호출의 밀리초 집계다. `tokens.input`과 `tokens.output`은 OpenAI가 사용량을 반환한 호출만 합산하며, 미보고 사용량은 `null`이다.
- 실패 사례의 `error`에는 `ANALYSIS409_1` 같은 안전한 도메인 오류 코드 또는 입력/예상 밖 실패
  category만 기록한다. raw 요청·응답·예외 메시지는 기록하지 않는다. 실패 사례는 baseline의
  tag precision/recall/F1 및 exact match 계산에는 예측이 없는 사례로 포함한다. latency와 token
  집계는 실제 OpenAI 응답을 받은 성공 호출만 사용한다.
- 각 사례에는 총 호출 횟수 `attemptCount`와 최종 평가 상태 `finalStatus`(`SUCCESS` 또는 `FAILED`)를
  기록한다. provider 실패 시 `providerHttpStatus`, `providerErrorCategory`, `responseParsingCategory`는
  최종 시도의 안전한 메타데이터만 보존하며, raw response·API key·image bytes/data URL은 기록하지 않는다.

추천 rank 및 reasonCode 평가는 이 runner의 범위에 포함하지 않는다.
