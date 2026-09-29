"""vo.py <reel-id> [line-id ...] — voiceover clips with Cartesia sonic-3.6 (key file in $CARTESIA_KEY_FILE, never printed).
Per line in script.json: voice, lang, text, optional emotion / speed (generation_config, doc: docs.cartesia.ai/build-with-cartesia/sonic-3/volume-speed-emotion,
read 2026-09-20: speed 0.6-1.5, volume 0.5-2.0, emotion string) and optional "say" (what is sent to TTS when it differs from the caption text).
Each clip is header-repaired, silence-trimmed, and given a gentle low-shelf + compressor so male voices sit deep and even."""
import json, os, sys, pathlib, subprocess, urllib.request

VOICES = {
    "kiara": "f8f5f1b2-f02d-4d8e-a40d-fd850a487b3d", "devansh": "1259b7e3-cb8a-43df-9446-30971a46b8b0", "aarav": "39d518b7-fd0b-4676-9b8b-29d64ff31e12",
    "krishna": "c63361f8-d142-4c62-8da7-8f8149d973d6", "mark": "5619d38c-cf51-4d8e-9575-48f61a280413", "orin": "4c2dcd38-5608-45ca-8f11-51c88208d01c",
    "sterling": "b134c304-d095-4d2b-a77a-914f5e8e84e7", "jewel": "f39d8500-0d9b-4b8b-a080-38f5188f5892", "tessa": "6ccbfb76-1fc6-48f7-b71d-91ac6298247b",
    "daphne": "09ed0318-2f4a-41b1-abe5-d11da7537c31", "leo": "0834f3df-e650-4766-a20c-5a93a43aa6e3", "maya": "cbaf8084-f009-4838-a096-07ee2e6612b1",
    "kabir-hi": "cb9c954d-bcaa-43ed-82bf-aeb5e88a3cb5", "riya-hi": "faf0731e-dfb9-4cfc-8119-259a79b27e12", "dev-hi": "910fb75e-1d20-4840-ac63-ac6b26a71bdc",
    "meera-hi": "a81fccdc-5595-4dfc-ae76-4de6a515b8a2", "ritesh-hi": "78e608e9-7258-464d-83c0-badcef173bab", "kavita-hi": "56e35e2d-6eb6-4226-ab8b-9776515a7094",
    "jace": "6776173b-fd72-460d-89b3-d85812ee518d", "kyle": "c961b81c-a935-4c17-bfb3-ba2239de8c2f", "gavin": "f4a3a8e4-694c-4c45-9ca0-27caf97901b5",
    "dana": "cc00e582-ed66-4004-8336-0175b85c85f6", "marian": "26403c37-80c1-4a1a-8692-540551ca2ae5",
}
MASTER = "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02,areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.06,areverse,highpass=f=60,bass=g={bass}:f=140:w=0.7,acompressor=threshold=-18dB:ratio=2.5:attack=8:release=120:makeup=2,alimiter=limit=0.9"
root = pathlib.Path(__file__).parent
rid, only = sys.argv[1], set(sys.argv[2:])
key = pathlib.Path(os.environ["CARTESIA_KEY_FILE"]).read_text().strip()
src = root / "reels" / rid
(src / "vo").mkdir(parents=True, exist_ok=True)
for ln in json.loads((src / "script.json").read_text())["lines"]:
    out = src / "vo" / f"{ln['id']}.wav"
    if out.exists() and ln["id"] not in only: continue
    body = {"model_id": "sonic-3.6", "transcript": ln.get("say", ln["text"]), "voice": {"mode": "id", "id": VOICES[ln.get("voice", "kiara")]},
            "language": ln.get("lang", "en"), "output_format": {"container": "wav", "encoding": "pcm_s16le", "sample_rate": 44100}}
    gc = {k: ln[k] for k in ("emotion", "speed") if k in ln and not (k == "emotion" and ln.get("lang", "en") != "en")}
    if gc: body["generation_config"] = gc
    req = urllib.request.Request("https://api.cartesia.ai/tts/bytes", data=json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {key}", "Cartesia-Version": "2026-08-14", "Content-Type": "application/json"})
    try:
        raw = out.with_suffix(".raw.wav"); raw.write_bytes(urllib.request.urlopen(req, timeout=120).read())
    except urllib.error.HTTPError as e:
        print("HTTP", e.code, ln["id"], e.read()[:300].decode(errors="replace")); sys.exit(1)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(raw), "-af", MASTER.format(bass=ln.get("bass", 3)), "-c:a", "pcm_s16le", "-ar", "44100", str(out)], check=True)
    raw.unlink(); print("ok", rid, ln["id"])
