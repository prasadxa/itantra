# iTantra LoRa Bridge firmware

Firmware for an ESP32-S3 + SX1262 standalone bridge that relays iTantra app
packets between BLE (phone) and LoRa (India 865-867 MHz ISM band), so two
phones out of BLE/Wi-Fi range of each other can still talk through one or more
of these bridges (SIH PS 26173 "Build upgrade C").

**No hardware was available to build/test this firmware.** It has been
compiled and size-checked on a workstation only (see "Build result" below).
Nothing here has been flashed to, or run on, a real board. Do not assume the
BLE tie-break trick, the SX1262 pin map, or the mesh timing constants are
correct until verified on hardware - see "What is untested" at the end.

## What it does

- **BLE side**: acts as a GATT peripheral exposing the exact same
  Nordic-UART-style service the phone app expects
  (`transport/src/main/kotlin/org/itantra/transport/BleTransport.kt`,
  `FrameCodec.kt`, `BleReassembler.kt` in the app repo - read-only reference,
  not owned by this firmware): service `6E400001-...`, RX write
  `6E400002-...`, TX notify `6E400003-...`, chunked with the app's 3-byte
  `[seq, index, count]` header. Frame payloads are treated as opaque bytes
  (JSON today; a compact binary format is being added separately) - this
  firmware never parses them.
- **LoRa side**: fragments/reassembles those opaque frames into <=255-byte
  LoRa packets, retries unacknowledged messages, de-duplicates packets it has
  already seen, and rebroadcasts unseen packets with TTL-1 (flood mesh /
  store-and-forward groundwork for upgrade D).
- **Console**: a serial CLI for `sf`, `pwr`, `name`, `stats`, `help`.

## Why the bridge never dials out over BLE

The app's `BleTransport` runs both BLE central and peripheral roles per phone
and decides who dials by comparing `deviceId` strings advertised as BLE
service data: whichever device has the lexicographically **smaller**
`deviceId` becomes the dialer (central). This firmware only implements the
peripheral role - it advertises a deviceId of twelve `0x7E` (`~`) bytes,
which will be lexicographically larger than any phone-generated id, so the
phone always ends up dialing the bridge. This avoids needing a BLE central/
scan stack in firmware. **Unverified**: this assumes phone-generated
`deviceId`s never start with `~` or higher; worth confirming against the
app's actual ID generator.

## Wiring (SX1262 over SPI)

### Heltec WiFi LoRa 32 V3 (ESP32-S3, on-board SX1262 - no external wiring needed)

| Signal | GPIO | Notes |
|---|---|---|
| NSS/CS | 8 | |
| SCK | 9 | |
| MOSI | 10 | |
| MISO | 11 | |
| RESET | 12 | |
| BUSY | 13 | |
| DIO1 | 14 | interrupt (RX/TX done) |
| VEXT | 36 | drive LOW to power the LoRa/OLED rail |

These are the well-known default pins for this board; the radio is onboard,
so "wiring" is just USB-C to the workstation/board. Confirm against your
specific revision's schematic before flashing.

### LilyGO T3-S3 (ESP32-S3, onboard SX1262/SX1268)

| Signal | GPIO |
|---|---|
| NSS/CS | 7 |
| SCK | 5 |
| MOSI | 6 |
| MISO | 3 |
| RESET | 8 |
| BUSY | 34 |
| DIO1 | 33 |

LilyGO has shipped multiple pin revisions of this board under the same name -
**verify against your board's schematic/silkscreen before flashing**; PlatformIO
has no first-class board id for it, so `platformio.ini`'s `lilygo_t3s3` env
builds on the generic `esp32-s3-devkitc-1` board definition with these pins
supplied via `config.h`.

Antenna: always attach a matched 868 MHz antenna (or a dummy load) to the
SX1262 before powering the radio - transmitting into an open SMA connector can
damage the PA.

## Build

No hardware needed to build/size-check; PlatformIO downloads the ESP32-S3
toolchain (xtensa-esp32s3 + arduino-esp32 core) on first run.

```bash
cd firmware
uv tool install platformio          # one-time, or use pip/pipx
pio run -e heltec_wifi_lora_32_V3   # or: pio run -e lilygo_t3s3
```

### Build result

