#!/usr/bin/env bash
# Local development only. Never packages/replaces the production-mounted server JAR.
set -euo pipefail
cd "$(dirname "$0")/../.."
export MAVEN_OPTS='-Xmx512m -XX:ActiveProcessorCount=2'
mvn -q -pl yudao-module-crm -am test \
  -Dtest=TrialConnectorHttpIntegrationTest,TrialOrchestratorTest,TrialServiceAuthTest,TrialToolHttpIntegrationTest,TrialSmsVerificationTest,MgsTrialSmsSenderTest,TrialAuthorizationTest,MgsTrialOAuthGatewayTest,KnowdoResponseBoundTest,TrialBusinessIntegrationTest,TrialOperatorBootstrapTest,TrialLoginDeliveryTest,MgsTrialAccountIntegrationTest,TrialCorporateTenantIntegrationTest,TrialLocalJourneyIntegrationTest,TrialProcessRestartTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'

python3 -m unittest discover -s script/trial -p 'test_connector_key.py' -v

# Generate docs without loading business infrastructure or modifying the running JAR.
mvn -q -pl yudao-server -am test \
  -Dtest=TrialOpenApiExportTest -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
python3 script/trial/build-tool-contract.py yudao-server/target/trial-openapi.json yudao-server/target/trial-tool-contract.json
python3 script/trial/test-agent-catalogs.py
python3 script/trial/build-agent-catalogs.py yudao-server/target/trial-openapi.json yudao-server/target/trial-agent-catalogs
for catalog in onboarding personal-business; do
  python3 script/trial/build-tool-contract.py "yudao-server/target/trial-agent-catalogs/${catalog}-openapi.json" "yudao-server/target/trial-agent-catalogs/${catalog}-tool-contract.json"
done
