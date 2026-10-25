#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

JNA_JAR="/usr/share/java/jna.jar"
if [[ ! -f "$JNA_JAR" ]]; then
  echo "No se encontró $JNA_JAR."
  echo "Instala JNA con: sudo apt update && sudo apt install -y libjna-java"
  exit 1
fi

mkdir -p out
javac -cp "$JNA_JAR" -d out src/ColaImpresion.java
exec java -cp "out:$JNA_JAR" ColaImpresion server "${1:-3}" "${2:-2}" "${3:-4}"
