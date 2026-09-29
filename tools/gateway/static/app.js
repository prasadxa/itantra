"use strict";

// Matches core/src/main/kotlin/org/itantra/core/Lang.kt (the 10 languages required by PS 26173).
const LANGS = [
  ["hi", "हिन्दी"], ["gu", "ગુજરાતી"], ["mr", "मराठी"], ["kn", "ಕನ್ನಡ"], ["ml", "മലയാളം"],
  ["ta", "தமிழ்"], ["te", "తెలుగు"], ["or", "ଓଡ଼ିଆ"], ["bn", "বাংলা"], ["en", "English"],
];

const feedEl = document.getElementById("feed");
const statsRowEl = document.getElementById("statsRow");
const peerListEl = document.getElementById("peerList");
const langCountsEl = document.getElementById("langCounts");
const mapCanvas = document.getElementById("mapCanvas");
const mapNote = document.getElementById("mapNote");
const connStatusEl = document.getElementById("connStatus");
const formStatusEl = document.getElementById("formStatus");

const feedRowsByMsgId = new Map();

function fillLangSelect(sel, includeAny) {
  sel.innerHTML = "";
  for (const [code, label] of LANGS) {
    const opt = document.createElement("option");
    opt.value = code;
    opt.textContent = `${code.toUpperCase()} — ${label}`;
    sel.appendChild(opt);
  }
}

fillLangSelect(document.getElementById("bLang"));
fillLangSelect(document.getElementById("capLang"));

function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function fmtMs(v) {
  return v === null || v === undefined ? "—" : `${Math.round(v)} ms`;
}

function timeLabel(tsMs) {
  if (!tsMs) return "";
  const d = new Date(tsMs);
  return d.toLocaleTimeString();
}

// ---- feed rendering ----

function makeRow(msgId) {
  const row = document.createElement("div");
  row.className = "msg-row";
  row.dataset.msgid = msgId || "";
  row.innerHTML = `
    <div class="top">
      <span class="badges"></span>
      <span class="time"></span>
    </div>
    <div class="text"></div>
    <div class="lat"></div>
  `;
  return row;
}

function badgeHtml(text, cls) {
  return `<span class="badge ${cls || ""}">${escapeHtml(text)}</span>`;
}

function renderMessageRow(row, { lang, priority, sender, dir, text, ts }) {
  const alert = (priority || "").toUpperCase() === "ALERT";
  row.classList.toggle("alert", alert);
  row.classList.remove("partial");
  const badges = [];
  if (sender) badges.push(badgeHtml(sender, ""));
  if (lang) badges.push(badgeHtml(lang, "lang"));
  if (alert) badges.push(badgeHtml("ALERT", "alert"));
  if (dir === "out") badges.push(badgeHtml("gateway→", "dir-out"));
  row.querySelector(".badges").innerHTML = badges.join(" ");
  row.querySelector(".time").textContent = timeLabel(ts);
  row.querySelector(".text").textContent = text || "";
}

function prependRow(row) {
  feedEl.insertBefore(row, feedEl.firstChild);
  while (feedEl.children.length > 300) feedEl.removeChild(feedEl.lastChild);
}

function onPartial(ev) {
  const p = ev.partial || {};
  const msgId = p.id || "";
  let row = feedRowsByMsgId.get(msgId);
  if (!row) {
    row = makeRow(msgId);
    row.classList.add("partial");
    feedRowsByMsgId.set(msgId, row);
    prependRow(row);
  }
  renderMessageRow(row, { lang: p.lang, priority: null, sender: ev.peerDeviceId || p.from, dir: "in", text: p.text, ts: ev.ts });
  row.classList.add("partial");
}

function onMessage(ev) {
  const m = ev.message || {};
  const msgId = m.id || "";
  let row = feedRowsByMsgId.get(msgId);
  if (!row) {
    row = makeRow(msgId);
    feedRowsByMsgId.set(msgId, row);
    prependRow(row);
  }
  renderMessageRow(row, {
    lang: m.lang, priority: m.priority, sender: ev.peerDeviceId || m.from, dir: "in", text: m.text, ts: ev.ts,
  });
}

