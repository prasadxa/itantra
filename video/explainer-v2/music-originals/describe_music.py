import base64, json, os, pathlib, subprocess, tempfile, urllib.request
KEY = pathlib.Path(os.environ["GEMINI_KEY_FILE"]).read_text().strip(); D = pathlib.Path(__file__).parent
S = {"type":"object","properties":{"sections":{"type":"array","items":{"type":"object","properties":{"from_s":{"type":"number"},"to_s":{"type":"number"},"instruments":{"type":"string"},"energy_1_10":{"type":"integer"}},"required":["from_s","to_s","instruments","energy_1_10"]}},
     "bpm":{"type":"integer"},"has_orchestral_strings":{"type":"boolean"},"has_flute":{"type":"boolean"},"has_lofi_hiphop_beat":{"type":"boolean"},"midrange_busy_1_10":{"type":"integer"},"genre":{"type":"string"},"mood":{"type":"string"}},
     "required":["sections","bpm","has_orchestral_strings","has_flute","has_lofi_hiphop_beat","midrange_busy_1_10","genre","mood"]}
for m in ("m1-film", "m2-film", "m4-film", "m5-film"):
    t = tempfile.mktemp(suffix=".mp3"); subprocess.run(["ffmpeg","-v","error","-y","-i",str(D/f"{m}.mp3"),"-ac","1","-b:a","96k",t], check=True)
    body = {"model":"gemini-3.1-pro-preview","input":[{"type":"audio","mime_type":"audio/mpeg","data":base64.b64encode(pathlib.Path(t).read_bytes()).decode()},
            {"type":"text","text":"Describe this instrumental track factually, like a music editor's cue sheet: its sections with start/end seconds, the instruments you actually hear in each, energy, BPM, genre and mood. Be literal; do not guess what it was meant to be."}],"response_format":S}
    r = json.loads(urllib.request.urlopen(urllib.request.Request("https://generativelanguage.googleapis.com/v1beta/interactions", data=json.dumps(body).encode(), headers={"x-goog-api-key":KEY,"Content-Type":"application/json"}), timeout=600).read())
    print(m, [c["text"] for s in r["steps"] if s.get("type")=="model_output" for c in s.get("content",[]) if c.get("type")=="text"][-1], flush=True)
