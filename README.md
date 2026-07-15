# FIT-BACK Backend

FIT-BACK 서비스의 백엔드 레포지토리입니다.

## 기술 스택

- Java 21
- Spring Boot 4.1.0
- Gradle
- Spring Data JPA
- Spring Security
- Spring Validation
- MySQL
- H2 Database
- SpringDoc OpenAPI

## 로컬 실행 방법

### 1. 환경변수 파일 생성

`.env.example` 파일을 복사해 `.env` 파일을 생성합니다.

```bash
cp .env.example .env
```

`.env` 파일 예시는 다음과 같습니다.

```env
DB_URL=jdbc:mysql://localhost:3306/fitback?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
DB_USER=your_mysql_user
DB_PASSWORD=your_mysql_password
```

### 2. MySQL 데이터베이스 생성

로컬 MySQL에 사용할 데이터베이스를 생성합니다.

```sql
CREATE DATABASE fitback;
```

### 3. 애플리케이션 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

운영 설정이 실수로 로컬 설정으로 대체되지 않도록 실행 프로필을 명시합니다.

## 테스트 및 빌드

```bash
./gradlew clean build
```

테스트 환경에서는 `application-test.yml`을 통해 H2 인메모리 DB를 사용합니다.

## Swagger

애플리케이션 실행 후 아래 주소에서 Swagger UI를 확인할 수 있습니다.

```text
http://localhost:8080/swagger-ui.html
http://localhost:8080/v3/api-docs
```

## 운영 배포

운영 이미지는 GitHub Actions에서 ECR에 발행하고, AWS Systems Manager Run Command를 통해 EC2의 Docker Compose stack으로 배포합니다.

필요한 GitHub 변수, IAM 최소 권한, Parameter Store 경로, EC2 runtime 및 rollback 절차는 [운영 배포 문서](docs/DEPLOYMENT.md)를 참고합니다.

현재 운영 배포는 `main` push 또는 수동 `workflow_dispatch`로 실행됩니다. Git SHA 기반 ECR 태그가 이미 있으면 기존 불변 태그를 재사용하고 digest로 배포하므로 같은 commit을 안전하게 다시 실행할 수 있습니다.

운영 확인 경로는 다음과 같습니다.

```text
GET /nginx-health
GET /actuator/health/readiness
```

Spring Boot의 8080 포트는 외부에 공개하지 않습니다. 운영 비밀값은 GitHub 변수나 저장소가 아니라 EC2 instance role이 Parameter Store SecureString에서 직접 읽습니다.

## Security

현재는 JWT 인증 구현 전 단계이므로 `SecurityConfig`에서 Swagger/OpenAPI 경로와 `/api/v1/**` 경로를 임시로 허용합니다.
REST API 기준으로 CSRF, Form Login, HTTP Basic, Session은 비활성화되어 있습니다.

## 브랜치 컨벤션

```text
main
develop
feature/#{issue-number}-{feature-name}
docs/{document-name}
```

예시:

```text
feature/#12-auth
docs/api-spec
```

## 커밋 컨벤션

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

## 협업 규칙

1. 이슈 템플릿을 활용해서 GitHub Issue를 먼저 생성합니다.
2. `develop` 브랜치에서 이슈 번호에 맞게 작업 브랜치를 생성합니다.
3. 이슈 범위 안에서만 작업하고 커밋은 의미 단위로 나눕니다.
4. 작업 완료 전 `./gradlew clean build`로 검증합니다.
5. PR 템플릿을 활용해서 `develop` 브랜치로 PR을 생성합니다.
6. 리뷰어를 지정하고 테스트 결과를 공유한 뒤 승인 후 merge합니다.

자세한 컨벤션은 `AGENTS.md`를 참고합니다.
