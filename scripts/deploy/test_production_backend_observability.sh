#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

compose_json="$(
  BACKEND_IMAGE='example.invalid/fitback-backend@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
  DB_URL='jdbc:mysql://database.internal:3306/fitback' \
  DB_USER='fitback_app' \
  DB_PASSWORD='validation-password' \
  JWT_SECRET_KEY='validation-jwt-secret-key-32bytes' \
  HMAC_SECRET_KEY='validation-hmac-secret-key-32bytes' \
  KAKAO_REST_API_KEY='validation-kakao-key' \
  KAKAO_REST_API_SECRET='validation-kakao-secret' \
  FRONT_REDIRECT_URI='https://frontend.example.invalid/oauth/success' \
  MAIL_EMAIL='validation@example.invalid' \
  MAIL_APP_PASSWORD='validation-mail-password' \
  FRONT_PASSWORD_RESET_URL='https://frontend.example.invalid/reset-password' \
  AWS_REGION='ap-northeast-2' \
  IMAGE_BUCKET='fitback-validation-images' \
  IMAGE_CDN_BASE_URL='https://images.example.invalid' \
  CLOUDFRONT_KEY_PAIR_ID='VALIDATIONKEY' \
  CLOUDFRONT_PRIVATE_KEY_BASE64='dmFsaWRhdGlvbi1rZXk=' \
  APP_CORS_ALLOWED_ORIGINS='https://frontend.example.invalid' \
    docker compose \
      --file "$repo_root/compose.yaml" \
      config \
      --format json
)"

export COMPOSE_CONFIG_JSON="$compose_json"
export OBSERVABILITY_TEMPLATE="$repo_root/deploy/aws/production-backend-observability.yaml"
export DEPLOYMENT_DOC="$repo_root/docs/DEPLOYMENT.md"

production_docker_server_version='25.0.16'
docker_server_version="$(docker version --format '{{.Server.Version}}')"
test -n "$docker_server_version"

scratch_image="$(docker image ls --format '{{.Repository}}:{{.Tag}}' | sed '/^<none>:/d' | sed -n '1p')"
if [ -z "$scratch_image" ]; then
  scratch_image='alpine:3.20'
  docker pull --quiet "$scratch_image" >/dev/null
fi

scratch_container_name="fitback-observability-contract-${RANDOM}"
scratch_container_id=''
cleanup_scratch_container() {
  if [ -n "$scratch_container_id" ]; then
    docker rm -f "$scratch_container_id" >/dev/null 2>&1 || true
  fi
}

scratch_container_id="$(docker create \
  --pull=never \
  --name "$scratch_container_name" \
  --log-driver=awslogs \
  --log-opt awslogs-region=ap-northeast-2 \
  --log-opt awslogs-group=/fitback/prod/backend \
  --log-opt 'tag=backend/{{.Name}}/{{.FullID}}' \
  --log-opt awslogs-create-group=false \
  "$scratch_image" true)"
trap cleanup_scratch_container EXIT
test "$(docker inspect --format '{{.HostConfig.LogConfig.Type}}' "$scratch_container_id")" = 'awslogs'
test "$(docker inspect --format '{{index .HostConfig.LogConfig.Config "tag"}}' "$scratch_container_id")" = 'backend/{{.Name}}/{{.FullID}}'

if [ "${REQUIRE_PRODUCTION_DOCKER_SERVER_VERSION:-false}" = 'true' ]; then
  test "$docker_server_version" = "$production_docker_server_version"
fi

echo "Docker $docker_server_version accepted the production awslogs tag on a stopped scratch container."

ruby <<'RUBY'
require 'json'
require 'yaml'

class CloudFormationTag
  attr_reader :value

  def init_with(coder)
    @value = coder.scalar || coder.seq || coder.map
  end
end

%w[Equals GetAtt If Ref Sub].each do |tag|
  Psych.add_tag("!#{tag}", CloudFormationTag)
end

def assert(condition, message)
  abort message unless condition
end

compose = JSON.parse(ENV.fetch('COMPOSE_CONFIG_JSON'))
backend = compose.fetch('services').fetch('backend')
logging = backend.fetch('logging')
options = logging.fetch('options')

