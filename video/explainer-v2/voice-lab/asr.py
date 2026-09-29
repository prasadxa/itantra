"""asr.py — word accuracy of each candidate with local whisper small.en (hyperframes transcribe)."""
import json, pathlib, re, shutil, subprocess, tempfile
LAB = pathlib.Path(__file__).parent
from gen import TEXT
norm = lambda s: re.sub(r"[^a-z0-9 ]", " ", s.lower().replace("-", " ")).split()
ref = norm(TEXT)
def wer(r, h):
    d = list(range(len(h) + 1))
    for i, rw in enumerate(r, 1):
        p, d[0] = d[0], i
        for j, hw in enumerate(h, 1):
            p, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, p + (rw != hw))
    return d[-1] / len(r)
res = {}
for f in sorted((LAB / "cand").glob("*.wav")):
    with tempfile.TemporaryDirectory() as t:
        shutil.copy(f, t)
        subprocess.run(["npx", "-y", "hyperframes@latest", "transcribe", f.name, "--json"], cwd=t, capture_output=True, check=True, timeout=900)
        words = json.loads((pathlib.Path(t) / "transcript.json").read_text())
    hyp = " ".join(w["text"] for w in words)
    h = norm(hyp.replace("I Tantra", "iTantra").replace("i Tantra", "iTantra").replace("eye tantra", "iTantra"))
    res[f.stem] = {"acc": round(1 - wer(ref, h), 3), "hyp": hyp}
    print(f.stem, res[f.stem]["acc"], "|", hyp[:140], flush=True)
(LAB / "asr.json").write_text(json.dumps(res, indent=1))
