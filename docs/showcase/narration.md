# iTantra explainer — narration script (short cut, ~2 min total)

## 00_title
iTantra is an offline, multilingual voice walkie-talkie for India — speech becomes text, text
becomes speech, when the network is gone.

## 01_problem
Voice is heavy: a raw spoken sentence needs about two hundred fifty six kilobits per second, but
disaster links carry only a few hundred bits per second. And a text-only alert misses the many
people who cannot read it.

## 02_idea
So iTantra sends only the text of what was said — a couple hundred bytes — over Wi-Fi, Bluetooth,
or a LoRa bridge, and speaks it back on the other phone, in the listener's own language.

## 03_diagrams
Under the hood: on-device speech recognition, a self-healing link that falls back from Wi-Fi to
Bluetooth to a long-range LoRa bridge, and a full deployment reaching phones, relay nodes, and an
offline control room.

## 04_models
Every model is open source and fully offline: SraVaani speech recognition from ARTPARK and I I Sc,
and Indic-Mio and VITS Rasa text-to-speech from SPRING Lab I I T Madras and AI4Bharat.

## 05_efficiency
And it's built for real phones: under two percent idle CPU, well under a gigabyte of memory in the
LITE profile, and sentences as small as sixty two bytes on the wire.

## 06_demo_intro
Here it is on a real phone.

## 07_credits
iTantra — built for the moment the towers go silent. Smart India Hackathon twenty twenty six, ISRO
problem statement two six one seven three.
