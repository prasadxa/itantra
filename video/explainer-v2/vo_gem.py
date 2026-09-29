"""vo_gem.py <chapter-id> [line-id ...] — narrator lines with Gemini TTS (gemini-3.8-flash-tts, Interactions API,
speech_metadata.style), voice en-in-storyteller-2 (won the voice-lab A/B, voice-lab/abtest.json). Key from $GEMINI_KEY_FILE,
never printed. Doc: https://ai.google.dev/gemini-api/docs/speech-generation. Clips trimmed, levelled to -18 LUFS, 48 kHz mono."""
import base64, json, os, sys, time, pathlib, subprocess, urllib.request, urllib.error
STYLE = "clear, confident, warm documentary explainer narration for a national technology showcase; measured pace; credible and human"
MASTER = ("silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02,areverse,"
          "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.10,areverse,"
          "highpass=f=70,acompressor=threshold=-20dB:ratio=2:attack=10:release=150:makeup=2,loudnorm=I=-18:TP=-2:LRA=9")
root = pathlib.Path(__file__).parent; rid, only = sys.argv[1], set(sys.argv[2:])
key = pathlib.Path(os.environ["GEMINI_KEY_FILE"]).read_text().strip()
src = root / "reels" / rid; (src / "vo").mkdir(parents=True, exist_ok=True)
for ln in json.loads((src / "script.json").read_text())["lines"]:
    out = src / "vo" / f"{ln['id']}.wav"
    if out.exists() and ln["id"] not in only: continue
    body = {"model": "gemini-3.8-flash-tts",
            "input": [{"type": "user_input", "content": [{"type": "text", "text": ln.get("say", ln["text"]),
                        "annotations": [{"type": "speech_metadata", "style": STYLE + ("; " + ln["style"] if ln.get("style") else "")}]}]}],
            "response_format": {"type": "audio"}, "generation_config": {"speech_config": [{"voice": ln["voice"]}]}}
    req = urllib.request.Request("https://generativelanguage.googleapis.com/v1beta/interactions", data=json.dumps(body).encode(),
                                 headers={"x-goog-api-key": key, "Content-Type": "application/json"})
    for attempt in range(8):
        try:
            r = json.loads(urllib.request.urlopen(req, timeout=300).read()); break
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 503) and attempt < 7: time.sleep(15 * (attempt + 1)); continue
            print("HTTP", e.code, ln["id"], e.read()[:300].decode(errors="replace")); sys.exit(1)
    aud = [c for s in r["steps"] if s.get("type") == "model_output" for c in s.get("content", []) if c.get("type") == "audio"]
    raw = out.with_suffix(".raw.wav"); raw.write_bytes(base64.b64decode(aud[-1]["data"]))
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(raw), "-af", MASTER, "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "1", str(out)], check=True)
    raw.unlink(); print("ok", rid, ln["id"], flush=True)
