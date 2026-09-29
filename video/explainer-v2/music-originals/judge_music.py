"""Compare two scores against the brief, both orders (Gemini 3.1 Pro). 96 kbps mono copies keep the request small."""
import base64, json, os, pathlib, subprocess, tempfile, urllib.request, sys
KEY = pathlib.Path(os.environ["GEMINI_KEY_FILE"]).read_text().strip(); D = pathlib.Path(__file__).parent
brief = (D / "m1-film.txt").read_text()
def enc(p):
    t = tempfile.mktemp(suffix=".mp3"); subprocess.run(["ffmpeg","-v","error","-y","-i",str(p),"-ac","1","-b:a","96k",t], check=True)
    return base64.b64encode(pathlib.Path(t).read_bytes()).decode()
S = {"type":"object","properties":{"winner":{"type":"string","enum":["A","B"]},"a_score":{"type":"integer"},"b_score":{"type":"integer"},"why":{"type":"string"},"vocals_or_speech_heard":{"type":"string"}},"required":["winner","a_score","b_score","why","vocals_or_speech_heard"]}
for a, b in (("m1-film", "m2-film"), ("m2-film", "m1-film")):
    body = {"model":"gemini-3.1-pro-preview","input":[{"type":"text","text":"Clip A:"},{"type":"audio","mime_type":"audio/mpeg","data":enc(D/f"{a}.mp3")},
            {"type":"text","text":"Clip B:"},{"type":"audio","mime_type":"audio/mpeg","data":enc(D/f"{b}.mp3")},
            {"type":"text","text":"You are a film music supervisor. Both clips are candidate scores for this brief:\n"+brief+"\nWhich better fits the brief as a bed under a voiceover (structure timing, restraint, premium feel, Indian colour without cliche)? Score each 1-10. Also report any vocals, whispers or speech you hear, with timestamps, or 'none'."}],
            "response_format": S}
    req = urllib.request.Request("https://generativelanguage.googleapis.com/v1beta/interactions", data=json.dumps(body).encode(), headers={"x-goog-api-key":KEY,"Content-Type":"application/json"})
    r = json.loads(urllib.request.urlopen(req, timeout=600).read())
    out = json.loads([c["text"] for s in r["steps"] if s.get("type")=="model_output" for c in s.get("content",[]) if c.get("type")=="text"][-1])
    print(f"A={a} B={b}", json.dumps(out), flush=True)
