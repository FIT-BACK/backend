# AGENTS.md

이 문서는 FIT-BACK Backend 레포지토리에서 AI 도구를 사용해 개발할 때 따라야 하는 공통 작업 지침이다.
AI 도구와 백엔드 팀원은 이 문서를 기준으로 같은 브랜치 전략, 코드 컨벤션, 검증 기준, PR 흐름을 따른다.

## 1. 프로젝트 기본 정보

- 프로젝트명: FIT-BACK Backend
- 기본 패키지: `com.fitback.backend`
- Java 버전: Java 21
- Spring Boot 버전: 4.1.0
- 빌드 도구: Gradle
- 기본 실행 프로필: `local`
- 테스트 프로필: `test`

## 2. AI 작업 기본 원칙

AI 도구는 작업 전 반드시 다음 파일을 확인한다.

- `README.md`
- `AGENTS.md`
- `.github/ISSUE_TEMPLATE/task.md`
- `.github/pull_request_template.md`
- 관련 소스 코드

작업 시 다음 원칙을 따른다.

- 사용자가 명시한 범위를 벗어나지 않는다.
- 파일을 수정하기 전에 현재 구조와 설정을 먼저 확인한다.
- 추측으로 수정하지 않고, 오류 메시지와 코드 근거를 확인한다.
- 하나의 PR에는 하나의 이슈 범위만 담는다.
- 불필요한 리팩터링, 포맷 변경, 파일 이동은 하지 않는다.
- 민감정보는 절대 커밋하지 않는다.
- 작업 완료 전 반드시 검증 명령을 실행한다.

## 3. 브랜치 전략

브랜치는 다음 구조를 사용한다.

```text
main
develop
feature/#{issue-number}-{feature-name}
docs/{document-name}
```

브랜치 역할은 다음과 같다.

- main: 최종 제출 및 배포 기준 브랜치
- develop: 백엔드 통합 개발 브랜치
- feature/#{issue-number}-{feature-name}: 이슈 기반 기능 작업 브랜치
- docs/{document-name}: 문서 작업 브랜치

기능 작업은 반드시 develop에서 새 브랜치를 생성해 진행한다.

예시:

```bash
git checkout develop
git pull --ff-only origin develop
git checkout -b feature/#12-auth
```

PR 흐름은 다음을 따른다.

```text
feature/#{issue-number}-{feature-name}
        ↓ PR
develop
        ↓ 최종 PR
main
```

## 4. 이슈 기반 작업 규칙

모든 작업은 GitHub Issue를 먼저 생성한 뒤 진행한다.

이슈 제목은 다음 형식을 따른다.

```text
Feat: 회원가입 API 구현
Fix: 로그인 예외 처리 수정
Docs: README 실행 방법 정리
```

이슈 본문은 다음 형식을 따른다.

```md
## Issue Overview

- 이슈 개요:

## Issue Description

- 세부 설명:
  -

## To do

- [ ]
```

AI 도구는 작업 시작 전 이슈 내용을 기준으로 다음을 확인한다.

- 작업 범위
- 수정 대상 파일
- 완료 기준
- 테스트 및 검증 방법
- 문서 업데이트 필요 여부

이슈 범위에 없는 작업은 임의로 추가하지 않는다.
필요한 경우 별도 이슈로 분리한다.

## 5. 커밋 규칙

커밋 메시지는 다음 형식을 따른다.

```text
태그: 한국어 설명
```

태그는 영어로 작성하고, 설명은 한국어로 작성한다.

사용 가능한 태그는 다음과 같다.

```text
feat: 새로운 기능 추가 및 기능 업데이트 커밋
refactor: 리팩터링 커밋
fix: 버그 수정 커밋
style: 코드 포맷팅 등 스타일 변경 커밋
docs: 문서 커밋
chore: 오타 수정 등 기타 커밋
test: 테스트 관련 커밋
build: 배포 커밋
ci: CI 설정 파일 및 스크립트 변경, GitHub Actions 설정 추가
```

