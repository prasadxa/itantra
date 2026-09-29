"""gen_image.py <deployment> <WxH> <out.png> <prompt-file> [ref.png ...] — Azure image models (gpt-image-2.5-sunburst, gpt-image-2, MAI-Image-2.6 …) via Entra token (no key on disk)."""
import sys, json, base64, subprocess, urllib.request, uuid, mimetypes, pathlib
dep, size, out, pf, *refs = sys.argv[1:]
prompt = pathlib.Path(pf).read_text().strip()
tok = subprocess.run(["az","account","get-access-token","--resource","https://cognitiveservices.azure.com","--query","accessToken","-o","tsv"],capture_output=True,text=True,check=True).stdout.strip()
base = f"https://callmissed-resource.cognitiveservices.azure.com/openai/deployments/{dep}/images/"
q = "?api-version=2025-04-01-preview"
if refs:
    b = uuid.uuid4().hex; parts = []
    for k, v in (("prompt", prompt), ("model", dep), ("size", size), ("n", "1"), ("quality", "high")):
        parts.append(f'--{b}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    for r in refs:
        p = pathlib.Path(r); mt = mimetypes.guess_type(p.name)[0] or "image/png"
        parts.append(f'--{b}\r\nContent-Disposition: form-data; name="image[]"; filename="{p.name}"\r\nContent-Type: {mt}\r\n\r\n'.encode() + p.read_bytes() + b"\r\n")
    parts.append(f"--{b}--\r\n".encode())
    req = urllib.request.Request(base + "edits" + q, data=b"".join(parts), headers={"Authorization": f"Bearer {tok}", "Content-Type": f"multipart/form-data; boundary={b}"})
else:
    req = urllib.request.Request(base + "generations" + q, data=json.dumps({"model": dep, "prompt": prompt, "n": 1, "size": size, "quality": "high"}).encode(), headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json"})
try:
    r = json.load(urllib.request.urlopen(req, timeout=600))
    pathlib.Path(out).write_bytes(base64.b64decode(r["data"][0]["b64_json"])); print("ok", out)
except urllib.error.HTTPError as e:
    print("HTTP", e.code, e.read()[:600].decode(errors="replace")); sys.exit(1)
