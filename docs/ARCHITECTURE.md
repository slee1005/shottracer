# shottracer — Architecture

Technical reference for developers (and coding agents) working on shottracer. User-facing
documentation is in [README.md](../README.md) / [README.ko.md](../README.ko.md); the
handoff notes and working rules are in [AGENTS.md](../AGENTS.md).

## 1. What the program does

A local golf-video editor. The user picks the ball once; the program detects the launch,
tracks the ball, fits a flight model, segments the golfer and finds the club-head path,
then renders a tracer, swing path, pop-up cards, shapes, logo and watermark onto the video.
Nothing leaves the machine (the only network access is the one-time, SHA-256-pinned pose
model download in developer mode).

Three front ends share one engine:

| Entry point | Module | Use |
| --- | --- | --- |
| `shottracer-ui` | `server.py` (`main`) | FastAPI server on `127.0.0.1:8765` + browser UI |
| `ShotTracer` app | `desktop.py` (via `packaging/launcher.py`) | Same server in a pywebview native window, bundled ffmpeg + model |
| `shottracer` | `cli.py` | Analyse + render one video, or render an editor project (`--project ID`) |

## 2. Module map

```
src/shottracer/
  settings.py    Pydantic models: Project, Trace, Shot, Style, SlowMo, Popup, Logo, Swing, Annotation
  engine.py      project storage, preview extraction, analysis pipeline, edit application, ShotPath
  server.py      FastAPI routes, job queues (analyze / render worker threads), project summaries
  desktop.py     standalone app: env setup, logging, pywebview window, native dialogs, drag&drop, --self-test
  cli.py         command line
  video.py       probe, analysis-resolution decoding, preview JPEGs, ffmpeg/OpenCV writer
  camera.py      per-frame similarity transforms (LK flow + RANSAC) -> reference frame
  ball.py        ball refinement at click, horizon estimate, StartPatch (address spot empty?), motion candidates
  trajectory.py  launch detection, track linking, perspective ballistic Model, refit, with_apex
  putt.py        template-matching tracker for rolling balls
  person.py      MediaPipe Pose Landmarker (masks + wrists), model download/verify
  swing.py       club-head / hands path detection, polar smoothing, densify, user swing points
  render.py      Renderer/Layer: tracer, cut-out, swing path, annotations, pop-ups, logo, watermark; slow-mo timeline; export
  popups.py      pop-up cards and logo with Pillow
  watermark.py   "Create by AJC_shottracer" badge + scrypt password lock
  web/           single-page editor: index.html, app.js (all UI logic), i18n.js (en/ko), style.css
packaging/       build.py (vendor ffmpeg/model, PyInstaller, DMG/ZIP, self-test), shottracer.spec, launcher.py, icons
.github/workflows/build-app.yml   manual "Build app" workflow (macOS arm64 + Windows x64)
tests/           pytest, pure numeric tests (no video files needed)
```

Dependencies: Python 3.13 (`.python-version`), managed by **uv**. OpenCV comes in
transitively as `opencv-contrib-python` through MediaPipe (it is not listed directly in
`pyproject.toml`); Pydantic comes through FastAPI. Dependency groups: `dev` (pytest),
`desktop` (pyinstaller, pywebview).

## 3. Data model (`settings.py`)

```
Project                      one video
 ├─ id, name, source, info   info: width, height, fps, frames, has_audio, work_width, work_height, scale
 ├─ status/error             aggregate of the traces (analysing > ready > error > new)
 ├─ style, slowmo, logo, swing, annotations      shared by all shots
 └─ traces[1..4]: Trace      one traced shot (MAX_TRACES = 4)
      ├─ key                 6-hex stable id; names shots/<key>/
      ├─ status, error
      ├─ shot: Shot          user inputs for this shot
      ├─ popups: [Popup]
      ├─ color               line colour override (None = style colour)
      └─ analysis: dict|None analysis results (see below)
```

Important `Shot` fields: `mode` (`flight`|`putt`), `ball_frame`, `ball`, `landing`,
`impact`, `shot` (`chip|pitch|full|drive` → `trajectory.SHOT_TYPES` flight-time limits),
`horizon`, `flight_time`, `apex_time`, `height` (0.2–3), `speed` (tracer speed 0.5–4),
`manual`/`manual_ctrl`/`manual_frame`/`manual_auto` (hand-drawn path), `swing_points`.

