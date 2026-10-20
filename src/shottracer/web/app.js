"use strict";

// ---------------------------------------------------------------- state
const S = {
  projects: [],
  cur: null,          // current project summary
  frame: 0,
  tool: null,         // "ball" | "landing" | null
  tab: "shot",
  overlay: null,      // edit handles for the current frame
  version: 0,         // bumps when settings change (preview cache-buster)
  playing: false,
  drag: null,
};
const $ = (id) => document.getElementById(id);
const view = $("view"), canvas = $("overlay"), ctx = canvas.getContext("2d");

const api = async (method, url, body) => {
  const opt = { method, headers: {} };
  if (body instanceof FormData) opt.body = body;
  else if (body !== undefined) { opt.body = JSON.stringify(body); opt.headers["Content-Type"] = "application/json"; }
  const r = await fetch(url, opt);
  if (!r.ok) {
    let msg = r.statusText;
    try { msg = (await r.json()).detail || msg; } catch {}
    throw new Error(msg);
  }
  return r.json();
};

function toast(msg, ms = 2600) {
  const box = $("toast"); box.textContent = msg; box.hidden = false;
  clearTimeout(toast._t); toast._t = setTimeout(() => (box.hidden = true), ms);
}

// ---------------------------------------------------------------- projects
async function refresh() {
  S.projects = await api("GET", "/api/projects");
  if (S.cur) {
    const fresh = S.projects.find((p) => p.id === S.cur.id);
    if (fresh) {
      const becameReady = S.cur.status !== "ready" && fresh.status === "ready";
      S.cur = fresh;
      if (becameReady) { S.version++; await loadOverlay(); showFrame(); renderPanel(); renderMarks(); renderTools(); toast(t("toast.analysed")); }
    } else S.cur = null;
  }
  renderList();
  renderBusy();
}

function statusBadge(p) {
  const a = p.jobs?.analyze, r = p.jobs?.render;
  if (p.status === "analysing" || a?.status === "running" || a?.status === "queued")
    return [t("badge.analysing", { p: Math.round((a?.progress || 0) * 100) }), "busy", a?.progress];
  if (r && (r.status === "running" || r.status === "queued"))
    return [r.status === "queued" ? t("badge.queued") : t("badge.rendering", { p: Math.round(r.progress * 100) }), "busy", r.progress];
  if (p.status === "error") return [t("badge.error"), "error"];
  if (r?.status === "done" || (!r && p.has_output)) return [t("badge.done"), "ready"];
  if (p.status === "ready") return [t("badge.ready"), "ready"];
  return [p.shot.ball ? t("badge.needAnalysis") : t("badge.needBall"), ""];
}

function renderList() {
  const ul = $("projects"); ul.innerHTML = "";
  for (const p of S.projects) {
    const li = document.createElement("li");
    if (S.cur && p.id === S.cur.id) li.classList.add("active");
    const [label, cls, prog] = statusBadge(p);
    li.innerHTML = `<img src="/api/projects/${p.id}/frames/${p.shot.ball_frame}.jpg" loading="lazy">
      <div class="meta"><span class="name" title="${esc(p.name)}">${esc(p.name)}</span><span class="badge ${cls}">${label}</span></div>
      <button class="del" title="${t("list.delete")}">✕</button>${prog !== undefined ? `<div class="bar" style="width:${prog * 100}%"></div>` : ""}`;
    li.onclick = () => select(p.id);
    li.querySelector(".del").onclick = async (e) => {
      e.stopPropagation();
      if (!confirm(t("list.confirmDelete", { name: p.name }))) return;
      await api("DELETE", `/api/projects/${p.id}`);
      if (S.cur?.id === p.id) { S.cur = null; $("stage").classList.add("empty"); renderPanel(); }
      refresh();
    };
    ul.appendChild(li);
  }
}

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

async function select(id) {
  stop();
  S.cur = S.projects.find((p) => p.id === id) || (await api("GET", `/api/projects/${id}`));
  S.frame = S.cur.shot.ball_frame || 0;
  S.tool = S.cur.shot.ball ? null : "ball";
  $("stage").classList.remove("empty");
  $("timeline").max = S.cur.info.frames - 1;
  await loadOverlay();
  showFrame(); renderList(); renderPanel(); renderTools(); renderMarks();
}

