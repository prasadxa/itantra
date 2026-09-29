#!/usr/bin/env bash
# Builds website/.r2-staging/ with the exact key layout uploaded to the R2 bucket:
#   apk/       the installable debug APK
#   media/     screenshots (full + thumbnails), diagrams, explainer video, phone clips, posters
#   models/v1/ the on-device model pack, big files split under wrangler's 300 MB limit, + manifest.json
# Run from anywhere; needs sips (macOS), ffmpeg, shasum, python3.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/website/.r2-staging"
SHOW="$ROOT/docs/showcase"
APK_SRC="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
APK_NAME="iTantra-0.1.0-debug.apk"
PART_SIZE=200m

rm -rf "$OUT"
mkdir -p "$OUT"/{apk,media/screens,media/thumbs,media/diagrams,media/video,media/clips,models/v1}

# --- APK -------------------------------------------------------------------
cp "$APK_SRC" "$OUT/apk/$APK_NAME"

# --- Screens: full size + 540 px wide thumbnails ---------------------------
SCREENS=(
  "$SHOW/images/01_alert_card.png"
  "$SHOW/images/03_sos_sheet.png"
  "$SHOW/images/02_sos_sent.png"
  "$SHOW/images/07_history.png"
  "$SHOW/images/07_metrics.png"
  "$SHOW/images/08_hindi_ui.png"
  "$SHOW/images/09_onboarding_credits.png"
  "$SHOW/images/04_settings_theme.png"
  "$SHOW/images/06_theme_light.png"
  "$ROOT/docs/brand/screens/pairing.png"
)
for src in "${SCREENS[@]}"; do
  name="$(basename "$src" .png)"
  cp "$src" "$OUT/media/screens/$name.png"
  sips --resampleWidth 540 -s format jpeg -s formatOptions 82 "$src" --out "$OUT/media/thumbs/$name.jpg" >/dev/null
done
cp "$SHOW/images/dashboard.png" "$OUT/media/screens/dashboard.png"
sips --resampleWidth 960 -s format jpeg -s formatOptions 82 "$SHOW/images/dashboard.png" --out "$OUT/media/thumbs/dashboard.jpg" >/dev/null

# --- Diagrams ----------------------------------------------------------------
for src in "$SHOW"/images/diagram_*.png; do
  name="$(basename "$src" .png)"
  cp "$src" "$OUT/media/diagrams/$name.png"
  sips --resampleWidth 960 -s format jpeg -s formatOptions 82 "$src" --out "$OUT/media/thumbs/$name.jpg" >/dev/null
done

# --- Video + clips with poster frames ---------------------------------------
cp "$SHOW/iTantra_explainer.mp4" "$OUT/media/video/explainer.mp4"
ffmpeg -v error -y -ss 3 -i "$SHOW/iTantra_explainer.mp4" -frames:v 1 -vf scale=1280:-2 -q:v 4 "$OUT/media/video/explainer-poster.jpg"
for src in "$SHOW"/clips/*.mp4; do
  name="$(basename "$src" .mp4)"
  cp "$src" "$OUT/media/clips/$name.mp4"
  ffmpeg -v error -y -ss 2 -i "$src" -frames:v 1 -q:v 4 "$OUT/media/clips/$name.jpg"
done

# --- Model pack ---------------------------------------------------------------
# Only the files the app loads: F16 codec and Q4 LLM (the app prefers both when present),
# no q8_0 / F32 codec / WavLM.
MODEL_FILES=(
  vad/silero_vad.onnx
  stt/sravaani/encoder.int8.onnx
  stt/sravaani/decoder.int8.onnx
  stt/sravaani/joiner.int8.onnx
  stt/sravaani/tokens.txt
  stt/sravaani/bpe.vocab
  tts/mio/indic-mio-q4.gguf
  tts/mio/miocodec-f16.gguf
  tts/vits-rasa/model.onnx
  tts/vits-rasa/tokens.txt
)
for lang in hi gu mr kn ml ta te or bn en; do MODEL_FILES+=("tts/mio/voices/$lang.emb.gguf"); done

MANIFEST_TSV="$OUT/models/v1/.manifest.tsv"
: > "$MANIFEST_TSV"
for rel in "${MODEL_FILES[@]}"; do
  src="$ROOT/models/$rel"
  dest="$OUT/models/v1/$rel"
  mkdir -p "$(dirname "$dest")"
  size=$(stat -f %z "$src")
  sha=$(shasum -a 256 "$src" | cut -d' ' -f1)
  if [ "$size" -gt 250000000 ]; then
    split -b "$PART_SIZE" "$src" "$dest.part-"
    parts=$(cd "$(dirname "$dest")" && ls "$(basename "$dest").part-"* | tr '\n' ',' | sed 's/,$//')
  else
    cp "$src" "$dest"
    parts=""
  fi
  printf '%s\t%s\t%s\t%s\n' "$rel" "$size" "$sha" "$parts" >> "$MANIFEST_TSV"
done

python3 - "$MANIFEST_TSV" "$OUT/models/v1/manifest.json" <<'PY'
import json, sys
files = []
for line in open(sys.argv[1], encoding="utf-8"):
    rel, size, sha, parts = line.rstrip("\n").split("\t")
    files.append({"path": rel, "size": int(size), "sha256": sha,
                  "parts": [p for p in parts.split(",") if p]})
json.dump({"version": 1, "device_dir": "/sdcard/Android/data/org.itantra.app/files/models",
           "total_bytes": sum(f["size"] for f in files), "files": files},
          open(sys.argv[2], "w"), indent=1)
PY
rm "$MANIFEST_TSV"

# Tab-separated list the install scripts read (path, size, sha256, parts) — no JSON parser needed.
python3 - "$OUT/models/v1/manifest.json" "$OUT/models/v1/manifest.tsv" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
with open(sys.argv[2], "w") as f:
    for x in m["files"]:
        f.write(f'{x["path"]}\t{x["size"]}\t{x["sha256"]}\t{",".join(x["parts"])}\n')
PY

echo "APK sha256: $(shasum -a 256 "$OUT/apk/$APK_NAME" | cut -d' ' -f1)"
echo "APK bytes:  $(stat -f %z "$OUT/apk/$APK_NAME")"
du -sh "$OUT"/*
