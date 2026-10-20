**English** | [한국어](README.ko.md)

# shottracer <a href="https://www.paypal.com/donate/?business=57PYF6ECLXA6L&no_recurring=0&currency_code=USD"><img src="https://www.paypalobjects.com/en_US/i/btn/btn_donate_SM.gif" border="0" title="PayPal - The safer, easier way to pay online!" alt="Donate with PayPal button" /></a>

A local editor that draws the ball flight (shot tracer), swing path and data pop-ups
onto golf swing videos. Everything runs on your machine; videos never leave it.

- Semi-automatic: click the ball once, the rest is detected
- Ball flight and putt/chip tracing, club-head swing path
- Golfer cut-out (tracer passes behind the golfer), camera-shake compensation
- Pop-ups (distance, speed, apex, hang time, club, player, text) and logo watermark
- Batch rendering, slow motion, English / Korean UI

## Standalone app

Shot Tracer can be packaged as a standalone app: no Python, uv or ffmpeg needed — everything
(including ffmpeg and the pose model) is inside. App files are **not** kept in this repository;
build them yourself (on the OS you build for):

```bash
uv sync --group desktop
uv run --group desktop python packaging/build.py      # -> dist/
```

On GitHub, **Actions → Build app → Run workflow** builds both (macOS and Windows) and offers
them as downloads on the run page for 7 days.

| System | File | Install |
| --- | --- | --- |
| macOS, Apple silicon (M1 or newer) | `ShotTracer-<version>-mac-arm64.dmg` | Open the DMG and drag **Shot Tracer** to **Applications** |
| Windows 10/11, 64-bit (x64) | `ShotTracer-<version>-windows-x64.zip` | Right-click → **Extract All…**, then run `ShotTracer\ShotTracer.exe` |

The apps are not signed with a paid developer certificate yet, so the first launch shows a
warning:

- **macOS:** "cannot be opened because the developer cannot be verified" → close it, then
  right-click **Shot Tracer** in Applications → **Open** → **Open**. (Or System Settings →
  Privacy & Security → **Open Anyway**.) Only needed once.
