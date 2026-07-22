# FIT-BACK Backend 운영 배포

## 배포 구조

`main`에 변경이 병합되면 `Backend CD`가 다음 순서로 실행된다.

1. Docker 이미지를 빌드하고 H2 기반 readiness 검증을 수행한다.
2. GitHub OIDC로 AWS IAM 역할을 위임받아 ECR의 `git-${GITHUB_SHA}` 태그를 조회하고, 없을 때만 푸시한다. 같은 SHA를 다시 실행하면 기존 불변 태그를 재사용한다.
3. ECR에서 `sha256` digest를 확인해 변경 불가능한 이미지 참조를 생성한다.
4. `EC2_INSTANCE_ID` 저장소 변수가 설정된 경우에만 SSM Run Command로 EC2 배포를 실행한다.
5. EC2는 고유한 release 디렉터리에서 Parameter Store의 DB와 JWT 값을 읽고 Compose stack을 갱신한다.
6. Nginx와 backend health check가 실패하면 직전 release 전체로 rollback한다.
7. `PUBLIC_BASE_URL`이 설정되어 있으면 CloudFront HTTPS 주소에서 Nginx와 backend readiness를 다시 확인한다.

SSH, EC2 key pair, 장기 AWS Access Key는 사용하지 않는다.

## 현재 운영 구성

2026-07-22 KST 기준 운영 구성은 다음과 같다. 비밀값과 private RDS endpoint는 문서에 기록하지 않는다.

| 항목 | 현재 값 |
| --- | --- |
| AWS Account / Region | `123209654535` / `ap-northeast-2` |
| ECR | `fitback-backend` |
| EC2 | `i-0e6d218e8b181a20e`, `t3.micro`, 20 GiB gp3, Elastic IP `54.180.189.89` |
| CloudFront API | `E1R1PDNM4AAKD8`, `https://d1ra74et9h0ohu.cloudfront.net` |
| S3 이미지 버킷 | `fitback-prod-images-123209654535-ap-northeast-2`, Block Public Access 및 Bucket owner enforced |
| CloudFront 이미지 | `EV1PM17XDVYU5`, `https://d1p2ierkew26r1.cloudfront.net`, OAC 및 trusted key group 적용 |
| CloudFront 이미지 서명 | public key `K1XNJ3JDEDCVL3`, key group `4d40e17b-cb3b-4374-845d-e6228157e7d1` |
| RDS | `fitback-prod-mysql`, `db.t4g.micro`, Single-AZ, 20 GiB gp3 |
| EC2 Security Group | `sg-0267bf70568ffb352`, CloudFront origin-facing prefix list에서 HTTP 80만 inbound |
| RDS Security Group | `sg-0655806f06e276341`, EC2 SG에서 MySQL 3306만 inbound |
| GitHub OIDC Role | `FitbackGitHubDeployRole` |
| EC2 Instance Role / Profile | `FitbackProductionEC2Role` / `FitbackProductionEC2Profile` |
| 현재 검증 digest | `sha256:a0d33a7c3566b7b7e2e5c984e493cd33fd73ca6166072be21dde337283f00620` |

실제 Production CD 증적:

