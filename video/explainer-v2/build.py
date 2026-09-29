"""build.py <reel-id> — assembles reels/<id>/reel.html into out/<id>/index.html.

Timing is driven by the voiceover: each line in script.json starts `gap` seconds after
the previous one ends (or at an absolute `at`). In reel.html, `@[expr]` is evaluated with
  <line>     start of that VO line        <line>_e   its end        <line>_d   its length
  END        total length (last line end + tail)
Directives:
  /*@BASE_CSS*/  /*@BASE_JS*/     shared styles + timeline helpers, inlined
  <!--@AUDIO-->                   music bed + every VO line + every `sfx` entry in script.json
"""
import json, re, shutil, sys, wave, subprocess, pathlib

root = pathlib.Path(__file__).parent
rid = sys.argv[1]
src = root / "reels" / rid
spec = json.loads((src / "script.json").read_text())

def dur(p):
    if p.suffix == ".wav":
        with wave.open(str(p)) as w: return w.getnframes() / w.getframerate()
    return float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(p)], capture_output=True, text=True).stdout)

env, t = {}, 0.0
for ln in spec["lines"]:
    d = dur(src / "vo" / f"{ln['id']}.wav")
    s = ln["at"] if "at" in ln else t + ln.get("gap", 0.25)
    env[ln["id"]], env[ln["id"] + "_d"], env[ln["id"] + "_e"] = s, d, s + d
    t = s + d
env["END"] = END = round(t + spec.get("tail", 2.0), 2)
# eval is over timing expressions written in our own reel.html/script.json (no outside input), with builtins removed
ev = lambda e: eval(e, {"__builtins__": {}, "min": min, "max": max}, env)
f = lambda x: f"{x:.2f}".rstrip("0").rstrip(".") if isinstance(x, float) else str(x)

audio = [f'<audio id="bgm" data-start="0" data-duration="{f(END)}" data-track-index="30" data-volume="1" src="assets/music/{spec["music"]}.mp3"></audio>']
for i, ln in enumerate(spec["lines"]):
    audio.append(f'<audio id="vo-{ln["id"]}" data-start="{f(round(env[ln["id"]], 2))}" data-duration="{f(round(env[ln["id"] + "_d"], 2))}" data-track-index="{40 + i}" data-volume="1" src="assets/vo/{ln["id"]}.wav"></audio>')
for i, (name, at, vol, *cut) in enumerate(spec.get("sfx", [])):  # optional 4th item: max length expr
    d = min(dur(root / "shared" / "sfx" / name), ev(cut[0]) if cut else 3.0)
    audio.append(f'<audio id="sx-{i}" data-start="{f(round(ev(at), 2))}" data-duration="{f(round(d, 2))}" data-track-index="{60 + i}" data-volume="{vol}" src="assets/sfx/{name}"></audio>')

html = (src / "reel.html").read_text()
html = html.replace("/*@BASE_CSS*/", (root / "shared" / "base.css").read_text())
html = html.replace("/*@BASE_JS*/", (root / "shared" / "base.js").read_text())
html = html.replace("<!--@AUDIO-->", "\n      ".join(audio))
html = re.sub(r"@\[([^\]]+)\]", lambda m: f(round(float(ev(m.group(1))), 2)), html)

out = root / "out" / rid
if out.exists(): shutil.rmtree(out)
shutil.copytree(root / "shared", out / "assets", ignore=shutil.ignore_patterns("base.*", "music"))
(out / "assets" / "music").mkdir()
shutil.copy(root / "shared" / "music" / f"{spec['music']}.mp3", out / "assets" / "music")
shutil.copytree(src / "vo", out / "assets" / "vo")
if (src / "img").exists(): shutil.copytree(src / "img", out / "assets" / "img", dirs_exist_ok=True)
(out / "index.html").write_text(html)
print(rid, "END", END, {k: round(v, 2) for k, v in env.items() if "_" not in k and k != "END"})