- **Windows:** "Windows protected your PC" → **More info** → **Run anyway**. The window uses
  Microsoft Edge WebView2, which is built into Windows 11 and current Windows 10; if the app
  opens your browser instead, install the
  [WebView2 Runtime](https://developer.microsoft.com/microsoft-edge/webview2/).

The app opens the editor in its own window; closing the window quits it. Projects are kept in
`~/.shottracer/projects` (Windows: `%USERPROFILE%\.shottracer\projects`), shared with the
developer version below, and a log for troubleshooting is written to `~/.shottracer/shottracer.log`.
The app includes FFmpeg (GPL), run as a separate program; see `licenses/FFMPEG-LICENSE.txt`
inside the app.

## Installation (developer version)

To run from source instead of the app:

shottracer runs locally: a small Python server plus your web browser. You need three tools:

| Tool | Why | Required? |
| --- | --- | --- |
| [uv](https://docs.astral.sh/uv/) | Installs Python 3.13 and all libraries for this project only (nothing system-wide) | Yes |
| [ffmpeg](https://ffmpeg.org/) | H.264 output **with the original audio** | Recommended (without it, videos are saved without sound) |
| [Git](https://git-scm.com/) | Download and update the code | Optional (you can download a ZIP instead) |

The first `uv sync` downloads about 300–500 MB (Python and libraries). The first analysis also
downloads a 9 MB pose model. After that everything works offline.

### macOS

**Supported:** Macs with Apple silicon (M1, M2, M3, M4 …). Intel Macs are not supported, because
the pose library (MediaPipe) has no Intel-Mac build. Check with Apple menu → **About This Mac** →
*Chip* should say **Apple M…**.

1. **Open Terminal** (Applications → Utilities → Terminal, or `⌘ Space` and type *Terminal*).

2. **Install Homebrew** (a package manager), if you don't have it yet. Paste this and follow the
   prompts (it asks for your Mac password):
   ```bash
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
   ```
   At the end Homebrew prints two or three `echo … >> ~/.zprofile` / `eval …` lines under
   *Next steps* — run them, then close and reopen Terminal. Check with `brew --version`.

3. **Install uv, ffmpeg and Git:**
   ```bash
   brew install uv ffmpeg git
   ```
   Check: `uv --version` and `ffmpeg -version` both print a version.

4. **Download shottracer** (into your home folder):
   ```bash
   cd ~
   git clone https://github.com/slee1005/shottracer.git
   cd shottracer
   ```
   Without Git: on the GitHub page press **Code → Download ZIP**, unzip it, then in Terminal
   `cd ~/Downloads/shottracer-main` (or wherever you put the folder).

5. **Install the dependencies** (first time only, takes a few minutes):
   ```bash
   uv sync
   ```

6. **Start the editor:**
   ```bash
   uv run shottracer-ui
   ```
   Your browser opens <http://127.0.0.1:8765>. Keep Terminal open while you work; press
   `Ctrl+C` in Terminal to stop the editor.

**Next time:** open Terminal and run `cd ~/shottracer && uv run shottracer-ui`.

**Update to the latest version:** `cd ~/shottracer && git pull && uv sync`.

### Windows

**Supported:** Windows 10 or 11, 64-bit on an Intel/AMD processor (x64). Windows on ARM
(e.g. Snapdragon laptops) is not supported, because OpenCV has no build for it. Check with
**Settings → System → About** → *System type* should say **64-bit operating system, x64-based
processor**.

> Windows support is newer and less tested than macOS. If something does not work, please
> [open an issue](https://github.com/slee1005/shottracer/issues) with the error message.

1. **Open PowerShell:** press the Windows key, type *PowerShell*, press Enter
   (a normal window is fine — no administrator rights needed).

2. **Install uv:**
   ```powershell
   powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"
   ```
   (or `winget install --id=astral-sh.uv -e`). **Close and reopen PowerShell**, then check with
   `uv --version`.

3. **Install ffmpeg and Git** with winget (built into Windows 10/11):
   ```powershell
   winget install --id=Gyan.FFmpeg -e
   winget install --id=Git.Git -e
   ```
   Accept the agreements if asked. **Close and reopen PowerShell** again so the new commands are
   found, then check with `ffmpeg -version` and `git --version`.

4. **Download shottracer** (into your user folder):
   ```powershell
   cd ~
   git clone https://github.com/slee1005/shottracer.git
   cd shottracer
   ```
   Without Git: on the GitHub page press **Code → Download ZIP**, right-click the ZIP →
   **Extract All…**, then `cd $HOME\Downloads\shottracer-main\shottracer-main` (adjust to where the
   folder with `pyproject.toml` is).

5. **Install the dependencies** (first time only, takes a few minutes):
   ```powershell
   uv sync
   ```

6. **Start the editor:**
   ```powershell
   uv run shottracer-ui
   ```
   Your browser opens <http://127.0.0.1:8765> (if not, open that address yourself). Keep the
   PowerShell window open while you work; press `Ctrl+C` there to stop the editor.

**Next time:** open PowerShell and run `cd ~\shottracer; uv run shottracer-ui`.

**Update to the latest version:** `cd ~\shottracer; git pull; uv sync`.

### Troubleshooting

| Problem | Fix |
| --- | --- |
| `uv`, `ffmpeg` or `git` is "not found" / "not recognized" | Close and reopen the terminal after installing. On macOS make sure you ran Homebrew's *Next steps* lines |
| Exported videos have no sound | ffmpeg is missing: install it (see above), restart the editor, render again |
| `address already in use` / port 8765 busy | Another copy is running — close it, or start with `uv run shottracer-ui --port 9000` |
| `uv sync` fails on a Mac | Check the Mac has Apple silicon (Intel Macs are not supported) |
| `uv sync` fails on Windows | Check the PC is x64, not ARM |
| The browser does not open | Open <http://127.0.0.1:8765> manually |
| The editor looks broken after updating | Stop it (`Ctrl+C`), start it again and reload the browser page |

## Quick start

After [installing](#installation-developer-version), start the editor with `uv run shottracer-ui` in the
`shottracer` folder; it opens <http://127.0.0.1:8765> in your browser.

1. **Add videos** (several at once, or drag & drop)
2. Move the timeline to a frame before the downswing, press **⦿ Pick ball** and click the ball
   (if the club head hides the ball, click the head)
3. **Analyse** — camera motion, ball tracking, golfer segmentation and swing path
   (a few seconds for a short clip)
4. In the preview, drag the **Landing** handle to set direction, curve and distance, and the
   **Apex** handle up or down to set the height
5. Choose style, pop-ups, swing and logo, then **Render this video** or **Render all** (batch)

Shortcuts: `←/→` step frames (`Shift` for 10), `Space` play, `B` pick ball, `L` landing spot,
`D` draw path, `P` swing points, `C` circle, `S` line, `Delete` remove shape, `1`–`4` select shot, `+`/`-`/`0` zoom in/out/fit (or `Ctrl/⌘`+scroll, pinch), `Ctrl/⌘+Z` undo, `Ctrl/⌘+Shift+Z` redo, `Esc` cancel tool.

**Several shots in one video (up to 4):** press **+ Add shot** in the tool bar. Each shot has its own
ball, analysis, drawn line, landing edits, pop-ups and (optionally) line color; style, swing, slow
motion and logo are shared. The Shot and Pop-ups tabs edit the selected shot.

**Draw the ball path by hand:** press **⌒ Draw path** (`D`), click where the ball starts and where it
lands, then drag the **Start / Apex / Landing** handles to shape the curve. The drawn path replaces the
automatic one and renders even without analysis (when auto detection fails). It starts at the impact
frame (or the frame you drew on) and takes the flight time. The line disappears right after landing by
default (Style → *Hold after landing*). **↺ Reset project** (Shot tab, or the ↺ on a project in the
list) clears everything except the video.

**Swing points:** if the detected club-head or hands path is off, press **✦ Swing points** (`P`) and click
the club head (or hands) frame by frame; each click steps forward (2 frames by default, Swing tab). With
two or more points a smooth curve through them replaces the detected path. Drag the current frame's
point to move it, `Delete` removes it.

**Shapes for swing analysis:** **◯ Circle** (`C`, drag from the center) and **／ Line** (`S`) mark e.g. the
head or hip position. They stay in the same place on screen for the whole video and are included in the
export. Click a shape to select it, drag to move, drag its white dots to resize, `Delete` to remove;
colors and widths are in the **Draw** tab.

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

**Watermark:** previews and exported videos carry a small *Create by AJC_shottracer* line at the
bottom. Removing it (Export tab → **Remove watermark…**) needs a password; it then stays off for every
video on that computer until turned back on.

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
| `SHOTTRACER_CACHE` | macOS `~/Library/Caches/shottracer`, Windows `%LOCALAPPDATA%\shottracer\Cache`, Linux `~/.cache/shottracer` | Downloaded models |

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
<a href="https://www.paypal.com/donate/?business=57PYF6ECLXA6L&no_recurring=0&currency_code=USD"><img src="https://www.paypalobjects.com/en_US/i/btn/btn_donate_LG.gif" border="0" title="PayPal - The safer, easier way to pay online!" alt="Donate with PayPal button" /></a>
