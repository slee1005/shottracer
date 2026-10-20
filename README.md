**English** | [한국어](README.ko.md)

# shottracer

A local editor that draws the ball flight (shot tracer), swing path and data pop-ups
onto golf swing videos. Everything runs on your machine; videos never leave it.

- Semi-automatic: click the ball once, the rest is detected
- Ball flight and putt/chip tracing, club-head swing path
- Golfer cut-out (tracer passes behind the golfer), camera-shake compensation
- Pop-ups (distance, speed, apex, hang time, club, player, text) and logo watermark
- Batch rendering, slow motion, English / Korean UI

## Requirements

- macOS (tested on Apple silicon). Linux should work but is untested; set `SHOTTRACER_CACHE`
  there, since the default cache path follows macOS conventions
- [uv](https://docs.astral.sh/uv/) — installs Python 3.13 and all dependencies
- [ffmpeg](https://ffmpeg.org/) on `PATH` for H.264 output with the original audio
  (without it, videos are written with OpenCV and have no audio)

## Quick start

```bash
uv sync
uv run shottracer-ui          # opens http://127.0.0.1:8765 in your browser
```

1. **Add videos** (several at once, or drag & drop)
2. Move the timeline to a frame before the downswing, press **⦿ Pick ball** and click the ball
   (if the club head hides the ball, click the head)
3. **Analyse** — camera motion, ball tracking, golfer segmentation and swing path
   (a few seconds for a short clip)
4. In the preview, drag the **Landing** handle to set direction, curve and distance, and the
   **Apex** handle up or down to set the height
5. Choose style, pop-ups, swing and logo, then **Render this video** or **Render all** (batch)

Shortcuts: `←/→` step frames (`Shift` for 10), `Space` play, `B` pick ball, `L` landing spot,
`D` draw line, `1`–`4` select shot, `Ctrl/⌘+Z` undo, `Ctrl/⌘+Shift+Z` redo, `Esc` cancel tool.

**Several shots in one video (up to 4):** press **+ Add shot** in the tool bar. Each shot has its own
ball, analysis, drawn line, landing edits, pop-ups and (optionally) line color; style, swing, slow
motion and logo are shared. The Shot and Pop-ups tabs edit the selected shot.

**Draw the line by hand:** press **✎ Draw line** and drag over the video. The drawn line replaces
the automatic path and renders even without analysis (when auto detection fails). It starts at the
impact frame (or the frame you drew on) and takes the flight time. The line disappears right after
landing by default (Style → *Hold after landing*). **↺ Reset project** (Shot tab, or the ↺ on a
project in the list) clears everything except the video.

Pick **English / 한국어** at the top right. The choice is remembered; the first visit follows
the browser language. You can also open `http://127.0.0.1:8765/#lang=ko`.

`shottracer-ui --port 9000 --no-browser` changes the port and skips opening a browser.
The server only listens on `127.0.0.1`.

## Features

| Tab | What you can do |
| --- | --- |
| Shot | Ball flight or putt/chip mode, shot type (bounds the flight time), manual impact frame, edit flight time, height and landing spot |
| Style | Presets (Classic, TV, Neon, Comet, Minimal), color, width, glow, comet tail, **human cut-out**, ball head, landing effect, hold time after landing, slow motion |
| Pop-ups | Distance, carry, ball speed, apex height, hang time, club, player and text cards that follow the landing spot / apex / start or stay in a corner, with delay, duration and colors |
| Swing | Club-head or hands swing path with color, width and gradient |
| Export | Logo watermark (corner, size, opacity), render at the source resolution with audio, download |

Distance and speed cannot be measured from a single video, so you type them in. Hang time is
filled in from the analysis when left empty.

## Command line

Analyse and render in one step:

```bash
uv run shottracer swing.mp4 --ball 984,994 --ball-frame 20 \
    [--landing 1400,600] [--shot drive] [--putt] \
    [--style neon] [--color FF2D2D] [--swing clubhead] [--slowmo 3] [-o out.mp4]
```

Render a project you prepared in the editor:

```bash
uv run shottracer --project <ID> -o out.mp4
```

Coordinates are video pixels on `--ball-frame`. Run `uv run shottracer --help` for all options.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SHOTTRACER_HOME` | `~/.shottracer/projects` | Projects: copied source video, preview frames, masks, settings, renders |
| `SHOTTRACER_CACHE` | `~/Library/Caches/shottracer` | Downloaded models |

On the first analysis the MediaPipe pose model (about 9 MB) is downloaded from Google's
model storage and checked against a pinned SHA-256 before use. This is the only network
access.

## How it works

1. **Camera motion** – background features (LK optical flow + RANSAC similarity) map every
   frame into one reference frame, so tracers stay fixed to the scene when the camera pans or shakes.
2. **Golfer** – MediaPipe Pose provides the person mask (cut-out) and wrist positions (swing path).
3. **Ball candidates** – small moving blobs from a camera-compensated three-frame difference,
   excluding the golfer and club.
4. **Impact** – the first frame where three strong candidates leave the ball position along a
   consistent, *decelerating* path while the ball's address spot becomes empty. This rejects the
   takeaway and backswing.
5. **Trajectory model** – a perspective ballistic model
   `u = u0 + (a·s + c·t²)/(1 + w·s)`, `v = vh + (v0 − vh − h·(b·s − g·t²))/(1 + w·s)`
   (`s`: drag-slowed time, `w`: speed away from the camera, `vh`: ground horizon, `h`: user
   height scale). After tracking ends the model continues to the landing spot; when only the
   early ascent is visible, the typical flight time of the shot type is used. Edits refit
   instantly with impact time and perspective fixed.
6. **Putts** – template matching follows the rolling ball until it stops.
7. **Swing path** – removing the golfer's silhouette from the motion mask leaves the shaft and
   head; the farthest moving point attached to the hands is the head. Detections are smoothed in
   polar form around the hands (club length, angle): velocity-aware angle unwrapping, outlier
   rejection scaled to the local rotation speed, Savitzky-Golay smoothing, and arcs between frames.
   A driver head can turn more than 100° per frame at 30 fps near impact.
8. **Rendering** – the preview and the export use the same renderer, so what you see is what you get.

## Limitations

- When the ball loses contrast against the background, tracking stops early and the rest of
  the flight is predicted. Setting the landing spot gives the most faithful tracer.
- Putt tracking has only been verified on synthetic video.
- The club head can drop out against busy backgrounds or with heavy motion blur; the hands path
  is more stable.
- Slow motion drops the audio.
- Only one second before to 15 seconds after the ball frame is analysed, so long videos stay
  fast. The tracer fades out after that range.
- No map (2D/3D) tracer, drone footage or live streaming.

## Project layout

```
src/shottracer/
  cli.py         command line          server.py   FastAPI backend + render queue
  engine.py      projects, analysis    render.py   compositing (tracer, cut-out, swing, pop-ups)
  camera.py      camera motion         ball.py     ball, horizon, launch evidence
  trajectory.py  launch, tracking, flight model
  putt.py        rolling ball          swing.py    club-head path and smoothing
  person.py      MediaPipe pose        popups.py   pop-up cards and logo (Pillow)
  settings.py    project settings      video.py    decoding, previews, ffmpeg output
  web/           editor UI (index.html, app.js, i18n.js, style.css)
tests/           trajectory, launch and putt tests
```

## Development

```bash
uv run pytest
```

UI strings live in `src/shottracer/web/i18n.js`; add a language by adding a block there and an
option to the language menu in `index.html`.

## Support

If shottracer is useful to you, you can support its development with a donation:
[Donate with PayPal](https://www.paypal.com/donate/?business=57PYF6ECLXA6L&no_recurring=0&currency_code=USD)
