#!/usr/bin/env python3
"""Select reviewed Agent operations from generated Springdoc; never manufacture schemas."""
import argparse
import copy
import hashlib
import json
from pathlib import Path


CATALOGS = {
    "onboarding": [("post", "/admin-api/crm/trial-tool/" + action)
                   for action in ("submit", "create-accounts", "status", "guide")],
    "personal-business": [(method, "/admin-api/crm/trial-business/" + action)
                          for method, action in (("get", "customer"), ("get", "follow-up-types"),
                                                 ("post", "follow-up"), ("get", "follow-ups"))],
}


def references(node):
    if isinstance(node, dict):
        if "$ref" in node:
            yield node["$ref"]
        for value in node.values():
            yield from references(value)
    elif isinstance(node, list):
        for value in node:
            yield from references(value)


def catalog(document, name):
    result = {key: copy.deepcopy(value) for key, value in document.items()
              if key not in {"paths", "components", "webhooks", "tags"}}
    result["paths"] = {}
    for method, path in CATALOGS[name]:
        item = document["paths"][path]
        operation = copy.deepcopy(item[method])  # Missing reviewed routes must fail the build.
        if not operation.get("operationId"):
            raise ValueError(f"Missing operationId: {method} {path}")
        result["paths"][path] = {key: copy.deepcopy(item[key]) for key in
                                 ("summary", "description", "servers", "parameters") if key in item}
        result["paths"][path][method] = operation
    pending = list(references(result))
    # Security requirements reference component names rather than using $ref.
    requirements = list(result.get("security", []))
    for method, path in CATALOGS[name]:
        requirements.extend(result["paths"][path][method].get("security", []))
    for requirement in requirements:
        for scheme in requirement:
            pending.append("#/components/securitySchemes/" + scheme.replace("~", "~0").replace("/", "~1"))
    copied = set()
    while pending:
        reference = pending.pop()
        if reference in copied:
            continue
        parts = reference.split("/")
        if len(parts) < 4 or parts[:2] != ["#", "components"]:
            raise ValueError(f"Only local component references are supported: {reference}")
        category, key = (part.replace("~1", "/").replace("~0", "~") for part in parts[2:4])
        target = document["components"][category][key]
        # Check subcomponent pointers too, while copying the entire owning component.
        resolved = target
        for part in parts[4:]:
            part = part.replace("~1", "/").replace("~0", "~")
            resolved = resolved[int(part)] if isinstance(resolved, list) else resolved[part]
        result.setdefault("components", {}).setdefault(category, {})[key] = copy.deepcopy(target)
        copied.add(reference)
        pending.extend(references(target))
    result["x-mgs-agent-catalog"] = {"name": name, "deployedVerified": False,
                                     "knowdoImportedVerified": False,
                                     "serverConfigurationRequired": True}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("document", type=Path)
    parser.add_argument("output_directory", type=Path)
    args = parser.parse_args()
    raw = args.document.read_bytes()
    source = json.loads(raw)
    # Validate every selection before writing any output.
    documents = {name: catalog(source, name) for name in CATALOGS}
    args.output_directory.mkdir(parents=True, exist_ok=True)
    manifest = {"sourceSha256": hashlib.sha256(raw).hexdigest(),
                "sourceVersion": source["info"]["version"],
                "contractVersion": source.get("x-mgs-trial-contract-version"),
                "deployedVerified": False, "knowdoImportedVerified": False, "catalogs": []}
    for name, document in documents.items():
        filename = f"{name}-openapi.json"
        content = (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode()
        (args.output_directory / filename).write_bytes(content)
        manifest["catalogs"].append({"file": filename, "sha256": hashlib.sha256(content).hexdigest(),
                                     "operations": [method.upper() + " " + path for method, path in CATALOGS[name]]})
    (args.output_directory / "agent-catalog-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print("Derived two Agent catalogs, four operations each; deployment and KnowDo import unverified")


if __name__ == "__main__":
    main()
