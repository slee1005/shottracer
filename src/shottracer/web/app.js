"use strict";

// ---------------------------------------------------------------- state
const S = {
  projects: [],
  cur: null,          // current project summary
  frame: 0,
  tool: null,         // "ball" | "landing" | "path" | "circle" | "line" | "swing" | null
  tab: "shot",
  overlay: null,      // edit handles for the current frame
  version: 0,         // bumps when settings change (preview cache-buster)
  playing: false,
  drag: null,
  draft: null,        // path start click, or the circle/line being dragged out
  sel: null,          // index of the selected on-screen shape
  zoom: 1,            // editor view: 1 = fit the window, up to ZOOM_MAX
  wm: true,           // "Create by AJC_shottracer" watermark on (unlocking needs the password)
  swingStep: +(localStorage.getItem("shottracer.swingStep") || 2), // frames to advance after a swing point
  annStyle: JSON.parse(localStorage.getItem("shottracer.annStyle") || "null") || { color: "#FFD400", width: 1 },
  trace: null,        // key of the shot being edited (a video has up to MAX_TRACES)
};
const MAX_TRACES = 4;
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
      // A shot whose analysis just finished.
      const becameReady = fresh.traces.some((x) => x.result && !S.cur.traces.find((y) => y.key === x.key)?.result);
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
  return [p.traces.some((x) => x.shot.ball) ? t("badge.needAnalysis") : t("badge.needBall"), ""];
}

function renderList() {
  const ul = $("projects"); ul.innerHTML = "";
  for (const p of S.projects) {
    const li = document.createElement("li");
    if (S.cur && p.id === S.cur.id) li.classList.add("active");
    const [label, cls, prog] = statusBadge(p);
    li.innerHTML = `<img src="/api/projects/${p.id}/frames/${p.traces[0].shot.ball_frame}.jpg" loading="lazy">
      <div class="meta"><span class="name" title="${esc(p.name)}">${esc(p.name)}</span><span class="badge ${cls}">${label}</span></div>
      <button class="reset" title="${t("reset.title")}">↺</button><button class="del" title="${t("list.delete")}">✕</button>${prog !== undefined ? `<div class="bar" style="width:${prog * 100}%"></div>` : ""}`;
    li.onclick = () => select(p.id);
    li.querySelector(".del").onclick = async (e) => {
      e.stopPropagation();
      if (!confirm(t("list.confirmDelete", { name: p.name }))) return;
      await api("DELETE", `/api/projects/${p.id}`);
      if (S.cur?.id === p.id) { S.cur = null; $("stage").classList.add("empty"); renderPanel(); }
      refresh();
    };
    li.querySelector(".reset").onclick = (e) => { e.stopPropagation(); resetProject(p); };
    ul.appendChild(li);
  }
}

async function resetProject(p) {
  if (!confirm(t("reset.confirm", { name: p.name }))) return;
  if (S.cur?.id === p.id) { stop(); clearTimeout(saveTimer); pending = {}; editOpen = false; }
  delete HISTORY[p.id]; // a reset cannot be undone, and older steps no longer apply
  try {
    const fresh = await api("POST", `/api/projects/${p.id}/reset`);
    await refresh();
    if (S.cur?.id === p.id) {
      S.cur = fresh; S.trace = fresh.traces[0].key; S.version++; S.tool = "ball"; S.draft = null; S.sel = null;
      await loadOverlay(); showFrame(); renderPanel(); renderTools(); renderMarks();
    }
    toast(t("reset.done"));
  } catch (e) { toast(serverText(e.message)); }
}

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

