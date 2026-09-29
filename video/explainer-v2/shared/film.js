/* iTantra explainer v2 — shared motion kit. Load after gsap; do NOT also inline the reel BASE_JS block (it redeclares pop/drift). Creates `tl`.
   Deterministic only: no Math.random / Date / repeat:-1. Use rng(seed) for scatter. Recipes from research/STYLE-NOTES.md. */
const tl = gsap.timeline({ paused: true });
const E = { in: "expo.out", out: "power2.in", cam: "power3.inOut", pop: "back.out(1.7)", soft: "sine.inOut", p3: "power3.out" };
const rng = (seed) => () => ((seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296);
const $ = (s) => (typeof s === "string" ? document.querySelector(s) : s);
const $$ = (s) => (typeof s === "string" ? [...document.querySelectorAll(s)] : [s]);

/* split an element's text into .w word spans (keeps inner <b>/<span class> by splitting text nodes only) */
const words = (sel) => {
  const out = [];
  $$(sel).forEach((el) => {
    if (el.dataset.split) { out.push(...el.querySelectorAll(".w")); return; }
    el.dataset.split = "1";
    const walk = (node) => [...node.childNodes].forEach((n) => {
      if (n.nodeType === 3) {
        const frag = document.createDocumentFragment();
        n.textContent.split(/(\s+)/).forEach((p) => {
          if (!p) return;
          if (/^\s+$/.test(p)) { frag.appendChild(document.createTextNode(p)); return; }
          const s = document.createElement("span"); s.className = "w"; s.textContent = p; frag.appendChild(s);
        });
        n.replaceWith(frag);
      } else if (n.nodeType === 1 && !n.classList.contains("w")) walk(n);
    });
    walk(el); out.push(...el.querySelectorAll(".w"));
  });
  return out;
};

/* blur-rise words (Sarvam/Gemini): blur 12->0, y 18->0, stagger 0.06. `times` = optional per-word start times (spoken words) */
const blurRise = (sel, t, st = 0.06, d = 0.8, times = null) => {
  const ws = words(sel);
  ws.forEach((w, i) => tl.fromTo(w, { opacity: 0, y: 18, filter: "blur(12px)" },
    { opacity: 1, y: 0, filter: "blur(0px)", duration: d, ease: E.p3 }, times ? times[Math.min(i, times.length - 1)] : t + i * st));
  return ws;
};
/* mask slide (Linear/Apple): each .mask > .ln slides up from yPercent 110 */
const maskUp = (sel, t, st = 0.08, d = 0.7) => tl.fromTo(`${sel} .ln`, { yPercent: 110 }, { yPercent: 0, duration: d, ease: E.in, stagger: st }, t);
const maskOut = (sel, t, d = 0.4) => tl.to(`${sel} .ln`, { yPercent: -110, duration: d, ease: E.out, stagger: 0.04 }, t);
const fadeIn = (sel, t, d = 0.7, from = { y: 24 }) => tl.fromTo(sel, { opacity: 0, ...from }, { opacity: 1, x: 0, y: 0, scale: 1, duration: d, ease: E.p3 }, t);
const fadeOut = (sel, t, d = 0.4) => tl.to(sel, { opacity: 0, duration: d, ease: E.out }, t);
const pop = (sel, t, d = 0.5, st = 0) => tl.fromTo(sel, { opacity: 0, scale: 0.4 }, { opacity: 1, scale: 1, duration: d, ease: E.pop, stagger: st }, t);

/* camera push: every shot drifts 1.00 -> 1.06 */
const push = (sel, t, d, from = 1, to = 1.06) => tl.fromTo(sel, { scale: from }, { scale: to, duration: d, ease: "none", immediateRender: false }, t);
const move = (sel, t, d, to) => tl.to(sel, { ...to, duration: d, ease: to.ease || E.cam }, t);
/* ambient hold drift so nothing is ever frozen */
const drift = (sel, t, d, amp = 6) => tl.fromTo(sel, { y: 0 }, { y: -amp, duration: d, ease: E.soft, immediateRender: false }, t);

/* trim-path draw for SVG strokes (Webflow): works on path/line/circle/polyline */
const draw = (sel, t, d = 0.9, st = 0.06, ease = E.cam) => $$(sel).forEach((p, i) => {
  const L = p.getTotalLength ? p.getTotalLength() : 1000;
  tl.set(p, { strokeDasharray: L, strokeDashoffset: L, visibility: "hidden" }, 0);   // hidden until its draw starts (no round-cap dot)
  tl.set(p, { visibility: "visible" }, t + i * st);
  tl.to(p, { strokeDashoffset: 0, duration: d, ease }, t + i * st);
});
const undraw = (sel, t, d = 0.5) => $$(sel).forEach((p) => { const L = p.getTotalLength ? p.getTotalLength() : 1000; tl.to(p, { strokeDashoffset: -L, duration: d, ease: E.out }, t); });

/* radio rings: n rings pulse out of a point for `until` seconds (finite repeats) */
const rings = (sel, t, until, period = 1.6, stag = 0.4, from = 0.35, to = 1.7) => $$(sel).forEach((r, i) => {
  const reps = Math.max(0, Math.floor((until - t - i * stag) / period) - 1);
  tl.fromTo(r, { scale: from, opacity: 0.85 }, { scale: to, opacity: 0, duration: period, ease: "power1.out", repeat: reps, immediateRender: false }, t + i * stag);
  tl.set(r, { opacity: 0 }, 0);
});

/* packet line: a dot (+ optional tail element) travels along an SVG path; colour can flip on arrival */
const packet = (pathSel, dotSel, t, d, ease = "power1.inOut", tailSel = null) => {
  const p = $(pathSel), dot = $(dotSel), L = p.getTotalLength(), o = { v: 0 };
  const svg = p.ownerSVGElement, vb = svg.viewBox.baseVal, box = svg.getBoundingClientRect ? null : null;
  const sx = svg.width.baseVal.value / (vb.width || svg.width.baseVal.value), sy = svg.height.baseVal.value / (vb.height || svg.height.baseVal.value);
  const ox = parseFloat(svg.style.left || 0), oy = parseFloat(svg.style.top || 0);
  const place = () => {
    const pt = p.getPointAtLength(o.v * L);
    const x = ox + (pt.x - (vb.x || 0)) * sx, y = oy + (pt.y - (vb.y || 0)) * sy;
    dot.style.transform = `translate(${x}px, ${y}px) translate(-50%, -50%)`;
    if (tailSel) { const tl2 = $(tailSel); tl2.setAttribute("stroke-dasharray", `${Math.max(1, o.v * L)} ${L}`); tl2.setAttribute("stroke-dashoffset", 0); tl2.style.opacity = o.v > 0 && o.v < 1 ? 1 : 0; }
  };
  tl.set(o, { v: 0, onUpdate: place }, 0);
  tl.to(o, { v: 1, duration: d, ease, onUpdate: place }, t);
};

/* rolling number (always roll stats, never show them static) */
const count = (sel, t, d, from, to, fmt = (v) => Math.round(v).toString()) => {
  const el = $(sel), o = { v: from };
  tl.set(o, { v: from, onUpdate: () => { el.textContent = fmt(o.v); } }, 0);
  tl.to(o, { v: to, duration: d, ease: "power4.out", onUpdate: () => { el.textContent = fmt(o.v); } }, t);
};

/* waveform: build `n` bars inside a container; eq() animates heights from a seeded pattern; collapse() shrinks them */
const bars = (sel, n = 56, w = 8, gap = 8, h = 220, cls = "") => {
  const el = $(sel); el.innerHTML = "";
  el.style.display = "flex"; el.style.alignItems = "center"; el.style.gap = gap + "px"; el.style.height = h + "px";
  for (let i = 0; i < n; i++) { const b = document.createElement("i"); b.className = "bar " + cls; b.style.cssText = `display:block;width:${w}px;height:${h}px;border-radius:${w}px;transform:scaleY(.04);`; el.appendChild(b); }
  return [...el.children];
};
const eq = (sel, t, dur, seed = 7, step = 0.12, amp = 1) => {
  const bs = $$(`${sel} .bar`), r = rng(seed), steps = Math.max(1, Math.floor(dur / step));
  bs.forEach((b, i) => {
    const env = Math.sin(Math.PI * (i + 0.5) / bs.length);           // taller in the middle
    for (let k = 0; k < steps; k++) {
      const v = (0.12 + 0.88 * r() * env) * amp;
      tl.to(b, { scaleY: Math.max(0.04, v), duration: step, ease: E.soft }, t + k * step);
    }
  });
};
const flat = (sel, t, d = 0.35, st = 0.008) => tl.to(`${sel} .bar`, { scaleY: 0.04, duration: d, ease: E.out, stagger: st }, t);

/* tricolour strip: draw under a title, or full-frame wipe in/out (use at most 3 times in the film) */
const triDraw = (sel, t, d = 0.9) => tl.fromTo(`${sel} i`, { scaleX: 0, transformOrigin: "0 50%" }, { scaleX: 1, duration: d, ease: E.cam, stagger: 0.08 }, t);
const triWipeIn = (sel, t, d = 0.8) => tl.fromTo(`${sel} i`, { scaleY: 0, transformOrigin: "50% 0%" }, { scaleY: 1, duration: d, ease: "expo.inOut", stagger: 0.08 }, t);
const triWipeOut = (sel, t, d = 0.8) => tl.to(`${sel} i`, { scaleY: 0, transformOrigin: "50% 100%", duration: d, ease: "expo.inOut", stagger: 0.08 }, t);

/* scene hand-off helpers: every chapter starts from ink and ends on ink (or on an agreed matched frame) */
const enter = (t = 0) => tl.fromTo(".cam", { opacity: 0 }, { opacity: 1, duration: 0.35, ease: "power1.out" }, t);
const leave = (END, d = 0.4) => tl.to(".cam", { opacity: 0, duration: d, ease: E.out }, END - d);
const wrap = () => { window.__timelines = window.__timelines || {}; window.__timelines["main"] = tl; };
