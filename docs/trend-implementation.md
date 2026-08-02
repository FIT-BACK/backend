# Trend 도메인 API 구현 내용

> **문서 상태 (2026-08-01): 역사적 구현 스냅샷.** 최초 구현 시점의 파일 구성·판단·환경을
> 보존한 기록이며 현재 전체 명세가 아니다. 현재 계약은 [API_SPEC.md](API_SPEC.md),
> [`TrendController.java`](../src/main/java/com/fitback/backend/domain/trend/controller/TrendController.java),
> [`TrendService.java`](../src/main/java/com/fitback/backend/domain/trend/service/TrendService.java)를
> 기준으로 한다.

`docs/trend-design.md` 설계를 기반으로 구현한 내용을 정리한 문서. 코드 전문 대신 구현 의도와 동작 방식 위주로 설명한다.

## 1. 구현 목적

기획서(`docs/FITBACK_API_명세서.md`)에 정의된 트렌드 콘텐츠 목록/상세 조회 API를 구현한다. `TrendContent`, `TrendTag` 엔티티는 이미 구현되어 있었고, 그 위에 DTO/Repository/Service/Controller와 예외 처리, 테스트를 추가하는 것이 이번 작업 범위다. 생성/수정/삭제는 명세에 없어 구현하지 않았다.

## 2. 구현한 API

| Method | Endpoint | 설명 |
|---|---|---|
| GET | `/api/v1/trends` | 홈 화면의 요즘 트렌드 영역에 표시할 트렌드 콘텐츠 목록을 커서 기반으로 조회 |
| GET | `/api/v1/trends/{trendId}` | 트렌드 카드 선택 시 트렌드 콘텐츠 상세 정보 조회 |

명세서에 인증 관련 언급이 없어, 두 API 모두 인증 없이 접근 가능한 공개 조회 API로 구현했다.

## 3. 추가한 파일 목록

```
src/main/java/com/fitback/backend
├── global/exception/ErrorCode.java                          (수정)
└── domain/trend
    ├── dto/TrendResponse.java                                (신규)
    ├── repository/TrendContentRepository.java                (신규)
    ├── repository/TrendTagRepository.java                    (신규)
    ├── service/TrendService.java                             (신규)
    └── controller/TrendController.java                       (신규)

src/test/java/com/fitback/backend/domain/trend
├── service/TrendServiceTest.java                             (신규)
└── controller/TrendControllerTest.java                       (신규)
```

## 4. 각 파일의 역할

- **`ErrorCode.java`**: 트렌드 도메인 전용 에러코드 `TREND_NOT_FOUND`(`TREND404_1`)를 추가. 트렌드를 찾을 수 없는 모든 상황에서 공용으로 사용.
- **`TrendResponse.java`**: 응답 전용 DTO 네임스페이스 클래스. `TrendList`(목록 응답), `TrendItem`(목록 아이템), `TrendDetail`(상세 응답) 3개의 record를 내부에 둔다. Request DTO는 없음 — 요청 본문이 없고, `cursor`/`trendId`는 컨트롤러에서 쿼리 파라미터/경로 변수로 직접 받기 때문.
- **`TrendContentRepository.java`**: `TrendContent`에 대한 조회 전담. 첫 페이지 조회와 커서 기반 다음 페이지 조회 메서드를 제공.
- **`TrendTagRepository.java`**: `TrendContent`-`Tag` 조인 엔티티인 `TrendTag`에 대한 조회 전담. 단건/벌크 태그 조회를 제공해 N+1을 방지.
- **`TrendService.java`**: 비즈니스 로직. 커서 계산, 페이지 잘라내기, 태그 매핑, 존재하지 않는 리소스에 대한 예외 처리를 담당.
- **`TrendController.java`**: HTTP 엔드포인트 노출과 요청 값(`cursor`, `trendId`) 검증만 담당. 비즈니스 로직은 갖지 않음.

## 5. 주요 구현 내용