async function select(id) {
  stop();
  S.cur = S.projects.find((p) => p.id === id) || (await api("GET", `/api/projects/${id}`));
  S.trace = S.cur.traces[0].key; S.draft = null; S.sel = null;
  S.frame = TR().shot.ball_frame || 0;
  S.tool = TR().shot.ball || TR().shot.manual ? null : "ball";
  $("stage").classList.remove("empty");
  setZoom(1);
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

// ---------------------------------------------------------------- shots (traces)
// The shot being edited; ball, line, edits and pop-ups belong to it.
function TR() { return S.cur.traces.find((x) => x.key === S.trace) || S.cur.traces[0]; }
const traceColor = (tr) => tr.color || S.cur.style.color;

async function switchTrace(key) {
  await flush();
  stop();
  S.trace = key; S.draft = null; S.drag = null;
  const tr = TR();
  S.tool = tr.shot.ball || tr.shot.manual ? null : "ball";
  if (tr.shot.ball) S.frame = tr.shot.ball_frame;
  await loadOverlay(); showFrame(); renderPanel(); renderTools(); renderMarks();
}

async function addTrace() {
  await flush();
  try {
    S.cur = await api("POST", `/api/projects/${S.cur.id}/traces`);
    await switchTrace(S.cur.traces[S.cur.traces.length - 1].key);
    renderList();
    toast(t("trace.added", { n: S.cur.traces.length }));
  } catch (e) { toast(serverText(e.message)); }
}

async function removeTrace() {
  const n = S.cur.traces.indexOf(TR()) + 1;
  if (!confirm(t("trace.confirmDelete", { n }))) return;
  await flush();
  try {
    S.cur = await api("DELETE", `/api/projects/${S.cur.id}/traces/${TR().key}`);
    S.version++;
    await switchTrace(S.cur.traces[Math.min(n - 1, S.cur.traces.length - 1)].key);
    renderList();
  } catch (e) { toast(serverText(e.message)); }
}

function renderTraces() {
  const box = $("traces"); box.innerHTML = "";
  if (!S.cur) return;
  S.cur.traces.forEach((tr, i) => {
    const busy = tr.status === "analysing" || ["queued", "running"].includes(tr.job?.status);
    const state = busy ? "busy" : tr.result ? "ready" : tr.status === "error" ? "error" : "";
    const b = el(`<button class="trace ${tr.key === TR().key ? "on" : ""} ${state}"><i style="background:${traceColor(tr)}"></i>${t("trace.name", { n: i + 1 })}</button>`);
    b.onclick = () => tr.key !== TR().key && switchTrace(tr.key);
    box.appendChild(b);
  });
  if (S.cur.traces.length < MAX_TRACES) {
    const add = el(`<button class="trace add" title="${t("trace.addTitle", { max: MAX_TRACES })}">${t("trace.add")}</button>`);
    add.onclick = addTrace; box.appendChild(add);
  }
  if (S.cur.traces.length > 1) {
    const del = el(`<button class="trace del" title="${t("trace.delete")}">✕</button>`);
    del.onclick = removeTrace; box.appendChild(del);
  }
}

// ---------------------------------------------------------------- settings save
let saveTimer = null, pending = {};

// ---------------------------------------------------------------- undo / redo
// Per video: snapshots of every editable setting. Edits saved together (a slider drag,
// typing in a field) form one step. Adding/removing shots and reset are not undone.
const HISTORY = {}, HISTORY_MAX = 100;
let editOpen = false;
const history = () => (HISTORY[S.cur.id] ??= { undo: [], redo: [] });
function snapshot() {
  const c = structuredClone;
  return {
    style: c(S.cur.style), slowmo: c(S.cur.slowmo), logo: c(S.cur.logo), swing: c(S.cur.swing),
    annotations: c(S.cur.annotations),
    traces: S.cur.traces.map((x) => ({ key: x.key, shot: c(x.shot), popups: c(x.popups), color: x.color })),
  };
}
function beginEdit() {
  if (editOpen || !S.cur) return;
  editOpen = true;
  const h = history();
  h.undo.push(snapshot()); h.redo = [];
  if (h.undo.length > HISTORY_MAX) h.undo.shift();
  renderUndo();
}
async function undo(redo = false) {
  if (!S.cur) return;
  if (Object.keys(pending).length) { clearTimeout(saveTimer); await flush(); }
  const h = history(), from = redo ? h.redo : h.undo, to = redo ? h.undo : h.redo;
  if (!from.length) { toast(t(redo ? "undo.nothingRedo" : "undo.nothing")); return; }
  const snap = from.pop();
  to.push(snapshot());
  const { traces, ...rest } = snap;
  try {
    S.cur = await api("PUT", `/api/projects/${S.cur.id}`, { ...rest, restore: traces });
  } catch (e) { toast(t("toast.saveFailed", { msg: serverText(e.message) })); return; }
  S.version++; S.draft = null; S.drag = null; S.sel = null;
  const tr = TR();
  if (tr.shot.ball && S.tool === "ball") S.tool = null;
  await loadOverlay(); showFrame(); renderList(); renderPanel(); renderTools(); renderMarks();
  toast(t(redo ? "undo.redone" : "undo.undone"), 1200);
}
function renderUndo() {
  const h = S.cur && HISTORY[S.cur.id];
  $("undo").disabled = !h?.undo.length;
  $("redo").disabled = !h?.redo.length;
}

// Settings of the current shot: applied locally, then saved with its key.
function patchTrace(part, immediate = false) {
  beginEdit();
  const tr = TR();
  for (const [k, v] of Object.entries(part)) {
    if (k === "shot") Object.assign(tr.shot, v);
    else if (k === "popups" || k === "color") tr[k] = v;
  }
  patch({ ...part, trace: tr.key }, immediate);
}
function patch(part, immediate = false) {
  beginEdit();
  if (part.trace && pending.trace && part.trace !== pending.trace) flush(); // never mix two shots in one save
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
  const body = pending; pending = {}; editOpen = false;
  const states = () => S.cur.traces.map((x) => x.status).join();
  const status = states();
  try {
    S.cur = await api("PUT", `/api/projects/${S.cur.id}`, body);
    S.version++;
    await loadOverlay(); showFrame(); renderStats(); renderList();
    if (states() !== status) { renderPanel(); renderTools(); renderMarks(); } // e.g. a drawn line made it renderable
  } catch (e) { toast(t("toast.saveFailed", { msg: serverText(e.message) })); }
}

// ---------------------------------------------------------------- frame display
// Some shot has a path to draw (previews then show the tracers).
function analysed() { return S.cur && S.cur.traces.some((x) => x.result || x.drawable); }

function frameUrl(i) {
  // Shapes are drawn by the editor canvas (ann=0) so they can be moved smoothly.
  return analysed() || S.wm ? `/api/projects/${S.cur.id}/preview/${i}.jpg?ann=0&v=${S.version}` : `/api/projects/${S.cur.id}/frames/${i}.jpg`;
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
  if (view.style.width === "" || view.dataset.aspect !== String(view.naturalWidth / view.naturalHeight)) {
    view.dataset.aspect = String(view.naturalWidth / view.naturalHeight);
    setZoom(S.zoom);
  }
  if (queued) { const u = queued; queued = null; loading = true; view.src = u; return; }
  sizeCanvas(); drawOverlay();
  if (S.playing) setTimeout(tick, 0);
};
view.onerror = () => { loading = false; };

async function loadOverlay() {
  S.overlay = S.cur && TR().result ? await api("GET", `/api/projects/${S.cur.id}/overlay/${S.frame}?trace=${TR().key}`) : null;
}

// ---------------------------------------------------------------- zoom
const ZOOM_MAX = 4, wrap = $("stageWrap");
// Image size that fits the window (zoom 1), from the wrap's inner size and the aspect.
function fitSize() {
  const cs = getComputedStyle(wrap);
  const w = wrap.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
  const h = Math.min(wrap.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom), innerHeight - 210);
  const aspect = (view.naturalWidth || 16) / (view.naturalHeight || 9);
  return Math.max(50, Math.min(w, h * aspect));
}
// Zoom to z, keeping the image point under (cx, cy) (client px; default: the centre) still.
function setZoom(z, cx, cy) {
  z = Math.max(1, Math.min(ZOOM_MAX, z));
  const r = wrap.getBoundingClientRect();
  cx ??= r.left + wrap.clientWidth / 2; cy ??= r.top + wrap.clientHeight / 2;
  const before = view.getBoundingClientRect();
  const fx = (cx - before.left) / (before.width || 1), fy = (cy - before.top) / (before.height || 1);
  S.zoom = z;
  wrap.classList.toggle("zoomed", z > 1);
  view.style.width = `${Math.round(fitSize() * z)}px`; // 100% fills the space, even for small previews
  sizeCanvas();
  if (z > 1) {
    const after = view.getBoundingClientRect();
    wrap.scrollLeft += after.left + fx * after.width - cx;
    wrap.scrollTop += after.top + fy * after.height - cy;
  }
  $("zoomFit").textContent = `${Math.round(z * 100)}%`;
  drawOverlay();
}
const zoomStep = (dir) => setZoom(dir > 0 ? S.zoom * 1.25 : S.zoom / 1.25);
$("zoomIn").onclick = () => zoomStep(1);
$("zoomOut").onclick = () => zoomStep(-1);
$("zoomFit").onclick = () => setZoom(1);
$("wide").onclick = () => {
  document.body.classList.toggle("wide");
  $("wide").classList.toggle("on", document.body.classList.contains("wide"));
  requestAnimationFrame(() => setZoom(S.zoom));
};
// Ctrl/⌘ + wheel and trackpad pinch zoom around the pointer; plain scrolling pans.
wrap.addEventListener("wheel", (e) => {
  if (!(e.ctrlKey || e.metaKey) || !S.cur) return;
  e.preventDefault();
  // About 25% per mouse-wheel notch; trackpad pinch sends many small deltas.
  setZoom(S.zoom * Math.exp(-Math.max(-50, Math.min(50, e.deltaY)) * 0.0045), e.clientX, e.clientY);
}, { passive: false });
// Drag to pan (empty spot of the zoomed image, or the middle mouse button anywhere).
let pan = null;
function startPan(e) {
  pan = { x: e.clientX, y: e.clientY, l: wrap.scrollLeft, t: wrap.scrollTop };
  wrap.classList.add("panning");
}
wrap.addEventListener("mousedown", (e) => { if (e.button === 1 && S.zoom > 1) { e.preventDefault(); startPan(e); } });
window.addEventListener("mousemove", (e) => {
  if (!pan) return;
  wrap.scrollLeft = pan.l - (e.clientX - pan.x); wrap.scrollTop = pan.t - (e.clientY - pan.y);
});
window.addEventListener("mouseup", () => { if (pan) { pan = null; wrap.classList.remove("panning"); } });