assert(logging['driver'] == 'awslogs', 'Backend logging driver must be awslogs.')
assert(options['awslogs-region'] == 'ap-northeast-2', 'Backend logs must use ap-northeast-2.')
assert(options['awslogs-group'] == '/fitback/prod/backend', 'Unexpected backend log group.')
assert(options['awslogs-create-group'] == 'false', 'The EC2 role must not create log groups.')

production_docker_server_version = '25.0.16'
supported_awslogs_options = %w[
  awslogs-create-group
  awslogs-credentials-endpoint
  awslogs-datetime-format
  awslogs-endpoint
  awslogs-force-flush-interval-seconds
  awslogs-format
  awslogs-group
  awslogs-max-buffered-events
  awslogs-multiline-pattern
  awslogs-region
  awslogs-stream
  tag
]
unsupported_awslogs_options = options.keys - supported_awslogs_options
assert(
  unsupported_awslogs_options.empty?,
  "Docker #{production_docker_server_version} does not support awslogs options: #{unsupported_awslogs_options.join(', ')}"
)
assert(options['tag'] == 'backend/{{.Name}}/{{.FullID}}', 'Backend log streams need a readable, unique Docker tag template.')
assert(options['awslogs-create-group'] == 'false', 'Backend logging must keep awslogs-create-group=false.')
assert(!options.key?('awslogs-stream'), 'A static awslogs stream would collide across recreation or scale-out.')

template_path = ENV.fetch('OBSERVABILITY_TEMPLATE')
template = YAML.safe_load(
  File.read(template_path),
  permitted_classes: [CloudFormationTag],
  aliases: true
)
resources = template.fetch('Resources')

log_group = resources.fetch('BackendLogGroup')
assert(log_group['Type'] == 'AWS::Logs::LogGroup', 'BackendLogGroup type is invalid.')
assert(log_group['DeletionPolicy'] == 'Retain', 'Backend log group must be retained on stack deletion.')
assert(log_group.dig('Properties', 'LogGroupName') == '/fitback/prod/backend', 'Template log group differs from Compose.')
assert(log_group.dig('Properties', 'RetentionInDays') == 30, 'Backend logs must retain 30 days.')

writer_policy = resources.fetch('BackendLogWriterPolicy')
assert(writer_policy['Type'] == 'AWS::IAM::RolePolicy', 'Log writer policy must attach to the existing role.')
statements = writer_policy.dig('Properties', 'PolicyDocument', 'Statement')
assert(statements.is_a?(Array) && statements.length == 1, 'Log writer policy must have one statement.')
writer_statement = statements.first
assert(
  Array(writer_statement['Action']).sort == %w[logs:CreateLogStream logs:PutLogEvents].sort,
  'EC2 log writer actions are broader than required.'
)
assert(writer_statement['Resource'].is_a?(CloudFormationTag), 'EC2 log writer resource must reference the log group ARN.')
assert(writer_statement['Resource'].value == 'BackendLogGroup.Arn', 'EC2 log writer resource must be BackendLogGroup.Arn.')

metric_filter = resources.fetch('ProviderRetryMetricFilter')
expected_pattern = '"AI tag provider logical request" "provider=openai" "providerAttemptCount=2"'
assert(metric_filter['Type'] == 'AWS::Logs::MetricFilter', 'Provider retry filter type is invalid.')
assert(metric_filter.dig('Properties', 'FilterPattern') == expected_pattern, 'Provider retry filter must count actual second attempts.')
transformation = metric_filter.dig('Properties', 'MetricTransformations')&.first
assert(transformation&.fetch('MetricNamespace') == 'Fitback/OpenAI', 'Unexpected retry metric namespace.')
assert(transformation&.fetch('MetricName') == 'ProviderRetryCount', 'Unexpected retry metric name.')
assert(transformation&.fetch('MetricValue') == '1', 'Each logical retry event must increment by one.')
assert(transformation&.fetch('Unit') == 'Count', 'Retry metric unit must be Count.')
assert(!transformation.key?('Dimensions'), 'Retry metrics must not include high-cardinality or sensitive dimensions.')

