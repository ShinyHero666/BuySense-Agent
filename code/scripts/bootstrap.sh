#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
VENV_ROOT="${SHOPREC_VENV:-$CODE_ROOT/.venv}"
SKIP_VERIFY=false

if [[ "${1:-}" == "--skip-verify" ]]; then
  SKIP_VERIFY=true
  shift
fi
if (( $# > 0 )); then
  echo "usage: bash scripts/bootstrap.sh [--skip-verify]" >&2
  exit 2
fi

PYTHON_BIN="${PYTHON_BIN:-}"
if [[ -z "$PYTHON_BIN" ]]; then
  if command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN="$(command -v python3)"
  elif command -v python >/dev/null 2>&1; then
    PYTHON_BIN="$(command -v python)"
  else
    echo "Python 3.10 or newer is required." >&2
    exit 1
  fi
fi

"$PYTHON_BIN" -c \
  'import sys; sys.exit(0 if sys.version_info >= (3, 10) else "Python 3.10 or newer is required")'

if [[ -d "$VENV_ROOT" && ! -x "$VENV_ROOT/bin/python" ]]; then
  cat >&2 <<EOF
$VENV_ROOT exists but is not a Linux virtual environment.
Remove or rename it, or set SHOPREC_VENV to another absolute path.
EOF
  exit 1
fi

if [[ ! -x "$VENV_ROOT/bin/python" ]]; then
  echo "[setup] Creating Linux virtual environment at $VENV_ROOT"
  if ! "$PYTHON_BIN" -m venv "$VENV_ROOT"; then
    if [[ -x "$VENV_ROOT/bin/python" ]]; then
      echo "[setup] ensurepip is unavailable; continuing with the dependency-free Python environment"
    else
      echo "Could not create a usable Linux Python environment." >&2
      exit 1
    fi
  fi
fi

echo "[setup] Linking local source without downloading dependencies"
SITE_PACKAGES="$(
  "$VENV_ROOT/bin/python" -c \
    'import sysconfig; print(sysconfig.get_paths()["purelib"])'
)"
printf '%s\n' "$CODE_ROOT/src" > "$SITE_PACKAGES/shoprec_local.pth"

cat > "$VENV_ROOT/bin/shoprec" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
BIN_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
exec "$BIN_DIR/python" -m shoprec.cli "$@"
EOF

cat > "$VENV_ROOT/bin/shoprec-server" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
BIN_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
exec "$BIN_DIR/python" -m shoprec.server "$@"
EOF

cp "$VENV_ROOT/bin/shoprec" "$VENV_ROOT/bin/moyuan"
cp "$VENV_ROOT/bin/shoprec-server" "$VENV_ROOT/bin/moyuan-server"
chmod +x \
  "$VENV_ROOT/bin/shoprec" \
  "$VENV_ROOT/bin/shoprec-server" \
  "$VENV_ROOT/bin/moyuan" \
  "$VENV_ROOT/bin/moyuan-server"

if [[ "$SKIP_VERIFY" == false ]]; then
  SHOPREC_VENV="$VENV_ROOT" bash "$SCRIPT_DIR/verify.sh"
fi

echo
echo "Ready. Try:"
echo "  $VENV_ROOT/bin/moyuan search iphone --view summary"
echo "  $VENV_ROOT/bin/moyuan compare-flowpool"