function sizeCanvas() {
  canvas.width = view.clientWidth; canvas.height = view.clientHeight;
  canvas.style.width = view.clientWidth + "px"; canvas.style.height = view.clientHeight + "px";
}
// preview px -> canvas px
const k = () => view.clientWidth / (view.naturalWidth || 1);
const scale = () => S.cur.info.scale; // video px per preview px

// video px <-> canvas px
const v2c = ([x, y]) => [(x / scale()) * k(), (y / scale()) * k()];
const c2v = ([x, y]) => [+((x / k()) * scale()).toFixed(1), +((y / k()) * scale()).toFixed(1)];
const near = (a, b, r = 12) => Math.hypot(a[0] - b[0], a[1] - b[1]) < r;

// Ball path from its start / apex / landing handles: a quadratic curve through the apex.
function bezier([s0, a, e], n = 64) {
  const c = [2 * a[0] - (s0[0] + e[0]) / 2, 2 * a[1] - (s0[1] + e[1]) / 2];
  return Array.from({ length: n + 1 }, (_, i) => {
    const u = i / n, w0 = (1 - u) ** 2, w1 = 2 * (1 - u) * u, w2 = u * u;
    return [+(w0 * s0[0] + w1 * c[0] + w2 * e[0]).toFixed(1), +(w0 * s0[1] + w1 * c[1] + w2 * e[1]).toFixed(1)];
  });
}
function commitPath(ctrl, { frame = S.frame, auto = false } = {}) {
  patchTrace({ shot: { manual_ctrl: ctrl, manual: bezier(ctrl), manual_frame: frame, manual_auto: auto } }, true);
  renderMarks();
}
// Apex handle for a new path: midway, raised by a third of the distance.
function autoApex(a, b) {
  const dist = Math.hypot(b[0] - a[0], b[1] - a[1]);
  return [(a[0] + b[0]) / 2, Math.min(a[1], b[1]) - 0.35 * dist];
}
// Landing tool: move the end of a drawn path, edit the analysed flight, or, before any
// analysis, make a stand-in path from the ball that analysis later replaces.
function setLanding(pt) {
  const tr = TR(), sh = tr.shot;
  if (sh.mode !== "flight") { toast(t("toast.landingFlightOnly")); return false; }
  if (sh.manual_ctrl) {
    const ctrl = sh.manual_ctrl.map((c) => [...c]);
    ctrl[2] = pt;
    commitPath(ctrl, { frame: sh.manual_frame, auto: sh.manual_auto });
  } else if (sh.manual) { // an older freehand line: keep its start and top, turn it into handles
    const top = sh.manual.reduce((m, q) => (q[1] < m[1] ? q : m));
    commitPath([sh.manual[0], top, pt], { frame: sh.manual_frame });
  } else if (tr.result) {
    patchTrace({ shot: { landing: pt }, landing_frame: S.frame }, true);
  } else if (sh.ball) {
    patchTrace({ shot: { landing: pt } });
    commitPath([sh.ball, autoApex(sh.ball, pt), pt].map((c) => c.map((v) => +v.toFixed(1))), { frame: sh.ball_frame, auto: true });
    toast(t("toast.landingPreview"), 4000);
  } else { toast(t("toast.landingNeedsBall")); return false; }
  return true;
}
// Handles of the current shot's drawn path, in canvas px (live while dragging).
function pathCtrl() {
  if (S.drag?.what === "ctrl") return S.drag.ctrl;
  const c = TR().shot.manual_ctrl;
  return c ? c.map(v2c) : null;
}

function drawOverlay() {
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  if (!S.cur) return;
  drawAnnotations();
  if (S.playing) return;
  const kk = k();
  const ball = TR().shot.ball;
  if (ball && (!TR().result || S.frame === TR().shot.ball_frame) && !S.overlay) ring(...v2c(ball), 11, "#ffd400");
  const d = S.draft;
  if (d?.kind === "path") { ring(...d.start, 8, "#ffd400"); label(d.start[0] + 10, d.start[1] - 8, t("handle.start")); }
  if (!$("guide").checked) return;
  drawSwingPoints();
  const ctrl = pathCtrl();
  if (ctrl) {
    dashed(bezier(ctrl, 48));
    ring(...ctrl[0], 8, "#ffd400"); label(ctrl[0][0] + 10, ctrl[0][1] - 8, t("handle.start"));
    diamond(...ctrl[1], "#ff9f1a"); label(ctrl[1][0] + 10, ctrl[1][1] - 8, t("handle.apex"));
    handle(...ctrl[2], "#00e0ff", t("handle.landing"));
    return;
  }
  const o = S.overlay;
  if (!o || !o.path) return;
  dashed(o.path.map(([x, y]) => [x * kk, y * kk]));
  ring(o.start[0] * kk, o.start[1] * kk, 8, "#ffd400");
  if (o.mode === "manual") return; // a freehand line from an older version: redraw to edit
  const lp = S.drag?.what === "landing" ? S.drag.pos : [o.landing[0] * kk, o.landing[1] * kk];
  handle(lp[0], lp[1], "#00e0ff", t("handle.landing"));
  if (o.mode === "flight") {
    const ap = S.drag?.what === "apex" ? S.drag.pos : [o.apex[0] * kk, o.apex[1] * kk];
    diamond(ap[0], ap[1], "#ff9f1a"); label(ap[0] + 10, ap[1] - 8, t("handle.apex"));
  }
}

