from __future__ import annotations

import json
import re
from pathlib import Path
from urllib.parse import unquote


CODE_ROOT = Path(__file__).resolve().parents[1]
COURSE_ROOT = CODE_ROOT.parent
LINK_PATTERN = re.compile(r"!?\[[^\]]*]\(([^)]+)\)")
FORBIDDEN_DOC_PATTERNS = {
    ".cmd": "Windows command wrapper",
    ".ps1": "PowerShell script",
    "PowerShell": "PowerShell instruction",
    r".venv\Scripts": "Windows virtual-environment path",
    "search_recommendation_course_v2": "old project directory",
    "moyuan-search-recommendation-course": "old project directory",
    "zhuanzhuan": "old brand slug",
    "转转": "old brand name",
    "official_verified": "old service mode",
}


def markdown_files() -> list[Path]:
    ignored_directories = {".venv", ".git", "node_modules", "dist"}
    return sorted(
        path
        for path in COURSE_ROOT.rglob("*.md")
        if ignored_directories.isdisjoint(path.parts)
    )


def check_markdown(path: Path) -> list[str]:
    errors: list[str] = []
    text = path.read_text(encoding="utf-8-sig")
    if sum(line.startswith("```") for line in text.splitlines()) % 2:
        errors.append(f"{path}: unclosed code fence")

    for pattern, description in FORBIDDEN_DOC_PATTERNS.items():
        if pattern in text:
            errors.append(f"{path}: contains {description}: {pattern}")

    for match in LINK_PATTERN.finditer(text):
        raw_target = match.group(1).strip()
        if raw_target.startswith(("http://", "https://", "mailto:", "#")):
            continue
        if raw_target.startswith("<") and raw_target.endswith(">"):
            raw_target = raw_target[1:-1]
        target = unquote(raw_target.split("#", 1)[0])
        if target and not (path.parent / target).exists():
            errors.append(f"{path}: missing local link target: {target}")
    return errors


def check_json() -> list[str]:
    errors: list[str] = []
    paths = [CODE_ROOT / "config" / "experiments.json"]
    paths.extend(sorted((CODE_ROOT / "scenarios").glob("*.json")))
    for path in paths:
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            errors.append(f"{path}: invalid JSON: {error}")
    return errors


def check_agent_assets() -> list[str]:
    errors: list[str] = []
    catalog = CODE_ROOT / "src" / "shoprec" / "data" / "iphone_catalog.jsonl"
    eval_set = CODE_ROOT / "agent_eval" / "agent_eval_v1.jsonl"
    for path, expected in ((catalog, 200), (eval_set, 100)):
        try:
            rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]
        except (OSError, json.JSONDecodeError) as error:
            errors.append(f"{path}: invalid JSONL: {error}")
            continue
        if len(rows) != expected:
            errors.append(f"{path}: expected {expected} rows, got {len(rows)}")
    return errors


def main() -> None:
    errors = check_json() + check_agent_assets()
    docs = markdown_files()
    for path in docs:
        errors.extend(check_markdown(path))
    if errors:
        raise SystemExit("\n".join(errors))
    print(
        f"Course checks passed: {len(docs)} Markdown files, "
        f"{len(list((CODE_ROOT / 'scenarios').glob('*.json')))} scenarios."
    )


if __name__ == "__main__":
    main()
