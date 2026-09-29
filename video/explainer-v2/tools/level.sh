#!/bin/bash
# level.sh <in.mp4> <out.mp4> — measure integrated loudness, apply exact gain to -16 LUFS with a -1.5 dBTP limiter; video stream copied.
set -e
I=$(ffmpeg -i "$1" -af ebur128 -f null - 2>&1 | grep -E '^ +I:' | tail -1 | awk '{print $2}')
G=$(python3 -c "print(round(-16-($I),2))")
ffmpeg -v error -y -i "$1" -c:v copy -af "volume=${G}dB,alimiter=limit=0.84:level=false" -c:a aac -b:a 192k -movflags +faststart "$2"
