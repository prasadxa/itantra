"""
dashboard.py - stdlib-only HTTP server for the control-room dashboard.

No web framework, no CDN: `http.server` + Server-Sent Events for the live feed, plain
JSON endpoints for everything else, and the dashboard's own HTML/CSS/JS served from
tools/gateway/static/ so the whole thing works with zero internet access.
"""

from __future__ import annotations

import json
import queue
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

STATIC_DIR = Path(__file__).resolve().parent / "static"
SAMPLES_DIR = Path(__file__).resolve().parent / "samples"

CONTENT_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
}

_SAFE_NAME = re.compile(r"^[A-Za-z0-9_.-]+$")


def make_server(gateway, http_port: int) -> ThreadingHTTPServer:
    class Handler(BaseHTTPRequestHandler):
        server_version = "iTantraGateway/0.1"

        def log_message(self, fmt: str, *args) -> None:  # quieter default logging
            pass

        # ---- helpers ----

        def _send_json(self, obj, status: int = 200) -> None:
            body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _read_json(self) -> dict:
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length <= 0:
                return {}
            raw = self.rfile.read(length)
            return json.loads(raw.decode("utf-8")) if raw else {}

        def _serve_static(self, rel_path: str) -> None:
            rel_path = rel_path.lstrip("/")
            if rel_path == "":
                rel_path = "index.html"
            target = (STATIC_DIR / rel_path).resolve()
            if STATIC_DIR not in target.parents and target != STATIC_DIR:
                self.send_error(403)
                return
            if not target.is_file():
                self.send_error(404)
                return
            ctype = CONTENT_TYPES.get(target.suffix, "application/octet-stream")
            body = target.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _serve_sse(self) -> None:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            q = gateway.subscribe()
            try:
                while True:
                    try:
                        event = q.get(timeout=15.0)
                        payload = f"data: {json.dumps(event, ensure_ascii=False)}\n\n".encode("utf-8")
                    except queue.Empty:
                        payload = b": keepalive\n\n"
                    self.wfile.write(payload)
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            finally:
                gateway.unsubscribe(q)

        # ---- routes ----

        def do_GET(self) -> None:  # noqa: N802
            parsed = urlparse(self.path)
            path = parsed.path
            if path == "/api/state":
                self._send_json(gateway.state_snapshot())
            elif path == "/api/events":
                self._serve_sse()
            elif path == "/api/cap/samples":
                names = sorted(p.name for p in SAMPLES_DIR.glob("*.xml")) if SAMPLES_DIR.is_dir() else []
                self._send_json({"samples": names})
            elif path.startswith("/static/"):
                self._serve_static(path[len("/static/"):])
            elif path == "/" or path == "/index.html":
                self._serve_static("index.html")
            else:
                self.send_error(404)

        def do_POST(self) -> None:  # noqa: N802
            parsed = urlparse(self.path)
            path = parsed.path
            try:
                body = self._read_json()
            except (json.JSONDecodeError, UnicodeDecodeError):
                self._send_json({"error": "invalid JSON body"}, status=400)
                return

            if path == "/api/broadcast":
                text = (body.get("text") or "").strip()
                lang = (body.get("lang") or "en").strip()
                alert = bool(body.get("alert", False))
                if not text:
                    self._send_json({"error": "text is required"}, status=400)
                    return
                result = gateway.broadcast(text, lang, alert=alert)
                self._send_json(result)

            elif path == "/api/cap":
                name = (body.get("file") or "").strip()
                lang = (body.get("lang") or "en").strip()
                if not name or not _SAFE_NAME.match(name):
                    self._send_json({"error": "invalid file name"}, status=400)
                    return
                target = (SAMPLES_DIR / name).resolve()
                if SAMPLES_DIR not in target.parents or not target.is_file():
                    self._send_json({"error": "sample not found"}, status=404)
                    return
                try:
                    result = gateway.send_cap_file(target, lang)
                except Exception as e:  # noqa: BLE001 - surface parse errors to the dashboard
                    self._send_json({"error": str(e)}, status=400)
                    return
                self._send_json(result)

            else:
                self.send_error(404)

    server = ThreadingHTTPServer(("0.0.0.0", http_port), Handler)
    server.daemon_threads = True
    return server