alarm = resources.fetch('ProviderRetryAlarm').fetch('Properties')
assert(alarm['Namespace'] == 'Fitback/OpenAI', 'Alarm namespace differs from the metric filter.')
assert(alarm['MetricName'] == 'ProviderRetryCount', 'Alarm metric differs from the metric filter.')
assert(alarm['Statistic'] == 'Sum', 'Retry alarm must aggregate with Sum.')
assert(alarm['Period'] == 300, 'Retry alarm period must be five minutes.')
assert(alarm['EvaluationPeriods'] == 1 && alarm['DatapointsToAlarm'] == 1, 'Retry alarm must evaluate one period.')
assert(alarm['Threshold'] == 1, 'Retry alarm threshold must be one event.')
assert(alarm['ComparisonOperator'] == 'GreaterThanOrEqualToThreshold', 'Retry alarm comparison is invalid.')
assert(alarm['TreatMissingData'] == 'notBreaching', 'Missing retry data must not alarm.')
assert(Array(alarm['AlarmActions']).length == 1, 'Retry alarm must use exactly one SNS action.')

topic = resources.fetch('AlarmNotificationTopic')
assert(topic['Type'] == 'AWS::SNS::Topic', 'Fallback notification resource must be SNS.')
topic_properties = topic.fetch('Properties')
topic_key = topic_properties['KmsMasterKeyId']
assert(topic_key.is_a?(CloudFormationTag), 'Fallback SNS topic must reference its customer-managed KMS key.')
assert(topic_key.value == 'AlarmNotificationKey.Arn', 'Fallback SNS topic must use AlarmNotificationKey.')
assert(!topic_properties.key?('Subscription'), 'Fallback SNS topic must not invent an external subscription.')

notification_key = resources.fetch('AlarmNotificationKey')
assert(notification_key['Type'] == 'AWS::KMS::Key', 'Fallback notification key must be customer-managed KMS.')
assert(notification_key['Condition'] == 'CreateAlarmTopic', 'Notification key must only exist with the fallback topic.')
assert(notification_key['DeletionPolicy'] == 'Retain', 'Notification key must be retained on stack deletion.')
assert(notification_key['UpdateReplacePolicy'] == 'Retain', 'Replaced notification keys must be retained.')
assert(notification_key.dig('Properties', 'EnableKeyRotation') == true, 'Notification key rotation must be enabled.')

key_statements = notification_key.dig('Properties', 'KeyPolicy', 'Statement')
assert(key_statements.is_a?(Array) && key_statements.length == 3, 'Notification key policy must have three statements.')
key_admin = key_statements.find { |statement| statement['Sid'] == 'AllowAccountKeyAdministration' }
assert(!key_admin.nil?, 'Notification key policy must retain account administration.')
assert(key_admin.dig('Principal', 'AWS').value == 'arn:${AWS::Partition}:iam::${AWS::AccountId}:root', 'Only the owning account may administer the notification key.')
expected_admin_actions = %w[
  kms:CancelKeyDeletion
  kms:DescribeKey
  kms:DisableKey
  kms:DisableKeyRotation
  kms:EnableKey
  kms:EnableKeyRotation
  kms:GetKeyPolicy
  kms:GetKeyRotationStatus
  kms:ListGrants
  kms:ListKeyPolicies
  kms:ListResourceTags
  kms:PutKeyPolicy
  kms:RevokeGrant
  kms:ScheduleKeyDeletion
  kms:TagResource
  kms:UntagResource
  kms:UpdateKeyDescription
]
assert(Array(key_admin['Action']).sort == expected_admin_actions.sort, 'Account KMS administration actions changed or are too broad.')
assert(key_admin['Resource'] == '*', 'KMS key policies must scope administration to the key itself.')

