#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
JNA_JAR="${JNA_JAR:-/usr/share/java/jna.jar}"

if [[ "${EUID}" -eq 0 ]]; then
  echo "Ejecute esta prueba con un usuario normal, no con root."
  exit 1
fi
if [[ ! -f "$JNA_JAR" ]]; then
  echo "No se encontró JNA: $JNA_JAR"
  echo "Instale con: sudo apt update && sudo apt install -y libjna-java"
  exit 1
fi

mkdir -p out
javac -cp "$JNA_JAR" -d out src/ColaImpresion.java src/SecurityPolicyTest.java
java --enable-native-access=ALL-UNNAMED -cp "out:$JNA_JAR" SecurityPolicyTest