async function upload(files) {
  for (const f of files) {
    const fd = new FormData(); fd.append("file", f);
    toast(t("toast.uploading", { name: f.name }), 60000);
    try {
      const p = await api("POST", "/api/projects", fd);
      await refresh(); await select(p.id);
      toast(t("toast.uploaded", { name: f.name }));
    } catch (e) { toast(t("toast.uploadFailed", { msg: serverText(e.message) })); }
  }
}

// ---------------------------------------------------------------- settings save
let saveTimer = null, pending = {};
function patch(part, immediate = false) {
  for (const [k, v] of Object.entries(part)) {
    pending[k] = (typeof v === "object" && !Array.isArray(v) && v !== null) ? { ...(pending[k] || {}), ...v } : v;
    if (k in S.cur && typeof v === "object" && !Array.isArray(v) && v !== null) Object.assign(S.cur[k], v);
    else if (k in S.cur) S.cur[k] = v;
  }
  clearTimeout(saveTimer);
  saveTimer = setTimeout(flush, immediate ? 0 : 250);
}
async function flush() {
  if (!S.cur) return;
  const body = pending; pending = {};
  try {
    S.cur = await api("PUT", `/api/projects/${S.cur.id}`, body);
    S.version++;
    await loadOverlay(); showFrame(); renderStats(); renderList();
  } catch (e) { toast(t("toast.saveFailed", { msg: serverText(e.message) })); }
}

// ---------------------------------------------------------------- frame display
function analysed() { return S.cur && S.cur.status === "ready" && S.cur.result; }

function frameUrl(i) {
  return analysed() ? `/api/projects/${S.cur.id}/preview/${i}.jpg?v=${S.version}` : `/api/projects/${S.cur.id}/frames/${i}.jpg`;
}

let loading = false, queued = null;
function showFrame() {
  if (!S.cur) return;
  $("timeline").value = S.frame;
  const fps = S.cur.info.fps, n = S.cur.info.frames;
  $("time").textContent = `${S.frame} / ${n - 1}  ${(S.frame / fps).toFixed(2)}s`;
  const url = frameUrl(S.frame);
  if (loading) { queued = url; return; }
  loading = true;
  view.src = url;
}
view.onload = () => {
  loading = false;
  if (queued) { const u = queued; queued = null; loading = true; view.src = u; return; }
  sizeCanvas(); drawOverlay();
  if (S.playing) setTimeout(tick, 0);
};
view.onerror = () => { loading = false; };

async function loadOverlay() {
  S.overlay = analysed() ? await api("GET", `/api/projects/${S.cur.id}/overlay/${S.frame}`) : null;
}

function sizeCanvas() {
  canvas.width = view.clientWidth; canvas.height = view.clientHeight;
  canvas.style.width = view.clientWidth + "px"; canvas.style.height = view.clientHeight + "px";
}
// preview px -> canvas px
const k = () => view.clientWidth / (view.naturalWidth || 1);
const scale = () => S.cur.info.scale; // video px per preview px

function drawOverlay() {
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  if (!S.cur || S.playing) return;
  const s = scale(), kk = k();
  const ball = S.cur.shot.ball;
  if (ball && (!analysed() || S.frame === S.cur.shot.ball_frame) && !S.overlay) {
    ring(ball[0] / s * kk, ball[1] / s * kk, 11, "#ffd400");
  }
  if (!$("guide").checked || !S.overlay || !S.overlay.path) return;
  const o = S.overlay;
  ctx.save();
  ctx.setLineDash([5, 5]); ctx.strokeStyle = "#ffffffaa"; ctx.lineWidth = 1.2;
  ctx.beginPath();
  o.path.forEach(([x, y], i) => (i ? ctx.lineTo(x * kk, y * kk) : ctx.moveTo(x * kk, y * kk)));
  ctx.stroke(); ctx.restore();
  ring(o.start[0] * kk, o.start[1] * kk, 8, "#ffd400");
  const lp = S.drag?.what === "landing" ? S.drag.pos : [o.landing[0] * kk, o.landing[1] * kk];
  handle(lp[0], lp[1], "#00e0ff", t("handle.landing"));
  if (o.mode === "flight") {
    const ap = S.drag?.what === "apex" ? S.drag.pos : [o.apex[0] * kk, o.apex[1] * kk];
    diamond(ap[0], ap[1], "#ff9f1a"); label(ap[0] + 10, ap[1] - 8, t("handle.apex"));
  }
}
function ring(x, y, r, c) {
  ctx.beginPath(); ctx.arc(x, y, r, 0, 7); ctx.strokeStyle = c; ctx.lineWidth = 2; ctx.stroke();
}
function handle(x, y, c, text) {
  ctx.beginPath(); ctx.arc(x, y, 7, 0, 7); ctx.fillStyle = c; ctx.fill();
  ctx.strokeStyle = "#000"; ctx.lineWidth = 1.5; ctx.stroke(); label(x + 10, y - 8, text);
}
function diamond(x, y, c) {
  ctx.beginPath(); ctx.moveTo(x, y - 8); ctx.lineTo(x + 8, y); ctx.lineTo(x, y + 8); ctx.lineTo(x - 8, y); ctx.closePath();
  ctx.fillStyle = c; ctx.fill(); ctx.strokeStyle = "#000"; ctx.lineWidth = 1.5; ctx.stroke();
}
function label(x, y, text) {
  ctx.font = "12px -apple-system, sans-serif"; ctx.lineWidth = 3; ctx.strokeStyle = "#000c";
  ctx.strokeText(text, x, y); ctx.fillStyle = "#fff"; ctx.fillText(text, x, y);
}