Backward-compatibility validators — keep them when changing models:
- `Project._single_shot_projects`: pre-multi-shot projects (top-level `shot`/`analysis`/`popups`) become one trace with key `legacy`.
- `Popup._keep_timed_popups`: popups saved before `hide` existed keep `hide="time"`.
- `Shot._one_point_per_frame`: swing points deduplicated per frame and sorted.

### `Trace.analysis` dict

| Key | Meaning |
| --- | --- |
| `mode` | `flight`, `putt` or `manual` (hand-drawn path / swing points without analysis) |
| `scale` | video px per analysis px |
| `work_width`, `frames`, `range` | analysis width, total frames, analysed frame range `[a0, a1]` |
| `start`, `radius`, `reference` | refined ball position (reference coords), radius, ball frame |
| `observations` | `[(frame, x, y), …]` tracked ball positions (reference analysis coords) |
| `impact` | impact time in (fractional) video frames |
| `horizon` | ground horizon row (flight) |
| `base_model` | the automatic fit (`Model.to_dict`) — never overwritten by edits |
| `model` | `base_model` + user edits, recomputed by `engine.apply_edits` |
| `swing` | `window`, `clubhead` rows `(t, hx, hy, r, theta)`, `hands` rows `(t, x, y)` |

## 4. Coordinate systems

Getting these wrong is the most common bug source.

