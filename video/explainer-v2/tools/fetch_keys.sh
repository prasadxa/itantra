#!/bin/bash
# fetch_keys.sh <dir> — copy the Cartesia (voice) and Gemini (Lyria music) keys from the staging VM's env into <dir>/cartesia.key and <dir>/gemini.key (mode 600).
# Never print them; delete the files when done. Azure image/video/whisper use your az login (Entra token), no key file.
set -e; D="$1"; mkdir -p "$D"; umask 077
az vm run-command invoke -g rg-callmissed-staging -n vm-callmissed-stg --command-id RunShellScript \
  --scripts "grep -E '^(CARTESIA_API_KEY|GEMINI_API_KEY)=' /opt/callmissed/.env" -o json > "$D/k.json"
python3 - "$D" <<'PY'
import json, re, sys, pathlib
d = sys.argv[1]; t = json.load(open(d + "/k.json"))["value"][0]["message"]
k = dict(re.findall(r'^(CARTESIA_API_KEY|GEMINI_API_KEY)=(.*)$', t, re.M))
pathlib.Path(d, "cartesia.key").write_text(k["CARTESIA_API_KEY"].strip().strip('"'))
pathlib.Path(d, "gemini.key").write_text(k["GEMINI_API_KEY"].strip().strip('"')); print("keys written to", d)
PY
rm -f "$D/k.json"