function goto(i) {
  if (!S.cur) return;
  S.frame = Math.max(0, Math.min(S.cur.info.frames - 1, i));
  showFrame();
  if (analysed()) loadOverlay().then(drawOverlay);
}

// ---------------------------------------------------------------- playback
let lastTick = 0;
function play() {
  if (!S.cur) return;
  S.playing = true; $("play").textContent = "❚❚"; drawOverlay(); lastTick = performance.now(); tick();
}
function stop() { S.playing = false; $("play").textContent = "▶︎"; if (S.cur) loadOverlay().then(drawOverlay); }
function tick() {
  if (!S.playing || loading) return;
  const wait = 1000 / S.cur.info.fps - (performance.now() - lastTick);
  setTimeout(() => {
    if (!S.playing) return;
    lastTick = performance.now();
    if (S.frame >= S.cur.info.frames - 1) { stop(); return; }
    S.frame++; showFrame();
  }, Math.max(0, wait));
}

// ---------------------------------------------------------------- canvas interaction
function eventPos(e) {
  const r = canvas.getBoundingClientRect();
  return [e.clientX - r.left, e.clientY - r.top];
}
canvas.addEventListener("mousedown", (e) => {
  if (!S.cur) return;
  const [x, y] = eventPos(e), kk = k(), s = scale();
  const o = S.overlay;
  if (o && $("guide").checked && !S.tool) {
    if (Math.hypot(x - o.landing[0] * kk, y - o.landing[1] * kk) < 12) { S.drag = { what: "landing", pos: [x, y] }; return; }
    if (o.mode === "flight" && Math.hypot(x - o.apex[0] * kk, y - o.apex[1] * kk) < 12) {
      S.drag = { what: "apex", pos: [o.apex[0] * kk, y], startY: o.start[1] * kk, apexY: o.apex[1] * kk, h: S.cur.shot.height };
      return;
    }
  }
  const vx = (x / kk) * s, vy = (y / kk) * s;
  if (S.tool === "ball") {
    patch({ shot: { ball: [vx, vy], ball_frame: S.frame } }, true);
    S.tool = null; renderTools(); drawOverlay(); renderList(); renderPanel(); renderMarks();
    toast(t("toast.ballSet"));
  } else if (S.tool === "landing") {
    if (S.cur.shot.mode !== "flight") { toast(t("toast.landingFlightOnly")); return; }
    patch({ shot: { landing: [vx, vy] }, landing_frame: S.frame }, true);
    S.tool = null; renderTools(); if (S.tab === "shot") renderPanel();
  }
});
canvas.addEventListener("mousemove", (e) => {
  if (!S.drag) {
    const o = S.overlay; if (!o || !$("guide").checked) { canvas.style.cursor = S.tool ? "crosshair" : "default"; return; }
    const [x, y] = eventPos(e), kk = k();
    const near = Math.hypot(x - o.landing[0] * kk, y - o.landing[1] * kk) < 12 ||
      (o.mode === "flight" && Math.hypot(x - o.apex[0] * kk, y - o.apex[1] * kk) < 12);
    canvas.style.cursor = S.tool ? "crosshair" : near ? "grab" : "default";
    return;
  }
  const [x, y] = eventPos(e);
  S.drag.pos = S.drag.what === "apex" ? [S.drag.pos[0], y] : [x, y];
  drawOverlay();
});
window.addEventListener("mouseup", () => {
  const d = S.drag; if (!d) return;
  S.drag = null;
  const kk = k(), s = scale();
  if (d.what === "landing") {
    patch({ shot: { landing: [(d.pos[0] / kk) * s, (d.pos[1] / kk) * s] }, landing_frame: S.frame }, true);
    if (S.tab === "shot") renderPanel();
  } else {
    const rise0 = d.startY - d.apexY, rise1 = d.startY - d.pos[1];
    if (rise0 > 2) {
      const h = Math.max(0.2, Math.min(3, (d.h * rise1) / rise0));
      patch({ shot: { height: +h.toFixed(3) } }, true);
    }
  }
});

