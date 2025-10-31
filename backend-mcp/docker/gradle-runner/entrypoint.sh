#!/usr/bin/env bash
set -euo pipefail

if [[ ! -d /workspace ]]; then
  echo "Workspace mount /workspace not found" >&2
  exit 90
fi

if [[ -d /workspace/.gradle ]]; then
  chmod -R a+rwX /workspace/.gradle || true
fi

exec "$@"
