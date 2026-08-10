# Trend 도메인 API 설계

> **보관 상태 (2026-08-01): Archived.** 구현 전 작성된 설계안으로, 아래의 "미구현" 상태와
> 작업 순서는 현재 상태가 아니다. 현재 계약은 [API_SPEC.md](API_SPEC.md),
> [`TrendController.java`](../src/main/java/com/fitback/backend/domain/trend/controller/TrendController.java),
> [`TrendService.java`](../src/main/java/com/fitback/backend/domain/trend/service/TrendService.java)를
> 기준으로 한다.

기획서(`docs/FITBACK_API_명세서.md`) 기준 Trend API 설계 문서. Entity(`TrendContent`, `TrendTag`)는 이미 구현되어 있고, DTO/Repository/Service/Controller는 미구현 상태(package-info.java만 존재)에서 작성한 설계안.

## 범위

명세서에는 **트렌드 목록 조회 / 상세 조회 2개(read-only) 엔드포인트만** 정의되어 있고, 생성/수정/삭제 API는 없다. `TrendContent` 엔티티에 `create()`/`changeContent()`가 있어 작성 기능을 전제하는 것처럼 보이지만, 명세에 없으므로 이번 설계는 **명세 기준 조회 2개**로 한정한다. (관리자용 CRUD가 필요하면 별도 논의 필요.)

기존에 구현된 `Lookbook` 도메인(태그 조인 + 커서 페이징 구조가 Trend와 가장 유사, `origin/feature/#22-lookbook-like` 브랜치)의 패턴을 그대로 따른다.

---

## 1. 필요한 DTO

`domain/trend/dto` 패키지에 Response 전용 네임스페이스 클래스 하나만 필요 (Request Body가 없으므로 Request DTO 불필요, cursor/trendId는 컨트롤러에서 `@RequestParam`/`@PathVariable` + Bean Validation으로 직접 검증).

```
TrendResponse (public final class, private 생성자 + 내부 record들)
├── Item    : trendId, title, imageUrl, tags(List<String>)         → 목록 아이템
├── List    : items(List<Item>), nextCursor(Long), hasNext(boolean), pageSize(int)  → 목록 응답
└── Detail  : title, imageUrl, description, tags(List<String>)      → 상세 응답
```

- 각 record는 `@Builder` + `entity → dto` 정적 팩토리(`toItem(TrendContent, List<String> tags)`, `toDetail(TrendContent, List<String> tags)`) 형태로 구성 (Lookbook 패턴 동일).
- `tags`는 `TrendTag` 목록을 `Tag.tagName`으로 매핑한 문자열 리스트 (Lookbook의 `TagInfo` 같은 별도 record 불필요 — 명세상 태그는 이름 문자열만 노출).

## 2. Repository 인터페이스와 필요한 메서드

`domain/trend/repository`에 2개.

**TrendContentRepository extends JpaRepository<TrendContent, Long>**
- `findAllByOrderByCreatedAtDescIdDesc(Pageable pageable)` — 첫 페이지(cursor 없음)
- `@Query`로 커서 다음 페이지:
  `findNextPage(LocalDateTime cursorCreatedAt, Long cursorId, Pageable pageable)`
  → `createdAt < :cursorCreatedAt OR (createdAt = :cursorCreatedAt AND id < :cursorId)` 조건, `ORDER BY createdAt DESC, id DESC`
  (cursor로 넘어온 id의 createdAt을 서비스에서 먼저 조회해 넘겨주는 Lookbook과 동일한 방식)

**TrendTagRepository extends JpaRepository<TrendTag, Long>**
- `@EntityGraph(attributePaths = "tag") List<TrendTag> findAllByTrendIdOrderByIdAsc(Long trendId)` — 상세 조회용 (단건)
- `@EntityGraph(attributePaths = "tag") List<TrendTag> findAllByTrendIdInOrderByIdAsc(List<Long> trendIds)` — 목록 조회용 (N+1 방지, 벌크 조회 후 서비스에서 trendId 기준 grouping)

## 3. Service 메서드 목록

`domain/trend/service/TrendService`