// Club head / hands points placed by the user for the current shot.
function swingPoints() {
  const pts = TR().shot.swing_points.map(([f, x, y]) => [f, ...v2c([x, y])]);
  const d = S.drag;
  if (d?.what === "swingpt") { const i = pts.findIndex((q) => q[0] === d.frame); if (i >= 0) pts[i] = [d.frame, ...d.pos]; }
  return pts;
}
function drawSwingPoints() {
  const pts = swingPoints();
  if (!pts.length) return;
  const c = S.cur.swing.color;
  ctx.save(); ctx.setLineDash([3, 4]); ctx.strokeStyle = c + "aa"; ctx.lineWidth = 1.2;
  ctx.beginPath(); pts.forEach(([, x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y))); ctx.stroke(); ctx.restore();
  for (const [f, x, y] of pts) {
    const now = f === S.frame;
    ctx.beginPath(); ctx.arc(x, y, now ? 6 : 3, 0, 7); ctx.fillStyle = now ? "#fff" : c; ctx.fill();
    ctx.strokeStyle = "#000"; ctx.lineWidth = 1.2; ctx.stroke();
  }
}
function setSwingPoints(pts) {
  patchTrace({ shot: { swing_points: pts } }, true);
  if (S.tab === "swing") renderPanel();
}
function addSwingPoint([x, y]) {
  const pts = TR().shot.swing_points.filter((q) => q[0] !== S.frame);
  pts.push([S.frame, x, y]); pts.sort((a, b) => a[0] - b[0]);
  setSwingPoints(pts);
  if (!S.cur.swing.enabled) patch({ swing: { enabled: true } }, true);
  goto(S.frame + S.swingStep); // ready for the next position
}
function removeSwingPoint(frame) {
  setSwingPoints(TR().shot.swing_points.filter((q) => q[0] !== frame));
  drawOverlay();
}

// On-screen shapes (head / hip markers). The editor draws them itself so they can be
// moved smoothly; exported videos and previews without `ann=0` get them from the server.
function annBase() { return Math.max(1.5, canvas.height / 360); }
function drawShape(a, pts) {
  const [p0, p1] = pts;
  const w = Math.max(1, annBase() * a.width);
  for (const [c, lw] of [["#000", w + 2], [a.color, w]]) {
    ctx.beginPath(); ctx.strokeStyle = c; ctx.lineWidth = lw; ctx.lineCap = "round";
    if (a.kind === "circle") ctx.arc(p0[0], p0[1], Math.hypot(p1[0] - p0[0], p1[1] - p0[1]), 0, 7);
    else { ctx.moveTo(...p0); ctx.lineTo(...p1); }
    ctx.stroke();
  }
}
function drawAnnotations() {
  S.cur.annotations.forEach((a, i) => {
    const pts = a.points.map(v2c);
    drawShape(a, pts);
    if (i === S.sel && !S.playing) for (const q of pts) { ctx.beginPath(); ctx.arc(q[0], q[1], 5, 0, 7); ctx.fillStyle = "#fff"; ctx.fill(); ctx.strokeStyle = "#000"; ctx.lineWidth = 1.5; ctx.stroke(); }
  });
  const d = S.draft;
  if (d && d.kind !== "path") drawShape({ kind: d.kind, color: S.annStyle.color, width: S.annStyle.width }, [d.a, d.b]);
}
// Index of the shape under canvas point q, and which part: 0/1 = handle, "body".
function hitShape(q) {
  for (let i = S.cur.annotations.length - 1; i >= 0; i--) {
    const a = S.cur.annotations[i], [p0, p1] = a.points.map(v2c);
    if (i === S.sel) { if (near(q, p0, 9)) return [i, 0]; if (near(q, p1, 9)) return [i, 1]; }
    let dist;
    if (a.kind === "circle") dist = Math.abs(Math.hypot(q[0] - p0[0], q[1] - p0[1]) - Math.hypot(p1[0] - p0[0], p1[1] - p0[1]));
    else {
      const [dx, dy] = [p1[0] - p0[0], p1[1] - p0[1]], len2 = dx * dx + dy * dy || 1;
      const u = Math.max(0, Math.min(1, ((q[0] - p0[0]) * dx + (q[1] - p0[1]) * dy) / len2));
      dist = Math.hypot(q[0] - p0[0] - u * dx, q[1] - p0[1] - u * dy);
    }
    if (dist < 8) return [i, "body"];
  }
  return null;
}
function selectShape(i) { S.sel = i; drawOverlay(); if (S.tab === "draw") renderPanel(); }

