#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out_sin_sync
javac -d out_sin_sync src/ColaImpresionSinSync.java
exec java -cp out_sin_sync ColaImpresionSinSync server "${1:-3}" "${2:-2}" "${3:-4}"
