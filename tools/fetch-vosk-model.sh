#!/usr/bin/env bash
# 0.1.23: downloads the offline wake-word model into app assets (not committed – 68 MB). Run once before building.
set -euo pipefail
cd "$(dirname "$0")/.."
D=app/src/main/assets/model-en-us
[ -f "$D/uuid" ] && { echo "model present"; exit 0; }
T=$(mktemp -d); curl -sSL -o "$T/m.zip" https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
unzip -q "$T/m.zip" -d "$T"; mkdir -p "$D"; cp -r "$T"/vosk-model-small-en-us-0.15/{am,conf,graph,ivector} "$D"/
echo "vosk-model-small-en-us-0.15" > "$D/uuid"; rm -rf "$T"; echo "model ready in $D"
