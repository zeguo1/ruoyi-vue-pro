#!/usr/bin/env python3
"""Reconstruct missing MySQL tables from Java ASTs and checked-in H2 schema hints.

This is a development schema, not a replacement for the official migrations or
seed data. Unknown Java mappings fail closed instead of silently dropping fields.
"""

import argparse
from collections import defaultdict
from dataclasses import dataclass, field
import json
from pathlib import Path
import re
import sys

import javalang


ROOT = Path(__file__).resolve().parents[3]
OUTPUT = ROOT / "sql/mysql/modules"
CREATE = re.compile(r'CREATE TABLE(?: IF NOT EXISTS)?\s+[`"]?(\w+)[`"]?\s*\(', re.I)
JSON_HANDLERS = {"JacksonTypeHandler", "PayClientConfigTypeHandler", "FileClientConfigTypeHandler"}
CSV_HANDLERS = {"LongListTypeHandler", "IntegerListTypeHandler", "StringListTypeHandler", "LongSetTypeHandler"}


def annotation(node, name):
    return next((a for a in node.annotations if a.name.split(".")[-1] == name), None)


def annotation_value(ann, key="value", default=None):
    if ann is None or ann.element is None:
        return default
    value = ann.element
    if isinstance(value, list):
        value = next((e.value for e in value if e.name == key), None)
    elif key != "value":
        return default
    if value is None:
        return default
    if isinstance(value, javalang.tree.Literal):
        if value.value in ("true", "false"):
            return value.value == "true"
        return json.loads(value.value)
    if isinstance(value, javalang.tree.ClassReference):
        return value.type.name
    if isinstance(value, javalang.tree.MemberReference):
        return value.member
    if isinstance(value, javalang.tree.ElementArrayValue):
        return [json.loads(v.value) for v in value.values]
    raise ValueError(f"Unsupported annotation: {ann.name}.{key}: {value}")


def snake(name):
    # MyBatis-Plus StringUtils.camelToUnderline inserts '_' before each capital.
    return re.sub(r"(?<!^)([A-Z])", r"_\1", name).lower()


def comment(doc, fallback):
    if not doc:
        return fallback
    for line in doc.splitlines():
        line = line.strip().lstrip("/* ").rstrip("*/ ")
        if line and not line.startswith("@"):
            line = re.sub(r"\{@\w+\s+([^}]+)\}", r"\1", line)
            return re.sub(r"<[^>]+>", "", line)[:200]
    return fallback


def quote(value):
    # Independent of MySQL's NO_BACKSLASH_ESCAPES setting.
    return "'" + value.replace("\\", "/").replace("'", "''").replace("\n", " ") + "'"


def split_sql(text, delimiter=","):
    """Split top-level SQL lists, respecting strings, comments and parentheses."""
    depth, start, i = 0, 0, 0
    quoted = None
    while i < len(text):
        c = text[i]
        if quoted:
            if c == "\\":
                i += 2
                continue
            if c == quoted:
                if i + 1 < len(text) and text[i + 1] == quoted:
                    i += 2
                    continue
                quoted = None
        elif c in "'\"`":
            quoted = c
        elif text.startswith("--", i):
            end = text.find("\n", i)
            i = len(text) if end < 0 else end
            continue
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            if end < 0:
                raise ValueError("Unclosed SQL comment")
            i = end + 2
            continue
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == delimiter and depth == 0:
            yield text[start:i].strip()
            start = i + 1
        i += 1
    if text[start:].strip():
        yield text[start:].strip()


def table_bodies(sql):
    for statement in split_sql(sql, ";"):
        match = CREATE.search(statement)
        if match:
            # DDL suffixes in these dumps do not contain closing parentheses.
            yield match[1], statement[match.end():statement.rfind(")")]


@dataclass
class Hints:
    columns: dict = field(default_factory=dict)
    indexes: list = field(default_factory=list)
    extra_columns: dict = field(default_factory=dict)
    source: str = ""


