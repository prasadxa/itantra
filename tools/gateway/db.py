"""
db.py - SQLite logging for the iTantra control-room gateway.

Schema is intentionally small: every Msg/Partial/Ack frame the gateway sees (in either
direction) gets a row. One connection is shared across threads (peer reader threads +
the dashboard HTTP server), serialized with a lock, since sqlite3 connections are not
safe to use concurrently from multiple threads without care.
"""

from __future__ import annotations

import sqlite3
import threading
import time
from pathlib import Path
from typing import Any, Iterable, Optional

SCHEMA = """
CREATE TABLE IF NOT EXISTS messages (
    row_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    msg_id      TEXT NOT NULL,
    direction   TEXT NOT NULL CHECK(direction IN ('in', 'out')),
    peer_device_id TEXT NOT NULL,
    from_id     TEXT,
    lang        TEXT,
    text        TEXT,
    priority    TEXT,
    emotion     TEXT,
    ssml        INTEGER,
    speech_end_at INTEGER,
    stt_done_at   INTEGER,
    sent_at       INTEGER,
    gateway_recv_at INTEGER,
    lat         REAL,
    lon         REAL,
    logged_at   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS partials (
    row_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    msg_id      TEXT NOT NULL,
    peer_device_id TEXT NOT NULL,
    from_id     TEXT,
    lang        TEXT,
    text        TEXT,
    gateway_recv_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS acks (
    row_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    msg_id      TEXT NOT NULL,
    peer_device_id TEXT NOT NULL,
    direction   TEXT NOT NULL CHECK(direction IN ('in', 'out')),
    received_at     INTEGER,
    play_started_at INTEGER,
    sent_at_gateway INTEGER,
    gateway_recv_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS peers (
    device_id   TEXT PRIMARY KEY,
    name        TEXT,
    address     TEXT,
    port        INTEGER,
    first_seen  INTEGER,
    last_seen   INTEGER,
    connected   INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_messages_msg_id ON messages(msg_id);
CREATE INDEX IF NOT EXISTS idx_acks_msg_id ON acks(msg_id);
"""


def now_ms() -> int:
    return int(time.time() * 1000)


class GatewayDB:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(str(path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.executescript(SCHEMA)
            self._conn.commit()

    # ---- writes ----

    def log_message(
        self,
        *,
        msg_id: str,
        direction: str,
        peer_device_id: str,
        from_id: Optional[str],
        lang: Optional[str],
        text: Optional[str],
        priority: Optional[str],
        emotion: Optional[str],
        ssml: bool,
        speech_end_at: int,
        stt_done_at: int,
        sent_at: int,
        lat: Optional[float] = None,
        lon: Optional[float] = None,
    ) -> None:
        with self._lock:
            self._conn.execute(
                """INSERT INTO messages
                (msg_id, direction, peer_device_id, from_id, lang, text, priority, emotion,
                 ssml, speech_end_at, stt_done_at, sent_at, gateway_recv_at, lat, lon, logged_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    msg_id, direction, peer_device_id, from_id, lang, text, priority, emotion,
                    1 if ssml else 0, speech_end_at, stt_done_at, sent_at, now_ms(), lat, lon, now_ms(),
                ),
            )
            self._conn.commit()

    def log_partial(self, *, msg_id: str, peer_device_id: str, from_id: str, lang: str, text: str) -> None:
        with self._lock:
            self._conn.execute(
                """INSERT INTO partials (msg_id, peer_device_id, from_id, lang, text, gateway_recv_at)
                VALUES (?,?,?,?,?,?)""",
                (msg_id, peer_device_id, from_id, lang, text, now_ms()),
            )
            self._conn.commit()

    def log_ack(
        self,
        *,
        msg_id: str,
        peer_device_id: str,
        direction: str,
        received_at: int,
        play_started_at: int,
        sent_at_gateway: Optional[int] = None,
    ) -> None:
        with self._lock:
            self._conn.execute(
                """INSERT INTO acks
                (msg_id, peer_device_id, direction, received_at, play_started_at, sent_at_gateway, gateway_recv_at)
                VALUES (?,?,?,?,?,?,?)""",
                (msg_id, peer_device_id, direction, received_at, play_started_at, sent_at_gateway, now_ms()),
            )
            self._conn.commit()

    def upsert_peer(self, *, device_id: str, name: str, address: str, port: int, connected: bool) -> None:
        with self._lock:
            row = self._conn.execute("SELECT device_id FROM peers WHERE device_id=?", (device_id,)).fetchone()
            ts = now_ms()
            if row is None:
                self._conn.execute(
                    "INSERT INTO peers (device_id, name, address, port, first_seen, last_seen, connected) VALUES (?,?,?,?,?,?,?)",
                    (device_id, name, address, port, ts, ts, 1 if connected else 0),
                )
            else:
                self._conn.execute(
                    "UPDATE peers SET name=?, address=?, port=?, last_seen=?, connected=? WHERE device_id=?",
                    (name, address, port, ts, 1 if connected else 0, device_id),
                )
            self._conn.commit()

    # ---- reads ----

    def recent_messages(self, limit: int = 100) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM messages ORDER BY row_id DESC LIMIT ?", (limit,)
            ).fetchall()
        return [dict(r) for r in rows]

    def recent_partials(self, limit: int = 50) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT * FROM partials ORDER BY row_id DESC LIMIT ?", (limit,)
            ).fetchall()
        return [dict(r) for r in rows]

    def connected_peers(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute("SELECT * FROM peers ORDER BY last_seen DESC").fetchall()
        return [dict(r) for r in rows]

    def lang_counts(self) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT lang, COUNT(*) as n FROM messages GROUP BY lang ORDER BY n DESC"
            ).fetchall()
        return [dict(r) for r in rows]

    def latency_samples(self) -> dict[str, list[float]]:
        """sent->received and received->playStarted, in ms, from ack rows that have both fields."""
        with self._lock:
            rows = self._conn.execute(
                "SELECT sent_at_gateway, received_at, play_started_at FROM acks "
                "WHERE sent_at_gateway IS NOT NULL AND received_at IS NOT NULL"
            ).fetchall()
        sent_to_recv: list[float] = []
        recv_to_play: list[float] = []
        for r in rows:
            if r["sent_at_gateway"] and r["received_at"]:
                sent_to_recv.append(r["received_at"] - r["sent_at_gateway"])
            if r["play_started_at"] and r["received_at"] and r["play_started_at"] >= r["received_at"]:
                recv_to_play.append(r["play_started_at"] - r["received_at"])
        return {"sent_to_received_ms": sent_to_recv, "received_to_play_ms": recv_to_play}

    def locations(self, limit: int = 200) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT msg_id, peer_device_id, from_id, lang, priority, lat, lon, logged_at FROM messages "
                "WHERE lat IS NOT NULL AND lon IS NOT NULL ORDER BY row_id DESC LIMIT ?",
                (limit,),
            ).fetchall()
        return [dict(r) for r in rows]


def percentile(values: Iterable[float], p: float) -> Optional[float]:
    s = sorted(values)
    if not s:
        return None
    k = (len(s) - 1) * (p / 100.0)
    f, c = int(k), min(int(k) + 1, len(s) - 1)
    if f == c:
        return s[f]
    return s[f] + (s[c] - s[f]) * (k - f)
