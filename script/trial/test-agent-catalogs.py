#!/usr/bin/env python3
"""Regress restricted exports against actual generated Springdoc, including malformed input."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("catalogs", Path(__file__).with_name("build-agent-catalogs.py"))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
SOURCE = Path(os.environ.get("TRIAL_OPENAPI", "yudao-server/target/trial-openapi.json"))


class AgentCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = json.loads(SOURCE.read_bytes())

    def test_reviewed_operations_preserve_actual_schemas_auth_and_success(self):
        for name, selection in builder.CATALOGS.items():
            with self.subTest(catalog=name):
                result = builder.catalog(self.source, name)
                self.assertEqual(set(result["paths"]), {path for _, path in selection})
                self.assertEqual(4, len(result["paths"]))
                for method, path in selection:
                    operation = result["paths"][path][method]
                    self.assertEqual(self.source["paths"][path][method], operation)
                    self.assertTrue(operation["security"])
                    self.assertIs(type(operation["x-business-success"]["equals"]), int)
                    self.assertEqual(0, operation["x-business-success"]["equals"])
                for category, components in result["components"].items():
                    for key, value in components.items():
                        self.assertEqual(self.source["components"][category][key], value)
                for reference in builder.references(result):
                    target = result
                    for part in reference[2:].split("/"):
                        part = part.replace("~1", "/").replace("~0", "~")
                        target = target[int(part)] if isinstance(target, list) else target[part]
                scheme = "MgsTrialSignature" if name == "onboarding" else "MgsTrialPersonalBearer"
                self.assertEqual({scheme}, set(result["components"]["securitySchemes"]))
                self.assertFalse(result["x-mgs-agent-catalog"]["knowdoImportedVerified"])

    def test_operator_event_and_new_unreviewed_routes_are_not_selected(self):
        source = copy.deepcopy(self.source)
        source["paths"]["/admin-api/crm/trial-tool/new-admin-operation"] = {"post": {"operationId": "unsafe"}}
        for name in builder.CATALOGS:
            result = builder.catalog(source, name)
            self.assertFalse(any("trial-operations" in path or "trial-event" in path or "trial-internal" in path
                                 or "new-admin-operation" in path for path in result["paths"]))
            self.assertFalse(any("TrialOperations" in name or "TrialEvent" in name
                                 for name in result["components"]["schemas"]))

    def test_missing_reviewed_operation_fails(self):
        source = copy.deepcopy(self.source)
        del source["paths"]["/admin-api/crm/trial-tool/submit"]["post"]
        with self.assertRaises(KeyError):
            builder.catalog(source, "onboarding")

    def test_missing_schema_or_external_reference_fails(self):
        for reference, error in (("#/components/schemas/Unknown", KeyError), ("https://example.invalid/schema", ValueError)):
            source = copy.deepcopy(self.source)
            source["paths"]["/admin-api/crm/trial-tool/submit"]["post"]["requestBody"] = {"$ref": reference}
            with self.assertRaises(error):
                builder.catalog(source, "onboarding")

    def test_recursive_schema_keeps_reference_without_expanding_forever(self):
        source = copy.deepcopy(self.source)
        source["components"]["schemas"]["Recursive"] = {"type": "object", "properties": {
            "child": {"$ref": "#/components/schemas/Recursive"}}}
        source["paths"]["/admin-api/crm/trial-tool/submit"]["post"]["requestBody"] = {"content": {
            "application/json": {"schema": {"$ref": "#/components/schemas/Recursive"}}}}
        result = builder.catalog(source, "onboarding")
        self.assertEqual(source["components"]["schemas"]["Recursive"], result["components"]["schemas"]["Recursive"])


if __name__ == "__main__":
    unittest.main()
