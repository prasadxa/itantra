# iTantra explainer v2 — build brief (read fully before writing anything)

Project: `/Users/apple/Project/BE_Project/sih/video/explainer-v2/`. A ~115 s, 16:9 (1920×1080) motion-graphics explainer for
**iTantra**, an offline multilingual voice walkie-talkie Android app (SIH 2026, ISRO problem statement 26173). Audience: ISRO
scientists and hackathon reviewers. The owner asked for an **After Effects–style** film: polished motion design, not a slideshow.
It is built as 9 chapter compositions (`reels/cNN-*/reel.html`, Hyperframes = HTML + one paused GSAP timeline) that are rendered
and concatenated; the score is laid under the whole film later. Each chapter is timed to its finished narration.

## Read first
1. `research/STYLE-NOTES.md` — the visual research (15 reference films). Follow its recipes: transform instead of cut, 4–7 s per
   idea, entrances `expo.out`/`power3.out` 0.6–0.9 s, exits faster `power2.in` 0.35–0.5 s, camera `power3.inOut`, overlap entrances
   30–50 %, never a frozen frame (camera push 1.00→1.05–1.06 over each chapter), 1–5 words of big type at a time.
2. `research/sheets/apple-sos-satellite.jpg`, `linear-agent.jpg`, `cloudflare-what-is.jpg`, `lora-semtech.jpg` — LOOK at them.
3. `reels/c01-silence/reel.html` — the **reference chapter** (built and approved). Copy its skeleton exactly: head (gsap, film.css),
   `#root` + one `section.scene.clip`, `.contours` / `.cam` / `.vignette` / `.grain` layers, `<!--@AUDIO-->`, then
   `<script src="assets/film.js">`, your timeline, and the inline registration line
   `window.__timelines = window.__timelines || {}; window.__timelines["main"] = tl;` as the last statement.
   Put a cue table comment at the top of your `<style>` (word → time → event) like c01.
4. `shared/film.css` + `shared/film.js` — the kit. Tokens, type classes (`.h1 .h2 .h3 .sub .mono .num .indic`), `.card`, `.chip`,
   `.tri`, `.triwipe`, `.phone`, `.ring`, `svg.draw`, and helpers: `words blurRise maskUp maskOut fadeIn fadeOut pop push move drift
   draw undraw rings packet count bars eq flat triDraw triWipeIn triWipeOut enter leave rng`. Read the source; extend locally in
   your reel if needed (do NOT edit film.js/film.css — other builders use them at the same time; put chapter-specific CSS/JS in
   your reel.html). Do NOT inline the reel `BASE_JS` block (it redeclares helpers).

## Look (non-negotiable)
- Dark: ink `#0E1013`, faint topographic contours + grain (already in the skeleton). Cards `#161920`, 1 px `rgba(255,255,255,.08)` border, 20 px radius.
- **One accent per moment. Saffron `#F97316` = speaker / transmit / action. Teal `#2DD4BF` = listener / receive / system reply.**
  Never both on one element; a colour change tells who is talking. Alert red `#EF4444` only in c07 for the ALERT.
- Line art (1.5–3 px strokes that draw on with `draw()` then optionally fill) like c01's tower and phone. The phone style is c01's
  (rounded rect outline 330×660, `rx 52`, stroke `--fg` 3 px, dark gradient screen inside).
- Type: `.h1/.h2` IBM Plex Sans Condensed, supporting `.sub` Plex Sans 70 % white, data in `.mono`/`.num` Plex Mono.
  Indian scripts use `.indic` (Noto fonts are loaded for all 10 scripts). Big type, few words, left-aligned or centred.
- Signature motifs: (1) the **packet line** — a 2–3 px saffron line/dot with a tail that travels between phones and turns teal on
  arrival; (2) **wave ↔ glyph** — waveform bars (`bars()`/`eq()`) collapse into text and back; (3) **radio rings** (`rings()`);
  (4) the **tricolour strip** `.tri` under titles; the full-frame `.triwipe` is reserved for c03 (problem → solution) and c09 (end).
