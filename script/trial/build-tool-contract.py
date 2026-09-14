#!/usr/bin/env python3
"""Derive tool field locations and completion semantics from actual Springdoc output."""
import argparse
import hashlib
import json
import pathlib

parser = argparse.ArgumentParser()
parser.add_argument("document", type=pathlib.Path)
parser.add_argument("output", type=pathlib.Path)
args = parser.parse_args()
raw = args.document.read_bytes()
doc = json.loads(raw)

def resolve(schema):
    if "$ref" in schema:
        target = doc
        for segment in schema["$ref"].removeprefix("#/").split("/"):
            target = target[segment.replace("~1", "/").replace("~0", "~")]
        return target
    return schema

def fields(schema, path="", ancestors=()):
    if schema.get("$ref") in ancestors:
        return [{"path": path, "recursiveReference": schema["$ref"]}]
    ancestors = (*ancestors, schema.get("$ref", object()))
    schema = resolve(schema)
    rows = []
    for name, value in schema.get("properties", {}).items():
        field_path = f"{path}.{name}" if path else name
        node = resolve(value)
        rows.append({"path": field_path, "type": node.get("type"), "requiredInParent": name in schema.get("required", []),
                     "description": node.get("description", ""), "schema": value})
        if node.get("properties"):
            rows.extend(fields(value, field_path, ancestors))
        if "items" in node:
            rows.extend(fields(node["items"], field_path + "[*]", ancestors))
    return rows

operations = []
for path, methods in sorted(doc.get("paths", {}).items()):
    for method, op in sorted(methods.items()):
        if method not in {"get", "post", "put", "patch", "delete"}:
            continue
        mapping = []
        for param in op.get("parameters", []):
            mapping.append({"source": "OpenAPI parameter", "in": param["in"], "path": param["name"],
                            "required": param.get("required", False), "schema": param.get("schema", {})})
        for media, content in op.get("requestBody", {}).get("content", {}).items():
            for field in fields(content.get("schema", {})):
                mapping.append({"source": "OpenAPI requestBody", "in": "requestBody", "mediaType": media, **field})
        # All controllers in this focused document have source-confirmed CommonResult return types.
        response = resolve(op.get("responses", {}).get("200", {}).get("content", {}).get("application/json", {}).get("schema", {}))
        props = response.get("properties", {})
        common_result = props.get("code", {}).get("type") == "integer" and "msg" in props and "data" in props
        item = {"key": method.upper() + " " + path, "operationId": op.get("operationId"), "parameterMapping": mapping,
                "requestSuccess": {"path": "code", "equals": 0} if common_result else None,
                "errorMessagePath": "msg" if common_result else None,
                "accountReadyCondition": op.get("x-mgs-account-ready-condition"),
                "readOnly": op.get("x-mgs-read-only", method == "get"),
                "trustedServiceContract": op.get("x-mgs-service-contract"),
                "evidence": "Actual Springdoc output; focused MGS trial controllers return CommonResult. Request success does not imply account readiness, binding or completed business.",
                "manualVerification": ["KnowDo importer must map only actual tool fields and inject identity from a verified server session.",
                                       "No claim that KnowDo has imported/applied this file; real WeChat end-to-end validation remains pending."]}
        operations.append(item)
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps({"contractVersion": "mgs-trial-v1-candidate", "openapiVersion": doc.get("info", {}).get("version"),
                                  "openapiSha256": hashlib.sha256(raw).hexdigest(), "operations": operations,
                                  "credentialEndpointsExcluded": True, "realEndToEndVerified": False}, ensure_ascii=False, indent=2) + "\n")
print(f"Derived {len(operations)} operations")
