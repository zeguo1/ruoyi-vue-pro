#!/usr/bin/env bash
# Isolated development checks only: no server boot, deployment, production writes or KnowDo access.
set -euo pipefail
cd "$(dirname "$0")/../.."
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx640m -XX:ActiveProcessorCount=2}"
mvn -q -pl yudao-server -am test \
  -Dtest=FullOpenApiExportTest,ContractSchemaCustomizerTest,WireContractTest,BackendHandoffContractTest,FullContractValidationTest,FullOpenApiModelAuditTest,ExternalOpenApiContractTest,OpenApiItemSchemaTest,ErpSaleOrderContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DargLine='-Xmx512m -XX:ActiveProcessorCount=2'
OPENAPI_PYTHON="${OPENAPI_PYTHON:-python3}"
"$OPENAPI_PYTHON" -m unittest discover -s script/openapi/tests -v
"$OPENAPI_PYTHON" script/openapi/audit_full_contract.py yudao-server/target/openapi-integration-all.json \
  --output yudao-server/target/integration-schema-audit.json \
  --inventory yudao-server/target/full-model-inventory.json --strict
"$OPENAPI_PYTHON" script/openapi/verify_integration_contract.py \
  --document yudao-server/target/openapi-integration-all.json \
  --inventory yudao-server/target/openapi-handler-inventory.json \
  --downloads script/openapi/v6-download-contracts.json \
  --examples yudao-server/target/v6-response-examples.json \
  --output yudao-server/target/integration-wire-verification.json
"$OPENAPI_PYTHON" script/openapi/build_integration_checklist.py yudao-server/target/openapi-integration-all.json \
  yudao-server/target/integration-checklist.json --summary yudao-server/target/integration-summary.json
"$OPENAPI_PYTHON" script/openapi/build_integration_checklist.py yudao-server/target/full-openapi-isolated.json \
  yudao-server/target/integration-raw-checklist.json.gz --summary yudao-server/target/integration-raw-summary.json
