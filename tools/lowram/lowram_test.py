"""lowram_test.py — measure iTantra on a high-RAM phone twice in one session:
  A) normal (all RAM available)          B) "4 GB emulation": memhold reserves RAM so only ~4 GB is left
Both runs force the LITE profile (as a real <6 GB phone would auto-select) and run the same workload:
10 STT clips (models/testclips, one per language) via DEBUG_STT_WAV, 4 TTS sentences via DEBUG_SPEAK,
a 30 s idle-CPU sample, app PSS, MemAvailable, low-memory kills, thermal status.

What this does NOT emulate: a budget phone's slower CPU/GPU. The chip stays the same.

Usage:  python3 tools/lowram/lowram_test.py [--reserve-mb 8192] [--serial SERIAL]
Output: tools/lowram/results-<timestamp>.json + a printed summary. Always releases memhold and restores
the profile setting on exit."""
import argparse, json, pathlib, re, subprocess, sys, time

ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = str(pathlib.Path.home() / "Library/Android/sdk/platform-tools/adb")
PKG = "org.itantra.app"; COMP = f"{PKG}/.DebugCommandReceiver"
FILES = f"/sdcard/Android/data/{PKG}/files"
PREFS = f"/data/data/{PKG}/shared_prefs/itantra_settings.xml"
TTS = [("hi", "बाढ़ का पानी बढ़ रहा है, कृपया ऊँचे स्थान पर जाएँ।"), ("hi", "राहत दल पुल के पास पहुँच गया है।"), ("ta", "வெள்ளம் அதிகரிக்கிறது, உயரமான இடத்திற்குச் செல்லுங்கள்."),
       ("bn", "বন্যার জল বাড়ছে, উঁচু জায়গায় যান।"), ("en", "Flood water is rising, please move to higher ground.")]

ap = argparse.ArgumentParser(); ap.add_argument("--targets", default="normal,3000,1800", help="normal and/or MemAvailable targets in MB"); ap.add_argument("--serial"); args = ap.parse_args()
S = ["-s", args.serial] if args.serial else []