def read_hints():
    hints = defaultdict(Hints)
    for path in sorted(ROOT.glob("yudao-module-*/**/src/test/resources/sql/create_tables.sql")):
        sql = path.read_text()
        for table, body in table_bodies(sql):
            hint = hints[table]
            hint.source = str(path.relative_to(ROOT))
            for part in split_sql(body):
                part = re.sub(r"--[^\n]*", "", part).strip()
                col = re.match(r'["`](\w+)["`]\s+(.+)', part, re.S)
                if col:
                    hint.columns[col[1]] = col[2]
                elif re.search(r"\bUNIQUE\s*\(", part, re.I):
                    name = re.search(r'CONSTRAINT\s+["`](\w+)["`]', part, re.I)
                    cols = re.findall(r'["`](\w+)["`]', part[part.index("("):])
                    hint.indexes.append((name[1] if name else f"uk_{len(hint.indexes) + 1}", cols, True))
        for m in re.finditer(
            r'CREATE\s+(UNIQUE\s+)?INDEX\s+(?:IF NOT EXISTS\s+)?["`](\w+)["`]'
            r'\s+ON\s+["`](\w+)["`]\s*\(([^;]+)\)\s*;', sql, re.I
        ):
            hints[m[3]].indexes.append((m[2], re.findall(r'["`](\w+)["`]', m[4]), bool(m[1])))
        # Preserve the checked-in FMS soft-delete uniqueness discriminator.
        for m in re.finditer(
            r'ALTER TABLE\s+"(\w+)"\s+ADD IF NOT EXISTS\s+"(\w+)"\s+'
            r'(int GENERATED ALWAYS AS \(CASE WHEN "deleted" = FALSE THEN 1 ELSE NULL END\));', sql, re.I
        ):
            hints[m[1]].extra_columns[m[2]] = m[3].replace('"deleted"', '`deleted`')
    return hints


def parse_classes():
    classes = {}
    paths = sorted(ROOT.glob("yudao-module-*/**/src/main/java/**/*.java"))
    paths += sorted(ROOT.glob("yudao-framework/**/src/main/java/**/BaseDO.java"))
    paths += sorted(ROOT.glob("yudao-framework/**/src/main/java/**/TenantBaseDO.java"))
    for path in paths:
        source = path.read_text()
        if "@TableName" not in source and path.stem not in {"BaseDO", "TenantBaseDO"}:
            continue
        tree = javalang.parse.parse(source)
        for node in tree.types:
            if annotation(node, "TableName") or node.name in {"BaseDO", "TenantBaseDO"}:
                if node.name in classes:
                    raise ValueError(f"Ambiguous class name: {node.name}")
                classes[node.name] = (node, path)
    return classes


def persistent_fields(node, classes):
    fields = {}
    if node.extends:
        if node.extends.name not in classes:
            raise ValueError(f"Unknown superclass: {node.name}: {node.extends.name}")
        fields.update(persistent_fields(classes[node.extends.name][0], classes))
    excluded = annotation_value(annotation(node, "TableName"), "excludeProperty", [])
    for f in node.fields:
        for decl in f.declarators:
            fields.pop(decl.name, None)
            if f.modifiers & {"static", "transient"}:
                continue
            if annotation_value(annotation(f, "TableField"), "exist", True) is False:
                continue
            fields[decl.name] = (f, decl)
    return {name: pair for name, pair in fields.items() if name not in excluded}


def java_type(f, decl):
    return f.type.name + ("[]" if f.type.dimensions or decl.dimensions else "")


def column_type(f, decl, hint):
    typ = java_type(f, decl)
    handler = annotation_value(annotation(f, "TableField"), "typeHandler")
    if handler in JSON_HANDLERS:
        return "json"
    if handler in CSV_HANDLERS:
        return "text"  # These handlers store comma-separated values, not JSON.
    if handler and handler != "EncryptTypeHandler":
        raise ValueError(f"Unknown type handler: {handler}")
    primitives = {
        "Long": "bigint", "long": "bigint", "Integer": "int", "int": "int",
        "Short": "smallint", "short": "smallint", "Byte": "tinyint", "byte": "tinyint",
        "Boolean": "bit(1)", "boolean": "bit(1)", "Double": "double", "double": "double",
        "Float": "float", "float": "float", "LocalDateTime": "datetime(6)",
        "LocalDate": "date", "LocalTime": "time(6)", "byte[]": "longblob",
    }
    if typ in primitives:
        return primitives[typ]
    if typ == "BigDecimal":
        match = re.match(r"decimal\s*\((\d+),\s*(\d+)\)", hint, re.I)
        return f"decimal({match[1]},{match[2]})" if match else "decimal(24,6)"
    if typ == "String":
        if handler == "EncryptTypeHandler":
            return "longtext"  # Ciphertext is longer than the original input.
        match = re.match(r"varchar\s*\((\d+)\)", hint, re.I)
        if match and int(match[1]) <= 2048:
            return f"varchar({match[1]})"
        # Java String has no intrinsic length limit. Do not silently truncate
        # prompts, form JSON, workflow definitions, URLs or rich text to 255.
        return "longtext"
    raise ValueError(f"Unknown persistent Java type: {typ} ({decl.name})")