예시:

```text
feat: 회원가입 API 추가
fix: 로그인 예외 처리 수정
docs: README 실행 방법 정리
test: 회원가입 서비스 테스트 추가
ci: 백엔드 CI 실행 조건 정리
```

커밋은 의미 단위로 나눈다.

좋은 예:

```text
feat: 회원 엔티티 추가
feat: 회원가입 서비스 추가
test: 회원가입 서비스 테스트 추가
docs: 회원 API 명세 업데이트
```

나쁜 예:

```text
feat: 여러 작업
fix: 수정
chore: 작업
```

## 6. PR 규칙

PR은 기본적으로 feature 브랜치에서 develop 브랜치로 생성한다.

PR 제목은 이슈 제목과 유사하게 작성한다.

예시:

```text
Feat: 회원가입 API 구현
Fix: 로그인 예외 처리 수정
Docs: README 실행 방법 정리
```

PR 본문은 `.github/pull_request_template.md`를 따른다.

```md
## 관련된 이슈

close #

## 작업 내용

-

## 공유 사항

-

## 체크리스트

- [ ] Reviewer에 팀원들을 선택 했나요?
- [ ] Assignees에 본인을 선택 했나요?
- [ ] Merge 하려는 브랜치가 올바르게 설정되어 있나요?
- [ ] 컨벤션을 지키고 있나요?
- [ ] 로컬에서 실행했을 때 에러가 발생하지 않나요?
- [ ] 불필요한 주석이 제거되었나요?
- [ ] 코드 스타일이 일관적인가요?
- [ ] Entity 또는 DB 변경 시 ERD/DDL 문서를 업데이트했나요?
- [ ] API 변경 시 API 명세서를 업데이트했나요?
```

PR 작성 시 다음을 지킨다.

- 관련 이슈 번호를 반드시 연결한다.
- 이슈를 닫는 PR이면 close #이슈번호를 사용한다.
- 후속 작업이거나 참고만 하는 경우 Related to #이슈번호를 사용한다.
- 작업 내용은 실제 변경사항 기준으로 작성한다.
- 공유 사항에는 리뷰어가 알아야 할 제한사항, 후속 작업, 제외 범위를 적는다.
- 테스트 결과를 명시한다.

## 7. 코드 구조 규칙

패키지는 도메인 중심으로 분리한다.

기본 패키지는 다음과 같다.

```text
com.fitback.backend
```

권장 구조는 다음과 같다.

```text
com.fitback.backend
├── domain
│   ├── member
│   │   ├── controller
│   │   ├── service
│   │   ├── repository
│   │   ├── entity
│   │   └── dto
│   ├── closet
│   ├── lookbook
│   ├── trend
│   ├── tag
│   ├── analysis
│   ├── recommendation
│   └── product
└── global
    ├── config
    ├── exception
    ├── response
    └── security
```

공통 설정, 예외 처리, 응답 형식, 보안 설정 등 여러 도메인에서 사용하는 코드는 global 패키지에 둔다.

도메인별 코드는 domain/{domain-name} 하위에 둔다.

## 8. 네이밍 규칙

코드 스타일은 Google Style Guide를 기준으로 한다.

네이밍은 다음을 따른다.

- 변수명: camelCase
- 메서드명: camelCase
- 클래스명: PascalCase
- 상수명: UPPER_SNAKE_CASE
- DB 컬럼명: snake_case
- Java 필드명: camelCase

예시:

```java
private String userName;

public UserProfileResponse getUserProfile() {
    // ...
}

private static final int MAX_IMAGE_SIZE = 5;
```

## 9. DTO 규칙

요청 DTO는 Request 접미사를 사용한다.

```text
LoginRequest
RecommendationCreateRequest
```

응답 DTO는 Response 접미사를 사용한다.

```text
LoginResponse
UserProfileResponse
RecommendationListResponse
```

