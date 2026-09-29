#!/usr/bin/env bash
# iTantra model installer (macOS / Linux).
# Downloads the ~1.2 GB on-device model pack, checks every file's SHA-256, and copies it to the
# phone over USB with adb. Safe to re-run: files already downloaded and verified are skipped.
#
#   curl -fsSL https://itantra-106.pages.dev/install-models.sh -o install-models.sh
#   bash install-models.sh
#
# Options (environment variables):
#   ITANTRA_CACHE    where downloads are kept (default: ~/.cache/itantra-models)
#   ITANTRA_NO_PUSH  set to 1 to download and verify only, without a phone
set -euo pipefail

BASE="${ITANTRA_MODELS_URL:-https://pub-d139da76dbb3434bbcb332e210a95fcf.r2.dev/models/v1}"
CACHE="${ITANTRA_CACHE:-$HOME/.cache/itantra-models}"
PKG="org.itantra.app"
DEVICE_DIR="/sdcard/Android/data/$PKG/files/models"

say()  { printf '\033[1;33m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

command -v curl >/dev/null || fail "curl is not installed."
if [ "${ITANTRA_NO_PUSH:-0}" != 1 ]; then
  command -v adb >/dev/null || fail "adb is not installed. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools"
  count=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
  [ "$count" = 1 ] || fail "Connect exactly one phone with USB debugging on (adb sees $count). Run 'adb devices' to check."
  adb shell pm path "$PKG" >/dev/null 2>&1 || fail "iTantra is not installed on the phone. Install the APK first, open it once, then re-run."
fi

mkdir -p "$CACHE"
say "Fetching file list"
curl -fsSL --retry 3 "$BASE/manifest.tsv" -o "$CACHE/manifest.tsv"

while IFS=$'\t' read -r path size sha parts; do
  dest="$CACHE/$path"
  mkdir -p "$(dirname "$dest")"
  if [ -f "$dest" ] && [ "$(sha256 "$dest")" = "$sha" ]; then
    echo "  ok       $path"
    continue
  fi
  echo "  download $path ($((size / 1000000)) MB)"
  if [ -z "$parts" ]; then
    curl -fL --retry 3 --progress-bar "$BASE/$path" -o "$dest.tmp"
  else
    dir="$(dirname "$path")"
    : > "$dest.tmp"
    IFS=',' read -r -a list <<< "$parts"
    for p in "${list[@]}"; do
      curl -fL --retry 3 --progress-bar "$BASE/$dir/$p" -o "$CACHE/$dir/$p"
      cat "$CACHE/$dir/$p" >> "$dest.tmp"
      rm -f "$CACHE/$dir/$p"
    done
  fi
  [ "$(sha256 "$dest.tmp")" = "$sha" ] || { rm -f "$dest.tmp"; fail "Checksum mismatch for $path. Re-run to download it again."; }
  mv "$dest.tmp" "$dest"
done < "$CACHE/manifest.tsv"

say "All model files downloaded and verified in $CACHE"
[ "${ITANTRA_NO_PUSH:-0}" = 1 ] && exit 0

say "Copying to the phone (this takes a few minutes over USB)"
adb shell mkdir -p "$DEVICE_DIR"
for top in vad stt tts; do
  adb push "$CACHE/$top" "$DEVICE_DIR/"
done
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true

say "Done. Open iTantra, then Settings > Models: every row should show as present."
