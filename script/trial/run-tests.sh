#!/usr/bin/env bash
# Local development only. Never packages/replaces the production-mounted server JAR.
set -euo pipefail
cd "$(dirname "$0")/../.."
export MAVEN_OPTS='-Xmx512m -XX:ActiveProcessorCount=2'
mvn -q -pl yudao-module-crm -am test \
  -Dtest=TrialOrchestratorTest,TrialServiceAuthTest,TrialToolHttpIntegrationTest,TrialAuthorizationTest,MgsTrialOAuthGatewayTest,KnowdoResponseBoundTest,TrialBusinessIntegrationTest,TrialOperatorBootstrapTest,TrialLoginDeliveryTest,MgsTrialAccountIntegrationTest,TrialCorporateTenantIntegrationTest,TrialLocalJourneyIntegrationTest,TrialProcessRestartTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'

# Generate docs without loading business infrastructure or modifying the running JAR.
mvn -q -pl yudao-server -am test \
  -Dtest=TrialOpenApiExportTest -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
python3 script/trial/build-tool-contract.py yudao-server/target/trial-openapi.json yudao-server/target/trial-tool-contract.json