DTO는 Entity를 직접 노출하지 않는다.
Controller 응답에는 Response DTO를 사용한다.

## 10. Entity 및 JPA 규칙

Entity 작성 시 다음을 따른다.

- Entity에는 Setter 사용을 지양한다.
- Entity 생성은 생성자 또는 정적 팩토리 메서드를 사용한다.
- 다만 Entity 생성 파라미터가 10개 이상인 경우에만 제한적으로 Entity Builder를 사용한다.
- `@Builder`는 클래스가 아니라 생성자에 적용한다.
- 접근 제어는 `@Builder(access = AccessLevel.PRIVATE)`를 기본으로 한다.
- 외부 생성은 정적 팩토리 메서드를 통해서만 허용한다.
- id, createdAt, updatedAt 등 시스템 관리 필드는 Builder 대상에서 제외한다.
- Entity 상태 변경은 비즈니스 메서드를 통해 처리한다.
- 연관관계는 기본적으로 LAZY 로딩을 사용한다.
- 양방향 연관관계는 필요한 경우에만 사용한다.
- 컬렉션 필드는 외부에서 직접 교체하지 않도록 관리한다.
- DB 컬럼명은 snake_case를 사용한다.
- Java 필드명은 camelCase를 사용한다.

예시:

```java
@Entity
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nickname;

    protected User() {
    }

    private User(String nickname) {
        this.nickname = nickname;
    }

    public static User create(String nickname) {
        return new User(nickname);
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }
}
```

## 11. API URI 규칙

API URI는 다음 규칙을 따른다.

- 리소스 중심으로 작성한다.
- 동사보다 명사를 사용한다.
- API 버전은 /api/v1을 기본 prefix로 사용한다.

예시:

```text
GET /api/v1/users/me
POST /api/v1/auth/login
GET /api/v1/recommendations
POST /api/v1/analysis-requests
```

## 12. API 응답 형식

API 명세서가 확정되기 전까지는 다음 공통 응답 형식을 기준으로 작성한다.

성공 응답:

```json
{
  "success": true,
  "code": "COMMON_200",
  "message": "요청에 성공했습니다.",
  "data": {}
}
```

실패 응답:

```json
{
  "success": false,
  "code": "USER_404",
  "message": "사용자를 찾을 수 없습니다.",
  "data": null
}
```

API 명세서가 확정되면 이 형식과 문서를 함께 업데이트한다.

## 13. 설정 파일 및 환경변수 규칙

로컬 실행은 .env 파일과 application-local.yml을 사용한다.

.env는 절대 커밋하지 않는다.

커밋 대상:

```text
.env.example
src/main/resources/application-local.yml
src/main/resources/application.properties
src/test/resources/application-test.yml
```

커밋 금지:

```text
.env
.env.*
application-secret.yml
application-secret.properties
```

.env.example은 팀원이 필요한 환경변수를 알 수 있도록 유지한다.

예시:

```properties
DB_URL=jdbc:mysql://localhost:3306/umc_db?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
DB_USER=your_mysql_user
DB_PW=your_mysql_password
```

## 14. 로컬 실행 및 검증 규칙

작업 완료 전 반드시 다음 명령을 실행한다.

```bash
./gradlew clean build
```

로컬 환경에서 Gradle 캐시 문제가 있으면 다음 명령을 사용할 수 있다.

```bash
GRADLE_USER_HOME=/tmp/fitback-gradle-home ./gradlew clean build
```

애플리케이션 실행은 다음 명령을 사용한다.

```bash
./gradlew bootRun
```

기본 프로필은 local이다.

테스트는 test 프로필과 H2 인메모리 DB를 사용한다.

## 15. CI 규칙

GitHub Actions는 다음 브랜치에서 실행된다.

- main
- develop

CI는 다음 명령을 수행한다.

```bash
./gradlew clean build
```

