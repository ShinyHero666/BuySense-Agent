#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
BACKUP_ROOT="$CODE_ROOT/.lab-backup"
LAB="${1:-}"

if [[ ! "$LAB" =~ ^([1-9]|1[0-8])$ ]]; then
  echo "usage: bash scripts/start-lab.sh <1-18>" >&2
  exit 2
fi
if [[ -e "$BACKUP_ROOT" ]]; then
  echo "A lab backup already exists. Run bash scripts/reset-lab.sh first." >&2
  exit 1
fi

TEMP_ROOT="$(mktemp -d "$CODE_ROOT/.lab-backup.tmp.XXXXXX")"
cleanup() {
  if [[ -d "$TEMP_ROOT" ]]; then
    rm -rf -- "$TEMP_ROOT"
  fi
}
trap cleanup EXIT

cp -a -- "$CODE_ROOT/src" "$TEMP_ROOT/src"
cp -a -- "$CODE_ROOT/tests" "$TEMP_ROOT/tests"
printf '%s\n' "$LAB" > "$TEMP_ROOT/lab.txt"
mv -- "$TEMP_ROOT" "$BACKUP_ROOT"
trap - EXIT

echo "Lab $LAB started. Source and tests were backed up to .lab-backup."
echo "Run bash scripts/check-lab.sh $LAB to check progress."
echo "Run bash scripts/reset-lab.sh to restore the baseline."