- [main push 실행 #6](https://github.com/FIT-BACK/backend/actions/runs/29426542508): image publish와 SSM deploy 성공
- [동일 SHA 수동 실행 #7](https://github.com/FIT-BACK/backend/actions/runs/29426904664): 기존 불변 태그 재사용, image publish와 SSM deploy 성공
- CloudFront HTTPS `/nginx-health`: `200 ok`
- CloudFront HTTPS `/actuator/health/readiness`: `200 {"status":"UP"}`
- Elastic IP HTTP 80 직접 접근: timeout으로 차단 확인
- 외부 8080 직접 접근: timeout으로 차단 확인
- 이미지 S3 직접 접근: `403`
- 서명 없는 이미지 CloudFront 접근: `403`
- Actions 로그: DB URL/user/password 미노출, runner token 마스킹 확인

## GitHub Repository Variables

| 변수 | 필수 시점 | 설명 |
| --- | --- | --- |
| `AWS_REGION` | ECR 발행 | AWS 리전. 현재 기준은 `ap-northeast-2`이다. |
| `AWS_DEPLOY_ROLE_ARN` | ECR 발행 | GitHub OIDC가 위임받는 IAM 역할 ARN이다. |
| `ECR_REPOSITORY` | ECR 발행 | backend ECR repository 이름이다. |
| `EC2_INSTANCE_ID` | EC2 배포 활성화 | SSM 관리형 노드로 등록된 운영 EC2 instance ID이다. 값이 없으면 `deploy-production` job은 건너뛴다. |
| `DEPLOY_PARAMETER_PREFIX` | 선택 | Parameter Store 경로 prefix이다. workflow가 이 저장소 변수를 원격 스크립트의 `PARAMETER_PREFIX`로 매핑한다. 기본값은 `/fitback/prod`이며, 다른 값을 사용하면 EC2 instance role의 Parameter Store resource ARN도 같은 경로로 변경해야 한다. |
| `PUBLIC_BASE_URL` | EC2 배포 | 경로가 없는 운영 CloudFront HTTPS origin이다. 현재 값은 `https://d1ra74et9h0ohu.cloudfront.net`이며 다른 값이면 배포 전에 거절한다. |
| `IMAGE_BUCKET` | EC2 배포 | private 사용자 이미지 버킷 이름이다. 현재 값은 `fitback-prod-images-123209654535-ap-northeast-2`이다. |
| `IMAGE_CDN_BASE_URL` | EC2 배포 | 서명된 이미지 조회 URL을 만들 CloudFront HTTPS origin이다. 현재 값은 `https://d1p2ierkew26r1.cloudfront.net`이다. |
| `CLOUDFRONT_KEY_PAIR_ID` | EC2 배포 | CloudFront signed URL에 포함할 public key ID이다. 현재 값은 `K1XNJ3JDEDCVL3`이다. |

민감정보는 Repository Variable 또는 GitHub command payload에 넣지 않는다.

## SSM Parameter Store

운영 EC2가 다음 값을 직접 읽는다. 모든 값은 `SecureString`으로 생성한다.

```text
/fitback/prod/db-url
/fitback/prod/db-user
/fitback/prod/db-password
/fitback/prod/jwt-secret-key
/fitback/prod/cloudfront-private-key
```

예시 형식은 다음과 같다. 실제 값은 문서나 저장소에 기록하지 않는다.

```text
db-url=jdbc:mysql://<private-rds-endpoint>:3306/fitback?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
db-user=<application-user>
db-password=<generated-password>
jwt-secret-key=<at-least-32-byte-random-secret>
cloudfront-private-key=<base64-encoded-pkcs8-pem>
```

`cloudfront-private-key`의 실제 키 원문과 Base64 값은 문서, 저장소, GitHub payload, 로그에 기록하지 않는다.

고객 관리형 KMS key로 암호화하면 EC2 instance role에 해당 key의 `kms:Decrypt` 권한도 추가해야 한다.

## GitHub OIDC 역할 권한

기존 ECR 발행 권한에 아래 SSM 권한을 추가한다. `<instance-id>`는 운영 EC2 ID로 교체한다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DescribeManagedNode",
      "Effect": "Allow",
      "Action": "ssm:DescribeInstanceInformation",
      "Resource": "*"
    },
    {
      "Sid": "SendDeploymentCommand",
      "Effect": "Allow",
      "Action": "ssm:SendCommand",
      "Resource": [
        "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript",
        "arn:aws:ec2:ap-northeast-2:123209654535:instance/<instance-id>"
      ]
    },
    {
      "Sid": "ManageDeploymentCommandResult",
      "Effect": "Allow",
      "Action": [
        "ssm:GetCommandInvocation",
        "ssm:CancelCommand"
      ],
      "Resource": "*"
    }
  ]
}
```

OIDC trust policy는 `FIT-BACK/backend`의 `main` branch에서만 역할을 위임받도록 유지한다.

## EC2 Instance Role

운영 EC2 instance profile에는 AWS 관리형 정책 `AmazonSSMManagedInstanceCore`와 다음 최소 권한이 필요하다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "GetEcrAuthorizationToken",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "PullBackendImage",
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage"
      ],
      "Resource": "arn:aws:ecr:ap-northeast-2:123209654535:repository/fitback-backend"
    },
    {
      "Sid": "ReadProductionParameters",
      "Effect": "Allow",
      "Action": "ssm:GetParameter",
      "Resource": "arn:aws:ssm:ap-northeast-2:123209654535:parameter/fitback/prod/*"
    },
    {
      "Sid": "ManageUserImageObjects",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:DeleteObject"
      ],
      "Resource": "arn:aws:s3:::fitback-prod-images-123209654535-ap-northeast-2/*"
    }
  ]
}
```

## EC2 Runtime 요구사항

SSM command는 root 권한으로 `/opt/fitback/releases/<release-id>`에 배포 asset을 설치하고 `/opt/fitback/current` symlink를 활성 release로 유지한다. 운영 AMI에는 다음 항목이 필요하다.

