#!/usr/bin/env python3
"""Generate dependency-free TypeScript and Python contract types from JSON Schema."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
SCHEMA_PATH = Path(__file__).with_name("commerce-agent-v2.schema.json")
TS_PATH = ROOT / "agent-control-plane" / "src" / "generated" / "contracts-v2.ts"
FRONTEND_TS_PATH = (
    ROOT / "apps" / "commerce-console" / "src" / "generated" / "contracts-v2.ts"
)
PY_PATH = ROOT / "src" / "shoprec" / "generated_contracts_v2.py"

TS_IDENTIFIER = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")
SUPPORTED_SCHEMA_KEYS = {
    "$defs",
    "$id",
    "$ref",
    "$schema",
    "additionalProperties",
    "anyOf",
    "const",
    "default",
    "description",
    "enum",
    "format",
    "items",
    "maximum",
    "maxItems",
    "maxLength",
    "minimum",
    "minItems",
    "minLength",
    "pattern",
    "properties",
    "required",
    "title",
    "type",
}


def assert_supported_schema(schema: dict[str, Any], path: str = "$") -> None:
    unsupported = sorted(set(schema) - SUPPORTED_SCHEMA_KEYS)
    if unsupported:
        raise ValueError(f"unsupported JSON Schema keywords at {path}: {', '.join(unsupported)}")
    schema_format = schema.get("format")
    if schema_format is not None and schema_format != "date-time":
        raise ValueError(f"unsupported JSON Schema format at {path}: {schema_format}")
    pattern = schema.get("pattern")
    if pattern is not None:
        if not isinstance(pattern, str):
            raise ValueError(f"JSON Schema pattern must be a string at {path}")
        try:
            re.compile(pattern)
        except re.error as error:
            raise ValueError(f"invalid JSON Schema pattern at {path}: {error}") from error
    annotation_keys = {"default", "description", "title"}
    if "$ref" in schema and set(schema) - annotation_keys - {"$ref"}:
        raise ValueError(f"$ref siblings are not supported at {path}")
    if "anyOf" in schema and set(schema) - annotation_keys - {"anyOf"}:
        raise ValueError(f"anyOf siblings are not supported at {path}")
    for container_name in ("$defs", "properties"):
        container = schema.get(container_name)
        if isinstance(container, dict):
            for name, child in container.items():
                if isinstance(child, dict):
                    assert_supported_schema(child, f"{path}.{container_name}.{name}")
    items = schema.get("items")
    if isinstance(items, dict):
        assert_supported_schema(items, f"{path}.items")
    additional = schema.get("additionalProperties")
    if isinstance(additional, dict):
        assert_supported_schema(additional, f"{path}.additionalProperties")
    any_of = schema.get("anyOf")
    if isinstance(any_of, list):
        for index, child in enumerate(any_of):
            if isinstance(child, dict):
                assert_supported_schema(child, f"{path}.anyOf[{index}]")


def ref_name(value: str) -> str:
    return value.rsplit("/", 1)[-1]


def type_values(schema: dict[str, Any]) -> list[str]:
    value = schema.get("type")
    if isinstance(value, str):
        return [value]
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return value
    return []


def ts_property_name(value: str) -> str:
    return value if TS_IDENTIFIER.fullmatch(value) else json.dumps(value, ensure_ascii=False)


def ts_object_type(schema: dict[str, Any]) -> str:
    properties = schema.get("properties")
    if isinstance(properties, dict):
        required = set(schema.get("required", []))
        fields = []
        for field, field_schema in properties.items():
            optional = "" if field in required else "?"
            fields.append(
                f"{ts_property_name(field)}{optional}: {ts_type(field_schema)};"
            )
        return "{ " + " ".join(fields) + " }"
    additional = schema.get("additionalProperties")
    if isinstance(additional, dict):
        return f"Record<string, {ts_type(additional)}>"
    return "Record<string, unknown>"


def ts_type(schema: dict[str, Any]) -> str:
    if "$ref" in schema:
        return ref_name(schema["$ref"])
    if "const" in schema:
        return json.dumps(schema["const"], ensure_ascii=False)
    if "enum" in schema:
        return " | ".join(json.dumps(item, ensure_ascii=False) for item in schema["enum"])
    if "anyOf" in schema:
        return " | ".join(ts_type(item) for item in schema["anyOf"])
    values = type_values(schema)
    mapped: list[str] = []
    for value in values:
        if value == "string":
            mapped.append("string")
        elif value in {"number", "integer"}:
            mapped.append("number")
        elif value == "boolean":
            mapped.append("boolean")
        elif value == "null":
            mapped.append("null")
        elif value == "array":
            mapped.append(f"Array<{ts_type(schema.get('items', {}))}>")
        elif value == "object":
            mapped.append(ts_object_type(schema))
        else:
            mapped.append("unknown")
    return " | ".join(mapped) if mapped else "unknown"


def py_type(schema: dict[str, Any]) -> str:
    if "$ref" in schema:
        return ref_name(schema["$ref"])
    if "const" in schema:
        return f"Literal[{schema['const']!r}]"
    if "enum" in schema:
        return "Literal[" + ", ".join(repr(item) for item in schema["enum"]) + "]"
    if "anyOf" in schema:
        return " | ".join(py_type(item) for item in schema["anyOf"])
    values = type_values(schema)
    mapped: list[str] = []
    for value in values:
        if value == "string":
            mapped.append("str")
        elif value == "number":
            mapped.append("float")
        elif value == "integer":
            mapped.append("int")
        elif value == "boolean":
            mapped.append("bool")
        elif value == "null":
            mapped.append("None")
        elif value == "array":
            mapped.append(f"list[{py_type(schema.get('items', {}))}]")
        elif value == "object":
            mapped.append("dict[str, Any]")
        else:
            mapped.append("Any")
    return " | ".join(mapped) if mapped else "Any"


def generate_ts(definitions: dict[str, dict[str, Any]]) -> str:
    lines = ["// Generated by packages/contracts/generate_contracts.py. Do not edit.", ""]
    for name, schema in definitions.items():
        if schema.get("type") == "object" and "properties" in schema:
            lines.append(f"export interface {name} {{")
            required = set(schema.get("required", []))
            for field, field_schema in schema["properties"].items():
                optional = "" if field in required else "?"
                lines.append(f"  {field}{optional}: {ts_type(field_schema)};")
            lines.extend(["}", ""])
        else:
            lines.extend([f"export type {name} = {ts_type(schema)};", ""])
    lines.extend(
        [
            "export interface ContractValidationIssue {",
            "  readonly path: string;",
            "  readonly message: string;",
            "}",
            "",
            "export interface ContractTypes {",
        ]
    )
    for name in definitions:
        lines.append(f"  {name}: {name};")
    serialized_definitions = json.dumps(
        definitions,
        ensure_ascii=False,
        indent=2,
        separators=(",", ": "),
    )
    lines.extend(
        [
            "}",
            "",
            "export type ContractName = keyof ContractTypes;",
            "",
            "type ContractSchema = {",
            "  readonly $ref?: string;",
            "  readonly anyOf?: readonly ContractSchema[];",
            "  readonly const?: unknown;",
            "  readonly enum?: readonly unknown[];",
            "  readonly type?: string | readonly string[];",
            "  readonly required?: readonly string[];",
            "  readonly properties?: Readonly<Record<string, ContractSchema>>;",
            "  readonly additionalProperties?: boolean | ContractSchema;",
            "  readonly items?: ContractSchema;",
            "  readonly minimum?: number;",
            "  readonly maximum?: number;",
            "  readonly minLength?: number;",
            "  readonly maxLength?: number;",
            "  readonly pattern?: string;",
            "  readonly minItems?: number;",
            "  readonly maxItems?: number;",
            "  readonly format?: string;",
            "};",
            "",
            "const contractDefinitions = "
            + serialized_definitions
            + " as unknown as Readonly<Record<ContractName, ContractSchema>>;",
            "",
            "function valueType(value: unknown): string {",
            '  if (value === null) return "null";',
            '  if (Array.isArray(value)) return "array";',
            '  if (typeof value === "number" && Number.isInteger(value)) return "integer";',
            "  return typeof value;",
            "}",
            "",
            "function acceptsType(expected: string, value: unknown): boolean {",
            '  if (expected === "null") return value === null;',
            '  if (expected === "array") return Array.isArray(value);',
            '  if (expected === "object") {',
            '    return typeof value === "object" && value !== null && !Array.isArray(value);',
            "  }",
            '  if (expected === "integer") return typeof value === "number" && Number.isInteger(value);',
            '  if (expected === "number") return typeof value === "number" && Number.isFinite(value);',
            "  return typeof value === expected;",
            "}",
            "",
            "function isValidDateTime(value: string): boolean {",
            "  const match = /^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d+)?(?:Z|[+-](\\d{2}):(\\d{2}))$/.exec(value);",
            "  if (match === null) return false;",
            "  const year = Number(match[1]);",
            "  const month = Number(match[2]);",
            "  const day = Number(match[3]);",
            "  const hour = Number(match[4]);",
            "  const minute = Number(match[5]);",
            "  const second = Number(match[6]);",
            "  const offsetHour = Number(match[7] ?? 0);",
            "  const offsetMinute = Number(match[8] ?? 0);",
            "  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);",
            "  const daysByMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];",
            "  const maxDay = daysByMonth[month - 1] ?? 0;",
            "  return month >= 1 && month <= 12 && day >= 1 && day <= maxDay &&",
            "    hour <= 23 && minute <= 59 && second <= 60 &&",
            "    offsetHour <= 23 && offsetMinute <= 59;",
            "}",
            "",
            "function childPath(path: string, field: string): string {",
            '  return /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(field)',
            "    ? `${path}.${field}`",
            "    : `${path}[${JSON.stringify(field)}]`;",
            "}",
            "",
            "function validateSchema(",
            "  schema: ContractSchema,",
            "  value: unknown,",
            "  path: string,",
            "  issues: ContractValidationIssue[],",
            "): void {",
            "  if (schema.$ref !== undefined) {",
            '    const name = schema.$ref.startsWith("#/$defs/")',
            '      ? schema.$ref.slice("#/$defs/".length) as ContractName',
            "      : undefined;",
            "    const referenced = name === undefined ? undefined : contractDefinitions[name];",
            "    if (referenced === undefined) {",
            '      issues.push({ path, message: `references unknown schema ${schema.$ref}` });',
            "      return;",
            "    }",
            "    validateSchema(referenced, value, path, issues);",
            "    return;",
            "  }",
            "  if (schema.anyOf !== undefined) {",
            "    const alternatives = schema.anyOf.map((candidate) => {",
            "      const candidateIssues: ContractValidationIssue[] = [];",
            "      validateSchema(candidate, value, path, candidateIssues);",
            "      return candidateIssues;",
            "    });",
            "    if (alternatives.some((candidateIssues) => candidateIssues.length === 0)) return;",
            "    const best = alternatives.reduce((left, right) =>",
            "      right.length < left.length ? right : left,",
            "    );",
            "    issues.push(...best);",
            "    return;",
            "  }",
            "  if (schema.const !== undefined && !Object.is(value, schema.const)) {",
            '    issues.push({ path, message: `must equal ${JSON.stringify(schema.const)}` });',
            "    return;",
            "  }",
            "  if (schema.enum !== undefined && !schema.enum.some((item) => Object.is(item, value))) {",
            '    issues.push({ path, message: `must be one of ${schema.enum.map(String).join(", ")}` });',
            "    return;",
            "  }",
            "  const expected = schema.type === undefined",
            "    ? []",
            "    : typeof schema.type === \"string\" ? [schema.type] : schema.type;",
            "  if (expected.length > 0 && !expected.some((type) => acceptsType(type, value))) {",
            '    issues.push({ path, message: `must be ${expected.join(" or ")}; got ${valueType(value)}` });',
            "    return;",
            "  }",
            "  if (value === null) return;",
            '  if (typeof value === "string") {',
            "    const characterLength = Array.from(value).length;",
            "    if (schema.minLength !== undefined && characterLength < schema.minLength) {",
            '      issues.push({ path, message: `must contain at least ${schema.minLength} characters` });',
            "    }",
            "    if (schema.maxLength !== undefined && characterLength > schema.maxLength) {",
            '      issues.push({ path, message: `must contain at most ${schema.maxLength} characters` });',
            "    }",
            "    if (schema.pattern !== undefined && !new RegExp(schema.pattern, \"u\").test(value)) {",
            '      issues.push({ path, message: `must match ${schema.pattern}` });',
            "    }",
            '    if (schema.format === "date-time" && !isValidDateTime(value)) {',
            '      issues.push({ path, message: "must be a valid date-time" });',
            "    }",
            "  }",
            '  if (typeof value === "number") {',
            "    if (!Number.isFinite(value)) {",
            '      issues.push({ path, message: "must be finite" });',
            "      return;",
            "    }",
            "    if (schema.minimum !== undefined && value < schema.minimum) {",
            '      issues.push({ path, message: `must be at least ${schema.minimum}` });',
            "    }",
            "    if (schema.maximum !== undefined && value > schema.maximum) {",
            '      issues.push({ path, message: `must be at most ${schema.maximum}` });',
            "    }",
            "  }",
            "  if (Array.isArray(value)) {",
            "    if (schema.minItems !== undefined && value.length < schema.minItems) {",
            '      issues.push({ path, message: `must contain at least ${schema.minItems} items` });',
            "    }",
            "    if (schema.maxItems !== undefined && value.length > schema.maxItems) {",
            '      issues.push({ path, message: `must contain at most ${schema.maxItems} items` });',
            "    }",
            "    if (schema.items !== undefined) {",
            "      value.forEach((item, index) => validateSchema(schema.items!, item, `${path}[${index}]`, issues));",
            "    }",
            "  }",
            '  if (typeof value === "object" && !Array.isArray(value)) {',
            "    const record = value as Record<string, unknown>;",
            "    for (const field of schema.required ?? []) {",
            "      if (!Object.prototype.hasOwnProperty.call(record, field)) {",
            '        issues.push({ path: childPath(path, field), message: "is required" });',
            "      }",
            "    }",
            "    for (const [field, fieldValue] of Object.entries(record)) {",
            "      const propertySchema = schema.properties?.[field];",
            "      if (propertySchema !== undefined) {",
            "        validateSchema(propertySchema, fieldValue, childPath(path, field), issues);",
            "      } else if (schema.additionalProperties === false) {",
            '        issues.push({ path: childPath(path, field), message: "is not allowed" });',
            '      } else if (typeof schema.additionalProperties === "object") {',
            "        validateSchema(schema.additionalProperties, fieldValue, childPath(path, field), issues);",
            "      }",
            "    }",
            "  }",
            "}",
            "",
            "export function validateContract<K extends ContractName>(",
            "  name: K,",
            "  value: unknown,",
            "): ContractValidationIssue[] {",
            "  const issues: ContractValidationIssue[] = [];",
            '  validateSchema(contractDefinitions[name], value, "$", issues);',
            "  return issues;",
            "}",
            "",
            "export function isContract<K extends ContractName>(",
            "  name: K,",
            "  value: unknown,",
            "): value is ContractTypes[K] {",
            "  return validateContract(name, value).length === 0;",
            "}",
            "",
            "export function assertContract<K extends ContractName>(",
            "  name: K,",
            "  value: unknown,",
            "): asserts value is ContractTypes[K] {",
            "  const issues = validateContract(name, value);",
            "  if (issues.length === 0) return;",
            '  const detail = issues.map((issue) => `${issue.path} ${issue.message}`).join("; ");',
            '  throw new Error(`Invalid ${name}: ${detail}`);',
            "}",
            "",
        ]
    )
    return "\n".join(lines).rstrip() + "\n"


def generate_py(definitions: dict[str, dict[str, Any]]) -> str:
    lines = [
        '"""Generated by packages/contracts/generate_contracts.py. Do not edit."""',
        "",
        "from __future__ import annotations",
        "",
        "from typing import Any, Literal, TypedDict",
        "",
    ]
    aliases: list[tuple[str, dict[str, Any]]] = []
    objects: list[tuple[str, dict[str, Any]]] = []
    for name, schema in definitions.items():
        (objects if schema.get("type") == "object" and "properties" in schema else aliases).append((name, schema))
    for name, schema in aliases:
        lines.extend([f"{name} = {py_type(schema)}", ""])
    for name, schema in objects:
        required = set(schema.get("required", []))
        optional_fields = [
            (field, field_schema)
            for field, field_schema in schema["properties"].items()
            if field not in required
        ]
        required_fields = [
            (field, field_schema)
            for field, field_schema in schema["properties"].items()
            if field in required
        ]
        if optional_fields and required_fields:
            lines.append(f"class _{name}Optional(TypedDict, total=False):")
            for field, field_schema in optional_fields:
                lines.append(f"    {field}: {py_type(field_schema)}")
            lines.append("")
            lines.append(f"class {name}(_{name}Optional):")
            for field, field_schema in required_fields:
                lines.append(f"    {field}: {py_type(field_schema)}")
        else:
            total = "" if required_fields else ", total=False"
            lines.append(f"class {name}(TypedDict{total}):")
            for field, field_schema in required_fields or optional_fields:
                lines.append(f"    {field}: {py_type(field_schema)}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def write_or_check(path: Path, content: str, check: bool) -> bool:
    current = path.read_text(encoding="utf-8") if path.exists() else None
    if check:
        return current == content
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    assert_supported_schema(schema)
    definitions = schema["$defs"]
    ts_output = generate_ts(definitions)
    outputs = [
        (TS_PATH, ts_output),
        (FRONTEND_TS_PATH, ts_output),
        (PY_PATH, generate_py(definitions)),
    ]
    failures = [str(path) for path, content in outputs if not write_or_check(path, content, args.check)]
    if failures:
        print("Generated contracts are stale: " + ", ".join(failures))
        return 1
    for path, _ in outputs:
        print(path.relative_to(ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
