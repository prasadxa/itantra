# iTantra explainer v2 — style research notes

Sources: 15 videos in `research/videos/` (720p). One 4x3 contact sheet per video in `research/sheets/`, plus 0.5 s samples of the first 10 s (`*-open10s.jpg`) for Ordinary Folk/Webflow, Cloudflare and Apple SOS.
Cut counts come from ffmpeg scene detection (threshold 0.35, whole video).

## 1. Reference table (primary set)

| Video | URL | Len | Why relevant | 3 techniques worth stealing |
|---|---|---|---|---|
| **Webflow: Age of No-code** (Ordinary Folk) `webflow-ordinaryfolk` | youtu.be/_qCBg_Wsr8M | 1:30 | Top-tier studio, dark → bright arc, **3 hard cuts in 90 s** | (1) Opens on 1 s of black, then a field of tiny horizontal "data dashes" builds out from one centre line (t=1.0–2.5 s) and gathers into one object. (2) Wireframe boxes with diamond vertex handles draw on, then fill solid blue (t=6.5–8.0 s): the "blueprint → built" transition. (3) Palette runs from ink to saturated blue to pastel, and the story ends on the biggest colour shift. |
| **What is Cloudflare?** `cloudflare-what-is` | youtu.be/XHvmX3FhTwU | 1:15 | Network explainer: packets, globe, routes. **1 hard cut** | (1) UI windows pop in on a stagger, about 0.1 s apart. Short pill-shaped "data dashes" slide in along rows. Charts build inside the windows: bars grow, a donut is drawn by trim path. (2) A camera move reframes everything flat as isometric and pulls it into a cluster (t=5–7 s). An orange brand-coloured ring swoops around the cluster, then a circular iris opens to a white scene (t=7.5–8 s). (3) Traffic is shown as dots with motion-blur tails on highways, and a globe is built from dotted halftone with arcs. |
| **Emergency SOS via satellite** (Apple) `apple-sos-satellite` | youtu.be/V35jHAkpUIk | 1:57 | Offline or emergency comms. Black background with one accent colour. **0 hard cuts** | (1) Real device UI floats on pure black with no frame chrome. The title sits left and the phone right, then the title fades and the phone slides to centre (t=6.5–7.5 s). (2) Concentric green rings pulse out from the UI to stand for the signal. A satellite-pointing dial is animated with the device itself. (3) Thin green line-art (trees, moon, stars) draws on as scene-setting. Faint topographic contour lines fill the background as texture. |
| **LoRa: How It Works** (Semtech) `lora-semtech` | youtu.be/qI4a9JHO2sc | 2:40 | Long-range radio explainer, flat 2D | (1) Radio shown as dashed concentric arcs that orbit and trim around an icon. (2) A giant counter rolls digit by digit next to a globe whose dots light up. (3) One circle scales into a scene, and one colour block is the transition between ideas (t=60–73 s). Icon-grid finale. |
| **Sarvam Studio** `sarvam-studio` | youtu.be/cpt87MDeRoE | 1:48 | Indian AI launch film, multilingual. 2 hard cuts | (1) Words resolve from a heavy Gaussian blur while rising slightly (word-stagger blur-in). Supporting lines trail in slightly later and softer. (2) Script glyphs (Devanagari, Tamil, Bengali…) float on soft rounded "petal" shapes, used as the language motif. (3) Large soft gradient washes (lilac→blue, lime) change per chapter. Product UI is shown as an unframed card with a gentle drift. |
| **Introducing Linear Agent** `linear-agent` | youtu.be/mRql2VJ99gM | 0:55 | Premium dark-UI film. 0 hard cuts | (1) The UI is laid on a 3D plane tilted about 25–35° with a very slow dolly and shallow depth of field. Out-of-focus edges fall to black. (2) Text types into an input and the answer list staggers in row by row, with status-coloured dots as the only colour. (3) A diagram of flat grey boxes and connector lines draws itself on the tilted plane. Ends on centred wordmark and tagline in white/grey type. |
| **Hello! UPI** (NPCI) `npci-hello-upi` | youtu.be/m8Qrje4G0fc | 1:25 | Indian voice-first payments (closest subject match). 12 hard cuts | (1) A particle wave tunnel around a phone is the "voice" visual. (2) The same phrase in Hindi and in English appears side by side. (3) Saffron/orange text cards overlay the footage. *Mostly a warning*: stock-photo montage and subtitle bars read as government-generic. |
| **UPI 123PAY** (NPCI) `npci-upi-123pay` | youtu.be/OC0me95QWX8 | 2:50 | Offline, feature-phone, rural India | (1) A device mock on the right shows step-by-step screens, with a numbered "Step 02" pill on the left. (2) A big-number stat ("40 crore+") over a photo mosaic. *Warning*: mixes too many styles. |