function dashed(pts) {
  ctx.save(); ctx.setLineDash([5, 5]); ctx.strokeStyle = "#ffffffaa"; ctx.lineWidth = 1.2;
  ctx.beginPath(); pts.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y))); ctx.stroke(); ctx.restore();
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
// What a plain click (no tool) would grab at canvas point q.
function grabAt(q) {
  const guide = $("guide").checked, ctrl = guide && pathCtrl();
  if (guide && swingPoints().some((p) => p[0] === S.frame && near(q, [p[1], p[2]], 10))) return { what: "swingpt", frame: S.frame };
  if (ctrl) { const i = ctrl.findIndex((c) => near(q, c)); if (i >= 0) return { what: "ctrl", i }; }
  const o = S.overlay, kk = k();
  if (o && o.mode !== "manual" && !ctrl && guide) {
    if (near(q, [o.landing[0] * kk, o.landing[1] * kk])) return { what: "landing" };
    if (o.mode === "flight" && near(q, [o.apex[0] * kk, o.apex[1] * kk])) return { what: "apex" };
  }
  const hit = hitShape(q);
  return hit ? { what: "ann", i: hit[0], part: hit[1] } : null;
}
canvas.addEventListener("mousedown", (e) => {
  if (!S.cur) return;
  const q = eventPos(e), [vx, vy] = c2v(q), kk = k();
  if (S.tool === "swing") { stop(); addSwingPoint([vx, vy]); return; }
  if (S.tool === "circle" || S.tool === "line") { stop(); S.sel = null; S.draft = { kind: S.tool, a: q, b: q }; return; }
  if (S.tool === "path") {
    stop();
    if (S.draft?.kind !== "path") { S.draft = { kind: "path", start: q }; renderTools(); drawOverlay(); return; }
    const a = S.draft.start;
    S.draft = null;
    if (Math.hypot(q[0] - a[0], q[1] - a[1]) < 15) { toast(t("toast.drawShort")); drawOverlay(); return; }
    commitPath([a, autoApex(a, q), q].map(c2v));
    S.tool = null; renderTools(); renderPanel(); toast(t("toast.drawn"));
    return;
  }
  if (S.tool === "ball") {
    patchTrace({ shot: { ball: [vx, vy], ball_frame: S.frame } }, true);
    S.tool = null; renderTools(); drawOverlay(); renderList(); renderPanel(); renderMarks();
    toast(t("toast.ballSet"));
    return;
  }
  if (S.tool === "landing") {
    if (!setLanding([vx, vy])) return;
    S.tool = null; renderTools(); drawOverlay(); if (S.tab === "shot") renderPanel();
    return;
  }
  const g = grabAt(q);
  if (!g) {
    if (S.sel != null) selectShape(null);
    if (S.zoom > 1 && e.button === 0) startPan(e);
    return;
  }
  stop();
  if (g.what === "ctrl") S.drag = { what: "ctrl", i: g.i, ctrl: pathCtrl().map((c) => [...c]) };
  else if (g.what === "swingpt") S.drag = { what: "swingpt", frame: g.frame, pos: q };
  else if (g.what === "landing") S.drag = { what: "landing", pos: q };
  else if (g.what === "apex") {
    const o = S.overlay;
    S.drag = { what: "apex", pos: [o.apex[0] * kk, q[1]], startY: o.start[1] * kk, apexY: o.apex[1] * kk, h: TR().shot.height };
  } else {
    if (S.sel !== g.i) selectShape(g.i);
    S.drag = { what: "ann", i: g.i, part: g.part, from: q, orig: S.cur.annotations[g.i].points.map(v2c), moved: false };
  }
});
canvas.addEventListener("mousemove", (e) => {
  const q = eventPos(e);
  if (S.draft && S.draft.kind !== "path") { S.draft.b = q; drawOverlay(); return; }
  const d = S.drag;
  if (!d) {
    canvas.style.cursor = S.tool ? "crosshair" : S.cur && grabAt(q) ? "grab" : "default";
    return;
  }
  if (d.what === "ctrl") d.ctrl[d.i] = q;
  else if (d.what === "swingpt") d.pos = q;
  else if (d.what === "apex") d.pos = [d.pos[0], q[1]];
  else if (d.what === "landing") d.pos = q;
  else {
    if (!d.moved) { beginEdit(); d.moved = true; } // one undo step per drag
    const [dx, dy] = [q[0] - d.from[0], q[1] - d.from[1]];
    const pts = d.orig.map((p, j) => (d.part === "body" || d.part === j ? [p[0] + dx, p[1] + dy] : p));
    S.cur.annotations[d.i].points = pts.map(c2v);
  }
  drawOverlay();
});
window.addEventListener("mouseup", () => {
  const dr = S.draft;
  if (dr && dr.kind !== "path") {
    S.draft = null;
    if (Math.hypot(dr.b[0] - dr.a[0], dr.b[1] - dr.a[1]) < 6) { drawOverlay(); return; }
    const shape = { kind: dr.kind, points: [c2v(dr.a), c2v(dr.b)], color: S.annStyle.color, width: S.annStyle.width };
    patch({ annotations: [...S.cur.annotations, shape] }, true);
    selectShape(S.cur.annotations.length - 1);
    return;
  }
  const d = S.drag; if (!d) return;
  S.drag = null;
  const kk = k(), s = scale();
  if (d.what === "ctrl") commitPath(d.ctrl.map(c2v), { frame: TR().shot.manual_frame });
  else if (d.what === "swingpt") setSwingPoints(TR().shot.swing_points.map((q) => (q[0] === d.frame ? [d.frame, ...c2v(d.pos)] : q)));
  else if (d.what === "ann") { if (d.moved) patch({ annotations: S.cur.annotations }, true); }
  else if (d.what === "landing") {
    patchTrace({ shot: { landing: [(d.pos[0] / kk) * s, (d.pos[1] / kk) * s] }, landing_frame: S.frame }, true);
    if (S.tab === "shot") renderPanel();
  } else {
    const rise0 = d.startY - d.apexY, rise1 = d.startY - d.pos[1];
    if (rise0 > 2) {
      const h = Math.max(0.2, Math.min(3, (d.h * rise1) / rise0));
      patchTrace({ shot: { height: +h.toFixed(3) } }, true);
    }
  }
  drawOverlay();
});
function deleteShape() {
  if (S.sel == null) return;
  patch({ annotations: S.cur.annotations.filter((_, i) => i !== S.sel) }, true);
  S.sel = null; drawOverlay(); if (S.tab === "draw") renderPanel();
}

// ---------------------------------------------------------------- tools / marks
function renderTools() {
  renderTraces();
  renderUndo();
  document.querySelectorAll(".tool").forEach((b) => b.classList.toggle("active", b.dataset.tool === S.tool));
  $("hint").textContent = S.tool ? t(S.tool === "path" && S.draft ? "hint.path2" : `hint.${S.tool}`) : S.cur && TR().result && TR().result.mode !== "manual" ? t("hint.edit") : "";
  canvas.style.cursor = S.tool ? "crosshair" : "default";
}
function renderMarks() {
  const m = $("marks"); m.innerHTML = "";
  if (!S.cur) return;
  const n = Math.max(1, S.cur.info.frames - 1);
  const add = (f, cls) => { const s = document.createElement("span"); s.className = cls; s.style.left = `${(f / n) * 100}%`; m.appendChild(s); };
  for (const tr of S.cur.traces) {
    const dim = tr.key === TR().key ? "" : " other";
    if (tr.shot.ball) add(tr.shot.ball_frame, "ball" + dim);
    if (tr.result) add(tr.result.impact, "impact" + dim);
  }
}