cloudwatch_key = key_statements.find { |statement| statement['Sid'] == 'AllowCloudWatchAlarmEncryption' }
assert(!cloudwatch_key.nil?, 'Notification key policy must allow CloudWatch alarm encryption.')
assert(cloudwatch_key.dig('Principal', 'Service') == 'cloudwatch.amazonaws.com', 'Only CloudWatch may use the notification key.')
assert(Array(cloudwatch_key['Action']).sort == %w[kms:Decrypt kms:GenerateDataKey*].sort, 'CloudWatch KMS actions must remain minimal.')
assert(cloudwatch_key['Resource'] == '*', 'KMS key policies must scope use to the key itself.')
assert(cloudwatch_key.dig('Condition', 'StringEquals', 'aws:SourceAccount').value == 'AWS::AccountId', 'CloudWatch KMS use must be account-scoped.')
assert(cloudwatch_key.dig('Condition', 'ArnEquals', 'aws:SourceArn').value.include?('alarm:fitback-prod-openai-provider-retry'), 'CloudWatch KMS use must be alarm-scoped.')

sns_key = key_statements.find { |statement| statement['Sid'] == 'AllowSnsTopicEncryption' }
assert(!sns_key.nil?, 'Notification key policy must allow SNS topic encryption.')
assert(sns_key.dig('Principal', 'Service') == 'sns.amazonaws.com', 'Only SNS may use the topic encryption statement.')
assert(Array(sns_key['Action']).sort == %w[kms:Decrypt kms:GenerateDataKey].sort, 'SNS KMS actions must remain minimal.')
assert(sns_key['Resource'] == '*', 'KMS key policies must scope SNS use to the key itself.')
assert(sns_key.dig('Condition', 'StringEquals', 'aws:SourceAccount').value == 'AWS::AccountId', 'SNS KMS use must be account-scoped.')
assert(sns_key.dig('Condition', 'ArnEquals', 'aws:SourceArn').value.include?('alarm:fitback-prod-openai-provider-retry'), 'SNS KMS use must be alarm-scoped.')
assert(sns_key.dig('Condition', 'StringEquals', 'kms:EncryptionContext:aws:sns:topicArn').value.include?('fitback-prod-openai-provider-retry'), 'SNS KMS use must be topic-scoped.')

topic_policy = resources.fetch('AlarmNotificationTopicPolicy')
assert(topic_policy['Type'] == 'AWS::SNS::TopicPolicy', 'Fallback topic must have an SNS topic policy.')
assert(topic_policy['Condition'] == 'CreateAlarmTopic', 'SNS topic policy must only exist with the fallback topic.')
publish_statements = topic_policy.dig('Properties', 'PolicyDocument', 'Statement')
assert(publish_statements.is_a?(Array) && publish_statements.length == 1, 'SNS topic policy must have one statement.')
cloudwatch_publish = publish_statements.first
assert(cloudwatch_publish.dig('Principal', 'Service') == 'cloudwatch.amazonaws.com', 'Only CloudWatch may publish through the topic policy.')
assert(Array(cloudwatch_publish['Action']) == ['sns:Publish'], 'CloudWatch SNS actions must remain minimal.')
assert(cloudwatch_publish['Resource'].value == 'AlarmNotificationTopic', 'CloudWatch publish must target only the fallback topic.')
assert(cloudwatch_publish.dig('Condition', 'StringEquals', 'aws:SourceAccount').value == 'AWS::AccountId', 'CloudWatch SNS publish must be account-scoped.')
assert(cloudwatch_publish.dig('Condition', 'ArnEquals', 'aws:SourceArn').value.include?('alarm:fitback-prod-openai-provider-retry'), 'CloudWatch SNS publish must be alarm-scoped.')

deployment_text = File.read(ENV.fetch('DEPLOYMENT_DOC'))
describe_streams_command = deployment_text[/aws logs describe-log-streams \\\n.*?(?=\n\n)/m]
assert(!describe_streams_command.nil?, 'Deployment guide must include the log stream verification command.')
assert(describe_streams_command.include?('--order-by LastEventTime'), 'Log stream verification must sort by last event time.')
assert(!describe_streams_command.include?('--log-stream-name-prefix'), 'LastEventTime ordering cannot use a log stream prefix.')

template_text = File.read(template_path)
forbidden = ['logs:CreateLogGroup', 'alias/aws/sns', 'kms:*', 'xRequestId', 'apiKey', 'raw response', 'request body', 'response body', 'data URL']
leaked = forbidden.select { |term| template_text.include?(term) }
assert(leaked.empty?, "Observability template contains forbidden data or permission: #{leaked.join(', ')}")
RUBY

echo 'Production backend observability contract tests passed.'
