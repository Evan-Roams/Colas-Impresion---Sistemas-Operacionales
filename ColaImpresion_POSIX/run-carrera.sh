#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/ComparacionCarrera.java
MODE="${1:-nosync}"
if [[ "$MODE" != "sync" && "$MODE" != "nosync" ]]; then
  echo "Uso: ./run-carrera.sh sync|nosync"
  exit 1
fi
exec java -cp out ComparacionCarrera "$MODE"