// ---------------------------------------------------------------- panel
const T = {
  shot: renderShot, style: renderStyle, popups: renderPopups, draw: renderDraw, swing: renderSwing, export: renderExport,
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
  const p = TR(), sh = p.shot, n = S.cur.traces.indexOf(p) + 1;
  const g = group(body, S.cur.traces.length > 1 ? t("trace.name", { n }) : t("shot.group"));
  seg(g, t("shot.mode"), [["flight", t("shot.flight")], ["putt", t("shot.putt")]], sh.mode, (v) => { patchTrace({ shot: { mode: v } }); renderPanel(); });
  if (sh.mode === "flight")
    select_(g, t("shot.type"), ["chip", "pitch", "full", "drive"].map((k) => [k, t(`shot.${k}`)]), sh.shot, (v) => patchTrace({ shot: { shot: v } }));
  const ball = sh.ball ? t("shot.ballAt", { f: sh.ball_frame, xy: sh.ball.map((v) => Math.round(v)).join(", ") }) : t("shot.unset");
  g.appendChild(el(`<div class="row"><label>${t("shot.ball")}</label><span>${ball}</span></div>`));
  const imp = el(`<div class="row"><label>${t("shot.impact")}</label><input type="number" min="0" placeholder="${t("shot.auto")}" value="${sh.impact ?? ""}"></div>`);
  imp.querySelector("input").onchange = (e) => patchTrace({ shot: { impact: e.target.value === "" ? null : +e.target.value } });
  g.appendChild(imp);

  const a = p.job;
  const running = p.status === "analysing" || a?.status === "running" || a?.status === "queued";
  const btn = el(`<button class="btn primary wide" ${!sh.ball || running ? "disabled" : ""}>${running ? t("shot.analysing") : p.result ? t("shot.reanalyse") : t("shot.analyse")}</button>`);
  btn.onclick = async () => {
    await flush();
    try { await api("POST", `/api/projects/${S.cur.id}/analyze?trace=${p.key}`); p.status = "analysing"; refresh(); renderPanel(); }
    catch (e) { toast(serverText(e.message)); }
  };
  g.appendChild(btn);
  if (p.status === "error" && p.error) g.appendChild(el(`<p class="note err">${esc(serverText(p.error))}</p>`));
  if (!sh.ball) g.appendChild(el(`<p class="note">${t("shot.help")}</p>`));

  const st = el(`<div class="group" id="stats"></div>`); body.appendChild(st); renderStats();

  const cg = group(body, t("trace.line"));
  toggle(cg, t("trace.ownColor"), p.color != null, (v) => { patchTrace({ color: v ? S.cur.style.color : null }); renderPanel(); });
  if (p.color != null) color(cg, t("style.color"), p.color, (v) => { patchTrace({ color: v }); renderTraces(); });

  const mg = group(body, t("manual.group"));
  const mrow = el(`<div class="row"><label>${t("manual.status")}</label><span>${sh.manual ? t("manual.drawn", { f: sh.manual_frame }) : t("manual.none")}</span></div>`);
  mg.appendChild(mrow);
  const drawBtn = el(`<button class="btn small">${sh.manual ? t("manual.redraw") : t("manual.draw")}</button>`);
  drawBtn.onclick = () => { S.tool = "path"; S.draft = null; renderTools(); };
  mg.appendChild(drawBtn);
  if (sh.manual) {
    const clr = el(`<button class="btn small" style="margin-left:6px">${t("manual.clear")}</button>`);
    clr.onclick = () => { patchTrace({ shot: { manual: null, manual_ctrl: null } }, true); renderPanel(); };
    mg.appendChild(clr);
    if (p.result) {
      const auto = sh.flight_time == null;
      toggle(mg, t("edit.autoTime"), auto, (v) => { patchTrace({ shot: { flight_time: v ? null : +(p.result.duration.toFixed(1)) } }); renderPanel(); });
      if (!auto) slider(mg, t("edit.time"), sh.flight_time, 0.5, 9, 0.1, (v) => patchTrace({ shot: { flight_time: v } }), (v) => `${v.toFixed(1)}s`);
      else distanceNote(mg);
    }
  }
  mg.appendChild(el(`<p class="note">${t("manual.help")}</p>`));

  if (p.result && sh.mode === "flight" && !sh.manual) {
    const e = group(body, t("edit.group"));
    const auto = sh.flight_time == null;
    toggle(e, t("edit.autoTime"), auto, (v) => { patchTrace({ shot: { flight_time: v ? null : +(p.result.duration.toFixed(1)) } }); renderPanel(); });
    if (!auto) slider(e, t("edit.time"), sh.flight_time, 0.5, 9, 0.1, (v) => patchTrace({ shot: { flight_time: v } }), (v) => `${v.toFixed(1)}s`);
    else distanceNote(e);
    slider(e, t("edit.height"), sh.height, 0.3, 2.5, 0.05, (v) => patchTrace({ shot: { height: v } }), (v) => `×${v.toFixed(2)}`);
    const row = el(`<div class="row"><label>${t("edit.landing")}</label><span>${sh.landing ? t("edit.set") : t("shot.auto")}</span></div>`);
    if (sh.landing) {
      const b = el(`<button class="btn small">${t("edit.clear")}</button>`);
      b.onclick = () => { patchTrace({ shot: { landing: null } }, true); renderPanel(); };
      row.appendChild(b);
    }
    e.appendChild(row);
    e.appendChild(el(`<p class="note">${t("edit.help")}</p>`));
  }

  const rb = el(`<button class="btn wide danger" style="margin-top:12px">${t("reset.button")}</button>`);
  rb.onclick = () => resetProject(S.cur);
  body.appendChild(rb);
}

function distanceNote(parent) {
  const dt = TR().result?.distance_time;
  parent.appendChild(el(`<p class="note">${dt != null ? t("edit.fromDistance", { s: dt.toFixed(1) }) : t("edit.distanceHint")}</p>`));
}

