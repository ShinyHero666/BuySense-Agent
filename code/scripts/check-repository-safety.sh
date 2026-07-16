#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
CODE_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
REPO_ROOT="$(cd -- "$CODE_ROOT/.." && pwd -P)"
DOCKERIGNORE="$CODE_ROOT/.dockerignore"

failures=()

require_dockerignore_pattern() {
  local description="$1"
  shift
  local pattern
  for pattern in "$@"; do
    if grep -Fqx -- "$pattern" "$DOCKERIGNORE"; then
      return 0
    fi
  done
  failures+=("$DOCKERIGNORE does not exclude $description")
}

if [[ ! -f "$DOCKERIGNORE" ]]; then
  echo "Missing Docker build-context policy: $DOCKERIGNORE" >&2
  exit 1
fi

# A broad COPY/ADD in any image makes an accidentally-created local .env part of a
# build layer. Keep this check dependency-free so it runs before package setup.
mapfile -d '' DOCKERFILES < <(
  find "$CODE_ROOT" -type f \( -name 'Dockerfile' -o -name 'Dockerfile.*' \) \
    -not -path '*/node_modules/*' -print0 | sort -z
)
for dockerfile in "${DOCKERFILES[@]}"; do
  while IFS= read -r instruction; do
    # A source of "." from a named build stage is not the host build context.
    read -r -a instruction_tokens <<<"$instruction"
    from_named_stage=false
    for ((index = 1; index < ${#instruction_tokens[@]}; index += 1)); do
      option="${instruction_tokens[index]}"
      [[ "$option" == --* ]] || break
      if [[ "$option" == --from=* ]]; then
        from_named_stage=true
        break
      fi
    done
    if [[ "$from_named_stage" == false ]]; then
      failures+=("${dockerfile#$REPO_ROOT/} uses a broad COPY/ADD instead of an explicit file list")
      break
    fi
  done < <(
    grep -Ei \
      -e '^[[:space:]]*(COPY|ADD)[[:space:]]+(--[^[:space:]]+[[:space:]]+)*\.(/\.)*/?[[:space:]]+' \
      -e '^[[:space:]]*(COPY|ADD)[[:space:]]+(--[^[:space:]]+[[:space:]]+)*\[[[:space:]]*"\.(/\.)*/?"[[:space:]]*,' \
      "$dockerfile" || true
  )
done

require_dockerignore_pattern "local .env files" '.env' '**/.env'
require_dockerignore_pattern "local .env variants" '.env.*' '**/.env.*'
require_dockerignore_pattern "Git metadata" '.git/' '.git' '**/.git/'
require_dockerignore_pattern "Python virtual environments" '.venv/' '.venv' '**/.venv/'
require_dockerignore_pattern "Node dependency trees" 'node_modules/' 'node_modules' '**/node_modules/'
require_dockerignore_pattern "PEM key material" '*.pem' '**/*.pem'
require_dockerignore_pattern "private-key files" '*.key' '**/*.key'
require_dockerignore_pattern "PKCS#12 key stores" '*.p12' '**/*.p12'
require_dockerignore_pattern "PFX key stores" '*.pfx' '**/*.pfx'
require_dockerignore_pattern "SSH private keys" 'id_rsa' '**/id_rsa'
require_dockerignore_pattern "Ed25519 private keys" 'id_ed25519' '**/id_ed25519'
require_dockerignore_pattern "credential exports" 'credentials.json' '**/credentials.json'

# Examples are intentionally committed documentation, but real environment and
# private-key filenames must never be tracked even when Docker ignores them.
mapfile -d '' TRACKED_FILES < <(git -C "$REPO_ROOT" ls-files -z)
for path in "${TRACKED_FILES[@]}"; do
  basename="${path##*/}"
  case "$basename" in
    .env.example|.env.*.example)
      ;;
    .env|.env.*|*.pem|*.key|*.p12|*.pfx|id_rsa|id_dsa|id_ecdsa|id_ed25519|credentials.json)
      failures+=("tracked secret-like filename: $path")
      ;;
  esac
done

# Only high-confidence credential formats are inspected. Report paths, never
# matching lines, so a CI log cannot echo the credential it is protecting.
credential_pattern='-----BEGIN ([A-Z0-9]+ )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{36,}|sk-(proj-)?[A-Za-z0-9_-]{32,}'
credential_status=0
if git -C "$REPO_ROOT" grep -Iq -E -e "$credential_pattern" -- . \
  ':!code/scripts/check-repository-safety.sh'; then
  mapfile -d '' CREDENTIAL_FILES < <(
    git -C "$REPO_ROOT" grep -Ilz -E -e "$credential_pattern" -- . \
      ':!code/scripts/check-repository-safety.sh'
  )
else
  credential_status="$?"
  CREDENTIAL_FILES=()
  if (( credential_status != 1 )); then
    failures+=("unable to scan tracked files for credential patterns")
  fi
fi
for path in "${CREDENTIAL_FILES[@]}"; do
  failures+=("tracked file contains a high-confidence credential pattern: $path")
done

if (( ${#failures[@]} > 0 )); then
  printf 'Repository safety check failed:\n' >&2
  printf '  - %s\n' "${failures[@]}" >&2
  exit 1
fi

echo "Repository safety check passed: selective Docker COPY, protected build context, no tracked secrets."
