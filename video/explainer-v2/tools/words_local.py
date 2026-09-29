"""words_local.py <reel-id> — per-word start times for each VO line with local whisper (hyperframes transcribe):
small.en for English lines, large-v3 with -l hi for Hindi lines. Writes reels/<id>/vo/words.txt as `id: word@sec ...`."""
import sys, json, shutil, tempfile, pathlib, subprocess
root = pathlib.Path(__file__).parent.parent; src = root / "reels" / sys.argv[1]
out = []
for ln in json.loads((src / "script.json").read_text())["lines"]:
    with tempfile.TemporaryDirectory() as d:
        shutil.copy(src / "vo" / f"{ln['id']}.wav", d)
        hi = ln.get("lang", "en") != "en"
        cmd = ["npx", "-y", "hyperframes@latest", "transcribe", f"{ln['id']}.wav", "--json"] + (["-m", "large-v3", "-l", "hi"] if hi else [])
        subprocess.run(cmd, cwd=d, capture_output=True, check=True, timeout=900)
        ws = json.loads((pathlib.Path(d) / "transcript.json").read_text())
    out.append(f"{ln['id']}: " + " ".join(f"{w['text']}@{w['start']:.2f}" for w in ws))
(src / "vo" / "words.txt").write_text("\n".join(out) + "\n"); print(sys.argv[1], "ok")
