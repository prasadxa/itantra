"""gen.py — English narrator audition: the same passage from the real script, read by Cartesia sonic-3.6 and
Gemini gemini-3.8-flash-tts voices. Keys from $KEYS_DIR (never printed). Output cand/<id>.wav, 48 kHz mono, -18 LUFS."""
import base64, json, os, sys, time, pathlib, subprocess, urllib.request, urllib.error
LAB = pathlib.Path(__file__).parent; KD = pathlib.Path(os.environ["KEYS_DIR"])
TEXT = ("When a flood takes the towers down, your phone still works. The network doesn't. "
        "So iTantra doesn't send your voice. It sends what you said. Speech becomes text on your phone. "
        "A few dozen bytes cross the link. And the other phone speaks it aloud, in ten Indian languages.")
STYLE = "clear, confident, warm documentary explainer narration for a national technology showcase; measured pace; a little gravity at the start, hopeful at the end"
CART = {"aarav": "39d518b7-fd0b-4676-9b8b-29d64ff31e12", "devansh": "1259b7e3-cb8a-43df-9446-30971a46b8b0",
        "kiara": "f8f5f1b2-f02d-4d8e-a40d-fd850a487b3d", "krishna": "c63361f8-d142-4c62-8da7-8f8149d973d6",
        "sterling": "b134c304-d095-4d2b-a77a-914f5e8e84e7"}
GEM = ["en-in-storyteller-11", "en-in-storyteller-2", "en-in-storyteller-7", "en-in-podcaster-3", "en-in-podcaster-5",
       "en-in-storyteller-8", "en-in-storyteller-1", "en-in-podcaster-4"]
(LAB / "cand").mkdir(exist_ok=True); (LAB / "raw").mkdir(exist_ok=True)

def master(raw, out):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(raw), "-af",
                    "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02,areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.08,areverse,loudnorm=I=-18:TP=-2:LRA=11",
                    "-ar", "48000", "-ac", "1", str(out)], check=True)

def cartesia(name, vid, emotion=None):
    body = {"model_id": "sonic-3.6", "transcript": TEXT, "voice": {"mode": "id", "id": vid}, "language": "en",
            "output_format": {"container": "wav", "encoding": "pcm_s16le", "sample_rate": 44100}}
    if emotion: body["generation_config"] = {"emotion": emotion, "speed": 0.95}
    req = urllib.request.Request("https://api.cartesia.ai/tts/bytes", data=json.dumps(body).encode(), headers={
        "Authorization": f"Bearer {(KD / 'cartesia.key').read_text().strip()}", "Cartesia-Version": "2026-08-14", "Content-Type": "application/json"})
    return urllib.request.urlopen(req, timeout=120).read()

def gemini(voice):
    body = {"model": "gemini-3.8-flash-tts",
            "input": [{"type": "user_input", "content": [{"type": "text", "text": TEXT, "annotations": [{"type": "speech_metadata", "style": STYLE}]}]}],
            "response_format": {"type": "audio"}, "generation_config": {"speech_config": [{"voice": voice}]}}
    req = urllib.request.Request("https://generativelanguage.googleapis.com/v1beta/interactions", data=json.dumps(body).encode(),
                                 headers={"x-goog-api-key": (KD / "gemini.key").read_text().strip(), "Content-Type": "application/json"})
    for attempt in range(8):
        try:
            r = json.loads(urllib.request.urlopen(req, timeout=300).read()); break
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < 7: time.sleep(15 * (attempt + 1)); continue
            raise SystemExit(f"HTTP {e.code} {voice} {e.read()[:300]!r}")
    aud = [c for s in r["steps"] if s.get("type") == "model_output" for c in s.get("content", []) if c.get("type") == "audio"]
    return base64.b64decode(aud[-1]["data"])

jobs = [(f"cart-{n}", lambda n=n, v=v: cartesia(n, v, "content")) for n, v in CART.items()]
jobs += [(f"gem-{v.replace('en-in-', '')}", lambda v=v: gemini(v)) for v in GEM]
for name, fn in jobs:
    out = LAB / "cand" / f"{name}.wav"
    if out.exists(): continue
    raw = LAB / "raw" / f"{name}.wav"; raw.write_bytes(fn()); master(raw, out)
    d = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(out)], capture_output=True, text=True).stdout)
    print(f"ok {name} {d:.1f}s {len(TEXT.split()) / d * 60:.0f} wpm", flush=True)
