# OpenAI 응답 관측성 구현 계획

> **에이전트 작업자용:** 필수 하위 스킬: 검증 체크포인트를 둔 인라인 실행을 사용한다. 단계 추적에는 체크박스(`- [ ]`) 형식을 사용한다.

**목표:** `OpenAiTagModelClient`가 전송, HTTP, 응답 구조, 출력 추출, 모델 출력 파싱 실패를 구분하면서 안전한 응답 메타데이터만 로깅하도록 한다.

**아키텍처:** 기존 `ANALYSIS_NOT_READY` 비즈니스 계약과 전송 payload는 변경하지 않는다. 공급자 응답을 명시적인 단계로 파싱하고, 제한된 메타데이터(`responseStatus`, `incomplete_details.reason`, output type, content type)를 도출한다. 응답 본문, 요청 데이터, API key, 이미지 byte, 예외 메시지를 기록하지 않고 실패 단계마다 구조화된 경고를 하나만 남긴다.

**기술 스택:** Java 21, Spring Boot 4.1.0, Gradle, SLF4J/Logback, JUnit 5, AssertJ, Jackson(`tools.jackson.databind`).

## 전체 제약조건

- 모든 구현 판단은 `origin/develop`의 `f979d511874db889e32478fa7b476bc9a1590147`을 기준으로 한다.
- 기존 OpenAI Responses API 요청 payload와 `ANALYSIS_NOT_READY` 오류 계약을 유지한다.
- 응답 본문, 모델 출력 text, prompt, 이미지 data URL/byte, API key, 예외 메시지를 절대 로깅하지 않는다.
- 응답 status, 안전한 `incomplete_details.reason`, 제한된 output/content type 목록, 실패 category, provider/model, 경과 시간만 로깅한다.
- commit하거나 push하지 않는다.

---

### 작업 1: 단계별 안전 응답 진단 추가

**파일:**
- 수정: `src/main/java/com/fitback/backend/external/aitag/openai/OpenAiTagModelClient.java`

**인터페이스:**
- 입력: `TransportResponse.statusCode()`와 공급자 응답 본문.
- 출력: 변경되지 않은 `AiTagModelResult` 성공 경로와 `ANALYSIS_NOT_READY` 실패 경로, 경고 필드 `responseStatus`, `incompleteDetailsReason`, `outputTypes`, `contentTypes`, 단계별 `responseParsingCategory`.

- [x] **1단계: API 계약을 바꾸지 않고 응답 단계를 분리한다.**

전송 오류와 HTTP status 처리는 기존 분기에 유지한다. 오류가 아닌 status 이후에는 응답 JSON decode, root/output 구조, `output_text` 추출, 모델 출력 JSON decode, schema parsing 단계를 각각 독립적으로 처리한다.

- [x] **2단계: 응답 내용을 보관하거나 로깅하지 않고 제한된 메타데이터를 도출한다.**

`incomplete_details.reason`, `output[].type`, `output[].content[].type`만 추출한다. 각 목록은 20개, 각 token은 안전한 ASCII 64자로 제한하고 token이 아닌 값은 `<redacted>`로 표시한다. 본문을 decode할 수 없으면 `UNKNOWN` 또는 빈 목록을 사용한다.

- [x] **3단계: category별 안전 경고를 기록한다.**

`INVALID_RESPONSE_JSON`, `INVALID_RESPONSE_SHAPE`, `MISSING_OUTPUT`, `MISSING_OUTPUT_TEXT`, `EMPTY_OUTPUT_TEXT`, `INVALID_MODEL_OUTPUT_JSON`, `INVALID_MODEL_OUTPUT_SCHEMA` category를 사용한다. logger에 exception을 전달하지 않는다.

### 작업 2: 관측성과 비식별화 회귀 테스트 추가

**파일:**
- 수정: `src/test/java/com/fitback/backend/external/aitag/openai/OpenAiTagModelClientTest.java`

**인터페이스:**
- 입력: 이 저장소에서 이미 사용하는 package-private transport seam과 Logback `ListAppender` 패턴.
- 출력: 메타데이터, 단계 category, HTTP status 분류, 민감값 부재를 검증하는 assertion.

- [x] **1단계: output이 없는 응답의 안전한 메타데이터를 검증한다.**

`incomplete_details.reason`, output type, refusal content type이 포함된 200 응답을 반환한다. 로그에 status, reason, 두 type 목록, `MISSING_OUTPUT_TEXT`가 있고 응답 본문, API key, `data:image`는 없는지 검증한다.

- [x] **2단계: 모델 출력 비식별화를 검증한다.**

유효한 공급자 envelope를 반환하되 `output_text.text`에는 secret marker가 있고 모델 JSON으로는 유효하지 않게 한다. content type 목록에 `output_text`가 포함된 `INVALID_MODEL_OUTPUT_JSON`이 기록되고 secret marker는 없는지 검증한다.

- [x] **3단계: 잘못된 공급자 JSON을 검증하면서 기존 동작을 유지한다.**

잘못된 공급자 JSON이 계속 `ANALYSIS_NOT_READY`로 변환되고, 본문 없이 `INVALID_RESPONSE_JSON`이 기록되는지 검증한다.

### 작업 3: 집중 검증과 저장소 전체 검증 실행

**파일:**
- 추가 source 파일 없음.

- [x] **1단계: diff와 민감값 패턴을 확인한다.**

`git diff --check`를 실행하고 diff에서 응답 본문, 이미지, prompt, API key, 예외 메시지 로깅 여부를 검사한다.

- [x] **2단계: 집중 테스트를 실행한다.**

`./gradlew --no-daemon test --tests com.fitback.backend.external.aitag.openai.OpenAiTagModelClientTest`를 실행한다.

- [x] **3단계: 집중 테스트가 통과하면 전체 build test gate를 실행한다.**

`./gradlew --no-daemon test`를 실행하고 정확한 결과를 기록한다. checkout은 commit하거나 push하지 않은 상태로 둔다.

## 검증 체크리스트

- [x] `git diff --check` 통과.
- [x] 집중 `OpenAiTagModelClientTest` 통과: 테스트 8개, 실패 0개, 오류 0개.
- [x] 전체 Gradle test suite 통과: 테스트 700개, 실패 0개, 오류 0개.
- [x] `git status`에 의도한 source, test, plan 변경만 표시됨.
- [x] commit 또는 push를 수행하지 않음.