function renderStats() {
  const box = document.getElementById("stats"); if (!box) return;
  const r = TR().result;
  if (!r) { box.innerHTML = ""; return; }
  const fps = S.cur.info.fps;
  box.innerHTML = `<h4>${t("stats.title")}</h4><div class="stat">
    <div><b>${r.impact.toFixed(1)}</b><span>${t("stats.impact", { s: (r.impact / fps).toFixed(2) })}</span></div>
    <div><b>${r.tracked}</b><span>${t("stats.tracked")}</span></div>
    <div><b>${r.duration.toFixed(1)}s</b><span>${r.mode === "putt" ? t("stats.roll") : t("stats.flight")}</span></div>
    <div><b>${r.mode === "putt" ? "—" : r.apex_time.toFixed(1) + "s"}</b><span>${t("stats.apex")}</span></div></div>`;
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
  toggle(e, t("style.keep"), st.hold == null, (v) => { patch({ style: { hold: v ? null : 0 } }); renderPanel(); });
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
      const list = [...TR().popups, { kind, label, value: value(), anchor, delay: anchor === "landing" ? 0.2 : 0.3, hide: "after_shot", duration: null, color: "#111111", accent: traceColor(TR()) }];
      patchTrace({ popups: list }); renderPanel();
    };
    chips.appendChild(b);
  }
  g.appendChild(chips);
  g.appendChild(el(`<p class="note">${t("popup.note")}</p>`));
  if (S.cur.traces.length > 1) g.appendChild(el(`<p class="note">${t("trace.popupsFor", { n: S.cur.traces.indexOf(TR()) + 1 })}</p>`));
  TR().popups.forEach((pp, i) => {
    const c = el(`<div class="card"><div class="head"><span>${t(`popup.${pp.kind}`)}</span><button class="btn small">${t("popup.delete")}</button></div></div>`);
    const upd = (part) => { const list = TR().popups.map((x, j) => (j === i ? { ...x, ...part } : x)); patchTrace({ popups: list }); };
    c.querySelector(".btn").onclick = () => { patchTrace({ popups: TR().popups.filter((_, j) => j !== i) }); renderPanel(); };
    const lab = el(`<div class="row"><label>${t("popup.label")}</label><input type="text" value="${esc(pp.label)}"></div>`);
    lab.querySelector("input").oninput = (e) => upd({ label: e.target.value });
    const val = el(`<div class="row"><label>${t("popup.value")}</label><input type="text" value="${esc(pp.value)}" placeholder="${pp.kind === "hangtime" ? t("popup.auto") : ""}"></div>`);
    val.querySelector("input").oninput = (e) => upd({ value: e.target.value });
    c.append(lab, val);
    select_(c, t("popup.position"), ANCHORS.map((a) => [a, t(`anchor.${a}`)]), pp.anchor, (v) => upd({ anchor: v }));
    slider(c, t("popup.delay"), pp.delay, 0, 5, 0.1, (v) => upd({ delay: v }), (v) => `${v.toFixed(1)}s`);
    select_(c, t("popup.hide"), ["after_shot", "end", "time"].map((h) => [h, t(`popup.hide.${h}`)]), pp.hide || "after_shot",
      (v) => { upd({ hide: v, duration: v === "time" ? pp.duration ?? 3 : null }); renderPanel(); });
    if (pp.hide === "time" && pp.duration != null) slider(c, t("popup.duration"), pp.duration, 0.5, 10, 0.5, (v) => upd({ duration: v }), (v) => `${v.toFixed(1)}s`);
    color(c, t("popup.bg"), pp.color, (v) => upd({ color: v }));
    color(c, t("popup.accent"), pp.accent, (v) => upd({ accent: v }));
    body.appendChild(c);
  });
}

function renderDraw(body) {
  const g = group(body, t("draw.tools"));
  const tools = el(`<div class="chips"></div>`);
  for (const [tool, key] of [["circle", "draw.circle"], ["line", "draw.line"]]) {
    const b = el(`<button class="btn small ${S.tool === tool ? "primary" : ""}">${t(key)}</button>`);
    b.onclick = () => { S.tool = S.tool === tool ? null : tool; renderTools(); renderPanel(); };
    tools.appendChild(b);
  }
  g.appendChild(tools);
  const saveStyle = () => localStorage.setItem("shottracer.annStyle", JSON.stringify(S.annStyle));
  color(g, t("draw.newColor"), S.annStyle.color, (v) => { S.annStyle.color = v; saveStyle(); });
  slider(g, t("style.width"), S.annStyle.width, 0.3, 5, 0.1, (v) => { S.annStyle.width = v; saveStyle(); }, (v) => `×${v.toFixed(1)}`);
  g.appendChild(el(`<p class="note">${t("draw.help")}</p>`));

  const list = S.cur.annotations;
  if (!list.length) return;
  const l = group(body, t("draw.shapes", { n: list.length }));
  const upd = (i, part) => { patch({ annotations: S.cur.annotations.map((a, j) => (j === i ? { ...a, ...part } : a)) }); drawOverlay(); };
  list.forEach((a, i) => {
    const c = el(`<div class="card ${i === S.sel ? "on" : ""}"><div class="head"><span>${t(a.kind === "circle" ? "draw.circle" : "draw.line")} ${i + 1}</span><button class="btn small">${t("popup.delete")}</button></div></div>`);
    c.querySelector(".head span").onclick = () => selectShape(i);
    c.querySelector(".btn").onclick = () => { S.sel = i; deleteShape(); };
    color(c, t("style.color"), a.color, (v) => upd(i, { color: v }));
    slider(c, t("style.width"), a.width, 0.3, 5, 0.1, (v) => upd(i, { width: v }), (v) => `×${v.toFixed(1)}`);
    l.appendChild(c);
  });
  const clear = el(`<button class="btn small danger">${t("draw.clearAll")}</button>`);
  clear.onclick = () => { patch({ annotations: [] }, true); S.sel = null; drawOverlay(); renderPanel(); };
  l.appendChild(clear);
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

  const pts = TR().shot.swing_points, n = S.cur.traces.indexOf(TR()) + 1;
  const m = group(body, S.cur.traces.length > 1 ? `${t("swing.points")} · ${t("trace.name", { n })}` : t("swing.points"));
  const tool = el(`<button class="btn small ${S.tool === "swing" ? "primary" : ""}">${t("tool.swing")}</button>`);
  tool.onclick = () => { S.tool = S.tool === "swing" ? null : "swing"; renderTools(); renderPanel(); };
  m.appendChild(tool);
  slider(m, t("swing.step"), S.swingStep, 1, 5, 1, (v) => { S.swingStep = v; localStorage.setItem("shottracer.swingStep", v); }, (v) => t("swing.frames", { n: v }));
  m.appendChild(el(`<p class="note">${t(pts.length >= 2 ? "swing.pointsOn" : "swing.pointsHelp", { n: pts.length })}</p>`));
  if (!pts.length) return;
  const chips = el(`<div class="chips"></div>`);
  for (const [f] of pts) {
    const c = el(`<button class="btn small ${f === S.frame ? "primary" : ""}">${f} <span class="x">✕</span></button>`);
    c.onclick = (e) => { if (e.target.classList.contains("x")) removeSwingPoint(f); else { stop(); goto(f); renderPanel(); } };
    chips.appendChild(c);
  }
  m.appendChild(chips);
  const clear = el(`<button class="btn small danger" style="margin-top:6px">${t("draw.clearAll")}</button>`);
  clear.onclick = () => { setSwingPoints([]); drawOverlay(); };
  m.appendChild(clear);
}