- Forbidden: emoji, stock icons in coloured circles, neon glow everywhere, particle soup, bullet lists, hard cuts inside a chapter,
  fade-to-black mid-chapter, lens flares, fake dashboards, anything that looks like a template slide. No third-party logos.

## Continuity (chapters must join seamlessly)
Each chapter STARTS on the exact frame the previous one ENDS on (the frames are listed per chapter below). Positions are in the
1920×1080 frame. Start state can be set with `tl.set(..., 0)`; do not fade in from nothing unless the chapter says so.

## Claims (hard rules)
Only these facts. No translation claims (the app speaks the message in the language it was spoken; translation is NOT built).
- 10 languages: Hindi, Bengali, Marathi, Telugu, Tamil, Gujarati, Kannada, Malayalam, Odia, English — on-device STT and TTS.
- Models: SraVaani-1.0 speech recognition (ARTPARK, IISc Bengaluru, MIT licence); Indic-Mio TTS (SPRING Lab, IIT Madras,
  Apache-2.0); VITS Rasa TTS (AI4Bharat, CC-BY-4.0); Silero VAD (MIT). Everything offline on the phone.
- Links: Wi-Fi LAN first; Wi-Fi Direct after 4 s and Bluetooth LE after 8 s if no Wi-Fi. LoRa bridge: ESP32-S3 + SX1262, 865–867 MHz,
  firmware compiled, NOT yet flashed → say/show "ready for", never "tested"/"km range proven".
- Binary frame (bytes): ver·type 1, flags 1, message id 4, sender 2, lang·emo 1, TTL 1, timestamp 6, codec 1, compressed text ≈40–75;
  +11 B location on SOS, +64 B Ed25519 signature on ALERT, +20 B nonce and tag when encrypted. **Median 62 B per sentence**
  (unit test over 310 FLEURS sentences; JSON would be 524 B). Encryption: X25519 + ChaCha20-Poly1305, Ed25519-signed alerts, after QR pairing.
- Bandwidth: raw audio 256 kbps; Opus ≈12 kbps; LoRa SF12 ≈250 bps; iTantra ≈165 bps (62 B per 3 s sentence).
- Alerts: alarm stream, full volume, exclusive audio focus, vibration. SOS: 6 one-tap templates, GPS/NavIC location read aloud.
- Measured (vivo I2202, Snapdragon 870 unless noted): speech-recognition real-time factor 0.05 (≈20× faster than speech);
  idle CPU 0.3–1.6 % in push-to-talk; LITE profile memory 688 MB; message→ACK over Wi-Fi 41 ms (OnePlus 12).
Anything else: leave it out.

## Files and mechanics
- `reels/<id>/script.json` — narration is FINAL (voice, text, `at`, `gap`, `tail` fixed). You may only fill `"sfx"`:
  `[file, "time expr", volume, optional "max length expr"]` from `shared/sfx/` (whoosh_up/down/long, tick, click2.ogg,
  msg_pop, notif_ping, sub_hit, swell_pad, success_chime, connect_beep, card-slide-1.ogg, chip-lay-1.ogg, keypress-00N,
  select_008.ogg, rollover2.ogg, impactSoft_medium_001.ogg, riser_short). Keep sfx subtle (0.12–0.35), 4–10 per chapter,
  never on the first syllable of a line.
- `reels/<id>/vo/words.txt` — per-word start times inside each line (`word@sec`). Land visuals ON the spoken word: `@[n5+2.95]`.
- `build.py <id>` → `out/<id>/index.html`. `@[expr]` = line start (`n5`), end (`n5_e`), length (`n5_d`), `END`.
- Assets: `assets/film.css`, `assets/film.js`, `assets/fonts/*`, `assets/img/contours.svg|grain.png|itantra-logo.png`,
  your own `reels/<id>/img/*` → `assets/img/*`.
