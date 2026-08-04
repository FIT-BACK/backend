# AI 태그 모델 블라인드 평가

OpenAI와 Amazon Bedrock 모델을 동일한 이미지, 프롬프트, JSON Schema, 태그 카탈로그로
호출한다. 운영에서는 두 공급자 모두 `CanonicalAiTagAnalyzer`를 사용하므로
`AnalysisService`가 의존하는 `AiTagAnalyzer` 계약은 바뀌지 않는다.

모델 응답은 다음 두 목록을 함께 반환한다.

- `canonicalTags`: 승인된 카탈로그 안에서만 선택하며 운영 `AiTagAnalyzer`가 DB `Tag`로
  변환할 수 있는 목록
- `suggestedTags`: 카탈로그에 없지만 이미지에서 직접 확인되는 자유 생성 후보로, 타입,
  한국어 이름, 신뢰도, 시각 근거를 포함하는 평가 전용 목록

`suggestedTags`는 분석 결과나 태그 테이블에 자동 저장하지 않는다. 유사어 정규화와 중복
병합을 거친 뒤 관리자가 승인한 후보만 별도 카탈로그 변경으로 반영한다.

## 운영 공급자 선택

```env
# OpenAI
FITBACK_AI_TAG_ANALYZER=openai
OPENAI_API_KEY=...
OPENAI_MODEL=gpt-5.6-luna

# 또는 Bedrock
FITBACK_AI_TAG_ANALYZER=bedrock
BEDROCK_REGION=ap-northeast-2
BEDROCK_MODEL_ID=global.anthropic.claude-haiku-4-5-20251001-v1:0
```

Bedrock은 AWS SDK 기본 자격 증명 체인을 사용하며 해당 모델의 `bedrock:InvokeModel`
권한과 리전/프로필의 모델 접근 권한이 필요하다. 키와 실제 이미지는 커밋하지 않는다.

## 블라인드 평가 실행

1. 실제 FIT-BACK 평가 이미지를 저장소 밖의 한 디렉터리에 넣는다. 파일명에는 정답이나
   공급자 정보를 넣지 않는다.
2. `catalog.example.json`을 복사하고 제품에서 승인한 태그만 남긴다. 예제의 MATERIAL
   값은 평가 시작점이며 DB 시드가 아니다.
3. 다음을 실행한다.

```bash
AI_TAG_BLIND_IMAGES_DIR=/absolute/path/to/fitback-images \
AI_TAG_BLIND_CATALOG=scripts/poc/ai-tag-blind/catalog.example.json \
OPENAI_API_KEY=... \
AWS_PROFILE=... \
./gradlew aiTagBlindEvaluation
```

`build/ai-tag-blind/blind-results.json`에는 무작위로 정해진 모델 `A`/`B`의
`canonicalTags`와 `suggestedTags`가 기록된다.
검수자는 키를 보기 전에 이미지별 태그 정확성, 누락, 과잉 태그를 평가한다. 평가를
마친 다음 별도 파일 `blind-key.json`을 열어 공급자를 공개한다. 두 결과 파일에는 이미지
바이트, API 키, AWS 자격 증명, 절대 경로가 기록되지 않는다.

평가 항목은 다음을 권장한다.

- 정확 태그 precision/recall 및 이미지 완전 일치율
- STYLE 및 MATERIAL의 타입-이름 정확성
- 자유 생성 태그의 구체성, 중복·유사어 비율, 신뢰도와 시각 근거의 일치성
- 육안으로 확인할 수 없는 MATERIAL의 과잉 추론 횟수
- 실패율, 응답 시간, 입력/출력 토큰

실제 운영 분석기는 모델의 `canonicalTags`가 카탈로그 밖 이름, 잘못된 타입 조합, 중복,
0개 또는 8개 초과를 포함하면 `ANALYSIS_NOT_READY`로 실패시킨다. 자유 생성
`suggestedTags`는 이 저장 경로에 들어가지 않는다.