Compiled clean (0 warnings in this firmware's own source) for
`heltec_wifi_lora_32_V3` (ESP32-S3, 8 MB flash, 320 KB RAM) with PlatformIO
6.2.0 / espressif32 7.1.3 / arduino-esp32 4.20017 / NimBLE-Arduino 1.4.3 /
RadioLib 6.6.0:

```
RAM:   [=         ]   9.3% (used 30436 bytes from 327680 bytes)
Flash: [==        ]  17.2% (used 574757 bytes from 3342336 bytes)
```

`lilygo_t3s3` was not separately verified (no official PlatformIO board id for
that board - it reuses `esp32-s3-devkitc-1` with pins overridden, see Wiring
below); it should compile identically since it shares all application code
and only differs in `config.h` pin constants, but that wasn't checked here.

## Flash (requires hardware - not done here)

```bash
pio run -e heltec_wifi_lora_32_V3 -t upload --upload-port /dev/ttyUSB0
pio device monitor -b 115200
```

## Regulatory note (India, 865-867 MHz)

The 865-867 MHz band is de-licensed by India's WPC (Wireless Planning &
Coordination wing, DoT) for low-power devices, subject to a maximum **1 W
ERP** (effective radiated power). This firmware defaults SX1262 TX power to a
conservative **14 dBm** (module max ~22 dBm) and a fixed 865.0625 MHz carrier
inside the commonly-used 865.000-865.9375 MHz sub-band. There is no hard
regulatory duty-cycle cap in this band (unlike EU 868 MHz's 1%/10% rules),
but this firmware still keeps packets short and uses jittered retry/rebroadcast
delays to be a polite shared-channel citizen. Raising TX power via the `pwr`
console command is the operator's responsibility to keep antenna-gain-adjusted
ERP within the 1 W limit.

## Serial console

| Command | Effect |
|---|---|
| `sf <7-12>` | set LoRa spreading factor |
| `pwr <dBm>` | set LoRa TX power |
| `name <text>` | set node name (BLE advertises `iTantra-Bridge-<text>`) |
| `stats` | RSSI/SNR of last RX, last TX airtime, packet/retry/dedup counters |
| `help` | list commands |

## Airtime calculator (Semtech AN1200.13 formula)

BW 125 kHz, CR 4/5, explicit header, CRC on, 8-symbol preamble, DE (low data
rate optimize) auto-enabled for SF11/12. `src/airtime.h` implements this
formula in firmware for the `stats` command; the numbers below were computed
with the same formula offline for a median **80-byte** application payload
(a short STT sentence after JSON/BLE framing overhead):

| SF | Airtime (80 B payload) | Airtime (89 B = 80 B + 9 B LoRa header) | Throughput ceiling |
|---|---|---|---|
| SF7  | 143.6 ms | 153.9 ms | ~4.5 kbps / ~557 B/s |
| SF9  | 451.6 ms | 492.5 ms | ~1.4 kbps / ~177 B/s |
| SF12 | 3285.0 ms | 3612.7 ms | ~0.2 kbps / ~24 B/s |

"Throughput ceiling" is airtime-only (payload bytes / airtime), i.e. the
absolute best case with the channel saturated back-to-back, zero retries, and
zero mesh rebroadcast overhead - never the sustained real-world rate.

### vs. the app's text stream

STT output for one spoken sentence is on the order of tens to a couple
hundred bytes of text; once wrapped in the app's JSON `Frame` envelope it will
typically land in the 80-250 byte range this table covers. A single sentence
at **SF7** (143-320 ms airtime, see `airtime.h` for other sizes) is close to
real-time - comfortably faster than someone can speak the next sentence. At
**SF9** (~450-1000 ms) it is still usable for push-to-talk-style turns but
noticeably lagged. At **SF12** (3.3-7.2 s) it is push-to-talk over a very long
link budget only - a single sentence takes longer to arrive than it took to
speak, and continuous conversation is not realistic; SF12 is meant for
maximum range / minimum data rate scenarios (e.g. an occasional short status
packet), not a live voice-to-text stream. Retries, ACKs, and multi-hop
rebroadcast (each hop re-pays the full airtime cost) all multiply these
numbers further in a real mesh - SF7-SF9 is the practical range for anything
resembling live conversation; drop to SF10-SF12 only when range requires it
and accept the corresponding latency.

## Bill of materials (approximate, INR - verify current prices before buying)

| Item | Approx. price (INR) | Notes |
|---|---|---|
| Heltec WiFi LoRa 32 V3 (ESP32-S3 + SX1262) | ~1,300-1,800 | includes OLED, USB-C, battery connector |
| *or* LilyGO T3-S3 (ESP32-S3 + SX1262/SX1268) | ~1,400-2,000 | variant/vendor dependent |
| 868 MHz SMA antenna (matched to 865-867 MHz) | ~150-350 | a 915 MHz antenna will mistune the band - buy 868 MHz |
| USB-C cable | ~100-200 | data-capable, not charge-only |
| LiPo battery (optional, 1000-2000 mAh, JST-PH) | ~250-500 | both boards have a battery charge circuit |
| Enclosure (optional, 3D-printed or off-the-shelf) | ~100-400 | |
| **Total per bridge node** | **~1,900-3,200** | prices vary by vendor/import duty; a 3-hop test needs 3+ nodes |

## Test plan (requires hardware)

1. **Bench sanity check** (2 nodes, same room)
   - Flash both nodes, confirm `stats` shows the radio initialized (no FATAL
     on boot) and BLE advertises `iTantra-Bridge-XXXX`.
   - Connect one phone (running the app) to node A's BLE; send a PTT/text
     message; confirm node B's BLE-connected phone receives it, and vice
     versa. Confirms end-to-end frame integrity through fragmentation +
     reassembly on both the BLE and LoRa legs.
   - Watch `stats` on both nodes for RSSI/SNR (should be strong, short
     range) and confirm `duplicatesDropped`/`rebroadcasts` behave sensibly
     with only 2 nodes (rebroadcast count should stay low - nothing to
     relay to).

2. **Range test** (2 nodes, increasing distance/SF)
   - At SF7 (default-ish), walk/drive node B away from node A in an open
     line-of-sight area, sending periodic test messages, logging distance vs.
     `stats` RSSI/SNR and delivery success (ACK'd vs. retried-out).
   - Repeat at SF9 and SF12, expect materially longer range at the cost of
     the airtime/latency numbers above. Record the SF at which delivery
     starts failing consistently for each range bucket - this is the
     link-budget data the mesh-planning story needs.
   - Try an obstructed path (through 1-2 buildings/floors) at each SF to
     characterize the non-line-of-sight case relevant to disaster response.

3. **3-hop relay test** (4 nodes: A, B, C, D in a line; only A and D run a
   connected phone; B and C are relay-only bridges)
   - Place B and C so A cannot hear D directly (verify by disabling relaying
     - or simply by distance/obstruction - and confirming A<->D delivery
     fails with only A+D powered on).
   - Power on all four; send a message from A's phone; confirm it reaches
     D's phone, and check each hop's `stats.rebroadcasts` incremented
     exactly once per message (not more - that would indicate a
     dedup-cache bug or rebroadcast storm).
   - Measure end-to-end latency (should be roughly `airtime x hops x
     (1 + jitter budget)`, per the table above) and confirm `duplicatesDropped`
     rises when a node hears the same rebroadcast from multiple neighbors
     (expected in a flood mesh with no unicast addressing - see Limitations).
   - Kill power to B mid-conversation and confirm the message either fails
     to deliver (if C also can't hear A) or the ACK/retry logic on A retries
     up to `MAX_RETRIES` (3) before giving up - checks the retry/backoff
     path under a node failure, which is the realistic disaster-relief
     scenario this mesh exists for.

## Known limitations / future work

- **No destination addressing.** The LoRa packet header and the app's
  `Frame` format both lack a destination node id, so this is a flood mesh:
  every bridge that hears a message both consumes it locally (delivers to
  its own connected phone, if any) and rebroadcasts it. Fine for a
  broadcast/PTT-group walkie-talkie use case; point-to-point routing across
  a large mesh would need addressing added to both the app's `Frame` and
  this firmware's header (upgrade D territory).
- **No offline store-and-forward.** If a bridge has no phone connected when
  a reassembled message arrives, the message is dropped on that hop rather
  than queued for later delivery.
- **Single in-flight message per direction.** The LoRa link only tracks one
  outbound (awaiting-ACK) and one inbound (being-reassembled) message at a
  time; a busy mesh with concurrent senders will interleave and drop
  overlapping messages. Fine for a low-traffic PTT app, a real bottleneck
  under load.

## What is untested

Everything below is untested because no ESP32-S3/SX1262 hardware was
available in this environment - only `pio run` (compile + link + size-check)
was performed, never `pio run -t upload` or `pio device monitor`:

- The SX1262 pin maps in `config.h` for both boards (compiled cleanly, but
  wiring correctness can only be confirmed on real hardware).
- Whether `NimBLEDevice`/`NimBLEAdvertising` actually advertises, accepts a
  connection from, and successfully negotiates a large MTU with a real phone
  running the app (this was checked against the installed NimBLE-Arduino
  1.4.3 headers for API correctness only, not runtime behavior).
- The BLE deviceId tie-break trick (`README`'s "Why the bridge never dials
  out over BLE") - depends on the app's actual `deviceId` generator, which
  was not inspected beyond the transport files listed at the top of this doc.
- All SX1262 radio behavior: `begin()` actually locking onto 865.0625 MHz,
  RSSI/SNR readings, DIO1 interrupt timing, and whether blocking `transmit()`
  followed by `startReceive()` behaves as RadioLib's examples suggest under
  this firmware's specific interrupt/service-loop structure.
- The mesh logic under real RF conditions: ACK/retry timing and
  `ACK_TIMEOUT_MS`/`RETRY_JITTER_MS` defaults, dedup-cache correctness with
  real duplicate/out-of-order packets, and TTL rebroadcast behavior beyond
  what's reasoned about in code comments.
- Actual airtime numbers (the table above is computed from the Semtech
  formula, not measured on air).
- Everything in the Test plan section - all three tests require hardware.