def column_definition(name, typ, f, decl, hint):
    primary = annotation(f, "TableId") is not None or decl.name == "id"
    if primary:
        id_type = annotation_value(annotation(f, "TableId"), "type", "NONE")
        auto = typ in {"bigint", "int"} and id_type in {"NONE", "AUTO"}
        # A string PK requires a bounded, indexable representation.
        if typ == "longtext":
            typ = "varchar(255)"
        suffix = " NOT NULL" + (" AUTO_INCREMENT" if auto else "")
    elif name in {"create_time", "update_time"}:
        suffix = " NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"
        if name == "update_time":
            suffix += " ON UPDATE CURRENT_TIMESTAMP(6)"
    elif name in {"creator", "updater"}:
        typ, suffix = "varchar(64)", " NULL DEFAULT ''"
    elif name == "deleted":
        suffix = " NOT NULL DEFAULT b'0'"
    elif name == "tenant_id":
        suffix = " NOT NULL DEFAULT 0"
    else:
        suffix = " NULL"
        # Test DDL is used as evidence for defaults, but not as proof that a
        # business field must be NOT NULL for every application write path.
        default = re.search(r"\bDEFAULT\s+('(?:''|[^'])*'|[-+]?\d+(?:\.\d+)?|TRUE|FALSE|NULL)(?=\s|$)", hint, re.I)
        if default and typ not in {"json", "text", "longtext", "longblob"}:
            value = default[1]
            if typ == "bit(1)" and value.upper() in {"TRUE", "FALSE"}:
                value = "b'1'" if value.upper() == "TRUE" else "b'0'"
            suffix += " DEFAULT " + value
    return typ, suffix, primary


def build_model():
    base_tables = {name for name, _ in table_bodies((ROOT / "sql/mysql/ruoyi-vue-pro.sql").read_text())}
    hints, classes = read_hints(), parse_classes()
    tables, existing = [], []
    seen = set()
    for node, path in sorted(classes.values(), key=lambda pair: str(pair[1])):
        ann = annotation(node, "TableName")
        if ann is None:
            continue
        table = annotation_value(ann)
        if not isinstance(table, str) or not re.fullmatch(r"\w+", table):
            raise ValueError(f"Unsupported table name: {table}")
        if table in seen:
            raise ValueError(f"Duplicate table: {table}")
        seen.add(table)
        if table in base_tables:
            existing.append(table)
            continue
        hint = hints[table]
        cols, primary_keys = {}, []
        for name, (f, decl) in persistent_fields(node, classes).items():
            mapping = annotation(f, "TableId") or annotation(f, "TableField")
            col = annotation_value(mapping) or snake(name)
            col = col.strip('`"')
            if not re.fullmatch(r"\w+", col) or col in cols:
                raise ValueError(f"Invalid/duplicate column: {table}.{col}")
            typ = column_type(f, decl, hint.columns.get(col, ""))
            typ, suffix, primary = column_definition(col, typ, f, decl, hint.columns.get(col, ""))
            cols[col] = {"type": typ, "suffix": suffix, "comment": comment(f.documentation, name)}
            if primary:
                primary_keys.append(col)
        # Matches TenantDatabaseInterceptor.computeIgnoreTable, including BaseDO
        # entities which receive tenant_id from the interceptor during INSERT.
        tenant = node.extends.name == "TenantBaseDO" or annotation(node, "TenantIgnore") is None
        if tenant and "tenant_id" not in cols:
            cols["tenant_id"] = {"type": "bigint", "suffix": " NOT NULL DEFAULT 0", "comment": "租户编号（租户拦截器写入）"}
        for col, definition in hint.extra_columns.items():
            cols[col] = {"type": definition, "suffix": "", "comment": "来自模块 H2 脚本的生成列"}
        if len(primary_keys) != 1:
            raise ValueError(f"Expected one primary key: {table}: {primary_keys}")
        indexes = list(hint.indexes)
        indexed = {tuple(c) for _, c, _ in indexes}
        if tenant and ("tenant_id", "deleted") not in indexed:
            indexes.append(("idx_tenant_deleted", ["tenant_id", "deleted"], False))
        # Non-unique lookup indexes. Business uniqueness is only copied when
        # explicitly present in the checked-in H2 DDL, never guessed from names.
        for col, definition in cols.items():
            if col not in {"tenant_id", *primary_keys} and col.endswith("_id") and definition["type"] == "bigint":
                keys = (["tenant_id"] if tenant else []) + [col]
                if not any(tuple(c[:len(keys)]) == tuple(keys) for _, c, _ in indexes):
                    indexes.append(("idx_" + col, keys, False))
        for name, keys, _ in indexes:
            if not keys or any(k not in cols for k in keys):
                raise ValueError(f"Index references absent columns: {table}.{name}: {keys}")
            if any(cols[k]["type"] in {"longtext", "text", "json", "longblob"} for k in keys):
                raise ValueError(f"Unbounded index column needs an explicit length: {table}.{name}: {keys}")
        tables.append({"name": table, "module": path.relative_to(ROOT).parts[0].removeprefix("yudao-module-"),
                       "source": str(path.relative_to(ROOT)), "h2_source": hint.source or None,
                       "comment": comment(node.documentation, table), "columns": cols,
                       "primary_key": primary_keys[0], "indexes": indexes, "tenant": tenant})
    return sorted(tables, key=lambda t: (t["module"], t["name"])), sorted(existing)