- SSM Agent가 실행 중이고 Fleet Manager에서 `Online` 상태여야 한다.
- AWS CLI v2, Docker Engine, Docker Compose v2, `curl`, `tar`, `base64`, `flock`가 설치되어야 한다.
- Docker daemon이 실행 중이어야 한다.
- ECR 이미지 platform과 EC2 architecture가 일치해야 한다. 현재 workflow는 multi-architecture 이미지를 만들지 않는다.
- `/opt/fitback/releases`에 배포 파일과 mode `600`의 비민감 runtime `.env`를 저장하고 `/opt/fitback/current` symlink를 교체할 수 있어야 한다. DB, JWT, CloudFront 개인 키 값은 파일에 기록하지 않고 Compose 프로세스 환경으로만 전달한다.

## 네트워크 계약

- 사용자는 CloudFront 기본 도메인의 HTTPS `443`으로 API에 접근한다.
- CloudFront는 캐시를 비활성화하고 모든 API HTTP method와 viewer 요청 값을 EC2 Nginx 원본으로 전달한다.
- EC2 Nginx 원본은 HTTP `80`을 사용하며 source를 AWS 관리형 prefix list `com.amazonaws.global.cloudfront.origin-facing`(`pl-22a6434b`)로 제한한다.
- Elastic IP의 HTTP `80`, SSH `22`, backend `8080`은 일반 인터넷에서 직접 접근할 수 없다.
- RDS는 public access를 비활성화한다.
- RDS security group의 MySQL `3306` source는 EC2 security group으로 제한한다.
- EC2는 ECR, SSM, Parameter Store, STS에 outbound access할 수 있어야 한다.
- Route 53 hosted zone이 없으므로 현재는 CloudFront 기본 도메인을 사용한다. 커스텀 도메인을 도입하면 ACM certificate와 DNS record를 함께 갱신한다.

## 사용자 이미지 저장소 계약

- S3 버킷은 Block Public Access와 Bucket owner enforced를 유지한다.
- 버킷 정책은 CloudFront distribution `EV1PM17XDVYU5`의 OAC `s3:GetObject`만 허용한다.
- CloudFront 기본 동작은 HTTPS redirect, `GET`/`HEAD`, `CachingOptimized`, trusted key group `fitback-private-images`를 사용한다.
- 이미지 조회는 signed URL 또는 signed cookie만 허용하며 서명 없는 요청은 `403`이다.
- 운영 EC2 역할은 Presigned PUT 발급, 업로드 완료 검증, 미사용 객체 정리에 필요한
  `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`만 가진다.
- Presigned PUT CORS는 `PUT`/`HEAD`, `Content-Type`, `ETag`, 300초를 허용한다. 현재 프론트엔드 운영 origin이 확정되지 않아 origin은 `*`이며, 프론트엔드 주소가 확정되면 별도 변경으로 축소한다.
- S3 객체 수명 주기 자동 만료는 `ACTIVE`와 `PENDING`을 구분할 수 없어 적용하지 않는다. 24시간 미사용 `PENDING` 정리는 DB 상태를 기준으로 애플리케이션 작업자가 수행한다.
- 외부 상품 공급자의 이미지는 이 버킷으로 복사하지 않는다.

운영 애플리케이션은 시작 시 Flyway를 단일 schema 변경 경로로 사용한다. 기존 운영 schema는
version `0`으로 baseline한 뒤 `V1__create_image_table.sql`,
`V2__add_analysis_image_reference_and_soft_delete.sql`을 순서대로 적용하고 Hibernate
`ddl-auto=validate`를 수행한다. 새 빈 DB에서는 선행 도메인 테이블(`member`,
`analysis_report` 등)이 먼저 준비되어 있어야 한다.

## 배포 및 Rollback 동작

`scripts/deploy/remote_deploy.sh`는 다음 작업을 수행한다.

1. digest가 포함된 ECR 이미지 참조를 검증한다.
2. Parameter Store의 DB, JWT, Base64 CloudFront 개인 키 값을 단일 행 값으로 검증한다.
3. host 단위 `flock`을 획득해 같은 EC2에서 두 배포가 동시에 실행되지 않게 한다.
4. 고유한 `/opt/fitback/releases/<release-id>/.env`에 image와 port 등 비민감 runtime 값만 mode `600`으로 원자적으로 작성하고, DB, JWT, CloudFront 개인 키 비밀값은 현재 Compose 프로세스 환경으로 전달한다.
5. EC2 instance role로 ECR에 로그인하고 backend 이미지를 pull한다.
6. 새 release의 `docker compose up -d --remove-orphans`를 실행한다.
7. `/nginx-health`와 backend container health가 모두 정상인지 확인한다.
8. 성공하면 `/opt/fitback/current` symlink를 새 release로 원자적으로 교체한다.
9. 실패하면 직전 release의 Compose asset, Nginx 설정, `.env`, image digest를 함께 다시 시작하고 health를 재검증한 뒤 `current` symlink도 직전 release로 복원한다.