**Secondary or rejected:** `stripe-s700` (mostly live action; the useful parts are condensed caps over a flat map with route lines and pins), `gotenna-mesh` (live action; the useful part is the map with mesh arcs and pill labels at t=70–78 s), `google-gemini-era` (live-action montage; the useful parts are the white-on-black type-on and a sparkle glyph as the end card), `isro-ch3-curtain` (3D render: cinematic but not motion design; shows the orange/gold-on-space palette), `airtable-vidico` (clean SaaS UI cards on white, generic), `raycast-focus` (live-action film), `cellbroadcast-ea` (**anti-reference**: clip-art characters, bullet-point slides).

## 2. Recurring techniques, written as GSAP/SVG recipes

**Pacing.** The premium films have almost no hard cuts: Linear, Apple and LoRa have 0, Cloudflare 1, Webflow 3. The less polished NPCI film has 12 in 85 s. Everything connects through continuous transformation: an object from the last idea becomes the next idea. Each idea gets **4–7 s**: 0.6–1 s to build, 2.5–4 s to hold with ambient motion, 0.6–0.8 s to transform out. There is never a fully static frame; hold states still drift (scale 1.00→1.03, y ±6 px).

**Typography**
- *Blur-rise words* (Sarvam, Gemini): split into words, then `from {filter:'blur(12px)', opacity:0, y:18}`, 0.8 s, `power3.out`, stagger 0.06. The secondary line starts 0.25 s later at 60% opacity.
- *Mask slide* (Linear, Apple): each line sits in an `overflow:hidden` wrapper, `from {yPercent:110}`, 0.7 s, `expo.out`, stagger 0.08. Out: `to {yPercent:-110}`, 0.4 s, `power2.in`.
- *Counter roll* (LoRa): each digit is a vertical strip of 0–9 inside a mask. Tween `y` per digit with a 0.05 s offset, `power4.out`. Use tabular mono digits.
- *Typed input* (Linear, Apple): a caret blinks, characters appear at about 30 ms each with slight random jitter, then the send button pulses once.
- Titles are big and few: 1–5 words on screen at a time, left-aligned or centred. No bullet lists.

**Line work and shapes**
- *Trim-path draw*: SVG `stroke-dasharray = length`, tween `stroke-dashoffset` from length to 0. Use 1–2 px strokes. Then fill with a 0.3 s opacity cross-fade (Webflow wireframe → solid).
- *Vertex handles*: small diamonds or squares at path corners that pop in (`scale 0→1, back.out(3)`) just before the lines connect (Webflow).
- *Radio rings*: 3–4 concentric circles, `scale 0.4→1.6`, `opacity 0.8→0`, 1.6 s, stagger 0.4, repeat (Apple, LoRa). Dashed arcs rotate slowly (about 20°/s).
- *Data dashes*: rounded pills 4–8 px tall with random widths, sliding along horizontal lanes. Width and x are driven by `gsap.utils.random` (Webflow, Cloudflare).
- *Packet with tail*: a dot followed by a gradient streak (a `linear-gradient` from transparent to colour), moving along a path with `MotionPathPlugin`, `power1.inOut` (Cloudflare).

**Transitions (use in place of cuts)**
1. **Zoom-through / iris**: scale a circle or element up to fill the frame, and the next scene lives inside it (Cloudflare t=7.5 s, LoRa t=60 s). `clip-path: circle(r at x y)` animated 0→150%, 0.8 s, `expo.inOut`.
2. **Reframe to isometric**: the parent gets `rotateX(55deg) rotateZ(-45deg)` with scale 0.6 over 1.2 s, `power3.inOut`, which turns the flat UI into an object (Cloudflare).
3. **Match move**: the phone stays put and only its screen content changes (Apple). The phone then becomes a node in a network diagram.
4. **Colour-block wipe**: a solid shape sweeps across, and the next scene is revealed behind its trailing edge (LoRa, Webflow).
5. **Wireframe → fill**: the new scene draws on as lines over the old one, then fills solid.

**UI and phone presentation**
- Real UI and exact pixels, with no fake lorem. Either no device frame (Apple, Sarvam) or a minimal flat bezel. Tilt in 3D (Linear: `perspective:2000px; rotateX(20deg) rotateY(-18deg)`) with a slow dolly (`z` +80 px over the shot).
- Pull individual UI elements out of the screen as floating cards with a larger shadow and a 1.05 scale (a chat bubble, a language chip), then send them back.
- The focus moves with depth of field: blur non-focus layers by 4–8 px (`filter: blur`) and add a radial vignette to the frame edges.

**Data and diagrams**: nodes pop in with `back.out(2)`, then edges trim-path between them, then packets travel the edges. Charts grow from their baseline (`scaleY` with a bottom origin) with a stagger of 0.04. Stat numbers are always a counter roll, never a static figure.