// ---------------------------------------------------------------- watermark
async function loadWatermark() {
  try { S.wm = (await api("GET", "/api/watermark")).enabled; } catch {}
}
function watermarkChanged(on) {
  S.wm = on; S.version++; showFrame(); renderPanel();
}
function openWatermarkDialog() {
  const dlg = $("wmDialog"), input = $("wmPassword"), err = $("wmError");
  input.value = ""; err.hidden = true;
  dlg.showModal(); input.focus();
}
$("wmForm").onsubmit = async (e) => {
  e.preventDefault();
  const ok = $("wmOk"); ok.disabled = true;
  try {
    await api("POST", "/api/watermark/unlock", { password: $("wmPassword").value });
    $("wmDialog").close(); watermarkChanged(false); toast(t("wm.removed"));
  } catch {
    $("wmError").hidden = false; $("wmPassword").select();
  } finally { ok.disabled = false; }
};
$("wmCancel").onclick = () => $("wmDialog").close();
function watermarkGroup(body) {
  const g = group(body, t("wm.group"));
  g.appendChild(el(`<div class="row"><label>${esc(t("wm.text"))}</label><span>${S.wm ? t("wm.on") : t("wm.off")}</span></div>`));
  const b = el(`<button class="btn small">${S.wm ? t("wm.remove") : t("wm.restore")}</button>`);
  b.onclick = async () => {
    if (S.wm) { openWatermarkDialog(); return; }
    await api("POST", "/api/watermark/lock"); watermarkChanged(true);
  };
  g.appendChild(b);
  g.appendChild(el(`<p class="note">${t(S.wm ? "wm.noteOn" : "wm.noteOff")}</p>`));
}

function renderExport(body) {
  watermarkGroup(body);
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
  const btn = el(`<button class="btn primary wide" ${!(analysed() || S.cur.annotations.length) || busy ? "disabled" : ""}>${busy ? t("export.rendering", { p: Math.round(r.progress * 100) }) : t("export.render")}</button>`);
  btn.onclick = async () => { await flush(); await api("POST", "/api/render", { ids: [S.cur.id] }); refresh(); };
  e.appendChild(btn);
  if (r?.status === "done" || (!r && S.cur.has_output)) {
    const dl = el(`<p><a class="btn wide" style="display:block;text-align:center;margin-top:8px;text-decoration:none" href="/api/projects/${S.cur.id}/output" download>${t("export.download")}</a></p>`);
    // The desktop app window has no download bar: save through a native dialog instead.
    dl.querySelector("a").onclick = async (ev) => {
      if (!window.pywebview?.api?.save_output) return;
      ev.preventDefault();
      const path = await window.pywebview.api.save_output(S.cur.id);
      if (path) toast(t("export.saved", { path }), 5000);
    };
    e.appendChild(dl);
  }
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
  const ids = S.projects.filter((p) => p.traces.some((x) => x.result) || p.annotations.length).map((p) => p.id);
  if (!ids.length) { toast(t("toast.noneReady")); return; }
  await api("POST", "/api/render", { ids }); toast(t("toast.queued", { n: ids.length })); refresh();
};
$("timeline").oninput = (e) => { stop(); goto(+e.target.value); };
$("prev").onclick = () => { stop(); goto(S.frame - 1); };
$("next").onclick = () => { stop(); goto(S.frame + 1); };
$("play").onclick = () => (S.playing ? stop() : play());
$("guide").onchange = drawOverlay;
document.querySelectorAll(".tool").forEach((b) => (b.onclick = () => {
  S.tool = S.tool === b.dataset.tool ? null : b.dataset.tool; S.draft = null; renderTools(); drawOverlay();
  if (S.tab === "swing" || S.tab === "draw") renderPanel();
}));
document.querySelectorAll("#tabs button").forEach((b) => (b.onclick = () => { S.tab = b.dataset.tab; renderPanel(); }));
window.addEventListener("resize", () => setZoom(S.zoom));
$("undo").onclick = () => undo();
$("redo").onclick = () => undo(true);
window.addEventListener("keydown", (e) => {
  const typing = e.target.tagName === "TEXTAREA" || (e.target.tagName === "INPUT" && ["text", "number", "search"].includes(e.target.type));
  const k = e.key.toLowerCase();
  if ((e.metaKey || e.ctrlKey) && (k === "z" || k === "y") && !typing && S.cur) {
    e.preventDefault();
    if (S.draft) { S.draft = null; renderTools(); drawOverlay(); return; } // cancel a shape being drawn
    undo(k === "y" || e.shiftKey);
    return;
  }
  if (e.target.tagName === "INPUT" || e.target.tagName === "SELECT" || !S.cur) return;
  if (e.key === "ArrowLeft") { stop(); goto(S.frame - (e.shiftKey ? 10 : 1)); }
  else if (e.key === "ArrowRight") { stop(); goto(S.frame + (e.shiftKey ? 10 : 1)); }
  else if (e.key === " ") { e.preventDefault(); S.playing ? stop() : play(); }
  else if (e.key === "b") { S.tool = "ball"; renderTools(); }
  else if (e.key === "l") { S.tool = "landing"; renderTools(); }
  else if (e.key === "d") { S.tool = "path"; S.draft = null; renderTools(); }
  else if (e.key === "c") { S.tool = "circle"; renderTools(); }
  else if (e.key === "s") { S.tool = "line"; renderTools(); }
  else if (e.key === "p") { S.tool = "swing"; renderTools(); }
  else if ((e.key === "+" || e.key === "=") && !e.metaKey && !e.ctrlKey) zoomStep(1);
  else if (e.key === "-" && !e.metaKey && !e.ctrlKey) zoomStep(-1);
  else if (e.key === "0" && !e.metaKey && !e.ctrlKey) setZoom(1);
  else if ((e.key === "Delete" || e.key === "Backspace") && S.sel != null) { e.preventDefault(); deleteShape(); }
  else if ((e.key === "Delete" || e.key === "Backspace") && TR().shot.swing_points.some((q) => q[0] === S.frame)) { e.preventDefault(); removeSwingPoint(S.frame); }
  else if (/^[1-4]$/.test(e.key) && S.cur.traces[+e.key - 1]) switchTrace(S.cur.traces[+e.key - 1].key);
  else if (e.key === "Escape") { S.tool = null; S.draft = null; S.sel = null; renderTools(); drawOverlay(); if (S.tab === "swing" || S.tab === "draw") renderPanel(); }
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
loadWatermark().then(refresh).then(() => { renderPanel(); if (S.projects.length) select(S.projects[S.projects.length - 1].id); poll(); });
