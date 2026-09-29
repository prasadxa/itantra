"""lyria.py <out.mp3> <prompt-file> [model] — Gemini API music generation (Interactions API). Key from $GEMINI_KEY_FILE, never printed."""
import sys, os, json, base64, pathlib, urllib.request
out, pf = sys.argv[1], sys.argv[2]; model = sys.argv[3] if len(sys.argv) > 3 else "lyria-3.5"
key = pathlib.Path(os.environ["GEMINI_KEY_FILE"]).read_text().strip()
body = {"model": model, "input": pathlib.Path(pf).read_text().strip(), "response_format": {"type": "audio"}}
req = urllib.request.Request("https://generativelanguage.googleapis.com/v1beta/interactions", data=json.dumps(body).encode(), headers={"x-goog-api-key": key, "Content-Type": "application/json"})
try:
    r = json.load(urllib.request.urlopen(req, timeout=900))
except urllib.error.HTTPError as e:
    print("HTTP", e.code, e.read()[:700].decode(errors="replace")); sys.exit(1)
def find(o):
    if isinstance(o, dict):
        if isinstance(o.get("data"), str) and len(o["data"]) > 10000: return o
        for v in o.values():
            f = find(v)
            if f: return f
    if isinstance(o, list):
        for v in o:
            f = find(v)
            if f: return f
a = find(r)
if not a: print("no audio; keys:", json.dumps(r)[:600]); sys.exit(1)
pathlib.Path(out).write_bytes(base64.b64decode(a["data"])); print("ok", out, a.get("mime_type"))
