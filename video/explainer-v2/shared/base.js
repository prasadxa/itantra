const tl = gsap.timeline({ paused: true });
const X = { expo: "expo.out", out: "power3.out", back: "back.out(1.7)", io: "power3.inOut" };
const up = (sel, t, d = 0.5, y = 60, extra = {}) => tl.fromTo(sel, { y, opacity: 0 }, { y: 0, opacity: 1, duration: d, ease: X.expo, ...extra }, t);
const pop = (sel, t, d = 0.4) => tl.fromTo(sel, { scale: 0.5, opacity: 0 }, { scale: 1, opacity: 1, duration: d, ease: X.back }, t);
const hide = (sel, t, d = 0.25) => tl.to(sel, { opacity: 0, duration: d, ease: "power1.in" }, t);
const off = (sel, t = 0) => tl.set(sel, { opacity: 0 }, t);
const slideIn = (sel, t, from = 1100, d = 0.5) => tl.fromTo(sel, { x: from, opacity: 1 }, { x: 0, duration: d, ease: X.expo }, t);
// swap kinetic headlines that share one zone: [[sel, start], ...] — each hides just before the next
const kinetic = (items, endT) => items.forEach(([sel, t], i) => {
  up(sel + " .line", t, 0.45, 80, { stagger: 0.09 });
  const out = i < items.length - 1 ? items[i + 1][1] - 0.18 : endT;
  if (out != null) hide(sel, out, 0.15);
});
const kenburns = (sel, t, d, from = 1.0, to = 1.18, origin = "50% 70%") => tl.fromTo(sel, { scale: from, transformOrigin: origin }, { scale: to, duration: d, ease: "none" }, t);
const wipeUp = (sel, t, d = 0.5) => tl.fromTo(sel, { clipPath: "inset(100% 0% 0% 0%)" }, { clipPath: "inset(0% 0% 0% 0%)", duration: d, ease: X.io }, t);
const wipeLeft = (sel, t, d = 0.5) => tl.fromTo(sel, { clipPath: "inset(0% 0% 0% 100%)" }, { clipPath: "inset(0% 0% 0% 0%)", duration: d, ease: X.io }, t);
const iris = (sel, t, at = "50% 40%", d = 0.6) => tl.fromTo(sel, { clipPath: `circle(0% at ${at})` }, { clipPath: `circle(130% at ${at})`, duration: d, ease: "power3.in" }, t);
const eq = (sel, t, dur, max = 1) => document.querySelectorAll(sel).forEach((b, i) => {
  const step = 0.17 + (i % 4) * 0.03;
  tl.fromTo(b, { scaleY: 0.15 }, { scaleY: (0.4 + ((i * 7) % 10) / 17) * max, duration: step, repeat: Math.max(1, Math.floor(dur / step) - 1), yoyo: true, ease: "sine.inOut" }, t + i * 0.03);
});
// typewriter: splits the element's text into spans once, then reveals them on the timeline
const type = (sel, t, cps = 28) => {
  const el = document.querySelector(sel);
  const chars = [...el.textContent];
  el.textContent = "";
  chars.forEach((c, i) => {
    const s = document.createElement("span");
    s.textContent = c;
    el.appendChild(s);
    tl.set(s, { opacity: 0 }, 0);
    tl.set(s, { opacity: 1 }, t + i / cps);
  });
  return t + chars.length / cps;
};
const stack = (prefix, times) => { // stacked spans #prefix0..n, show one at a time
  times.forEach((t, i) => { if (i) { tl.set("#" + prefix + (i - 1), { opacity: 0 }, t); } tl.set("#" + prefix + i, { opacity: i ? 0 : 1 }, 0); if (i) tl.set("#" + prefix + i, { opacity: 1 }, t); });
};
const drift = (sel, t, d, fromY = 140, rot = -3) => tl.fromTo(sel, { y: fromY, rotation: rot, opacity: 0 }, { y: 0, rotation: 0, opacity: 1, duration: 0.9, ease: X.expo }, t) && tl.fromTo(sel, { scale: 1 }, { scale: 1.05, duration: Math.max(0.5, d - 0.9), ease: "sine.inOut", immediateRender: false }, t + 0.9);
// word-lit captions: splits each .line (or the element) into word spans; all words start dim, each lights up at its spoken time
const litWords = (sel, times, dim = 0.55) => {
  const spans = [];
  document.querySelectorAll(sel + " .line").forEach((ln) => {
    const words = ln.textContent.trim().split(/\s+/); ln.textContent = "";
    const coral = ln.classList.contains("coral"); ln.classList.remove("coral"); // gradient text must sit on the word spans themselves
    const overlap = ln.hasAttribute("data-layout-allow-overlap") || ln.closest("[data-layout-allow-overlap]");
    words.forEach((w, i) => { const s = document.createElement("span"); s.className = coral ? "w coral" : "w"; if (overlap) s.setAttribute("data-layout-allow-overlap", ""); s.textContent = w + (i < words.length - 1 ? " " : ""); ln.appendChild(s); spans.push(s); });
  });
  spans.forEach((s, i) => {
    tl.set(s, { opacity: dim }, 0);
    const t = times[Math.min(i, times.length - 1)];
    tl.fromTo(s, { opacity: dim, y: 0 }, { opacity: 1, duration: 0.12, ease: "power2.out", immediateRender: false }, t);
    tl.fromTo(s, { y: 8 }, { y: 0, duration: 0.25, ease: X.back, immediateRender: false }, t);
  });
  return spans;
};
// the spoken URL: pop + underline draw at the moment "CallMissed dot com" is said (markup: <div class="cta-url big">callmissed.com<span class="ul"></span></div>)
const urlPop = (t) => {
  tl.set(".cta-url .ul", { scaleX: 0 }, 0);
  tl.fromTo(".cta-url", { scale: 1 }, { scale: 1.12, duration: 0.16, repeat: 1, yoyo: true, ease: "power2.out", immediateRender: false }, t);
  tl.fromTo(".cta-url .ul", { scaleX: 0 }, { scaleX: 1, duration: 0.5, ease: X.expo, immediateRender: false }, t + 0.05);
};
const ctaIn = (t, end) => {
  pop(".cta-lock", t + 0.3, 0.5);
  up(".cta-h1", t + 0.5, 0.5, 60);
  tl.fromTo(".cta-h2", { y: 90, opacity: 0, scale: 0.9 }, { y: 0, opacity: 1, scale: 1, duration: 0.55, ease: X.expo }, t + 0.75);
  up(".cta-chips", t + 1.1, 0.45, 40);
  pop(".cta-btn", t + 1.35, 0.5);
  up(".cta-free", t + 1.55, 0.4, 30);
  up(".cta-url", t + 1.7, 0.4, 30);
  tl.fromTo(".cta-btn", { scale: 1 }, { scale: 1.06, duration: 0.4, repeat: 3, yoyo: true, ease: "sine.inOut", immediateRender: false }, t + 1.95);
  hide("#brand", t, 0.2);
};
const finish = (END, ducks = []) => { // ducks: [[from, to], ...] where the bed drops under dialogue
  for (let i = 0; i < END * 12; i++) tl.set("#grain", { x: ((i * 37) % 60) - 30, y: ((i * 53) % 60) - 30 }, i / 12);
  const V = 0.15, D = 0.08;
  tl.set("#bgm", { volume: V }, 0);
  ducks.forEach(([a, b]) => {
    tl.fromTo("#bgm", { volume: V }, { volume: D, duration: 0.3, immediateRender: false }, a);
    tl.fromTo("#bgm", { volume: D }, { volume: V, duration: 0.3, immediateRender: false }, b);
  });
  tl.fromTo("#bgm", { volume: V }, { volume: 0.3, duration: 0.6, immediateRender: false }, END - 1.9);
  tl.fromTo("#bgm", { volume: 0.3 }, { volume: 0, duration: 0.8, ease: "power1.in", immediateRender: false }, END - 0.85);
  window.__timelines["main"] = tl;
};