| 메서드 | 설명 |
|---|---|
| `getTrends(Long cursor)` (`@Transactional(readOnly = true)`) | cursor가 null이면 첫 페이지, 있으면 해당 id의 createdAt 조회 후 다음 페이지 조회. `PAGE_SIZE + 1`건 요청 → 초과분으로 `hasNext` 판단 후 마지막 1건 잘라냄 → id 목록 추출 → `TrendTagRepository`로 태그 벌크 조회 후 trendId별 grouping → `TrendResponse.List` 조립 (`nextCursor`는 마지막 item의 trendId, 없으면 `null`) |
| `getTrendDetail(Long trendId)` (`@Transactional(readOnly = true)`) | `TrendContentRepository.findById` 없으면 `BusinessException(TREND404_1)` → 태그 조회(`findAllByTrendIdOrderByIdAsc`) → `TrendResponse.Detail` 변환 |
| (private) `findTrendPage(Long cursor)` | 커서 유무에 따라 첫 페이지/다음 페이지 쿼리 분기하는 헬퍼 |
| (private) `findTagsByTrendId(Long trendId)` / `findTagsByTrendIds(List<Long> trendIds)` | 태그 조회 후 이름 리스트 변환 헬퍼 |

- `PAGE_SIZE`는 서비스 내 상수 (기획서 예시상 10 등, 팀과 합의 필요).
- 로그인 여부와 무관한 공개 API이므로 `Member`/`AuthMember` 의존성 없음 (Lookbook과 달리 좋아요·저장 상태 계산이 없음).

## 4. Controller 엔드포인트

`domain/trend/controller/TrendController`, `@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/trends")`

| Method | Path | 메서드 | 반환 |
|---|---|---|---|
| GET | `/api/v1/trends` | `getTrends(@Positive @RequestParam(required=false) Long cursor)` | `ApiResponse<TrendResponse.List>` |
| GET | `/api/v1/trends/{trendId}` | `getTrendDetail(@Positive @PathVariable Long trendId)` | `ApiResponse<TrendResponse.Detail>` |

- `@Operation(summary, description)`으로 Swagger 문서화 (Lookbook 컨벤션 동일).
- 응답은 `ApiResponse.onSuccess(data)`로 감싸고 HTTP status 200 고정 (조회 API이므로 `onCreated` 불필요).
- 인증 불필요 — `@AuthenticationPrincipal` 파라미터 없음.

## 5. 예외 처리가 필요한 부분

- **상세 조회 시 존재하지 않는 trendId**: `ErrorCode`에 도메인 전용 코드 `TREND404_1("존재하지 않는 트렌드입니다.")` 추가 후 `BusinessException(ErrorCode.TREND404_1)` 사용 (컨벤션 6.5 — 도메인 구현 시점에 prefix 코드 추가하는 규칙 그대로 적용). 코드 추가 시 `docs/FITBACK_API_명세서.md`의 예외 응답 섹션도 함께 업데이트 필요.
- **cursor / trendId에 대한 `@Positive` 위반**: 별도 처리 불필요 — `ConstraintViolationException`으로 `GlobalExceptionHandler`가 이미 공통 400(`COMMON400_2`) 처리.
- **Request Body 자체가 없으므로** `MethodArgumentNotValidException` 케이스는 해당 없음.
- 그 외 인증/인가(401/403) 예외 케이스 없음 (공개 API).

## 6. 구현 순서

1. `ErrorCode`에 `TREND404_1` 추가
2. `TrendResponse` DTO 작성 (Item/List/Detail)
3. `TrendContentRepository`, `TrendTagRepository` 작성 (커서 쿼리, `@EntityGraph` 포함)
4. `TrendService` 작성 (`getTrends`, `getTrendDetail`) + 단위 테스트(Mockito, Lookbook 테스트 패턴 참고)
5. `TrendController` 작성 (엔드포인트 2개) + 단위 테스트(MockMvc 없이 직접 호출 검증)
6. `docs/FITBACK_API_명세서.md` 예외 응답 섹션에 `TREND404_1` 반영
7. `EntityInvariantTest` 등 기존 테스트 영향 없는지 확인 후 `./gradlew clean build`
