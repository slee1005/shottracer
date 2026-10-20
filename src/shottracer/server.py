"""Local web editor: FastAPI backend + static single-page UI (src/shottracer/web).

Run with `shottracer-ui` (opens http://127.0.0.1:8765). Everything stays on this
machine; the server only listens on localhost."""

from __future__ import annotations

import argparse
import queue
import shutil
import threading
import time
import traceback
import uuid
import webbrowser
from dataclasses import asdict, dataclass, field
from pathlib import Path

import cv2
import numpy as np
from fastapi import Body, FastAPI, File, HTTPException, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, Response
from fastapi.staticfiles import StaticFiles

from . import camera, engine, render
from .settings import Logo, Popup, Project, Shot, SlowMo, Style, Swing
from .trajectory import AnalysisError

WEB = Path(__file__).parent / "web"
app = FastAPI(title="shottracer")
app.mount("/static", StaticFiles(directory=WEB), name="static")


# --------------------------------------------------------------------------- jobs


@dataclass
class Job:
    id: str
    project: str
    kind: str  # "analyze" | "render"
    status: str = "queued"  # queued | running | done | error
    progress: float = 0.0
    message: str = ""
    output: str | None = None
    created: float = field(default_factory=time.time)


JOBS: dict[str, Job] = {}
_queues = {"analyze": queue.Queue(), "render": queue.Queue()}


def _worker(kind: str) -> None:
    while True:
        job: Job = _queues[kind].get()
        job.status = "running"
        try:
            if kind == "analyze":
                _run_analysis(job)
            else:
                _run_render(job)
            job.status, job.progress = "done", 1.0
        except AnalysisError as e:
            job.status, job.message = "error", str(e)
        except Exception as e:  # keep the worker alive; surface the error in the UI
            traceback.print_exc()
            job.status, job.message = "error", f"{type(e).__name__}: {e}"


def _run_analysis(job: Job) -> None:
    with engine.lock(job.project):
        p = engine.load(job.project)
        p.status, p.error = "analysing", None
        engine.save(p)

    def progress(f, msg=""):
        job.progress, job.message = f, msg

    try:
        engine.analyze(p, progress)
        p.status = "ready"
    except Exception as e:
        p.status, p.error, p.analysis = "error", str(e), None
        raise
    finally:
        with engine.lock(job.project):
            current = engine.load(job.project)
            # Keep settings edited while analysing; take the analysis result.
            current.analysis, current.status, current.error = p.analysis, p.status, p.error
            if p.analysis:
                engine.apply_edits(current)
            engine.save(current)


def _run_render(job: Job) -> None:
    p = engine.load(job.project)
    out = engine.workdir(p.id) / f"{Path(p.name).stem}_traced.mp4"
    render.export(p, out, lambda f: setattr(job, "progress", f))
    job.output = str(out)


for _kind in _queues:
    threading.Thread(target=_worker, args=(_kind,), daemon=True).start()


def _enqueue(pid: str, kind: str) -> Job:
    job = Job(uuid.uuid4().hex[:8], pid, kind)
    JOBS[job.id] = job
    _queues[kind].put(job)
    return job


# --------------------------------------------------------------------------- helpers


def _project(pid: str) -> Project:
    try:
        return engine.load(pid)
    except KeyError:
        raise HTTPException(404, "project not found") from None


def _summary(p: Project) -> dict:
    d = p.model_dump()
    jobs = sorted((j for j in JOBS.values() if j.project == p.id), key=lambda j: j.created)
    d["jobs"] = {j.kind: asdict(j) for j in jobs}  # latest job per kind
    d["has_output"] = (engine.workdir(p.id) / f"{Path(p.name).stem}_traced.mp4").exists()
    if p.analysis:
        path = engine.ShotPath(p)
        d["result"] = {
            "mode": path.mode,
            "impact": float(p.analysis["impact"]),
            "tracked": len(p.analysis["observations"]),
            "duration": float(path.end),
            "apex_time": path.apex_time,
        }
        d.pop("analysis")
    return d


def _jpeg(img: np.ndarray) -> Response:
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 85])
    return Response(buf.tobytes(), media_type="image/jpeg", headers={"Cache-Control": "no-store"})


# --------------------------------------------------------------------------- routes


@app.get("/", response_class=HTMLResponse)
def index() -> str:
    # Cache-bust static assets whenever they change on disk.
    v = int(max((WEB / f).stat().st_mtime for f in ("app.js", "i18n.js", "style.css")))
    return (WEB / "index.html").read_text().replace("{{v}}", str(v))


@app.get("/api/projects")
def projects() -> list[dict]:
    return [_summary(p) for p in engine.list_projects()]


@app.post("/api/projects")
def upload(file: UploadFile = File(...)) -> dict:
    suffix = Path(file.filename or "video.mp4").suffix or ".mp4"
    tmp = engine.WORKSPACE.parent / "uploads" / f"{uuid.uuid4().hex}{suffix}"
    tmp.parent.mkdir(parents=True, exist_ok=True)
    with tmp.open("wb") as f:
        shutil.copyfileobj(file.file, f)
    try:
        p = engine.create(tmp, name=file.filename)
    except ValueError as e:
        raise HTTPException(400, str(e)) from None
    finally:
        tmp.unlink(missing_ok=True)
    return _summary(p)


@app.get("/api/projects/{pid}")
def get_project(pid: str) -> dict:
    return _summary(_project(pid))