- **커서 기반 페이지네이션**: offset(`Page<T>`) 대신 `(createdAt, id)` 복합 커서를 사용. 정렬 기준이 같은 `createdAt`을 가진 레코드가 여러 개 있어도 `id`를 보조 정렬 키로 사용해 페이지 경계에서 중복/누락이 생기지 않도록 함.
- **N+1 방지 및 태그 그룹핑**: 목록 조회 시 트렌드 N개를 먼저 조회한 뒤, 그 id 목록으로 태그를 한 번에(`IN` 절) 벌크 조회한다. 조회된 `TrendTag` 목록을 `Collectors.groupingBy`로 `trendId` 기준 그룹핑하고, 그룹 내 태그는 `Collectors.mapping`으로 태그 이름 문자열 리스트로 변환해 `Map<Long, List<String>>`을 만든다. 각 트렌드를 DTO로 변환할 때 이 맵에서 `getOrDefault(trendId, List.of())`로 태그를 꺼내므로, 태그가 없는 트렌드는 빈 리스트로 채워진다. `@EntityGraph(attributePaths = "tag")`로 `Tag` 엔티티까지 함께 가져와 태그 이름 접근 시 추가 쿼리가 발생하지 않는다.
- **응답에 불필요한 연관관계 노출 안 함**: `TrendContent.createdBy`(작성자, LAZY)는 응답 DTO에 없으므로 코드 어디에서도 접근하지 않는다 — 프록시 초기화에 의한 불필요한 쿼리가 발생하지 않는다.

## 6. Repository 구현 방식

- `TrendContentRepository`
  - `findAllByOrderByCreatedAtDescIdDesc(Pageable)`: cursor가 없는 첫 페이지 조회. 최신순(`createdAt DESC`) + 동시간 레코드 순서 보정용 `id DESC`.
  - `findNextPage(cursorCreatedAt, cursorId, Pageable)`: `@Query` JPQL로 `createdAt < :cursorCreatedAt OR (createdAt = :cursorCreatedAt AND id < :cursorId)` 조건을 걸어 커서 다음 페이지를 가져옴.
  - `Pageable`은 개수 제한(`limit`) 용도로만 쓰고, offset 페이지네이션(`Page<T>`)은 사용하지 않음 — 별도 feature 브랜치(`origin/feature/#22-lookbook-like`)에 구현된 `Lookbook` 도메인의 방식을 참고함.
- `TrendTagRepository`
  - `findAllByTrendIdOrderByIdAsc(Long)`: 상세 조회에서 트렌드 하나에 딸린 태그 조회.
  - `findAllByTrendIdInOrderByIdAsc(List<Long>)`: 목록 조회에서 여러 트렌드의 태그를 한 번에 조회.
  - 두 메서드 모두 `@EntityGraph(attributePaths = "tag")`를 붙여 `Tag` 엔티티를 함께 가져옴.

## 7. Service 동작 방식

**`getTrends(Long cursor)`**
1. cursor가 `null`이면 첫 페이지, 아니면 cursor id로 트렌드를 조회해 그 `createdAt`을 얻은 뒤 다음 페이지 쿼리 실행 (cursor에 해당하는 트렌드가 없으면 `TREND_NOT_FOUND` 예외).
2. 항상 `페이지 크기 + 1`건을 요청해서, 실제 반환 개수보다 많이 왔으면 `hasNext = true`로 판단하고 초과분 1건은 잘라서 버림.
3. 남은 트렌드들의 id를 모아 태그를 벌크 조회하고, 트렌드별로 그룹핑.
4. 각 트렌드를 `TrendItem`으로 변환하면서 그룹핑된 태그를 붙임.
5. `hasNext`가 true면 마지막 트렌드의 id를 `nextCursor`로, 아니면 `null`로 설정.

**`getTrendDetail(Long trendId)`**
1. `trendId`로 트렌드를 조회 (없으면 `TREND_NOT_FOUND` 예외).
2. 해당 트렌드의 태그를 단건 조회.
3. `TrendDetail`로 변환해 반환.

두 메서드 모두 `@Transactional(readOnly = true)`로 조회 전용 트랜잭션을 명시.

## 8. Controller 엔드포인트

```
GET /api/v1/trends?cursor={cursor}
  - cursor: 선택, 양수(@Positive), 직전 페이지 마지막 트렌드 id
  - 반환: ApiResponse<TrendResponse.TrendList>

GET /api/v1/trends/{trendId}
  - trendId: 필수, 양수(@Positive)
  - 반환: ApiResponse<TrendResponse.TrendDetail>
```

두 메서드 모두 요청 값 검증 외의 로직 없이 서비스 호출 결과를 `ApiResponse.onSuccess(...)`로 감싸 그대로 반환하며, HTTP status는 200 고정이다. Swagger 문서화를 위한 `@Operation(summary, description)`을 붙였다.

## 9. 예외 처리

