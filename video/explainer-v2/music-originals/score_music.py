import base64, json, os, pathlib, subprocess, tempfile, urllib.request
KEY = pathlib.Path(os.environ["GEMINI_KEY_FILE"]).read_text().strip(); D = pathlib.Path(__file__).parent; brief = (D / "m1-film.txt").read_text()
S = {"type":"object","properties":{"score":{"type":"integer"},"structure_matches":{"type":"boolean"},"voice_space":{"type":"integer"},"cheesiness":{"type":"integer"},"why":{"type":"string"}},"required":["score","structure_matches","voice_space","cheesiness","why"]}
for run in (1, 2):
    for m in ("m4-film", "m5-film"):
        t = tempfile.mktemp(suffix=".mp3"); subprocess.run(["ffmpeg","-v","error","-y","-i",str(D/f"{m}.mp3"),"-ac","1","-b:a","96k",t], check=True)
        body = {"model":"gemini-3.1-pro-preview","input":[{"type":"audio","mime_type":"audio/mpeg","data":base64.b64encode(pathlib.Path(t).read_bytes()).decode()},
                {"type":"text","text":"You are a film music supervisor. Rate this score against the brief below, strictly, on its own (1-10 overall; voice_space 1-10 = how much room it leaves for a narrator; cheesiness 1-10 = how stock/cheesy it sounds, 10 = very cheesy). Brief:\n"+brief}],"response_format":S}
        r = json.loads(urllib.request.urlopen(urllib.request.Request("https://generativelanguage.googleapis.com/v1beta/interactions", data=json.dumps(body).encode(), headers={"x-goog-api-key":KEY,"Content-Type":"application/json"}), timeout=600).read())
        print(run, m, [c["text"] for s in r["steps"] if s.get("type")=="model_output" for c in s.get("content",[]) if c.get("type")=="text"][-1], flush=True)
