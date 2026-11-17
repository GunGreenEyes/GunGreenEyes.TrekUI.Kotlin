#!/usr/bin/env bash
# Bash entry point for the capture runner: forwards to run-matrix.ps1 (PowerShell 7 `pwsh` on
# Linux/macOS CI, Windows PowerShell otherwise). All options are passed through, e.g.
#   scripts/capture/run-matrix.sh -Only 'api36' -Parallel 2
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
if command -v pwsh >/dev/null 2>&1; then ps=pwsh; else ps=powershell.exe; fi
exec "$ps" -NoProfile -ExecutionPolicy Bypass -File "$here/run-matrix.ps1" "$@"