**Easing**: builds use `expo.out` or `power3.out`, 0.6–0.9 s. Exits use `power2.in`, 0.35–0.5 s (exits are faster than entrances). Camera and reframe moves use `power3.inOut` or `expo.inOut`, 1.0–1.6 s. Pops use `back.out(1.7)`. No linear easing except for looping ambient rotation.

**Colour, texture, 3D**: one background tone and one accent per scene (Apple: black and green; Linear: charcoal and status dots; Cloudflare: navy, orange ring, cyan). Texture is subtle: topographic contours or a thin grid at 4–6% opacity, and 2–3% grain. Depth comes from parallax: 3–4 layers moving at 0.3×, 0.6× and 1× of the camera move. No real 3D engine is needed.

## 3. Do / Don't

**Do**
- Carry one object through the whole film (a line, a dot, the phone) and let it transform.
- Keep type big with 1–5 words per beat. Let the voice-over carry the detail.
- Use exact brand hex values, one accent per moment, and lots of negative space.
- Add restrained motion blur to fast elements (a streak or a duplicated ghost at 30% opacity).
- Offset overlapping animations (entrances overlap by 30–50%) so nothing starts in perfect sync.
- End on the wordmark plus a one-line promise, held 2.5 s or more.

**Don't (reads cheap or AI-generated)**
- Hard-cut slideshows, fade-to-black between every idea, or bullet-point cards (Cell Broadcast film).
- Clip-art characters, stock-photo collages, subtitle bars as design (NPCI).
- Glowing "cyber" gradients everywhere, lens flares, particle soup, neon on every edge.
- Everything easing in at once with identical timing. Default `ease-in-out` on everything.
- Generic icons that each sit in a coloured circle (LoRa's weakest frames), or emoji.
- Mixing styles (3D render, flat, live action) in one film. Pick one language.

## 4. Recommended direction for iTantra

**Go dark.** The reasons:
- The brand ink `#0E1013` / surface `#161920` fits a night or no-signal disaster story.
- Apple SOS and Linear show that premium, "serious tool" comms reads best as one saturated accent on near-black.
- Saffron and teal both glow on ink, while on white they look like a template.
- Brightness becomes a narrative tool. Hold one bright beat for the payoff ("connected"), as Webflow does, and make it a warm off-white `#F4F1EA` with the tricolour, not a fully white film.

**System**
- Background `#0E1013` with a faint grid or topographic contours at 5% and 2% grain. Cards use `#161920` with a 1 px `#ffffff14` border and 20 px radius.
- Saffron `#F97316` = the speaker, TX, action. Teal `#2DD4BF` = the listener, RX, the system replying. Never use both on the same element. When a colour changes, it tells you who is talking.
- Type: IBM Plex Sans Condensed SemiBold for display at 96–160 px, tight tracking (-1%), sentence case. IBM Plex Sans for supporting lines at 36–44 px, 70% white. IBM Plex Mono for data (latency ms, kbps, language codes `hi-IN`, byte counts) at 20–24 px, uppercase, +8% tracking. Rolling counters always use Mono.
- Camera: every shot pushes 1.00→1.06 over its duration. Reframes use `expo.inOut` over 1.2 s. The phone UI is tilted about 18° in 3D when it is "the product" and flat and centred when it is "the conversation".

**Signature motifs**
1. **The packet line**: a single 2 px saffron line with a bright head and a fading tail. It leaves the speaker's phone, travels along a trim-path arc (a mesh hop or radio link) and turns teal on arrival. It is the film's continuity thread and replaces cuts: every scene is entered by following the line. Radio rings (3 concentric circles, 1.6 s loop) pulse out of any phone that is transmitting.
2. **Wave ↔ glyph**: a live waveform (40–60 vertical bars in SVG, heights driven by noise) collapses bar by bar into characters. The text types out in the target script (Devanagari → Tamil → Bengali) with a blur-rise, then the characters dissolve back into bars on the receiving phone in teal. This shows STT → translate → TTS in one gesture without a diagram.
3. **Tricolour strip wipe**: a thin horizontal strip of saffron, white and green, about 6 px tall at rest, used as a signature under titles. At the key chapter transitions (problem → solution, solution → ending) it expands vertically to fill the frame. The three bands are staggered 0.08 s apart with `expo.inOut` over 0.9 s, then it retracts to reveal the next scene. Use it at most 3 times.

**Opening (suggested, about 8 s)**: 0.8 s of black. A single saffron dot appears centre frame and pulses once as a radio ring. The dot stretches into the packet line, which draws across the frame. The line slows and the waveform rises out of it. The title blur-rises, with the tricolour strip trimming on underneath it. Push the camera 1.00→1.05 throughout.
