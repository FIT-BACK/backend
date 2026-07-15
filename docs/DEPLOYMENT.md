# FIT-BACK Backend 운영 배포

## 배포 구조

`main`에 변경이 병합되면 `Backend CD`가 다음 순서로 실행된다.

1. Docker 이미지를 빌드하고 H2 기반 readiness 검증을 수행한다.
2. GitHub OIDC로 AWS IAM 역할을 위임받아 ECR에 `git-${GITHUB_SHA}` 태그를 푸시한다.
3. ECR에서 `sha256` digest를 확인해 변경 불가능한 이미지 참조를 생성한다.
4. `EC2_INSTANCE_ID` 저장소 변수가 설정된 경우에만 SSM Run Command로 EC2 배포를 실행한다.
5. EC2는 고유한 release 디렉터리에서 Parameter Store의 DB 값을 읽고 Compose stack을 갱신한다.
6. Nginx와 backend health check가 실패하면 직전 release 전체로 rollback한다.

SSH, EC2 key pair, 장기 AWS Access Key는 사용하지 않는다.

## GitHub Repository Variables

| 변수 | 필수 시점 | 설명 |
| --- | --- | --- |
| `AWS_REGION` | ECR 발행 | AWS 리전. 현재 기준은 `ap-northeast-2`이다. |
| `AWS_DEPLOY_ROLE_ARN` | ECR 발행 | GitHub OIDC가 위임받는 IAM 역할 ARN이다. |
| `ECR_REPOSITORY` | ECR 발행 | backend ECR repository 이름이다. |
| `EC2_INSTANCE_ID` | EC2 배포 활성화 | SSM 관리형 노드로 등록된 운영 EC2 instance ID이다. 값이 없으면 `deploy-production` job은 건너뛴다. |
| `DEPLOY_PARAMETER_PREFIX` | 선택 | Parameter Store 경로 prefix이다. workflow가 이 저장소 변수를 원격 스크립트의 `PARAMETER_PREFIX`로 매핑한다. 기본값은 `/fitback/prod`이며, 다른 값을 사용하면 EC2 instance role의 Parameter Store resource ARN도 같은 경로로 변경해야 한다. |

민감정보는 Repository Variable 또는 GitHub command payload에 넣지 않는다.

## SSM Parameter Store

운영 EC2가 다음 값을 직접 읽는다. 세 값은 모두 `SecureString`으로 생성한다.

```text
/fitback/prod/db-url
/fitback/prod/db-user
/fitback/prod/db-password
```

예시 형식은 다음과 같다. 실제 값은 문서나 저장소에 기록하지 않는다.

```text
db-url=jdbc:mysql://<private-rds-endpoint>:3306/fitback?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
db-user=<application-user>
db-password=<generated-password>
```

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
- `/opt/fitback/releases`에 배포 파일과 mode `600`의 비민감 runtime `.env`를 저장하고 `/opt/fitback/current` symlink를 교체할 수 있어야 한다. DB 값은 파일에 기록하지 않고 Compose 프로세스 환경으로만 전달한다.

## 네트워크 계약

- EC2 security group은 애플리케이션 공개에 필요한 HTTP `80`만 허용하고 SSH `22`는 열지 않는다.
- RDS는 public access를 비활성화한다.
- RDS security group의 MySQL `3306` source는 EC2 security group으로 제한한다.
- EC2는 ECR, SSM, Parameter Store, STS에 outbound access할 수 있어야 한다.
- HTTPS와 domain 적용 전에는 테스트 목적 외의 장기 공개 운영을 하지 않는다.

## 배포 및 Rollback 동작

`scripts/deploy/remote_deploy.sh`는 다음 작업을 수행한다.

1. digest가 포함된 ECR 이미지 참조를 검증한다.
2. Parameter Store의 DB 값을 단일 행 값으로 검증한다.
3. host 단위 `flock`을 획득해 같은 EC2에서 두 배포가 동시에 실행되지 않게 한다.
4. 고유한 `/opt/fitback/releases/<release-id>/.env`에 image와 port 등 비민감 runtime 값만 mode `600`으로 원자적으로 작성하고, DB 값은 현재 Compose 프로세스 환경으로 전달한다.
5. EC2 instance role로 ECR에 로그인하고 backend 이미지를 pull한다.
6. 새 release의 `docker compose up -d --remove-orphans`를 실행한다.
7. `/nginx-health`와 backend container health가 모두 정상인지 확인한다.
8. 성공하면 `/opt/fitback/current` symlink를 새 release로 원자적으로 교체한다.
9. 실패하면 직전 release의 Compose asset, Nginx 설정, `.env`, image digest를 함께 다시 시작하고 health를 재검증한 뒤 `current` symlink도 직전 release로 복원한다.

첫 배포가 실패해 이전 release가 없으면 실패한 stack을 내리고 `.env`를 제거한다. 배포 중 예기치 않은 오류, `INT`/`TERM`, 활성 symlink 교체 실패도 같은 rollback 경로를 사용한다. rollback 중 pull, 시작 또는 health 검증이 실패하면 별도의 rollback 실패 코드로 종료한다.

Run Command의 실제 shell 실행 제한은 `executionTimeout=900`초이다. GitHub Actions는 managed node 전달 제한 60초와 실행 제한 900초를 합친 구간에 여유 시간을 더해 polling한다. workflow 중단 또는 polling timeout이 발생하면 `ssm:CancelCommand`를 호출하고, 최대 60초 동안 terminal 상태를 확인한 뒤 마지막 상태와 표준 출력을 기록한다. `send-command --timeout-seconds`는 실행 제한이 아니라 managed node 전달 제한이다.

## 활성화 체크리스트

- [ ] EC2와 private RDS가 생성되어 있다.
- [ ] EC2 전용 security group과 RDS 전용 security group이 연결되어 있다.
- [ ] EC2 instance profile이 연결되어 있고 SSM node 상태가 `Online`이다.
- [ ] EC2 runtime 요구사항을 모두 설치했다.
- [ ] 세 Parameter Store SecureString을 생성했다.
- [ ] GitHub OIDC 역할에 SSM 최소 권한을 추가했다.
- [ ] GitHub Repository Variable `EC2_INSTANCE_ID`를 추가했다.
- [ ] `Backend CD`를 수동 실행해 SSM command와 health check를 확인했다.
- [ ] EC2 public endpoint에서 `/actuator/health/readiness` 또는 서비스 API를 확인했다.
