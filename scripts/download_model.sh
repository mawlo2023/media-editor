#!/usr/bin/env bash
# Lädt das LaMa-Inpainting-Modell (Apache-2.0) für den Objekt-Radierer.
set -euo pipefail
DEST="$(dirname "$0")/../app/src/main/assets/lama_fp32.onnx"
if [ -f "$DEST" ]; then echo "Modell bereits vorhanden."; exit 0; fi
mkdir -p "$(dirname "$DEST")"
curl -fL --retry 3 -o "$DEST" https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx
echo "Modell gespeichert: $DEST"
