"""sora.py <ref.jpg|-> <out.mp4> <prompt-file> [seconds 4|8|12] — Azure sora-2 video (OpenAI v1 videos schema), Entra token, no key on disk.
Run at most 2 at a time (Azure returns 429 "Too many running tasks"). Images with people as input_reference get moderation_blocked; text-only is fine."""
import sys, json, subprocess, time, uuid, pathlib, urllib.request
ref, out, pf = sys.argv[1:4]; secs = sys.argv[4] if len(sys.argv) > 4 else "4"
tok = subprocess.run(["az","account","get-access-token","--resource","https://cognitiveservices.azure.com","--query","accessToken","-o","tsv"],capture_output=True,text=True,check=True).stdout.strip()
base = "https://callmissed-resource.cognitiveservices.azure.com/openai/v1/videos"
H = {"Authorization": f"Bearer {tok}"}
b = uuid.uuid4().hex; parts = []
for k, v in (("model","sora-2"),("prompt",pathlib.Path(pf).read_text().strip()),("size","720x1280"),("seconds",secs)):
    parts.append(f'--{b}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
if ref != "-":
    parts.append(f'--{b}\r\nContent-Disposition: form-data; name="input_reference"; filename="ref.jpg"\r\nContent-Type: image/jpeg\r\n\r\n'.encode() + pathlib.Path(ref).read_bytes() + b"\r\n")
parts.append(f"--{b}--\r\n".encode())
def call(req):
    try: return urllib.request.urlopen(req, timeout=300)
    except urllib.error.HTTPError as e: print("HTTP", e.code, e.read()[:700].decode(errors="replace")); sys.exit(1)
job = json.load(call(urllib.request.Request(base, data=b"".join(parts), headers={**H, "Content-Type": f"multipart/form-data; boundary={b}"})))
print("job", job.get("id"), job.get("status"))
while job.get("status") in ("queued","in_progress","preprocessing","running","processing"):
    time.sleep(10); job = json.load(call(urllib.request.Request(f"{base}/{job['id']}", headers=H)))
print("final", job.get("status"), json.dumps(job.get("error"))[:300])
if job.get("status") == "completed":
    pathlib.Path(out).write_bytes(call(urllib.request.Request(f"{base}/{job['id']}/content", headers=H)).read()); print("ok", out)
else: sys.exit(1)