- Hyperframes rules: deterministic only (no Math.random/Date/CSS animations/`repeat:-1`); re-used targets need `fromTo(...,
  {immediateRender:false})`; a timed `<video>` must be a DIRECT child of `#root` (not inside the section) with `class="clip"`,
  `data-start`, `data-duration` ≤ clip length, `data-track-index` lower than the section's, `muted playsinline`, and the section
  above it transparent where the video shows (see c07 notes). `data-layout-ignore` on decorative layers,
  `data-layout-allow-overflow` on things that move off-frame, `data-layout-allow-overlap` on text that replaces text.
  Keep text contrast ≥ 4.5:1 (`--fg2` or brighter for small text; `--fg3` only for ≥ 40 px or decorative).

## Verify loop (mandatory, repeat until clean)
```
cd /Users/apple/Project/BE_Project/sih/video/explainer-v2 && python3 build.py <id>
cd out/<id> && npx -y hyperframes@latest check            # must end "Check passed" (ignore nested_structure_needs_subcomposition)
rm -rf snapshots && npx -y hyperframes@latest snapshot --at <8–12 times covering every beat, incl. 0.1 and END-0.05> --no-end
```
Then make a contact sheet (PIL, 3 columns of 640×360) and READ it. Judge each frame against the Apple SOS / Linear references:
premium? readable? one accent? nothing overlapping or clipped? start frame = previous chapter's end frame? end frame = the agreed
hand-off? Fix and repeat. Do NOT run `render`.

## Report back (short)
END length; what is on screen at start, each spoken beat, and the end frame; every claim shown; sfx added; open issues.

---
## Chapters (narration and word times are in each `reels/<id>/vo/words.txt`)

### c02-heavy — n2 + n3 (END 16.81)
n2 "A spoken sentence is heavy: two hundred and fifty-six kilobits a second, as raw audio." n3 "But the links that survive a
disaster, Bluetooth, a crowded hotspot, a long-range radio, carry only a few hundred bits."
- START: c01's end — ink, the c01 phone outline (left 1180, top 170, 330×660, dim lock screen) with a saffron dot at (1345, 560).
- The dot opens into a saffron waveform (`bars`, ~60 bars) that grows out of the phone and the camera pulls so the waveform spans
  most of the frame (phone fades away). On "heavy" the bars swell tall and dense. On "256" a big mono counter rolls to
  **256 kbps** (label "RAW AUDIO · 16 kHz"). The waveform is visibly a thick band of data.
- n3: three thin horizontal pipes draw on (line art) to the right, labelled on their words: BLUETOOTH · CROWDED HOTSPOT ·
  LONG-RANGE RADIO, each with a mono capacity tag "~ few hundred bps". The thick waveform slides toward them and jams at the
  entrance (compresses, stutters, cannot pass) — the idea "voice does not fit". On "a few hundred bits" a small counter
  "≈ 250 bps" appears next to the radio pipe.
- END: everything collapses to ONE thin horizontal saffron line across the centre (y 540, x 360→1560, 3 px) on ink. That line is c03's start.

### c03-idea — n4 + n5 (END 16.09)
n4 "So iTantra doesn't send your voice. It sends what you said." n5 "Speech becomes text on your phone. A few dozen bytes cross
the link. And the other phone speaks it aloud, in ten Indian languages."
- START: the thin saffron line across the centre (end of c02).
- n4: the line becomes the tricolour: `.triwipe` full-frame wipe in and out (this is one of only two uses) revealing the title
  **iTantra** (big `.h1`, with the logo `assets/img/itantra-logo.png` 120 px left of it) and a `.tri` strip under it. On
  "doesn't send your voice" a small waveform next to the title is struck through (a saffron strike line); on "what you said" a
  line of Devanagari text blur-rises in its place: "बाढ़ का पानी बढ़ रहा है" (the flood water is rising).
