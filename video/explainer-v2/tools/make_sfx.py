"""Synthesises the designed SFX pack into shared/sfx/ (deterministic; numpy + wave only)."""
import numpy as np, wave, pathlib
SR = 44100; out = pathlib.Path(__file__).parent.parent / "shared" / "sfx"; rng = np.random.default_rng(7)
def save(name, x, peak=0.85):
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    st = np.stack([x, x], 1) if x.ndim == 1 else x.copy()
    fade = min(len(st) // 8, int(SR * 0.02)); st[:fade] *= np.linspace(0, 1, fade)[:, None]; st[-fade:] *= np.linspace(1, 0, fade)[:, None]
    with wave.open(str(out / name), "wb") as w: w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((st * 32767).astype("<i2").tobytes())
def t(d): return np.arange(int(SR * d)) / SR
def bp_noise(d, f0, f1, q=6.0):  # noise through a swept resonant band-pass (state-variable filter)
    n = rng.standard_normal(int(SR * d)); f = np.geomspace(f0, f1, len(n)); low = band = 0.0; y = np.empty_like(n)
    for i in range(len(n)):
        g = 2 * np.sin(np.pi * min(f[i], SR / 6.5) / SR); high = n[i] - low - band / q; band += g * high; low += g * band; y[i] = band
    return y
def env(d, a, r, curve=2.0):
    x = t(d) / d; e = np.where(x < a, (x / a) ** curve, ((1 - x) / (1 - a)) ** r); return e
def pan(x, a, b):  # stereo sweep
    p = np.linspace(a, b, len(x)); return np.stack([x * np.cos(p * np.pi / 2), x * np.sin(p * np.pi / 2)], 1)
save("whoosh_up.wav", pan(bp_noise(0.45, 300, 5200) * env(0.45, 0.62, 2.2), 0.25, 0.75), 0.7)
save("whoosh_down.wav", pan(bp_noise(0.5, 5200, 260) * env(0.5, 0.3, 2.0), 0.75, 0.25), 0.7)
save("whoosh_long.wav", pan(bp_noise(0.9, 220, 6400) * env(0.9, 0.72, 3.0), 0.2, 0.8), 0.7)
for d, nm in ((1.2, "riser_short.wav"), (2.2, "riser_long.wav")):
    x = t(d); ph = 2 * np.pi * np.cumsum(np.geomspace(180, 1400, len(x))) / SR
    save(nm, (0.5 * np.sin(ph) + 0.25 * np.sin(2.01 * ph) + 0.6 * bp_noise(d, 800, 9000, 3.0) / 3) * (x / d) ** 2.4, 0.6)
x = t(0.9); ph = 2 * np.pi * np.cumsum(38 + 90 * np.exp(-x * 9)) / SR
save("sub_hit.wav", np.sin(ph) * np.exp(-x * 4.2) + 0.35 * rng.standard_normal(len(x)) * np.exp(-x * 60), 0.95)
def bell(fs, d, dec): x = t(d); return sum(a * np.sin(2 * np.pi * f * x) * np.exp(-x * dec * (1 + i * 0.6)) for i, (f, a) in enumerate(fs))
save("notif_ping.wav", bell([(1318.5, 1), (2637, 0.4), (3955, 0.15)], 0.7, 7), 0.6)
save("msg_pop.wav", np.sin(2 * np.pi * np.cumsum(520 + 700 * np.exp(-t(0.16) * 40)) / SR) * np.exp(-t(0.16) * 28), 0.7)
ch = np.concatenate([bell([(783.99, 1), (1568, 0.3)], 0.14, 9), bell([(1046.5, 1), (2093, 0.3)], 0.14, 9), bell([(1318.5, 1), (2637, 0.35)], 0.8, 5)])
save("success_chime.wav", ch, 0.6)
bp = np.concatenate([np.sin(2 * np.pi * 480 * t(0.12)), np.zeros(int(SR * 0.05)), np.sin(2 * np.pi * 620 * t(0.16))]); save("connect_beep.wav", bp, 0.5)
x = t(0.9); vb = np.sign(np.sin(2 * np.pi * 155 * x)) * 0.5 + np.sin(2 * np.pi * 155 * x); gate = ((x % 0.45) < 0.3).astype(float)
save("vibrate.wav", np.convolve(vb * gate, np.ones(40) / 40, "same"), 0.55)
save("tick.wav", rng.standard_normal(int(SR * 0.03)) * np.exp(-t(0.03) * 220), 0.5)
x = t(0.5); save("swell_pad.wav", (np.sin(2 * np.pi * 220 * x) + np.sin(2 * np.pi * 277.2 * x) + np.sin(2 * np.pi * 329.6 * x)) * np.sin(np.pi * x / 0.5) ** 2, 0.4)
print("sfx pack written")