def adb(*a, timeout=120, check=True):
    r = subprocess.run([ADB, *S, *a], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0: raise SystemExit(f"adb {' '.join(a)} failed: {r.stderr.strip()[:300]}")
    return r.stdout
def sh(cmd, **k): return adb("shell", cmd, **k)

def meminfo():
    m = dict(re.findall(r"^(\w+):\s+(\d+) kB", sh("cat /proc/meminfo"), re.M))
    return {k: round(int(m[k]) / 1024) for k in ("MemTotal", "MemAvailable", "SwapTotal", "SwapFree") if k in m}
def thermal():
    t = sh("dumpsys thermalservice", check=False); m = re.search(r"Thermal Status:\s*(\d+)", t); return int(m.group(1)) if m else None
def pid(): return sh(f"pidof {PKG}", check=False).strip().split()[0] if sh(f"pidof {PKG}", check=False).strip() else None
def pss_mb():
    t = sh(f"dumpsys meminfo {PKG}", check=False, timeout=60); m = re.search(r"TOTAL PSS:\s+(\d+)", t) or re.search(r"^\s*TOTAL\s+(\d+)", t, re.M)
    return round(int(m.group(1)) / 1024) if m else None
def cpu_pct(seconds=30):
    p = pid()
    if not p: return None
    def ticks():
        f = sh(f"cat /proc/{p}/stat", check=False).split(")")[-1].split(); return int(f[11]) + int(f[12])
    a = ticks(); time.sleep(seconds); b = ticks()
    hz = int(sh("getconf CLK_TCK", check=False).strip() or 100)
    return round((b - a) / hz / seconds * 100, 2)   # % of one core
def metrics_since(mark):
    out = []
    for line in adb("logcat", "-d", "-v", "epoch", "-s", "ITANTRA_METRIC:I", timeout=60).splitlines():
        m = re.match(r"\s*(\d+\.\d+).*?(\{.*\})\s*$", line)
        if m and float(m.group(1)) >= mark:
            try: out.append(json.loads(m.group(2)))
            except json.JSONDecodeError: pass
    return out
def lmk_kills_since(mark):
    n = 0
    for line in adb("logcat", "-d", "-v", "epoch", "-b", "events", timeout=60).splitlines():
        m = re.match(r"\s*(\d+\.\d+)", line)
        if m and float(m.group(1)) >= mark and ("am_kill" in line or "lowmemorykiller" in line or "killinfo" in line): n += 1
    return n
def device_now(): return float(sh("date +%s.%N", check=False).strip()[:17] or time.time())

def set_profile(value):
    sh(f"am force-stop {PKG}")
    xml = sh(f"run-as {PKG} cat {PREFS}", check=False)
    if "<map" not in xml: xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
    if 'name="profile_override"' in xml: xml = re.sub(r'<string name="profile_override">[^<]*</string>', f'<string name="profile_override">{value}</string>', xml)
    else: xml = xml.replace("</map>", f'    <string name="profile_override">{value}</string>\n</map>')
    subprocess.run([ADB, *S, "shell", f"run-as {PKG} sh -c 'cat > {PREFS}'"], input=xml, text=True, check=True)
def get_profile():
    m = re.search(r'name="profile_override">([^<]*)<', sh(f"run-as {PKG} cat {PREFS}", check=False)); return m.group(1) if m else "AUTO"

def start_app():
    sh(f"am start -n {PKG}/.MainActivity", check=False); time.sleep(12)

def workload(tag):
    res = {"tag": tag, "mem_before": meminfo(), "thermal_before": thermal()}
    start_app(); mark = device_now(); p0 = pid(); res["pid_start"] = p0
    for wav in sorted((ROOT / "models/testclips").glob("*.wav")):
        lang = wav.stem
        sh(f"am broadcast -a org.itantra.DEBUG_STT_WAV -n {COMP} --es path {FILES}/lowram/{wav.name} --es lang {lang}", check=False)
        time.sleep(9)
    for lang, text in TTS:
        subprocess.run([ADB, *S, "shell", f"am broadcast -a org.itantra.DEBUG_SPEAK -n {COMP} --es lang {lang} --es text '{text}'"], capture_output=True, timeout=60)
        time.sleep(12)
    res["pss_after_work_mb"] = pss_mb()
    time.sleep(75)                                   # LITE unloads idle engines after 60 s
    res["cpu_idle_pct_one_core"] = cpu_pct(30)
    res["pss_after_idle_mb"] = pss_mb()
    res["pid_end"] = pid(); res["app_survived"] = bool(p0) and res["pid_end"] == p0
    res["metrics"] = metrics_since(mark); res["lmk_kills"] = lmk_kills_since(mark)
    res["mem_after"] = meminfo(); res["thermal_after"] = thermal()
    hp = sh("pidof memhold", check=False).strip()
    if hp:  # proof of how much of the reservation really sat in RAM vs swap during the run
        st = dict(re.findall(r"^(VmRSS|VmSwap):\s+(\d+) kB", sh(f"cat /proc/{hp}/status", check=False), re.M))
        res["memhold_rss_mb"] = round(int(st.get("VmRSS", 0)) / 1024); res["memhold_swap_mb"] = round(int(st.get("VmSwap", 0)) / 1024)
    return res

def main():
    devs = [l.split()[0] for l in adb("devices").splitlines()[1:] if l.strip().endswith("device")]
    if not devs: raise SystemExit("No phone connected (adb devices is empty).")
    if not args.serial and len(devs) > 1: raise SystemExit(f"Several phones connected {devs}; pass --serial.")
    if not sh(f"pm path {PKG}", check=False).strip(): raise SystemExit("iTantra is not installed on the phone.")
    if "encoder.int8.onnx" not in sh(f"ls {FILES}/models/stt/sravaani/", check=False): raise SystemExit("Models are missing on the phone.")
    info = {"model": sh("getprop ro.product.model").strip(), "soc": sh("getprop ro.soc.model", check=False).strip(),
            "android": sh("getprop ro.build.version.release").strip(), "targets": args.targets}
    sh(f"mkdir -p {FILES}/lowram")
    for w in sorted((ROOT / "models/testclips").glob("*.wav")): adb("push", str(w), f"{FILES}/lowram/{w.name}", timeout=120)
    assert "hi.wav" in sh(f"ls {FILES}/lowram"), "clips not on phone"
    adb("push", str(pathlib.Path(__file__).with_name("memhold")), "/data/local/tmp/memhold"); sh("chmod 755 /data/local/tmp/memhold")
    old_profile = get_profile(); out = {"device": info, "runs": []}
    sh("svc power stayon usb", check=False); sh("input keyevent KEYCODE_WAKEUP", check=False); sh("wm dismiss-keyguard", check=False)
    try:
        set_profile("LITE")
        for tgt in args.targets.split(","):
            adb("logcat", "-c", check=False)
            if tgt == "normal":
                out["runs"].append(workload("normal")); continue
            sh(f"am force-stop {PKG}"); time.sleep(5)
            avail = meminfo()["MemAvailable"]; reserve = max(0, avail - int(tgt))
            sh(f"nohup /data/local/tmp/memhold {reserve} > /data/local/tmp/memhold.log 2>&1 &", timeout=30, check=False)
            for _ in range(90):
                time.sleep(2)
                if "holding" in sh("cat /data/local/tmp/memhold.log", check=False): break
            time.sleep(8)
            run = workload(f"available_{tgt}MB"); run["reserved_mb"] = reserve; run["avail_before_reserve_mb"] = avail
            out["runs"].append(run)
            sh("p=$(pidof memhold); [ -n \"$p\" ] && kill $p", check=False); time.sleep(4)
    finally:
        sh("p=$(pidof memhold); [ -n \"$p\" ] && kill $p", check=False); time.sleep(2)
        set_profile(old_profile); sh(f"rm -rf {FILES}/lowram", check=False)
        out["profile_restored_to"] = old_profile
        sh("svc power stayon false", check=False)
    p = pathlib.Path(__file__).with_name(f"results-{time.strftime('%Y%m%d-%H%M%S')}.json")
    p.write_text(json.dumps(out, indent=1, ensure_ascii=False)); print("wrote", p)
    for r in out["runs"]:
        print(f"== {r['tag']}: MemTotal {r['mem_before'].get('MemTotal')} MB, MemAvailable {r['mem_before'].get('MemAvailable')} MB -> {r['mem_after'].get('MemAvailable')} MB, "
              f"PSS work {r['pss_after_work_mb']} MB / idle {r['pss_after_idle_mb']} MB, idle CPU {r['cpu_idle_pct_one_core']}%, "
              f"app survived {r['app_survived']}, LMK kill events {r['lmk_kills']}, thermal {r['thermal_before']}->{r['thermal_after']}, metric lines {len(r['metrics'])}")

main()
