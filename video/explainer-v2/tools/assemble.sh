#!/bin/bash
# assemble.sh [music-offset-sec] — join rendered chapters (chapters/<id>.mp4, in reels/ order) into one film,
# lay the score (shared/music/film.mp3) under it ducked by the voice, level to -16 LUFS.
# Output: iTantra-Explainer.mp4 (+ share/ copy, cover). Render chapters first with tools/render_ch.sh <id>.
set -e
P="$(cd "$(dirname "$0")/.." && pwd)"; cd "$P"
OFF="${1:-0}"   # seconds skipped at the head of the score
mkdir -p build share covers
: > build/list.txt
for d in reels/*/; do id=$(basename "$d"); [ -f "chapters/$id.mp4" ] || { echo "missing chapters/$id.mp4"; exit 1; }; echo "file '$P/chapters/$id.mp4'" >> build/list.txt; done
# 1. concat (re-encode once so every boundary is frame-exact)
ffmpeg -v error -y -f concat -safe 0 -i build/list.txt -c:v libx264 -crf 14 -preset slow -pix_fmt yuv420p -r 30 -c:a pcm_s16le -ar 48000 build/film_vo.mov
LEN=$(ffprobe -v error -show_entries format=duration -of csv=p=0 build/film_vo.mov)
# 2. score: skip OFF s, fade out over the last 3.5 s of the film, duck under the voice (sidechain), mix
ffmpeg -v error -y -i build/film_vo.mov -ss "$OFF" -i shared/music/film.mp3 -filter_complex "
 [0:a]aresample=48000,asplit=2[vo][key];
 [1:a]aresample=48000,volume=0.40,apad,atrim=0:$LEN,afade=t=out:st=$(python3 -c "print(max(0,$LEN-3.5))"):d=3.5[m];
 [m][key]sidechaincompress=threshold=0.02:ratio=6:attack=40:release=450:makeup=1[md];
 [vo][md]amix=inputs=2:duration=first:normalize=0,alimiter=limit=0.95[a]" \
 -map 0:v -map "[a]" -t "$LEN" -c:v copy -c:a pcm_s16le build/film_mix.mov
# 3. loudness to -16 LUFS (two-pass via level.sh) → final
bash tools/level.sh build/film_mix.mov iTantra-Explainer.mp4
ffmpeg -v error -y -i iTantra-Explainer.mp4 -c:v libx264 -crf 23 -preset slow -pix_fmt yuv420p -c:a aac -b:a 160k -movflags +faststart share/iTantra-Explainer.mp4
ffmpeg -v error -y -ss 21 -i iTantra-Explainer.mp4 -frames:v 1 -q:v 2 covers/iTantra-Explainer.jpg
echo "film: $(ffprobe -v error -show_entries format=duration -of csv=p=0 iTantra-Explainer.mp4)s $(ffmpeg -i iTantra-Explainer.mp4 -af ebur128 -f null - 2>&1 | grep -E '^ +I:' | tail -1)"