function onBroadcastSent(ev) {
  const row = makeRow(ev.msgId);
  feedRowsByMsgId.set(ev.msgId, row);
  renderMessageRow(row, {
    lang: ev.lang, priority: ev.alert ? "ALERT" : "NORMAL", sender: "gateway", dir: "out", text: ev.text, ts: ev.ts,
  });
  const sentTo = (ev.sentTo || []).join(", ") || "(no peers connected)";
  row.querySelector(".lat").textContent = `sent to: ${sentTo}`;
  prependRow(row);
}

function onAck(ev) {
  const row = feedRowsByMsgId.get(ev.msgId);
  if (!row) return;
  const bits = [];
  if (ev.peerDeviceId) bits.push(`ack from ${ev.peerDeviceId}`);
  if (ev.sentToReceivedMs !== null && ev.sentToReceivedMs !== undefined) bits.push(`sent→received ${fmtMs(ev.sentToReceivedMs)}`);
  if (ev.receivedToPlayMs !== null && ev.receivedToPlayMs !== undefined) bits.push(`received→played ${fmtMs(ev.receivedToPlayMs)}`);
  row.querySelector(".lat").textContent = bits.join(" · ");
}

// ---- stats / peers / lang counts / map ----

function renderStats(state) {
  const lat = state.latency || {};
  const tiles = [
    ["Connected peers", state.connectedPeers.length],
    ["Sent→Recv p50", fmtMs(lat.sentToReceivedP50)],
    ["Sent→Recv p95", fmtMs(lat.sentToReceivedP95)],
    ["Recv→Play p50", fmtMs(lat.receivedToPlayP50)],
    ["Recv→Play p95", fmtMs(lat.receivedToPlayP95)],
    ["Latency samples", lat.n || 0],
  ];
  statsRowEl.innerHTML = tiles.map(([label, v]) => `
    <div class="stat-tile"><div class="v">${escapeHtml(v)}</div><div class="l">${escapeHtml(label)}</div></div>
  `).join("");
}

function renderPeers(state) {
  const connected = new Set(state.connectedPeers);
  if (state.peers.length === 0) {
    peerListEl.innerHTML = `<div class="l" style="color:var(--muted)">no peers seen yet</div>`;
    return;
  }
  peerListEl.innerHTML = state.peers.map((p) => {
    const live = connected.has(p.device_id);
    return `<div class="peer-item">
      <span class="id">${live ? "●" : "○"} ${escapeHtml(p.device_id)} ${p.name ? "(" + escapeHtml(p.name) + ")" : ""}</span>
      <span class="addr">${escapeHtml(p.address)}</span>
    </div>`;
  }).join("");
}

function renderLangCounts(state) {
  const rows = state.langCounts || [];
  if (rows.length === 0) {
    langCountsEl.innerHTML = `<div style="color:var(--muted); font-size:12px;">no messages yet</div>`;
    return;
  }
  const max = Math.max(...rows.map((r) => r.n));
  langCountsEl.innerHTML = rows.map((r) => `
    <div class="lang-bar-row">
      <span class="code">${escapeHtml(r.lang || "?")}</span>
      <span class="lang-bar-track"><span class="lang-bar-fill" style="width:${Math.max(4, (r.n / max) * 100)}%"></span></span>
      <span class="n">${r.n}</span>
    </div>`).join("");
}

function renderMap(state) {
  const ctx = mapCanvas.getContext("2d");
  const w = mapCanvas.width, h = mapCanvas.height;
  ctx.clearRect(0, 0, w, h);
  const points = (state.locations || []).filter((p) => p.lat !== null && p.lon !== null);
  if (points.length === 0) {
    mapNote.textContent = "no location data yet (VoiceMessage carries no lat/lon field today — panel will populate once it does)";
    return;
  }
  mapNote.textContent = `${points.length} located message(s) — approximate positions, no basemap (offline)`;
  let minLat = Math.min(...points.map((p) => p.lat)), maxLat = Math.max(...points.map((p) => p.lat));
  let minLon = Math.min(...points.map((p) => p.lon)), maxLon = Math.max(...points.map((p) => p.lon));
  if (minLat === maxLat) { minLat -= 0.01; maxLat += 0.01; }
  if (minLon === maxLon) { minLon -= 0.01; maxLon += 0.01; }
  const pad = 20;
  ctx.strokeStyle = "#223047";
  ctx.strokeRect(pad, pad, w - 2 * pad, h - 2 * pad);
  for (const p of points) {
    const x = pad + ((p.lon - minLon) / (maxLon - minLon)) * (w - 2 * pad);
    const y = h - pad - ((p.lat - minLat) / (maxLat - minLat)) * (h - 2 * pad);
    ctx.beginPath();
    ctx.fillStyle = (p.priority || "").toUpperCase() === "ALERT" ? "#ff4d5e" : "#3fa9f5";
    ctx.arc(x, y, 4, 0, Math.PI * 2);
    ctx.fill();
  }
}