첫 배포가 실패해 이전 release가 없으면 실패한 stack을 내리고 `.env`를 제거한다. 배포 중 예기치 않은 오류, `INT`/`TERM`, 활성 symlink 교체 실패도 같은 rollback 경로를 사용한다. rollback 중 pull, 시작 또는 health 검증이 실패하면 별도의 rollback 실패 코드로 종료한다.

Run Command의 실제 shell 실행 제한은 `executionTimeout=900`초이다. GitHub Actions는 managed node 전달 제한 60초와 실행 제한 900초를 합친 구간에 여유 시간을 더해 polling한다. workflow 중단 또는 polling timeout이 발생하면 `ssm:CancelCommand`를 호출하고, 최대 60초 동안 terminal 상태를 확인한 뒤 마지막 상태와 표준 출력을 기록한다. `send-command --timeout-seconds`는 실행 제한이 아니라 managed node 전달 제한이다.

## 배포 및 Rollback 검증 범위

운영 서비스에 고의 장애를 주지 않기 위해 실제 AWS와 결정적 mock 통합 테스트의 검증 범위를 구분한다.

| 시나리오 | 검증 방식 | 결과 |
| --- | --- | --- |
| main 정상 배포 | 실제 AWS/ECR/SSM | 성공 |
| 동일 SHA 중복 실행 | 실제 GitHub Actions/ECR/SSM | 불변 태그 재사용 후 성공 |
| Nginx 및 backend readiness | 실제 CloudFront HTTPS | 성공 |
| Elastic IP 80 직접 접근 | 실제 외부 HTTP | 차단 |
| 8080 직접 접근 | 실제 외부 HTTP | 차단 |
| image pull 실패 | `scripts/deploy/test_remote_deploy.sh` | stack 변경 전 실패, 이전 release 유지 |
| readiness timeout/비정상 container | `scripts/deploy/test_remote_deploy.sh` | 직전 release와 symlink 복원 |
| 잘못된 digest | remote deploy 입력 검증 및 mock test | 배포 전 거절 |
| 중복 배포 | `flock` mock test | 두 번째 실행 거절 |
| rollback 자체 실패 | mock test | 비정상 종료 코드 반환 |
| 활성화 실패 및 INT/TERM | mock test | 직전 release 복원 |
| DB/JWT 비밀값 특수문자 | mock test | `.env`와 로그에 남지 않음 |
| Flyway V1/V2 MySQL DDL | `scripts/ci/test_mysql_migrations.sh` | MySQL 8.4 적용 및 nullable 계약 확인 |

검증 명령:

```bash
bash scripts/ci/test_publish_ecr_image.sh
bash scripts/deploy/test_remote_deploy.sh
GRADLE_USER_HOME=/tmp/fitback-gradle-home ./gradlew clean build
```

## 장애 대응

1. GitHub Actions의 `Deploy production via SSM` job에서 command ID와 최종 SSM 상태를 확인한다.
2. SSM Session Manager 또는 Run Command로 `/opt/fitback/current` symlink와 현재 release의 Compose 상태를 확인한다.
3. `/nginx-health`, backend container health, `/actuator/health/readiness` 순서로 확인한다.
4. 자동 rollback이 실패한 경우 직전 release 디렉터리에서 비밀값을 출력하지 않고 `remote_deploy.sh`의 rollback 실패 원인을 확인한다.
5. 같은 정상 SHA를 `workflow_dispatch`로 다시 실행해 ECR 태그 재사용과 SSM 배포를 복구 경로로 사용할 수 있다.
6. 로그나 이슈에는 DB URL/user/password, Parameter Store 복호화 값, 인증 토큰을 붙이지 않는다.

SSM 배포 완료 후 CloudFront 외부 검증은 원본과 무관한 CloudFront 장애나 일시적인 네트워크 오류로 정상 release를 되돌리지 않도록 비차단 경고로 처리한다. 경고가 발생하면 현재 release는 유지하며 위 순서로 CloudFront와 원본 상태를 확인한 뒤 같은 main SHA를 다시 실행한다.

