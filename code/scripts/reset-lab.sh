#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
BACKUP_ROOT="$CODE_ROOT/.lab-backup"

if [[ ! -d "$BACKUP_ROOT/src" || ! -d "$BACKUP_ROOT/tests" ]]; then
  echo "No complete .lab-backup directory exists. Start a lab first." >&2
  exit 1
fi
if [[ "$CODE_ROOT" == "/" || "$BACKUP_ROOT" != "$CODE_ROOT/.lab-backup" ]]; then
  echo "Refusing to restore an unexpected path: $BACKUP_ROOT" >&2
  exit 1
fi

RESTORE_ROOT="$(mktemp -d "$CODE_ROOT/.lab-restore.tmp.XXXXXX")"
cleanup() {
  if [[ -d "$RESTORE_ROOT" ]]; then
    rm -rf -- "$RESTORE_ROOT"
  fi
}
trap cleanup EXIT

cp -a -- "$BACKUP_ROOT/src" "$RESTORE_ROOT/src"
cp -a -- "$BACKUP_ROOT/tests" "$RESTORE_ROOT/tests"
rm -rf -- "$CODE_ROOT/src" "$CODE_ROOT/tests"
mv -- "$RESTORE_ROOT/src" "$CODE_ROOT/src"
mv -- "$RESTORE_ROOT/tests" "$CODE_ROOT/tests"
rm -rf -- "$BACKUP_ROOT"
rm -rf -- "$RESTORE_ROOT"
trap - EXIT

echo "Baseline restored and .lab-backup removed."