// ---- initial state seeding + polling ----

async function loadState() {
  try {
    const res = await fetch("/api/state");
    const state = await res.json();
    renderStats(state);
    renderPeers(state);
    renderLangCounts(state);
    renderMap(state);
    if (feedEl.children.length === 0) {
      // seed feed once from history; live updates take over from here.
      for (const m of state.recentMessages.slice().reverse()) {
        const row = makeRow(m.msg_id);
        feedRowsByMsgId.set(m.msg_id, row);
        renderMessageRow(row, {
          lang: m.lang, priority: m.priority, sender: m.direction === "out" ? "gateway" : (m.from_id || m.peer_device_id),
          dir: m.direction, text: m.text, ts: m.logged_at,
        });
        prependRow(row);
      }
    }
  } catch (e) {
    // dashboard still usable without state; SSE will keep the feed live.
  }
}

loadState();
setInterval(loadState, 4000);

// ---- SSE live feed ----

function connectEvents() {
  const es = new EventSource("/api/events");
  es.onopen = () => { connStatusEl.textContent = "live"; connStatusEl.className = "live"; };
  es.onerror = () => { connStatusEl.textContent = "reconnecting…"; connStatusEl.className = "down"; };
  es.onmessage = (raw) => {
    let ev;
    try { ev = JSON.parse(raw.data); } catch { return; }
    switch (ev.type) {
      case "message": onMessage(ev); break;
      case "partial": onPartial(ev); break;
      case "ack": onAck(ev); break;
      case "broadcast_sent": onBroadcastSent(ev); break;
      case "peer_connected":
      case "peer_disconnected":
        loadState();
        break;
      default: break;
    }
  };
}
connectEvents();

// ---- forms ----

document.getElementById("broadcastForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const text = document.getElementById("bText").value.trim();
  const lang = document.getElementById("bLang").value;
  const alert = document.getElementById("bAlert").checked;
  if (!text) return;
  formStatusEl.textContent = "sending…";
  try {
    const res = await fetch("/api/broadcast", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text, lang, alert }),
    });
    const out = await res.json();
    if (!res.ok) throw new Error(out.error || "broadcast failed");
    formStatusEl.textContent = `sent to ${out.sentTo.length} peer(s)`;
    document.getElementById("bText").value = "";
  } catch (err) {
    formStatusEl.textContent = `error: ${err.message}`;
  }
});

async function loadCapSamples() {
  try {
    const res = await fetch("/api/cap/samples");
    const { samples } = await res.json();
    const sel = document.getElementById("capFile");
    sel.innerHTML = samples.map((s) => `<option value="${escapeHtml(s)}">${escapeHtml(s)}</option>`).join("");
  } catch (e) {
    // ignore
  }
}
loadCapSamples();

document.getElementById("capForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const file = document.getElementById("capFile").value;
  const lang = document.getElementById("capLang").value;
  if (!file) return;
  formStatusEl.textContent = "ingesting CAP…";
  try {
    const res = await fetch("/api/cap", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ file, lang }),
    });
    const out = await res.json();
    if (!res.ok) throw new Error(out.error || "CAP ingestion failed");
    formStatusEl.textContent = `CAP "${out.event || file}" sent to ${out.sentTo.length} peer(s)`;
  } catch (err) {
    formStatusEl.textContent = `error: ${err.message}`;
  }
});
