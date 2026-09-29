#!/bin/bash
# render_ch.sh <chapter-id> — build + render one chapter to chapters/<id>.mp4 (voice + sfx, silent score; not levelled —
# the whole film is levelled once in assemble.sh). Don't edit this file while renders are running.
set -e
P="$(cd "$(dirname "$0")/.." && pwd)"; R="$1"; cd "$P"
python3 build.py "$R"
mkdir -p chapters
cd "out/$R"
npx -y hyperframes@latest render --quality delivery --fps 30 --output "$P/chapters/$R.mp4"
echo "$R: $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$P/chapters/$R.mp4")s"
