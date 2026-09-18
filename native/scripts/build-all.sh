#!/usr/bin/env bash
# One-shot driver: fetch -> deps -> core -> package.
#
#   ./build-all.sh                       # arm64-v8a + x86_64
#   ABIS=arm64-v8a ./build-all.sh        # phone only, roughly half the time
#   BITCOIN_SRC=/path/to/bitcoin-30.3 ./build-all.sh
#
# Every step is idempotent and resumable; re-running skips completed work.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
start=$(date +%s)
"${here}/00-fetch-deps.sh"
"${here}/10-build-deps.sh"
"${here}/20-build-core.sh"
"${here}/30-package-jnilibs.sh"
end=$(date +%s)
printf '\033[38;5;51m[bcn]\033[0m native build complete in %d min %d s\n' \
  $(( (end-start)/60 )) $(( (end-start)%60 ))