## 비용 관리와 중지 기준

현재 구성은 NAT Gateway와 ALB 없이 EC2 `t3.micro`, RDS `db.t4g.micro` Single-AZ, gp3 20 GiB씩, public IPv4 1개를 사용하는 최소 사양이다. 아래 계산은 2026-07-15에 AWS Pricing Calculator와 각 서비스 요금 페이지에서 `ap-northeast-2` Linux On-Demand 단가를 검토한 스냅샷이다.

| 비용 항목 | 계산 기준 | 730시간 월 환산 |
| --- | --- | ---: |
| EC2 `t3.micro` | USD 0.013/시간 × 730시간 | USD 9.49 |
| EC2 EBS gp3 20 GiB | USD 0.096/GiB-월 × 20 GiB | USD 1.92 |
| RDS MySQL `db.t4g.micro` Single-AZ | USD 0.026/시간 × 730시간 | USD 18.98 |
| RDS gp3 20 GiB | USD 0.138/GiB-월 × 20 GiB | USD 2.76 |
| public IPv4 1개 | USD 0.005/시간 × 730시간 | USD 3.65 |
| 기본 고정비 합계 | CPU credit, 추가 backup/IO 제외 | USD 36.80 |

ECR 및 S3 저장량, CloudFront 요청·데이터 전송, 소량의 CloudWatch 변동분을 더해 크레딧 적용 전 월 예상액을 약 USD 38~41로 잡는다. 2026-07-15부터 8월 말까지 1,128시간을 같은 방식으로 계산하면 고정비 약 USD 56.86이며, 변동분을 포함한 누적 예상액은 약 USD 58~61이다. 이는 세금 별도 금액으로 한국 부가가치세 10%가 적용되면 약 USD 64~67까지 청구될 수 있다. CPU baseline 초과 credit, 추가 RDS backup/snapshot, 대량 이미지 저장·전송은 포함하지 않았다.

재계산 시 [AWS Pricing Calculator](https://calculator.aws/), [EC2 On-Demand](https://aws.amazon.com/ec2/pricing/on-demand/), [EBS](https://aws.amazon.com/ebs/pricing/), [RDS for MySQL](https://aws.amazon.com/rds/mysql/pricing/), [VPC public IPv4](https://aws.amazon.com/vpc/pricing/)의 최신 서울 리전 단가로 위 수식을 갱신한다. 체험 크레딧/무료 사용량이 우선 적용될 수 있지만 적용 범위와 잔액에 따라 비용이 청구될 수 있다.

- AWS Budgets 알림과 Free Tier/크레딧 잔액을 정기적으로 확인한다.
- 장기간 미사용 시 EC2만 정지해도 RDS 스토리지와 public IPv4 관련 비용은 계속될 수 있다.
- 개발 종료 또는 운영 중단 시 EC2, RDS, EBS snapshot, ECR image, Parameter Store, public IPv4 사용 여부를 함께 검토한다.
- RDS 삭제 전 최종 snapshot 보존 여부를 결정하고, 불필요하면 snapshot도 삭제한다.
- 예상치 못한 비용이 발생하면 먼저 배포를 중단하고 Cost Explorer에서 서비스별 사용량을 확인한다.

## 활성화 체크리스트

- [x] EC2와 private RDS가 생성되어 있다.
- [x] EC2 전용 security group과 RDS 전용 security group이 연결되어 있다.
- [x] EC2 instance profile이 연결되어 있고 SSM node 상태가 `Online`이다.
- [x] EC2 runtime 요구사항을 모두 설치했다.
- [x] Elastic IP를 운영 EC2에 연결했다.
- [x] CloudFront API 배포와 `PUBLIC_BASE_URL`을 구성했다.
- [x] EC2 HTTP 80 source를 CloudFront origin-facing prefix list로 제한했다.
- [x] private S3 이미지 버킷, CloudFront OAC, trusted key group을 구성했다.
- [x] 이미지 저장소 Repository Variable 세 개와 `/fitback/prod/cloudfront-private-key` SecureString을 구성했다.
- [x] `/fitback/prod/jwt-secret-key`를 포함한 운영 Parameter Store SecureString을 모두 생성했다.
- [x] GitHub OIDC 역할에 SSM 최소 권한을 추가했다.
- [x] GitHub Repository Variable `EC2_INSTANCE_ID`를 추가했다.
- [x] `Backend CD`를 실제 실행해 SSM command와 health check를 확인했다.
- [x] CloudFront HTTPS endpoint에서 Nginx와 backend readiness를 확인했다.