- n5 (the core mechanism, two phones left and right in c01's line-art phone style, ~0.75 scale): "Speech becomes text" → the
  left phone shows saffron waveform bars that collapse into that Hindi text inside the phone; "a few dozen bytes cross the
  link" → the text compresses into a small saffron chip "≈ 62 B" that travels along an arc (packet line) to the right phone,
  turning teal on arrival; "speaks it aloud" → the right phone shows the same text in teal, which dissolves into teal waveform
  bars + small radio rings. "ten Indian languages" → a row of the 10 language names in their own scripts blur-rises under the
  phones: हिन्दी · বাংলা · मराठी · తెలుగు · தமிழ் · ગુજરાતી · ಕನ್ನಡ · മലയാളം · ଓଡ଼ିଆ · English (use `.indic`).
- END: the language row fades, the left phone fades, the right phone glides to the centre (left 795, top 210, 330×660 at scale 1)
  with an empty dark screen. That centred phone is c04's start.

### c04-device — n6 + n7 (END 17.55)
n6 "Everything runs on the phone itself. No cloud. No internet." n7 "Open speech models from IISc, IIT Madras and AI4Bharat
listen, and speak, in Hindi, Tamil, Bengali and seven more."
- START: one phone centred (left 795, top 210, 330×660), dark screen.
- n6: a processor/brain glyph (line art, not a stock icon) lights up inside the phone in saffron; a line-art cloud above-right
  draws on and gets a saffron strike on "No cloud"; a line-art globe draws on and gets struck on "No internet". Big type
  "On the phone. Offline." left.
- n7: three model cards float OUT of the phone (like UI elements lifting off a screen, with depth and slight 3D tilt) and settle
  around it, each on its spoken institute: **SraVaani-1.0** "speech recognition" · ARTPARK, IISc Bengaluru; **Indic-Mio** "speech"
  · SPRING Lab, IIT Madras; **VITS Rasa** "speech" · AI4Bharat. Card labels in mono ("LISTENS" on SraVaani in saffron, "SPEAKS" on
  the TTS cards in teal). On "Hindi, Tamil, Bengali and seven more" three script chips pop (हिन्दी, தமிழ், বাংলা) then "+7".
  A small mono footnote "open models · on-device · no cloud API".
- END: cards fly back into the phone; the phone shrinks and moves to the LEFT as a network node: phone at scale 0.42 centred at
  (330, 540). That small phone is c05's start (c05 adds the right-hand phone).

### c05-link — n8 (END 9.78)
n8 "If there's no Wi-Fi, it finds Wi-Fi Direct. Then Bluetooth. And the same packets are ready for a LoRa radio bridge."
- START: small phone node at (330, 540) at scale 0.42 of c01's phone.
- A second small phone node at (1590, 540). Three route arcs between them, stacked (top/middle/bottom), each with a mono label:
  "WI-FI LAN" (on "no Wi-Fi": it draws dashed then breaks with a small saffron ×), "WI-FI DIRECT" (draws on "finds Wi-Fi Direct",
  a packet travels), "BLUETOOTH LE" (draws on "Bluetooth", a packet travels). Small mono timers "+4 s", "+8 s" beside them (the
  real fallback delays). On "LoRa radio bridge" the view pulls back (camera) and a line-art LoRa node (small box + antenna, label
  "ESP32 · LoRa 865 MHz" and a chip "firmware ready") appears on the far right with dashed long-range arcs and radio rings.
- END: the camera zooms into the travelling packet dot on the Bluetooth route until the saffron dot fills the centre as a
  64 px dot at (960, 540) on ink. That dot is c06's start.

### c06-bytes — n9 (END 9.65)
n9 "Each sentence packs into a compact binary frame, a median of sixty-two bytes, encrypted and signed once phones are paired."
- START: saffron dot 64 px at (960, 540) on ink.
- The dot stretches horizontally into the binary frame strip (like the website's "one message on the wire"): segments build
  left→right with field names + byte counts in mono (ver·type 1 · flags 1 · message id 4 · sender 2 · lang·emo 1 · TTL 1 ·
  timestamp 6 · codec 1 · compressed text ≈40–75, the text segment saffron-tinted). Above: big rolling counter **62 B** landing
  on "sixty-two bytes", with a small mono note "median · 310 sentences · unit test" and "JSON: 524 B" struck/dimmed for contrast.
  On "encrypted" a line-art lock clicks shut over the text segment (label "ChaCha20-Poly1305"); on "signed" a seal stamp
  (label "Ed25519") attaches; on "paired" a small QR glyph + "X25519 pairing".
- END: the strip compresses back into a single saffron dot which drops to (960, 540) at 24 px and turns ALERT red. c07 starts on that red dot.

### c07-alert — n10 (END 10.13)
n10 "Alerts cut through at full volume. And one tap sends an SOS, with your location, read aloud on the other side."
- START: a red (#EF4444) 24 px dot at (960, 540) on ink.
- The dot bursts into red radio rings and a phone frame (minimal flat bezel, `.phone` 360×800 style, slightly tilted in 3D
  ~12–18°) showing the REAL screen recording `assets/img/03_alert.mp4` (15.9 s, 576×1280, a Hindi flood ALERT arriving and
  being spoken). Big type left: "Alerts cut through." + mono chips "ALARM STREAM · FULL VOLUME · VIBRATES" on "full volume".
- On "one tap sends an SOS": a second phone slides in (right) with `assets/img/05_sos.mp4` (18.0 s, SOS sheet: location +
  templates). Start that clip at ~2 s in (use a second element or `data-media-start` if supported; otherwise start at 0).
  On "location" a chip "GPS / NavIC · 18.49 N, 73.84 E" appears; on "read aloud" teal rings from the receiving side.
- Videos: two `<video class="clip">` elements as direct children of `#root` on track 0 with the section on track 1 and a
  TRANSPARENT background where the videos sit (put the ink background on a separate div inside the section that has two
  rectangular holes, or place the phone frames' bezels in the section and let the video show through). Size/position the
  videos to sit exactly inside the phone frames (use CSS on the video element: left/top/width/height/border-radius). Check
  frames carefully: the video must be visible and aligned.
- END: both phones fade/slide out; ink with ONE teal dot at (960, 540). c08 starts there.

### c08-proof — n11 (END 9.69)
n11 "On a real phone, recognition runs twenty times faster than speech, and idle CPU stays under two percent."
- START: teal dot (960, 540) on ink.
- Three big stats as rolling counters, each landing on its words, in a clean horizontal row with thin dividers and mono labels:
  **20×** "faster than speech" (sub-label "real-time factor 0.05"); **< 2 %** "idle CPU, push-to-talk" (sub "0.3–1.6 %");
  **688 MB** "memory, LITE profile" (appears on the last words as a third quiet stat). A mono footnote "vivo I2202 · Snapdragon 870 ·
  measured with the app's own logging". Add a subtle speed motif for "20× faster": a waveform line (speech) and a text line
  racing, the text finishing far ahead.
- END: stats fade out; the tricolour `.tri` strip (6 px, 480 px wide) draws on at the centre (y 560). c09 starts there.

### c09-end — n12 + n13 (END 17.66)
n12 "iTantra. Speak in your language, and be heard, even when the towers go silent." n13 "Smart India Hackathon twenty
twenty-six. ISRO problem statement two six one seven three."
- START: tricolour strip 480×6 at the centre (y 560) on ink.
- On "iTantra": the strip expands into the full-frame `.triwipe` (second and last use) and retracts to reveal the end card:
  logo (`assets/img/itantra-logo.png`, 160 px) + **iTantra** `.h1`, tagline blur-rising on its words: "Speak in your language.
  Be heard — even when the towers go silent." A callback to c01: the line-art tower (from c01, small, bottom-left, grey) with the
  phone beside it — this time a saffron packet line leaves the phone and radio rings pulse teal (connected).
- n13: mono lines: "SMART INDIA HACKATHON 2026 · ISRO · PS 26173"; then "Open models by IISc · IIT Madras · AI4Bharat" and the
  URL "itantra-106.pages.dev" (teal). Hold the final card still (just the camera drift) for the last 2.5 s. End on the card (no fade-out).
