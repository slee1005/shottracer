# AGENTS.md — handoff notes for coding agents (Codex, Claude, …)

shottracer: a local golf-video editor that draws ball-flight tracers, swing paths and data
pop-ups. Python 3.13 + FastAPI backend, plain-JS single-page editor, optional standalone
desktop app (pywebview + PyInstaller). Owner: John Lee (GitHub `slee1005/shottracer`).

Read first:
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — modules, data model, coordinate systems, pipeline, API, build
- [README.md](README.md) — user features and installation ([README.ko.md](README.ko.md) is the Korean twin)

## Commands

```bash
uv sync                                   # install (Python 3.13 via uv)
uv run pytest                             # 50 tests, ~6 s, must pass before every commit
uv run shottracer-ui                      # editor at http://127.0.0.1:8765 (--port, --no-browser)
uv run shottracer clip.mp4 --ball X,Y --ball-frame N -o out.mp4   # CLI analyse + render
uv run shottracer --project <ID> -o out.mp4                        # render an editor project
uv sync --group desktop && uv run --group desktop python packaging/build.py   # standalone app -> dist/
uv run python -m shottracer.desktop --browser   # desktop entry point without packaging
```

Projects are stored in `~/.shottracer/projects` (override with `SHOTTRACER_HOME`; use a temp
dir for experiments so you do not touch the owner's real projects).

## Working rules

1. **Run `uv run pytest`** after changes; add a test for new numeric/model behaviour (tests are synthetic, no video files).
2. **Keep the README pair in sync**: every user-visible change goes into both `README.md` and `README.ko.md` (same structure).
3. **Every UI string goes through `web/i18n.js`** with both `en` and `ko` entries. No hard-coded text in `app.js`/`index.html`.
4. **Preview == export**: both use `render.Renderer`. Never add drawing that only happens in one of them.
5. **Settings are backward compatible**: `project.json` files from older versions must still load. Add fields with defaults; migrate with Pydantic validators (see `Project._single_shot_projects`, `Popup._keep_timed_popups`).
6. **Mind the coordinate spaces** (video px / analysis px / reference coords / output px — ARCHITECTURE §4). User inputs are stored in video px of a specific frame.
7. **`base_model` is the automatic fit and is never overwritten**; edits recompute `model` via `engine.apply_edits`. Edits must stay fast (no re-analysis).
8. **Local only**: the server binds `127.0.0.1`. No telemetry, no new network calls. Downloads (model, ffmpeg) are pinned by SHA-256.
9. **Never commit** videos (`*.mp4`, `*.MP4`), build outputs (`build/`, `dist/`, `*.dmg`, `*.app`, `*.exe`, `ShotTracer-*.zip`).
10. **Version bumps** touch 4 files: `pyproject.toml`, `src/shottracer/__init__.py`, `packaging/build.py`, `packaging/shottracer.spec`.
11. Style: match the existing code — type hints, `from __future__ import annotations`, docstrings that explain *why*, small focused commits with imperative English messages ("Add …", "Make …"). Work on a branch and open a PR; `main` is what users clone.
12. Watermark (`watermark.py`) is an intentional product feature: keep it on by default and keep the password check (only a salted scrypt hash is stored). Do not remove or bypass it.

## Platform constraints

- macOS: Apple silicon only (MediaPipe has no Intel-Mac wheels). Windows: x64 only (no OpenCV Windows-on-ARM). Linux works in developer mode but has no app build.
- PyInstaller cannot cross-compile: build each app on its own OS, or use GitHub **Actions → Build app → Run workflow** (macOS + Windows, artifacts for 7 days).
- Apps are unsigned; first-launch warnings are documented in the README.
- OpenCV is a transitive dependency (`opencv-contrib-python` via MediaPipe), not listed in `pyproject.toml`.

## Current state (2026-11-03)

Version **0.0.1**. `main` is clean and in sync with `origin/main`; all 50 tests pass.

Done so far (chronological, see `git log`):
1. Core pipeline: camera compensation, ball launch/track, perspective flight model, putt tracking, golfer cut-out, club-head path, pop-ups, logo, batch render, slow motion, en/ko UI.
2. Multi-shot (up to 4 traces per video), hand-drawn path tool, undo/redo, project reset.
3. Drawing tools (circle/line annotations), manual swing points, watermark with password lock, zoom/pan, install guide.
4. Standalone desktop app for macOS arm64 and Windows x64 (bundled ffmpeg + pose model, self-test, CI workflow).
5. Desktop app opens videos in place (no copy), background preview extraction with resume, adjustable tracer speed (×0.5–×4), "Delete all videos", clean shutdown of preview threads, more robust build downloads (mirrors, retries, timeouts).
6. Editable time to apex (ball fastest at launch, slows, steeper descent; same landing), landing handle ends the line exactly, landing frame shown in stats; README updates (PR #1 merged).

## Known limitations / open ideas

Not bugs to fix blindly — discuss with the owner before large changes.

- Tracking stops early when the ball loses contrast; the rest is predicted. Setting the landing spot gives the best tracer.
- Putt tracking is verified only on synthetic video.
- Club head can drop out on busy backgrounds / heavy blur (hands path is more stable).
- Slow motion drops the audio (export writes video-only when the timeline is retimed).
- Only −1 s … +15 s around the ball frame is analysed.
- Jobs are in memory: a server restart forgets queued/running jobs (the trace may stay `analysing` until re-analysed).
- Desktop app reads videos in place: moving/deleting the original breaks that project.
- No automated tests for the web UI or the end-to-end video pipeline; verify manually with a real clip.
- Apps are not code-signed / notarized.
- Not implemented: 2D/3D map tracer, drone footage, live streaming.

## Where to change what

| Task | Files |
| --- | --- |
| New shot/style/pop-up setting | `settings.py` (field + default) → `render.py` (use it) → `web/app.js` (panel control) → `web/i18n.js` (en+ko) → README pair |
| Flight behaviour | `trajectory.py` (+ `engine.apply_edits` / `ShotPath`) + tests |
| Detection quality | `ball.py`, `trajectory.detect_launch/track`, `swing.py`, `putt.py` |
| New API route | `server.py` (+ `_summary` if the UI needs new derived data) |
| Desktop-only behaviour | `desktop.py` (`_Api` methods are exposed to JS as `window.pywebview.api.*`) |
| Packaging | `packaging/build.py`, `packaging/shottracer.spec`, `.github/workflows/build-app.yml` |