| Space | Where it is used |
| --- | --- |
| **Video px** | All user inputs stored in `Shot` (`ball`, `landing`, `manual`, `swing_points`), `Annotation.points`. Each is relative to a specific frame (`ball_frame`, `manual_frame`, the point's own frame). |
| **Analysis px** | Downscaled frames (`work_width` 960, or 640 for clips > 900 frames). `scale = video / analysis`. Preview JPEGs are written at this resolution, so preview px == analysis px. |
| **Reference coords** | Analysis px mapped by `camera` transforms into the ball frame (`reference`). Observations, models and `ShotPath` points live here, so the tracer stays fixed to the scene while the camera moves. |
| **Output px** | Render size (source resolution for export, preview size for previews). `Layer.scale` maps analysis → output. |

Helpers: `camera.apply(T, pts)`, `camera.between(transforms, src, dst)`,
`engine.landing_ref(tr)`, `server._to_reference(...)`. `transforms.npy` stores one 3×3
matrix per video frame (frames outside the analysed range reuse the nearest one).

In the browser (`app.js`): `v2c` video→canvas, `c2v` canvas→video, `scale()` = video px per preview px.

## 5. On-disk layout

```
$SHOTTRACER_HOME (default ~/.shottracer/projects)/<pid>/
  project.json                 Project (atomic save)
  source.<ext>                 copy of the video (browser upload only)
  previews/NNNNN.jpg           analysis-resolution frames
  previews/.pending            present while extraction is unfinished (resumes next start)
  previews/.count              decoded frame count when finished
  shots/<trace key>/masks/NNNNN.png   golfer masks (cut-out)
  shots/<trace key>/transforms.npy    camera transforms
  logo.<ext>                   uploaded logo
  <name>_traced.mp4            last render
~/.shottracer/settings.json    machine settings (watermark on/off)
~/.shottracer/shottracer.log   app log (packaged app only)
~/.shottracer/webview/         pywebview storage (localStorage: language, draw style, swing step)
$SHOTTRACER_CACHE/models/pose_landmarker_full.task   pose model (developer mode)
```

`Project.source` is relative (copied file) or absolute (desktop app / CLI open the
original in place — `engine.create(copy=False)`; moving the original breaks the project).

## 6. Analysis pipeline (`engine.analyze`)

Runs per trace on the analyze worker thread (`server._run_analysis`).

1. Range: `ball_frame − 1 s` … `ball_frame + 15 s` (`BEFORE_SECONDS`, `AFTER_SECONDS`).
2. Decode at analysis resolution (`video.read_analysis_frames`), running MediaPipe Pose per frame (`PoseRunner`) → masks written to `masks/`, hands + body height kept in memory.
3. Camera motion: `camera.estimate(frames, reference=ball frame)` → `transforms.npy`.
4. Ball: `ball.refine_ball` snaps the click to a bright blob.
5. **Flight**: horizon (`ball.estimate_horizon`, grass line preferred) → motion candidates per frame (`ball.candidates`, three-frame difference, golfer excluded) → `trajectory.detect_launch` (consistent, decelerating triple leaving the address spot while `StartPatch.gone`), with a retry tolerating gaps when the address spot emptied much earlier → `trajectory.track` links candidates → `trajectory.fit` → `base_model`.
   **Putt**: `putt.track` template-matches the rolling ball until it stops.
6. Swing: `swing.swing_window` (hands still at address → follow-through), `swing.club_path` + `swing.hands_path`, `swing.smooth` (polar unwrap, Hampel outliers, Savitzky-Golay).
7. `apply_edits` produces `model`.

Failures raise `trajectory.AnalysisError` (user-facing message). The server keeps settings
edited while analysis ran and only takes the analysis result (`_run_analysis` finally-block).
If a landing-tool stand-in path exists (`manual_auto`), a successful analysis replaces it.

## 7. Flight model (`trajectory.py`)

Reference-frame image coordinates, `t` seconds since impact:

```
s(t) = (1 − exp(−k t)) / k                                   drag-slowed travel (k = DRAG_K = 0.35)
u(t) = u0 + (a s + c t²) / (1 + w s)                         lateral: direction + curve
v(t) = vh + (v0 − vh − (b s − g t² + l t³)) / (1 + w s)      height: launch − gravity + lift
```

`w` = speed away from the camera (perspective shrink toward horizon `vh`). For fixed
`(w, k, t0)` the model is linear in `(a, c)` and `(b, g)` → grid search over `W_GRID` + least squares.

- `fit`: automatic fit with `SHOT_TYPES` limits; if the track covers less than `MIN_OBSERVED_FRACTION` of the flight, the typical flight time is used.
- `refit(base, obs, fps, limits, landing, flight_time, height)`: fast edit refit, impact time and perspective fixed. The line ends exactly at the user landing point.
- `with_apex(model, obs, fps, apex)`: adds lift `l` so the ball peaks at `apex` but lands at the same time/place; accepted range `APEX_FRACTION = (0.35, 0.62)` of hang time.
- Flight time priority (`engine.flight_time`): user `flight_time` > hang time implied by a typed carry/distance pop-up (`engine.HANG_TIME` table, `parse_distance` understands yd/m/야드/미터) > automatic.

`engine.ShotPath` turns any trace into a time-parameterised path: model (flight),
interpolated observations (putt), or the hand-drawn path animated with an ease-out
(optionally stretched to hit `apex_time`). **Tracer speed**: `times`/`end` are divided by
`shot.speed` (drawing timeline), while `flight` / `flight_apex_time` keep the real ball
times (used by stats and the hang-time pop-up). Covered by `tests/test_trace_speed.py`.

## 8. Rendering (`render.py`)

- `Renderer(p, out_size)` builds one `Layer` per trace with an analysis. `draw(frame, index)`: tracers (each layer re-applies its golfer cut-out, so the golfer stays in front of all earlier shots) → swing paths → annotations → pop-ups → logo → watermark.
- `index` is a fractional source frame; `timeline(p)` maps output frames to source frames (slow-motion windows around each impact merge when they overlap).
- `preview(p, index)` uses the same renderer on preview JPEGs — previews and exports are WYSIWYG; keep it that way.
- `export` decodes the source, interpolates frames for slow motion, and writes through `video.Writer`: ffmpeg H.264 (`crf 18`) with the source audio copied; slow motion drops audio; without ffmpeg falls back to OpenCV `mp4v` without audio.
- Style presets: `render.PRESETS` (classic, tv, neon, comet, minimal) scaled by `Style.glow/width`.

## 9. Server (`server.py`)

Two worker threads, one queue each (`analyze`, `render`); jobs live in memory (`JOBS`) and
are lost on restart. Per-project `engine.lock(pid)` guards read-modify-write of `project.json`.

| Method & path | Purpose |
| --- | --- |
| `GET /` | editor page (`web/index.html`); static files under `/static` |
| `GET /api/projects` | list summaries |
| `POST /api/projects` | upload a video (browser mode, copied) |
| `POST /api/projects/local` | register files by path (desktop app, read in place) |
| `GET /api/projects/{pid}` | summary (project + jobs + per-trace `result`, no raw analysis) |
| `PUT /api/projects/{pid}` | partial update: `style/slowmo/logo/swing/name/annotations` for the video; `shot/popups/color/landing_frame` for `trace`; `restore` (undo/redo) |
| `DELETE /api/projects` / `/{pid}` | delete all / one (original video files are never deleted) |
| `POST /api/projects/{pid}/reset` | back to just-added state |
| `POST /api/projects/{pid}/traces`, `DELETE …/traces/{key}` | add / remove a shot |
| `POST /api/projects/{pid}/analyze?trace=` | queue analysis |
| `POST /api/render` `{ids:[…]}` | queue renders (batch) |
| `GET /api/jobs` | job list (UI polls) |
| `GET /api/projects/{pid}/frames/{i}.jpg` | raw preview frame |
| `GET /api/projects/{pid}/preview/{i}.jpg?ann=` | rendered preview frame |
| `GET /api/projects/{pid}/overlay/{i}?trace=` | editor handles (start/apex/landing, swing…) in preview px |
| `POST /api/projects/{pid}/logo` | upload logo |
| `GET /api/projects/{pid}/output` | download the render |
| `GET /api/watermark`, `POST /api/watermark/unlock` `{password}`, `POST /api/watermark/lock` | watermark state |

`_summary` is the contract with the UI: `traces[].result` carries `mode, impact, tracked,
duration, apex_time, apex_range, draw_time, landing_frame, landing_set, distance_time`.

## 10. Web UI (`web/`)

Plain JavaScript, no build step, no framework. `app.js` holds global state `S`
(current project, frame, tool, tab, zoom, selected trace/shape…), an `api()` fetch helper,
debounced saves (`patch`/`patchTrace` → `flush` → `PUT`), per-project undo/redo history
(snapshots of all traces' shot/popups/color, sent as `restore`), canvas overlay drawing
(handles, swing points, shapes), tools (`ball`, `landing`, `path`, `circle`, `line`,
`swing`), tab panels (`renderShot/Style/Popups/Draw/Swing/Export`), job polling (`poll`).

Desktop integration: when `window.pywebview.api` exists, the UI uses `pick_videos()` /
`save_output()` and `window.addLocalVideos(paths)` (called from Python on drag & drop).

i18n: every string goes through `i18n.js` (`en`, `ko`); `#lang=ko` in the URL or the
language menu; choice saved in localStorage.

## 11. Standalone app & build

- `desktop.py`: sets `MPLBACKEND=Agg`, puts bundled `bin/` (ffmpeg) first on `PATH`, redirects output to `~/.shottracer/shottracer.log` when frozen, starts uvicorn on a free port (prefers 8765), opens a pywebview window (falls back to the browser), stops preview threads on quit. `--self-test` checks OpenCV/MediaPipe imports, ffmpeg with libx264, the bundled model and the server, then exits (used by the build and CI). Other flags: `--browser`, `--port`, `--headless`.
- `person.model_path()` uses the bundled model when frozen (`sys._MEIPASS/models`).
- `packaging/build.py`: checks platform (macOS arm64 / Windows x64 only), downloads pinned ffmpeg 9.0.2 and the pose model (mirrors, retries, timeouts, SHA-256), runs PyInstaller with `shottracer.spec`, packages `dist/ShotTracer-<ver>-mac-arm64.dmg` or `dist/ShotTracer-<ver>-windows-x64.zip`, then runs `--self-test`.
- CI: `.github/workflows/build-app.yml`, manual `workflow_dispatch`, runs pytest then build on `macos-14` and `windows-latest`; artifacts kept 7 days. Builds are never committed.
- Apps are unsigned (Gatekeeper / SmartScreen warnings documented in the README).

Version `0.0.1` appears in **four** places — bump all together: `pyproject.toml`,
`src/shottracer/__init__.py`, `packaging/build.py` (`VERSION`), `packaging/shottracer.spec`
(`CFBundleShortVersionString`).

## 12. Tests

`uv run pytest` (50 tests, ~6 s, no video files). Tests build synthetic observations /
projects and check numeric behaviour:

| File | Covers |
| --- | --- |
| `test_trajectory.py` | launch detection, fitting, refit, landing ends the line |
| `test_apex.py` | `with_apex`, apex range, same landing |
| `test_distance.py` | `parse_distance`, `distance_time` |
| `test_putt.py` | putt tracker on synthetic frames |
| `test_swing_points.py` | user swing points curve |
| `test_trace_speed.py` | `ShotPath` speed vs. measured flight |
| `test_watermark.py` | password check, enable/disable persistence |

The UI and full video pipeline have no automated tests; verify them manually with
`uv run shottracer-ui` and a real clip (sample `*.mp4/*.MP4` files are git-ignored).
