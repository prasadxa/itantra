#!/usr/bin/env bash
# Uploads website/.r2-staging/ (built by stage-assets.sh) to the R2 bucket with wrangler.
# Usage: website/scripts/upload-assets.sh [subdir]   e.g. "media" to re-upload only media.
set -euo pipefail

BUCKET="${ITANTRA_BUCKET:-itantra-assets}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STAGE="$ROOT/website/.r2-staging"
SUB="${1:-}"
JOBS="${JOBS:-4}"

[ -d "$STAGE" ] || { echo "Run website/scripts/stage-assets.sh first." >&2; exit 1; }

upload_one() {
  local file="$1" key="${1#"$STAGE"/}" type cache disp=()
  case "$file" in
    *.apk)  type="application/vnd.android.package-archive"; disp=(--content-disposition "attachment; filename=\"$(basename "$file")\"") ;;
    *.png)  type="image/png" ;;
    *.jpg)  type="image/jpeg" ;;
    *.mp4)  type="video/mp4" ;;
    *.json) type="application/json" ;;
    *.tsv|*.txt|*.vocab) type="text/plain; charset=utf-8" ;;
    *)      type="application/octet-stream" ;;
  esac
  case "$key" in
    apk/*|models/*/manifest.*) cache="public, max-age=300" ;;
    *)                         cache="public, max-age=604800" ;;
  esac
  wrangler r2 object put "$BUCKET/$key" --file "$file" --content-type "$type" \
    --cache-control "$cache" "${disp[@]}" --remote >/dev/null 2>"$STAGE/.err.$$.$RANDOM" \
    && echo "ok   $key" || { echo "FAIL $key" >&2; return 1; }
}
export -f upload_one
export BUCKET STAGE

find "$STAGE/${SUB}" -type f ! -name '.*' -print0 \
  | xargs -0 -n 1 -P "$JOBS" bash -c 'upload_one "$0"'
rm -f "$STAGE"/.err.*