현재 CI에서는 MySQL service를 띄우지 않는다.
테스트는 H2 기반 application-test.yml을 사용한다.

MySQL service CI는 다음 시점에 별도 이슈로 추가한다.

- Entity와 Repository가 추가된 이후
- MySQL 고유 제약조건 검증이 필요해진 경우
- Flyway 또는 Liquibase가 도입된 경우
- H2와 MySQL 차이로 인한 검증 필요성이 생긴 경우

GitHub Actions 외부 액션은 full commit SHA로 고정한다.
actions/checkout에는 다음 설정을 사용한다.

```yaml
with:
  persist-credentials: false
```

GitHub Actions 업데이트는 Dependabot PR을 통해 관리한다.

## 16. 문서 동기화 규칙

다음 변경이 발생하면 관련 문서도 함께 업데이트한다.

- Entity 또는 DB 컬럼 변경
  - ERD/DDL 문서 업데이트 여부 확인
- API URI, 요청값, 응답값 변경
  - API 명세서 업데이트 여부 확인
- 에러 코드 추가 또는 변경
  - API 명세서의 예외 응답 업데이트 여부 확인
- 실행 방법 또는 환경변수 변경
  - README.md
  - .env.example
  - 관련 application 설정 파일 업데이트

문서 업데이트가 필요 없다고 판단한 경우 PR 공유 사항에 그 이유를 적는다.

## 17. AI 도구 작업 체크리스트

AI 도구는 작업 완료 전 다음을 확인한다.

- [ ] 이슈 범위 안에서만 작업했는가?
- [ ] 브랜치가 feature/#{issue-number}-{feature-name} 형식인가?
- [ ] PR 대상이 develop인가?
- [ ] 커밋 메시지가 태그: 한국어 설명 형식인가?
- [ ] .env 또는 민감정보를 커밋하지 않았는가?
- [ ] 불필요한 파일을 추가하지 않았는가?
- [ ] Entity/API 변경 시 문서 업데이트 여부를 확인했는가?
- [ ] ./gradlew clean build를 실행했는가?
- [ ] 실행 결과를 PR에 적었는가?

## 18. 금지 사항

다음 작업은 하지 않는다.

- 이슈 없이 작업 시작
- 사용자 요청 범위 밖의 기능 추가
- 임의의 대규모 리팩터링
- .env 또는 민감정보 커밋
- 실패한 테스트를 무시하고 완료 처리
- 검증 없이 “완료”라고 보고
- 원격 main 직접 push
- 원격 develop 직접 push
- PR 없이 보호 브랜치에 반영
- API/DB 변경 후 문서 업데이트 여부 누락

## 19. CI/CD 및 운영 작업 규칙

- `main` push는 production 배포를 실행하므로 `develop → main` PR의 배포 영향을 확인한다.
- GitHub Actions AWS 인증은 OIDC만 사용하고 장기 Access Key를 만들거나 저장하지 않는다.
- 운영 이미지는 `git-${GITHUB_SHA}` 태그로 발행한 뒤 digest 참조로 배포한다.
- 동일 SHA를 다시 실행할 때 기존 불변 ECR 태그를 재사용하며, 조회 실패를 이미지 미존재로 간주하지 않는다.
- 운영 DB 값은 Parameter Store SecureString에서 EC2가 직접 읽고 workflow payload, `.env`, 문서, 로그에 기록하지 않는다.
- EC2 접근은 SSM을 사용하며 SSH 22와 key pair를 열지 않는다.
- 배포 확인은 Nginx health, backend readiness, 외부 8080 차단, Actions 로그 민감정보 노출 여부를 포함한다.
- 장애 경로를 mock으로 검증한 경우 실제 AWS 검증과 구분해 PR과 운영 문서에 기록한다.
- 배포 workflow, IAM 권한, Repository Variable, Parameter Store 경로가 바뀌면 `docs/DEPLOYMENT.md`를 함께 갱신한다.