def render(tables, existing):
    grouped = defaultdict(list)
    for table in tables:
        grouped[table["module"]].append(table)
    files = {}
    header = ("-- Generated by sql/tools/schema/generate.py; see sql/mysql/modules/README.md.\n"
              "-- Development schema reconstructed from entities; contains no seed data.\n"
              "-- Existing tables are preserved; this is NOT an ALTER/migration script.\n"
              "SET NAMES utf8mb4;\n\n")
    for module, group in grouped.items():
        parts = [header]
        for table in group:
            definitions = [f"  `{name}` {col['type']}{col['suffix']} COMMENT {quote(col['comment'])}"
                           for name, col in table["columns"].items()]
            definitions.append(f"  PRIMARY KEY (`{table['primary_key']}`)")
            for name, columns, unique in table["indexes"]:
                definitions.append(f"  {'UNIQUE KEY' if unique else 'KEY'} `{name}` (" + ", ".join(f"`{c}`" for c in columns) + ")")
            parts.append(f"-- Entity: {table['source']}\n")
            if table["h2_source"]:
                parts.append(f"-- Schema hints: {table['h2_source']}\n")
            parts.append(f"CREATE TABLE IF NOT EXISTS `{table['name']}` (\n" + ",\n".join(definitions)
                         + f"\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT={quote(table['comment'])};\n\n")
        files[f"{module}.sql"] = "".join(parts).rstrip() + "\n"
    files["all.sql"] = ("-- Run mysql from the repository root. Import the base schema first on a NEW database.\n"
                        "-- SOURCE paths are relative to the mysql client's working directory.\n"
                        + "".join(f"SOURCE sql/mysql/modules/{m}.sql;\n" for m in grouped)
                        + "SOURCE sql/mysql/third-party/jimureport-schema.sql;\n")
    manifest = {"base_schema": "sql/mysql/ruoyi-vue-pro.sql", "existing_entity_tables": existing,
                "generated_table_count": len(tables), "generated_column_count": sum(len(t["columns"]) for t in tables),
                "modules": {m: len(g) for m, g in grouped.items()}, "tables": [
                    {k: v for k, v in t.items() if k not in {"comment", "indexes", "columns"}}
                    | {"columns": list(t["columns"])} for t in tables]}
    files["manifest.json"] = json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if generated files differ from the source model")
    args = parser.parse_args()
    tables, existing = build_model()
    files = render(tables, existing)
    mismatches = []
    for path in OUTPUT.glob("*.sql"):
        if path.name not in files and path.read_text().startswith("-- Generated by sql/tools/schema/generate.py;"):
            if args.check:
                mismatches.append(path.name + " (obsolete)")
            else:
                path.unlink()
    for name, content in files.items():
        path = OUTPUT / name
        if args.check:
            if not path.exists() or path.read_text() != content:
                mismatches.append(name)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
    if mismatches:
        print("Outdated generated files: " + ", ".join(mismatches), file=sys.stderr)
        return 1
    print(f"{'Checked' if args.check else 'Generated'} {len(tables)} missing tables in {len(files) - 2} modules; {len(existing)} existing entity tables preserved.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
