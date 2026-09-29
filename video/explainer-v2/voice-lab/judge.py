"""judge.py <run> — Gemini 3.1 Pro listens to each cand/*.wav with a fixed rubric (JSON). Writes judge/run<run>.json.
Caveat: the judge is a Gemini model and half the candidates are Gemini voices; pairwise A/B in both orders (abtest.py) is the tiebreak."""
import base64, json, os, pathlib, sys, time, urllib.request, urllib.error
LAB = pathlib.Path(__file__).parent; KEY = (pathlib.Path(os.environ["KEYS_DIR"]) / "gemini.key").read_text().strip()
from gen import TEXT
RUBRIC = f"""You are a senior voice-casting director for documentary and technology explainer films in India.
Listen to this clip: a candidate English voiceover (Indian English is welcome) for a ~100-second explainer film about "iTantra",
an offline multilingual voice walkie-talkie app built for India's Smart India Hackathon (ISRO problem statement). The audience is
ISRO scientists and hackathon judges. Desired delivery: clear, confident, warm, credible documentary narrator; measured pace
(about 135-155 words per minute); a little gravity at the start, hopeful at the end; NOT an advert announcer, NOT an IVR, NOT rushed.
The script is: "{TEXT}"
Score strictly and comparably, full 1-10 range (10 = indistinguishable from a top professional voice artist; 5 = obviously synthetic
but usable; 1 = unusable): naturalness (human, no TTS artifacts), clarity (every word intelligible, correct pronunciation of
"iTantra" as "eye-TUN-truh" or "ee-TUN-truh"), warmth, fit (for this film and audience), pacing. List mispronounced/skipped/added words.
One-line critique (max 25 words)."""
SCHEMA = {"type": "object", "properties": {k: {"type": "integer"} for k in ("naturalness", "clarity", "warmth", "fit", "pacing")} |
          {"errors": {"type": "string"}, "critique": {"type": "string"}},
          "required": ["naturalness", "clarity", "warmth", "fit", "pacing", "errors", "critique"]}
def ask(parts, schema):
    body = {"model": "gemini-3.1-pro-preview", "input": parts, "response_format": schema}
    for attempt in range(5):
        try:
            req = urllib.request.Request("https://generativelanguage.googleapis.com/v1beta/interactions", data=json.dumps(body).encode(),
                                         headers={"x-goog-api-key": KEY, "Content-Type": "application/json"})
            r = json.loads(urllib.request.urlopen(req, timeout=300).read())
            return json.loads([c["text"] for s in r["steps"] if s.get("type") == "model_output" for c in s.get("content", []) if c.get("type") == "text"][-1])
        except Exception as e:
            print("retry", getattr(e, "code", e), flush=True); time.sleep(12 * (attempt + 1))
    raise SystemExit("judge failed")
aud = lambda p: {"type": "audio", "mime_type": "audio/wav", "data": base64.b64encode(p.read_bytes()).decode()}
if __name__ == "__main__":
    run = sys.argv[1]; (LAB / "judge").mkdir(exist_ok=True); out = LAB / "judge" / f"run{run}.json"
    res = json.loads(out.read_text()) if out.exists() else {}
    for f in sorted((LAB / "cand").glob("*.wav")):
        if f.stem in res: continue
        res[f.stem] = ask([aud(f), {"type": "text", "text": RUBRIC}], SCHEMA); out.write_text(json.dumps(res, indent=1))
        print(run, f.stem, {k: res[f.stem][k] for k in ("naturalness", "clarity", "warmth", "fit", "pacing")}, "|", res[f.stem]["critique"], flush=True)
