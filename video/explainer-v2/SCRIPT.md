# iTantra explainer v2 — script (English, 16:9, ~100 s, one narrator)

Audience: SIH 2026 reviewers (ISRO, PS 26173) and anyone opening the reviewer site.
Tone: clear, confident, warm; a documentary explainer, not an ad. Measured pace (~145 wpm), slight gravity in the opening.
Claims: only what the website marks Built, with the same caveats. No "in the listener's language" (translation is planned,
not built). 62 B is a median from a unit test ("a median of"). LoRa bridge firmware is compiled, not flashed ("ready for").

| Chapter | Line id | Narration (`text`; `say` differs only where marked) | On screen (idea) |
|---|---|---|---|
| c01-silence | n1 | When a flood takes the towers down, your phone still works. The network doesn't. | Signal bars drain to zero; a tower icon cuts out; "No service". |
| c02-heavy | n2 | A spoken sentence is heavy: two hundred and fifty-six kilobits a second, as raw audio. | Waveform fills the frame; 256 kbps counter. |
| c02-heavy | n3 | But the links that survive a disaster, Bluetooth, a crowded hotspot, a long-range radio, carry only a few hundred bits. | Three thin pipes; the waveform can't fit. |
| c03-idea | n4 | So iTantra doesn't send your voice. It sends what you said. | Title card: iTantra wordmark + tricolour wipe. |
| c03-idea | n5 | Speech becomes text on your phone. A few dozen bytes cross the link. And the other phone speaks it aloud, in ten Indian languages. | Waveform → Devanagari text → bytes travel → text → waveform; 10 scripts. |
| c04-device | n6 | Everything runs on the phone itself. No cloud. No internet. | Phone outline; cloud crossed out. |
| c04-device | n7 | Open speech models from IISc, IIT Madras and AI4Bharat listen, and speak, in Hindi, Tamil, Bengali and seven more. | Model cards: SraVaani / Indic-Mio / VITS Rasa + credits. |
| c05-link | n8 | If there's no Wi-Fi, it finds Wi-Fi Direct. Then Bluetooth. And the same packets are ready for a LoRa radio bridge. | Link fallback path draws on; LoRa node at the end. |
| c06-bytes | n9 | Each sentence packs into a compact binary frame, a median of sixty-two bytes, encrypted and signed once phones are paired. | Frame strip assembles field by field; lock + signature. |
| c07-alert | n10 | Alerts cut through at full volume. And one tap sends an SOS, with your location, read aloud on the other side. | Real phone clips: flood alert, SOS sheet. |
| c08-proof | n11 | On a real phone, recognition runs twenty times faster than speech, and idle CPU stays under two percent. | Big numbers: 0.05 RTF, 0.3–1.6 % CPU, 688 MB LITE. |
| c09-end | n12 | iTantra. Speak in your language, and be heard, even when the towers go silent. | Logo, tagline, tricolour. |
| c09-end | n13 | Smart India Hackathon twenty twenty-six. ISRO problem statement two six one seven three. | "SIH 2026 · ISRO · PS 26173" + itantra-106.pages.dev |

Word count ≈ 215 → about 90–100 s of narration plus breathing room and the phone-clip beat.
