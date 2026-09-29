# iTantra control-room gateway

An offline control-room gateway + dashboard for the iTantra walkie-talkie mesh (SIH PS
26173). It joins the phone network **as a peer** — same mDNS discovery
(`_itantra._tcp.`), same TCP framing (4-byte big-endian length + UTF-8 JSON `Frame`,
class discriminator `type`) as the app's `WifiTransport` — so no phone-side changes are
needed. It connects to every phone it finds (not just one), logs every `Msg`/`Partial`/
`Ack` to SQLite, and serves a small local dashboard for monitoring and broadcasting.

Everything here (`tools/gateway/`) is Python; `tools/peer_sim.py` (owned elsewhere) is
only imported from, never edited — see `frames.py`.

## Setup

```
python3.11 -m venv tools/gateway/.venv
tools/gateway/.venv/bin/pip install -r tools/gateway/requirements.txt
```

## Run

```
tools/gateway/.venv/bin/python tools/gateway/gateway.py
```

Then open **http://localhost:8787/**. Flags:

- `--device-id` (default random `gateway-xxxxxx`) — the mDNS/Hello identity phones see.
- `--port` — TCP listen port for the peer protocol (default: any free port).
- `--http-port` — dashboard port (default `8787`).
- `--db` — SQLite path (default `tools/gateway/data/log.db`).
- `--cap FILE --cap-lang LANG` — ingest a CAP 1.2 alert and broadcast it as `ALERT` once
  at startup (see below; the dashboard can also do this at any time).
- `--no-http` — run the peer/TCP side only, no dashboard.

## Demo without a phone

```
tools/gateway/.venv/bin/python tools/gateway/demo.py
```

Spins up two in-process "fake phones" (`demo.py:FakePhone`) speaking the exact same wire
protocol, lets the gateway discover/connect/log/broadcast/CAP-ingest against them, and
prints a summary (message counts, per-language counts, latency). No Android device or
`peer_sim.py` process required — though `peer_sim.py --send "..." --lang hi` against a
running `gateway.py` works too, as a second manual way to test without a phone.

## Tests

```
tools/gateway/.venv/bin/python -m pytest tools/gateway/tests -q
```

Covers CAP 1.2 parsing (language selection incl. fallback to English, malformed/absent
blocks, an XXE/DOCTYPE guard) and frame encoding (byte-for-byte shape against
`core/Messages.kt`'s field names).

## Architecture

```
                      mDNS `_itantra._tcp.`            TCP: 4B length + JSON Frame
   phone A  <───────────────────────────────────────────────────────────►  gateway.py
   phone B  <───────────────────────────────────────────────────────────►      │
      …                                                                        │  SQLite
                                                                                ▼
                                                                          data/log.db
                                                                                │
                                                            http.server (stdlib, no CDN)
                                                                                │
                                                                    dashboard (localhost)
```

- **`gateway.py`** (`Gateway` class): advertises its own `_itantra._tcp.` service and
  keeps a continuous mDNS browser (`_BrowserListener`) so it discovers phones as they come
  and go. For each discovered peer it applies the *same pairwise dial rule* the phones use
  (`WifiTransport`/`peer_sim.py`: the lexicographically smaller `deviceId` dials, the other
  accepts) — this lets the gateway hold many simultaneous peer connections without ever
  racing a phone into two TCP connections for the same pair. Every connection gets its own
  reader thread; `Hello`/`Msg`/`Partial`/`Ack`/`Ping`/`Pong`/`Ptt` are all handled. Inbound
  `Msg` frames are Acked immediately (the dashboard "plays" a message the instant it's
  displayed, so `receivedAt`/`playStartedAt` are effectively equal for phone→gateway
  traffic). Outbound sends (`broadcast()`, `send_cap_file()`) are tracked in
  `_pending_sends` so the *phone's* returning `Ack` lets us compute sent→received
  (gateway clock → phone clock, so it also carries clock skew — noted in the dashboard as
  an approximation) and received→playStarted (both phone-clock fields from the same Ack,
  so that one is skew-free).
- **`db.py`**: one shared SQLite connection guarded by a lock (`messages`, `partials`,
  `acks`, `peers` tables); simple aggregate queries back the dashboard's stats/percentiles.
- **`cap.py`**: OASIS CAP 1.2 parsing (stdlib `xml.etree`, with a DOCTYPE/ENTITY guard
  since CAP feeds like SACHET's are network-origin data — see `_reject_unsafe_xml`).
  Picks the `<info>` block whose `<language>` primary subtag matches the requested
  language, else the English block, else the first block.
- **`dashboard.py`**: stdlib `http.server.ThreadingHTTPServer`, JSON endpoints
  (`/api/state`, `/api/broadcast`, `/api/cap`, `/api/cap/samples`) plus a Server-Sent
  Events stream (`/api/events`) for the live feed — no external web framework, no CDN;
  `static/` ships its own HTML/CSS/JS.
- **`frames.py`**: imports (does not duplicate) `peer_sim.py`'s frame builders/codec so
  the two stay byte-compatible; adds only `frame_partial()`, which `peer_sim.py` doesn't
  need since it never originates partials itself.

### Location field

`VoiceMessage` (`core/Messages.kt`) has no `lat`/`lon` field today. The map panel and
`_extract_location()` are written to *use* one if/when it's added (checked shapes:
top-level `lat`/`lon`, `lat`/`lng`, or a nested `location: {lat, lon|lng}`) but degrade
to "no location data yet" otherwise — nothing breaks in the meantime.

### Future: ESP32 LoRa node over USB serial

Another agent is building `firmware/` for an ESP32 LoRa node the gateway could attach to
over USB serial, for the case where a phone is out of Wi-Fi range of every other phone but
in LoRa range of a relay. The assumption this gateway is written to (not yet implemented
here — no `firmware/` code exists yet to integrate against) is that **the serial link
reuses the same chunk framing BLE already uses** (see `docs/design.md` → "Wire framing":
Nordic-UART-style, each JSON `Frame` split into chunks with a 3-byte header
`[seq, index, count]`), rather than the TCP length-prefix framing this gateway speaks over
Wi-Fi. A serial transport module would therefore need its own chunk reassembler (mirroring
whatever `:transport`'s BLE path does) feeding the *same* `Gateway._on_frame()` dispatch —
the frame JSON shape and all logging/dashboard code is transport-agnostic already.
