#!/bin/bash
# finish.sh <reel-id> — build, check, render, loudness-level, share copy and cover for one reel.
# Output: <project>/<reel-id>.mp4 (-16 LUFS), share/<reel-id>.mp4 (smaller), covers/<reel-id>.jpg (frame at 0.4 s)
set -e
P="$(cd "$(dirname "$0")/.." && pwd)"; R="$1"; cd "$P"
python3 build.py "$R"
cd "out/$R"
npx -y hyperframes@latest check | tail -3
npx -y hyperframes@latest render --quality delivery --output "$P/out/$R.raw.mp4"
cd "$P"
bash "$P/tools/level.sh" "out/$R.raw.mp4" "$R.mp4"
mkdir -p share covers
ffmpeg -v error -y -i "$R.mp4" -c:v libx264 -crf 24 -preset slow -pix_fmt yuv420p -c:a aac -b:a 128k -movflags +faststart "share/$R.mp4"
ffmpeg -v error -y -ss 0.4 -i "$R.mp4" -frames:v 1 -q:v 2 "covers/$R.jpg"
rm -f "out/$R.raw.mp4"
echo "$R: $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$R.mp4")s $(ffmpeg -i "$R.mp4" -af ebur128 -f null - 2>&1 | grep -E '^ +I:' | tail -1)"