// ---------------------------------------------------------------- tools / marks
function renderTools() {
  document.querySelectorAll(".tool").forEach((b) => b.classList.toggle("active", b.dataset.tool === S.tool));
  $("hint").textContent = S.tool ? t(`hint.${S.tool}`) : analysed() ? t("hint.edit") : "";
  canvas.style.cursor = S.tool ? "crosshair" : "default";
}
function renderMarks() {
  const m = $("marks"); m.innerHTML = "";
  if (!S.cur) return;
  const n = Math.max(1, S.cur.info.frames - 1);
  const add = (f, cls) => { const s = document.createElement("span"); s.className = cls; s.style.left = `${(f / n) * 100}%`; m.appendChild(s); };
  if (S.cur.shot.ball) add(S.cur.shot.ball_frame, "ball");
  if (S.cur.result) add(S.cur.result.impact, "impact");
}

// ---------------------------------------------------------------- panel
const T = {
  shot: renderShot, style: renderStyle, popups: renderPopups, swing: renderSwing, export: renderExport,
};
function renderPanel() {
  document.querySelectorAll("#tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === S.tab));
  const body = $("tabBody"); body.innerHTML = "";
  if (!S.cur) { body.innerHTML = `<p class="note">${t("panel.selectVideo")}</p>`; return; }
  T[S.tab](body);
}

// small DOM builders
function el(html) { const tpl = document.createElement("template"); tpl.innerHTML = html.trim(); return tpl.content.firstChild; }
function group(parent, title) { const g = el(`<div class="group"><h4>${title}</h4></div>`); parent.appendChild(g); return g; }
function slider(parent, label, value, min, max, step, onInput, fmt = (v) => v) {
  const r = el(`<div class="row"><label>${label}</label><input type="range" min="${min}" max="${max}" step="${step}" value="${value}"><span class="val">${fmt(value)}</span></div>`);
  const inp = r.querySelector("input"), out = r.querySelector(".val");
  inp.oninput = () => { out.textContent = fmt(+inp.value); onInput(+inp.value); };
  parent.appendChild(r); return r;
}
function toggle(parent, label, value, onChange) {
  const r = el(`<div class="row"><label>${label}</label><input type="checkbox" ${value ? "checked" : ""}></div>`);
  r.querySelector("input").onchange = (e) => onChange(e.target.checked);
  parent.appendChild(r); return r;
}
function color(parent, label, value, onChange) {
  const r = el(`<div class="row"><label>${label}</label><input type="color" value="${value}"></div>`);
  r.querySelector("input").oninput = (e) => onChange(e.target.value);
  parent.appendChild(r); return r;
}
function seg(parent, label, options, value, onChange) {
  const r = el(`<div class="row"><label>${label}</label><div class="seg"></div></div>`);
  const box = r.querySelector(".seg");
  for (const [v, text] of options) {
    const b = el(`<button class="${v === value ? "on" : ""}">${text}</button>`);
    b.onclick = () => { box.querySelectorAll("button").forEach((x) => x.classList.remove("on")); b.classList.add("on"); onChange(v); };
    box.appendChild(b);
  }
  parent.appendChild(r); return r;
}
function select_(parent, label, options, value, onChange) {
  const r = el(`<div class="row"><label>${label}</label><select>${options.map(([v, text]) => `<option value="${v}" ${v === value ? "selected" : ""}>${text}</option>`).join("")}</select></div>`);
  r.querySelector("select").onchange = (e) => onChange(e.target.value);
  parent.appendChild(r); return r;
}

function renderShot(body) {
  const p = S.cur, sh = p.shot;
  const g = group(body, t("shot.group"));
  seg(g, t("shot.mode"), [["flight", t("shot.flight")], ["putt", t("shot.putt")]], sh.mode, (v) => { patch({ shot: { mode: v } }); renderPanel(); });
  if (sh.mode === "flight")
    select_(g, t("shot.type"), ["chip", "pitch", "full", "drive"].map((k) => [k, t(`shot.${k}`)]), sh.shot, (v) => patch({ shot: { shot: v } }));
  const ball = sh.ball ? t("shot.ballAt", { f: sh.ball_frame, xy: sh.ball.map((v) => Math.round(v)).join(", ") }) : t("shot.unset");
  g.appendChild(el(`<div class="row"><label>${t("shot.ball")}</label><span>${ball}</span></div>`));
  const imp = el(`<div class="row"><label>${t("shot.impact")}</label><input type="number" min="0" placeholder="${t("shot.auto")}" value="${sh.impact ?? ""}"></div>`);
  imp.querySelector("input").onchange = (e) => patch({ shot: { impact: e.target.value === "" ? null : +e.target.value } });
  g.appendChild(imp);

  const a = S.cur.jobs?.analyze;
  const running = p.status === "analysing" || a?.status === "running" || a?.status === "queued";
  const btn = el(`<button class="btn primary wide" ${!sh.ball || running ? "disabled" : ""}>${running ? t("shot.analysing") : p.result ? t("shot.reanalyse") : t("shot.analyse")}</button>`);
  btn.onclick = async () => {
    await flush();
    try { await api("POST", `/api/projects/${p.id}/analyze`); S.cur.status = "analysing"; refresh(); renderPanel(); }
    catch (e) { toast(serverText(e.message)); }
  };
  g.appendChild(btn);
  if (p.status === "error" && p.error) g.appendChild(el(`<p class="note err">${esc(serverText(p.error))}</p>`));
  if (!sh.ball) g.appendChild(el(`<p class="note">${t("shot.help")}</p>`));

  const st = el(`<div class="group" id="stats"></div>`); body.appendChild(st); renderStats();

  if (p.result && sh.mode === "flight") {
    const e = group(body, t("edit.group"));
    const auto = sh.flight_time == null;
    toggle(e, t("edit.autoTime"), auto, (v) => { patch({ shot: { flight_time: v ? null : +(p.result.duration.toFixed(1)) } }); renderPanel(); });
    if (!auto) slider(e, t("edit.time"), sh.flight_time, 0.5, 9, 0.1, (v) => patch({ shot: { flight_time: v } }), (v) => `${v.toFixed(1)}s`);
    slider(e, t("edit.height"), sh.height, 0.3, 2.5, 0.05, (v) => patch({ shot: { height: v } }), (v) => `×${v.toFixed(2)}`);
    const row = el(`<div class="row"><label>${t("edit.landing")}</label><span>${sh.landing ? t("edit.set") : t("shot.auto")}</span></div>`);
    if (sh.landing) {
      const b = el(`<button class="btn small">${t("edit.clear")}</button>`);
      b.onclick = () => { patch({ shot: { landing: null } }, true); renderPanel(); };
      row.appendChild(b);
    }
    e.appendChild(row);
    e.appendChild(el(`<p class="note">${t("edit.help")}</p>`));
  }
}

function renderStats() {
  const box = document.getElementById("stats"); if (!box) return;
  const r = S.cur.result;
  if (!r) { box.innerHTML = ""; return; }
  const fps = S.cur.info.fps;
  box.innerHTML = `<h4>${t("stats.title")}</h4><div class="stat">
    <div><b>${r.impact.toFixed(1)}</b><span>${t("stats.impact", { s: (r.impact / fps).toFixed(2) })}</span></div>
    <div><b>${r.tracked}</b><span>${t("stats.tracked")}</span></div>
    <div><b>${r.duration.toFixed(1)}s</b><span>${r.mode === "flight" ? t("stats.flight") : t("stats.roll")}</span></div>
    <div><b>${r.mode === "flight" ? r.apex_time.toFixed(1) + "s" : "—"}</b><span>${t("stats.apex")}</span></div></div>`;
}

const PRESETS = ["classic", "tv", "neon", "comet", "minimal"];
function renderStyle(body) {
  const st = S.cur.style;
  const g = group(body, t("style.presets"));
  const box = el(`<div class="presets"></div>`);
  for (const v of PRESETS) {
    const b = el(`<button class="${st.preset === v ? "on" : ""}"><i style="background:${st.color};box-shadow:0 0 ${v === "neon" ? 10 : v === "minimal" ? 0 : 5}px ${st.color}"></i>${t(`preset.${v}`)}</button>`);
    b.onclick = () => { patch({ style: { preset: v } }); renderPanel(); };
    box.appendChild(b);
  }
  g.appendChild(box);
  const l = group(body, t("style.line"));
  color(l, t("style.color"), st.color, (v) => patch({ style: { color: v } }));
  color(l, t("style.core"), st.core_color, (v) => patch({ style: { core_color: v } }));
  slider(l, t("style.width"), st.width, 0.3, 4, 0.1, (v) => patch({ style: { width: v } }), (v) => `×${v.toFixed(1)}`);
  slider(l, t("style.glow"), st.glow, 0, 3, 0.1, (v) => patch({ style: { glow: v } }), (v) => `×${v.toFixed(1)}`);
  toggle(l, t("style.tailOnly"), st.tail != null, (v) => { patch({ style: { tail: v ? 0.7 : null } }); renderPanel(); });
  if (st.tail != null) slider(l, t("style.tail"), st.tail, 0.1, 3, 0.1, (v) => patch({ style: { tail: v } }), (v) => `${v.toFixed(1)}s`);
  const e = group(body, t("style.effects"));
  toggle(e, t("style.cutout"), st.cutout, (v) => patch({ style: { cutout: v } }));
  toggle(e, t("style.ballHead"), st.ball_head, (v) => patch({ style: { ball_head: v } }));
  toggle(e, t("style.landingFx"), st.landing_marker, (v) => patch({ style: { landing_marker: v } }));
  toggle(e, t("style.keep"), st.hold == null, (v) => { patch({ style: { hold: v ? null : 2 } }); renderPanel(); });
  if (st.hold != null) slider(e, t("style.hold"), st.hold, 0, 10, 0.5, (v) => patch({ style: { hold: v } }), (v) => `${v.toFixed(1)}s`);
  const sm = S.cur.slowmo, s = group(body, t("slowmo.group"));
  toggle(s, t("common.enable"), sm.enabled, (v) => { patch({ slowmo: { enabled: v } }); renderPanel(); });
  if (sm.enabled) {
    slider(s, t("slowmo.factor"), sm.factor, 1.5, 8, 0.5, (v) => patch({ slowmo: { factor: v } }), (v) => `1/${v}`);
    slider(s, t("slowmo.before"), sm.before, 0, 3, 0.1, (v) => patch({ slowmo: { before: v } }), (v) => `${v.toFixed(1)}s`);
    slider(s, t("slowmo.after"), sm.after, 0, 6, 0.1, (v) => patch({ slowmo: { after: v } }), (v) => `${v.toFixed(1)}s`);
    s.appendChild(el(`<p class="note">${t("slowmo.note")}</p>`));
  }
}

// kind -> [default label, default value (string or i18n key via function), default anchor]
const POPUP_TYPES = {
  distance: ["DISTANCE", () => "245 yd", "landing"],
  carry: ["CARRY", () => "230 yd", "landing"],
  speed: ["BALL SPEED", () => "150 mph", "start"],
  apex: ["APEX", () => "32 yd", "apex"],
  hangtime: ["HANG TIME", () => "", "landing"],
  club: ["CLUB", () => t("popup.clubValue"), "top-left"],
  player: ["", () => t("popup.playerName"), "bottom-left"],
  text: ["", () => t("popup.textValue"), "top-right"],
};
const ANCHORS = ["landing", "apex", "start", "top-left", "top-right", "bottom-left", "bottom-right"];
const CORNERS = ["top-left", "top-right", "bottom-left", "bottom-right"];
function renderPopups(body) {
  const g = group(body, t("popup.add"));
  const chips = el(`<div class="chips"></div>`);
  for (const [kind, [label, value, anchor]] of Object.entries(POPUP_TYPES)) {
    const b = el(`<button class="btn small">+ ${t(`popup.${kind}`)}</button>`);
    b.onclick = () => {
      const list = [...S.cur.popups, { kind, label, value: value(), anchor, delay: anchor === "landing" ? 0.2 : 0.3, duration: null, color: "#111111", accent: S.cur.style.color }];
      patch({ popups: list }); renderPanel();
    };
    chips.appendChild(b);
  }
  g.appendChild(chips);
  g.appendChild(el(`<p class="note">${t("popup.note")}</p>`));
  S.cur.popups.forEach((pp, i) => {
    const c = el(`<div class="card"><div class="head"><span>${t(`popup.${pp.kind}`)}</span><button class="btn small">${t("popup.delete")}</button></div></div>`);
    const upd = (part) => { const list = S.cur.popups.map((x, j) => (j === i ? { ...x, ...part } : x)); patch({ popups: list }); };
    c.querySelector(".btn").onclick = () => { patch({ popups: S.cur.popups.filter((_, j) => j !== i) }); renderPanel(); };
    const lab = el(`<div class="row"><label>${t("popup.label")}</label><input type="text" value="${esc(pp.label)}"></div>`);
    lab.querySelector("input").oninput = (e) => upd({ label: e.target.value });
    const val = el(`<div class="row"><label>${t("popup.value")}</label><input type="text" value="${esc(pp.value)}" placeholder="${pp.kind === "hangtime" ? t("popup.auto") : ""}"></div>`);
    val.querySelector("input").oninput = (e) => upd({ value: e.target.value });
    c.append(lab, val);
    select_(c, t("popup.position"), ANCHORS.map((a) => [a, t(`anchor.${a}`)]), pp.anchor, (v) => upd({ anchor: v }));
    slider(c, t("popup.delay"), pp.delay, 0, 5, 0.1, (v) => upd({ delay: v }), (v) => `${v.toFixed(1)}s`);
    toggle(c, t("popup.keep"), pp.duration == null, (v) => { upd({ duration: v ? null : 3 }); renderPanel(); });
    if (pp.duration != null) slider(c, t("popup.duration"), pp.duration, 0.5, 10, 0.5, (v) => upd({ duration: v }), (v) => `${v.toFixed(1)}s`);
    color(c, t("popup.bg"), pp.color, (v) => upd({ color: v }));
    color(c, t("popup.accent"), pp.accent, (v) => upd({ accent: v }));
    body.appendChild(c);
  });
}

function renderSwing(body) {
  const sw = S.cur.swing;
  const g = group(body, t("swing.group"));
  toggle(g, t("common.enable"), sw.enabled, (v) => { patch({ swing: { enabled: v } }); renderPanel(); });
  seg(g, t("swing.track"), [["clubhead", t("swing.clubhead")], ["hands", t("swing.hands")]], sw.source, (v) => patch({ swing: { source: v } }));
  color(g, t("style.color"), sw.color, (v) => patch({ swing: { color: v } }));
  slider(g, t("style.width"), sw.width, 0.3, 4, 0.1, (v) => patch({ swing: { width: v } }), (v) => `×${v.toFixed(1)}`);
  toggle(g, t("swing.gradient"), sw.gradient, (v) => patch({ swing: { gradient: v } }));
  g.appendChild(el(`<p class="note">${t("swing.note")}</p>`));
}

function renderExport(body) {
  const lg = S.cur.logo;
  const g = group(body, t("export.logo"));
  const up = el(`<label class="btn small">${lg.path ? t("export.changeLogo") : t("export.uploadLogo")}<input type="file" accept="image/*" hidden></label>`);
  up.querySelector("input").onchange = async (e) => {
    const f = e.target.files[0]; if (!f) return;
    const fd = new FormData(); fd.append("file", f);
    try { S.cur = await api("POST", `/api/projects/${S.cur.id}/logo`, fd); S.version++; showFrame(); renderPanel(); }
    catch (err) { toast(serverText(err.message)); }
  };
  g.appendChild(up);
  if (lg.path) {
    const rm = el(`<button class="btn small" style="margin-left:6px">${t("export.remove")}</button>`);
    rm.onclick = () => { patch({ logo: { path: null } }); renderPanel(); };
    g.appendChild(rm);
    select_(g, t("popup.position"), CORNERS.map((a) => [a, t(`anchor.${a}`)]), lg.corner, (v) => patch({ logo: { corner: v } }));
    slider(g, t("export.size"), lg.size, 0.03, 0.4, 0.01, (v) => patch({ logo: { size: v } }), (v) => `${Math.round(v * 100)}%`);
    slider(g, t("export.opacity"), lg.opacity, 0.1, 1, 0.05, (v) => patch({ logo: { opacity: v } }), (v) => `${Math.round(v * 100)}%`);
  }
  const e = group(body, t("export.group"));
  const r = S.cur.jobs?.render;
  const busy = r && (r.status === "running" || r.status === "queued");
  const btn = el(`<button class="btn primary wide" ${!analysed() || busy ? "disabled" : ""}>${busy ? t("export.rendering", { p: Math.round(r.progress * 100) }) : t("export.render")}</button>`);
  btn.onclick = async () => { await flush(); await api("POST", "/api/render", { ids: [S.cur.id] }); refresh(); };
  e.appendChild(btn);
  if (r?.status === "done" || (!r && S.cur.has_output)) e.appendChild(el(`<p><a class="btn wide" style="display:block;text-align:center;margin-top:8px;text-decoration:none" href="/api/projects/${S.cur.id}/output" download>${t("export.download")}</a></p>`));
  if (r?.status === "error") e.appendChild(el(`<p class="note err">${esc(serverText(r.message))}</p>`));
  e.appendChild(el(`<p class="note">${t("export.note")}</p>`));
}

// ---------------------------------------------------------------- polling
async function poll() {
  const busy = S.projects.some((p) => {
    const a = p.jobs?.analyze, r = p.jobs?.render;
    return p.status === "analysing" || [a?.status, r?.status].some((s) => s === "running" || s === "queued");
  });
  if (busy) {
    const prevJobs = JSON.stringify(S.cur?.jobs);
    await refresh().catch(() => {});
    if (S.cur && JSON.stringify(S.cur.jobs) !== prevJobs && (S.tab === "export" || S.tab === "shot")) renderPanel();
  }
  setTimeout(poll, busy ? 700 : 1500);
}
function renderBusy() {
  const a = S.cur?.jobs?.analyze;
  const on = S.cur && (S.cur.status === "analysing" || a?.status === "running" || a?.status === "queued");
  $("busy").hidden = !on;
  if (on) $("busyText").textContent = t("busy.analysing", { p: Math.round((a?.progress || 0) * 100), msg: serverText(a?.message || "") });
}

// ---------------------------------------------------------------- wiring
$("file").onchange = (e) => upload([...e.target.files]);
const drop = $("drop");
for (const target of [drop, document.body]) {
  target.addEventListener("dragover", (e) => { e.preventDefault(); drop.classList.add("over"); });
  target.addEventListener("dragleave", () => drop.classList.remove("over"));
  target.addEventListener("drop", (e) => {
    e.preventDefault(); drop.classList.remove("over");
    const files = [...e.dataTransfer.files].filter((f) => f.type.startsWith("video/") || /\.(mp4|mov|m4v|avi|mkv)$/i.test(f.name));
    if (files.length) upload(files);
  });
}
$("renderAll").onclick = async () => {
  await flush();
  const ids = S.projects.filter((p) => p.status === "ready").map((p) => p.id);
  if (!ids.length) { toast(t("toast.noneReady")); return; }
  await api("POST", "/api/render", { ids }); toast(t("toast.queued", { n: ids.length })); refresh();
};
$("timeline").oninput = (e) => { stop(); goto(+e.target.value); };
$("prev").onclick = () => { stop(); goto(S.frame - 1); };
$("next").onclick = () => { stop(); goto(S.frame + 1); };
$("play").onclick = () => (S.playing ? stop() : play());
$("guide").onchange = drawOverlay;
document.querySelectorAll(".tool").forEach((b) => (b.onclick = () => { S.tool = S.tool === b.dataset.tool ? null : b.dataset.tool; renderTools(); }));
document.querySelectorAll("#tabs button").forEach((b) => (b.onclick = () => { S.tab = b.dataset.tab; renderPanel(); }));
window.addEventListener("resize", () => { sizeCanvas(); drawOverlay(); });
window.addEventListener("keydown", (e) => {
  if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT" || !S.cur) return;
  if (e.key === "ArrowLeft") { stop(); goto(S.frame - (e.shiftKey ? 10 : 1)); }
  else if (e.key === "ArrowRight") { stop(); goto(S.frame + (e.shiftKey ? 10 : 1)); }
  else if (e.key === " ") { e.preventDefault(); S.playing ? stop() : play(); }
  else if (e.key === "b") { S.tool = "ball"; renderTools(); }
  else if (e.key === "l") { S.tool = "landing"; renderTools(); }
  else if (e.key === "Escape") { S.tool = null; renderTools(); }
});

$("lang").value = LANG;
setLang(LANG);
$("lang").onchange = (e) => {
  setLang(e.target.value);
  renderList(); renderPanel(); renderTools(); renderBusy(); drawOverlay();
};

const hashParams = new URLSearchParams(location.hash.slice(1));
if (hashParams.get("lang")) { setLang(hashParams.get("lang")); $("lang").value = LANG; }
const hashTab = hashParams.get("tab");
if (hashTab && hashTab in T) S.tab = hashTab;
refresh().then(() => { renderPanel(); if (S.projects.length) select(S.projects[S.projects.length - 1].id); poll(); });