@app.delete("/api/projects/{pid}")
def delete_project(pid: str) -> dict:
    engine.delete(pid)
    return {"ok": True}


@app.put("/api/projects/{pid}")
def update(pid: str, body: dict = Body(...)) -> dict:
    """Partial update of settings: any of shot/style/slowmo/popups/logo/swing/name."""
    with engine.lock(pid):
        p = _project(pid)
        models = {"shot": Shot, "style": Style, "slowmo": SlowMo, "logo": Logo, "swing": Swing}
        try:
            for key, model in models.items():
                if key in body:
                    setattr(p, key, model.model_validate({**getattr(p, key).model_dump(), **body[key]}))
            if "popups" in body:
                p.popups = [Popup.model_validate(x) for x in body["popups"]]
            if "name" in body:
                p.name = str(body["name"])
            if "landing_frame" in body and p.shot.landing and p.analysis:
                p.shot.landing = _to_reference(p, p.shot.landing, int(body["landing_frame"]))
            if p.analysis:
                engine.apply_edits(p)
        except (ValueError, AnalysisError) as e:
            raise HTTPException(422, str(e)) from None
        engine.save(p)
    return _summary(p)


def _to_reference(p: Project, pt, frame: int) -> tuple[float, float]:
    """Video pixels on `frame` -> video pixels on the reference (ball) frame."""
    s = p.analysis["scale"]
    t = engine.transforms(p)
    frame = int(np.clip(frame, 0, len(t) - 1))
    x, y = camera.apply(t[frame], [(pt[0] / s, pt[1] / s)])[0]
    return (float(x * s), float(y * s))


@app.post("/api/projects/{pid}/analyze")
def analyze(pid: str) -> dict:
    p = _project(pid)
    if p.shot.ball is None:
        raise HTTPException(422, "pick the ball first")
    return asdict(_enqueue(pid, "analyze"))


@app.post("/api/render")
def render_many(body: dict = Body(...)) -> list[dict]:
    jobs = []
    for pid in body.get("ids", []):
        p = _project(pid)
        if p.analysis:
            jobs.append(asdict(_enqueue(pid, "render")))
    return jobs


@app.get("/api/jobs")
def jobs() -> list[dict]:
    return [asdict(j) for j in sorted(JOBS.values(), key=lambda j: j.created)]


@app.get("/api/projects/{pid}/frames/{index}.jpg")
def frame(pid: str, index: int) -> FileResponse:
    path = engine.workdir(pid) / "previews" / f"{index:05d}.jpg"
    if not path.exists():
        raise HTTPException(404)
    return FileResponse(path, media_type="image/jpeg")


@app.get("/api/projects/{pid}/preview/{index}.jpg")
def preview(pid: str, index: int) -> Response:
    p = _project(pid)
    try:
        return _jpeg(render.preview(p, index))
    except KeyError:
        raise HTTPException(404) from None


@app.get("/api/projects/{pid}/overlay/{index}")
def overlay(pid: str, index: int) -> dict:
    """Edit handles for the UI, in preview pixels of frame `index`."""
    p = _project(pid)
    if not p.analysis:
        return {}
    path = engine.ShotPath(p)
    t = engine.transforms(p)
    inv = np.linalg.inv(t[int(np.clip(index, 0, len(t) - 1))])

    f = float(p.info["work_width"]) / float(p.analysis.get("work_width", p.info["work_width"]))

    def to_view(pts):  # analysis px -> preview px
        return (camera.apply(inv, np.asarray(pts, dtype=np.float64)) * f).round(1).tolist()

    step = max(1, len(path.points) // 120)
    return {
        "path": to_view(path.points[::step]),
        "start": to_view(path.points[:1])[0],
        "apex": to_view(path.points[path.apex_index : path.apex_index + 1])[0],
        "landing": to_view(path.points[-1:])[0],
        "mode": path.mode,
        "scale": p.analysis["scale"],
    }


@app.post("/api/projects/{pid}/logo")
def upload_logo(pid: str, file: UploadFile = File(...)) -> dict:
    p = _project(pid)
    dest = engine.workdir(pid) / f"logo{Path(file.filename or 'logo.png').suffix or '.png'}"
    with dest.open("wb") as f:
        shutil.copyfileobj(file.file, f)
    if cv2.imread(str(dest), cv2.IMREAD_UNCHANGED) is None:
        dest.unlink(missing_ok=True)
        raise HTTPException(400, "not an image")
    with engine.lock(pid):
        p = _project(pid)
        p.logo.path = str(dest)
        engine.save(p)
    return _summary(p)


@app.get("/api/projects/{pid}/output")
def output(pid: str) -> FileResponse:
    p = _project(pid)
    path = engine.workdir(pid) / f"{Path(p.name).stem}_traced.mp4"
    if not path.exists():
        raise HTTPException(404, "not rendered yet")
    return FileResponse(path, media_type="video/mp4", filename=path.name)


def main(argv: list[str] | None = None) -> int:
    import uvicorn

    ap = argparse.ArgumentParser(prog="shottracer-ui")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--no-browser", action="store_true")
    args = ap.parse_args(argv)
    url = f"http://127.0.0.1:{args.port}"
    if not args.no_browser:
        threading.Timer(1.0, lambda: webbrowser.open(url)).start()
    print(f"shottracer editor: {url}")
    uvicorn.run(app, host="127.0.0.1", port=args.port, log_level="warning")
    return 0