- **`TREND_NOT_FOUND` (`TREND404_1`, 404, "트렌드를 찾을 수 없습니다.")**: 다음 두 상황에서 동일하게 사용한다.
  - 상세 조회 시 `trendId`에 해당하는 트렌드가 없을 때
  - 목록 조회 시 `cursor`에 해당하는 트렌드가 없을 때 (유효하지 않은 커서)
  - 두 상황을 같은 에러코드로 처리한 이유는, 별도 feature 브랜치(`origin/feature/#35-Analysis-Report`)에 구현된 `Analysis` 도메인이 도메인 전용 코드 도입 이후 상황별 커스텀 메시지 없이 `new BusinessException(ErrorCode.XXX_NOT_FOUND)`만 사용하는 것을 확인했고, Trend도 동일한 방식을 따랐기 때문이다.
- **`cursor`/`trendId`가 양수가 아닌 경우**: `@Positive` 위반은 `ConstraintViolationException`으로 이어지고, 이미 있는 `GlobalExceptionHandler`가 공통 400(`COMMON400_2`)으로 처리한다. Trend 쪽에 별도 처리를 추가하지 않았다.
- **요청 본문 검증**: 해당 없음 (Request DTO 자체가 없음).

## 10. 테스트 내용

`TrendServiceTest` (Mockito 기반 단위 테스트, 리포지토리 전부 mock 처리)
- 상세 조회: 태그가 있는 경우 정상 변환, 태그가 없는 경우 빈 리스트 반환, 존재하지 않는 trendId에 대한 예외
- 목록 조회: 페이지 크기만큼 반환 + `nextCursor` 계산, 마지막 페이지에서 `nextCursor = null` / `hasNext = false`, cursor 전달 시 해당 트렌드의 `createdAt`/`id`로 다음 페이지 쿼리가 호출되는지, 존재하지 않는 cursor에 대한 예외

`TrendControllerTest` (Mockito 기반, MockMvc 미사용 — 서비스를 mock으로 주입한 컨트롤러 인스턴스를 직접 호출)
- 목록 조회 응답이 `ApiResponse.onSuccess` 형태로 오는지, cursor 인자가 서비스로 그대로 전달되는지
- 상세 조회 응답이 `ApiResponse.onSuccess` 형태로 오는지

MockMvc를 쓰지 않은 이유는 별도 feature 브랜치의 `Lookbook` 도메인 테스트가 Spring 컨텍스트 없이 mock 기반 단위 테스트로 작성되어 있어, 그 방식을 그대로 따랐기 때문이다.

## 11. 구현하면서 고려한 사항

- **엔티티 필드명과 DTO 필드명 일치**: `TrendContent`의 실제 필드(`imageUrl`, `description` 등)를 그대로 DTO 필드명으로 사용했다. `id`만 `trendId`로 이름을 바꿨는데, 별도 feature 브랜치의 `Lookbook`이 `id → lookbookId`로 매핑하는 것과 같은 관례를 따른 것이다.
- **페이지 크기**: 명세서 예시나 컨벤션 문서에 고정값이 없어 `TREND_PAGE_SIZE = 10`으로 임의 설정했다. 실제 서비스 요구사항에 맞게 조정이 필요할 수 있다.
- **명세서에 없는 CRUD 미구현**: `TrendContent` 엔티티에 `create()`/`changeContent()`가 있어 작성 기능을 암시하지만, 명세서에는 조회 2개만 정의되어 있어 이번 구현 범위에서 제외했다.

## 12. 남아있는 TODO 및 개선 사항

- **관리자용 트렌드 등록/수정/삭제 API**: 명세서에 없어 미구현. 필요 여부를 기획 쪽과 확인 필요.
- **`TREND_PAGE_SIZE` 값 확정**: 현재 10으로 임의 설정. 실제 프론트/기획 요구사항에 맞춰 조정 필요.
- **경로 변수/쿼리 파라미터 타입 불일치 처리**: `trendId`나 `cursor`에 숫자가 아닌 값이 들어오면 전역 `MethodArgumentTypeMismatchException` 핸들러가 `COMMON400_2` 검증 오류로 변환한다.
- **Gradle 테스트 실행 환경 이슈**: 이 작업 환경(`C:\Users\김재민\backend`, 경로에 한글 포함)에서 `./gradlew test`가 `GradleWorkerMain ClassNotFoundException`으로 실패한다. Trend 코드와 무관하게 기존 테스트(`GlobalExceptionHandlerTest` 등)도 동일하게 실패하는 것을 확인했다. 컴파일(`compileJava`/`compileTestJava`)은 정상 통과하므로 코드 자체의 문제는 아니며, 이 환경 이슈 해결이 별도로 필요하다.
