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
DB_URL=jdbc:mysql://localhost:3306/umc_db?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
DB_USER=your_mysql_user
DB_PW=your_mysql_password
```

### 2. MySQL 데이터베이스 생성

로컬 MySQL에 사용할 데이터베이스를 생성합니다.

```sql
CREATE DATABASE umc_db;
```

### 3. 애플리케이션 실행

```bash
./gradlew bootRun
```

기본 프로필은 `local`입니다.

```properties
spring.profiles.default=local
```

## 테스트 및 빌드

```bash
./gradlew clean build
```

테스트 환경에서는 `application-test.yml`을 통해 H2 인메모리 DB를 사용합니다.

## Swagger

애플리케이션 실행 후 아래 주소에서 Swagger UI를 확인할 수 있습니다.

```text
http://localhost:8080/swagger-ui.html
```

## 브랜치 컨벤션

```text
main
develop
feature/#{issue-number}-{feature-name}
docs/{document-name}
```

예시:

```text
feature/#1-initial-setup
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

1. 이슈 템플릿을 활용해서 이슈를 생성합니다.
2. 생성된 이슈 번호에 맞게 브랜치를 생성합니다.
3. 작업 후 커밋을 기능 단위로 나눕니다.
4. PR 템플릿을 활용해서 PR을 생성합니다.
5. 리뷰어를 지정하고 리뷰를 요청합니다.
6. 승인 후 merge합니다.

자세한 컨벤션은 `BACKEND_CONVENTION.md`를 참고합니다.
