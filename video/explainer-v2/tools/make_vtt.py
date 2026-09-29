"""make_vtt.py <out.vtt> — captions for the assembled film from script.json text + vo/words.txt word times + chapter offsets
(chapter lengths are measured from the rendered chapters/<id>.mp4, the same files assemble.sh concatenates)."""
import json, re, subprocess, sys, wave, pathlib
root = pathlib.Path(__file__).parent.parent
def dur(p): return float(subprocess.run(["ffprobe","-v","error","-show_entries","format=duration","-of","csv=p=0",str(p)],capture_output=True,text=True).stdout)
def ts(t): h, r = divmod(t, 3600); m, s = divmod(r, 60); return f"{int(h):02d}:{int(m):02d}:{s:06.3f}"
cues, off = [], 0.0
for d in sorted((root / "reels").iterdir()):
    j = json.loads((d / "script.json").read_text()); t = 0.0
    words = {k: [(w.rsplit("@", 1)[0], float(w.rsplit("@", 1)[1])) for w in v.split()] for k, v in
             (l.split(": ", 1) for l in (d / "vo" / "words.txt").read_text().strip().splitlines())}
    for ln in j["lines"]:
        with wave.open(str(d / "vo" / f"{ln['id']}.wav")) as w: du = w.getnframes() / w.getframerate()
        s = ln["at"] if "at" in ln else t + ln.get("gap", 0.25); t = s + du
        text = ln["text"].split(); ws = words.get(ln["id"], [])
        # chunk the caption text into <= 9 words, split preferably after punctuation; time chunks from whisper word starts
        chunks, cur = [], []
        for w in text:
            cur.append(w)
            if len(cur) >= 9 or (len(cur) >= 4 and re.search(r"[.,:;!?]$", w)): chunks.append(cur); cur = []
        if cur: chunks.append(cur)
        i = 0
        for k, c in enumerate(chunks):
            st = off + s + (ws[min(i, len(ws) - 1)][1] if ws else du * i / len(text))
            i += len(c)
            en = off + s + (ws[min(i, len(ws) - 1)][1] - 0.05 if (ws and k < len(chunks) - 1) else du)
            cues.append((st, max(en, st + 0.8), " ".join(c)))
    off += dur(root / "chapters" / f"{d.name}.mp4")
out = ["WEBVTT", ""]
for a, b, txt in cues: out += [f"{ts(a)} --> {ts(b)}", txt, ""]
pathlib.Path(sys.argv[1]).write_text("\n".join(out)); print("cues", len(cues), "film", round(off, 2))
