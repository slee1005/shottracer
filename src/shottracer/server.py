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

from . import camera, engine, render, watermark
from .settings import Annotation, Logo, Popup, Project, Shot, SlowMo, Style, Swing, Trace
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
    trace: str | None = None  # shot key for analysis jobs
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
        tr = p.trace(job.trace)
        tr.status, tr.error = "analysing", None
        engine.save(p)

    def progress(f, msg=""):
        job.progress, job.message = f, msg

    try:
        engine.analyze(p, tr, progress)
        tr.status = "ready"
    except Exception as e:
        tr.status, tr.error, tr.analysis = "error", str(e), None
        raise
    finally:
        with engine.lock(job.project):
            current = engine.load(job.project)
            try:
                cur = current.trace(tr.key)
            except KeyError:  # the shot was removed while analysing
                cur = None
            if cur is not None:
                # Keep settings edited while analysing; take the analysis result.
                cur.analysis, cur.status, cur.error = tr.analysis, tr.status, tr.error
                if tr.analysis and cur.shot.manual_auto:  # a stand-in path from the landing tool
                    cur.shot.manual = cur.shot.manual_ctrl = None
                    cur.shot.manual_auto = False
                engine.sync_manual(current, cur)  # a drawn line stays renderable if analysis failed
                if cur.analysis:
                    engine.apply_edits(current, cur)
                engine.save(current)


def _run_render(job: Job) -> None:
    p = engine.load(job.project)
    out = engine.workdir(p.id) / f"{Path(p.name).stem}_traced.mp4"
    render.export(p, out, lambda f: setattr(job, "progress", f))
    job.output = str(out)


for _kind in _queues:
    threading.Thread(target=_worker, args=(_kind,), daemon=True).start()


def _enqueue(pid: str, kind: str, trace: str | None = None) -> Job:
    job = Job(uuid.uuid4().hex[:8], pid, kind, trace)
    JOBS[job.id] = job
    _queues[kind].put(job)
    return job


# --------------------------------------------------------------------------- helpers


def _project(pid: str) -> Project:
    try:
        return engine.load(pid)
    except KeyError:
        raise HTTPException(404, "project not found") from None


def _trace(p: Project, key: str | None) -> Trace:
    try:
        return p.trace(key)
    except KeyError:
        raise HTTPException(404, "shot not found") from None


def _busy(pid: str, trace: str | None = None) -> bool:
    return any(
        j.project == pid and j.status in ("queued", "running") and (trace is None or j.trace == trace)
        for j in JOBS.values()
    )


def _summary(p: Project) -> dict:
    d = p.model_dump()
    jobs = sorted((j for j in JOBS.values() if j.project == p.id), key=lambda j: j.created)
    d["jobs"] = {j.kind: asdict(j) for j in jobs}  # latest job per kind
    d["has_output"] = (engine.workdir(p.id) / f"{Path(p.name).stem}_traced.mp4").exists()
    for tr, td in zip(p.traces, d["traces"]):
        mine = [j for j in jobs if j.kind == "analyze" and j.trace == tr.key]
        td["job"] = asdict(mine[-1]) if mine else None
        td.pop("analysis")
        td["drawable"] = bool(tr.analysis)  # something to render (a ball path or swing points)
        if engine.has_path(tr):
            path = engine.ShotPath(p, tr)
            td["result"] = {
                "mode": path.mode,
                "impact": float(tr.analysis["impact"]),
                "tracked": len(tr.analysis["observations"]),
                "duration": float(path.end),
                "apex_time": path.apex_time,
                "distance_time": engine.distance_time(tr),
            }
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
    """Partial update: style/slowmo/logo/swing/name for the video, and shot/popups/color/
    landing_frame for the shot selected by `trace` (default: the first shot). `restore`
    replaces the settings of the listed shots (undo/redo)."""
    with engine.lock(pid):
        p = _project(pid)
        tr = _trace(p, body.get("trace"))
        models = {"style": Style, "slowmo": SlowMo, "logo": Logo, "swing": Swing}
        try:
            for key, model in models.items():
                if key in body:
                    setattr(p, key, model.model_validate({**getattr(p, key).model_dump(), **body[key]}))
            if "shot" in body:
                tr.shot = Shot.model_validate({**tr.shot.model_dump(), **body["shot"]})
            if "popups" in body:
                tr.popups = [Popup.model_validate(x) for x in body["popups"]]
            if "color" in body:
                tr.color = body["color"] or None
            if "name" in body:
                p.name = str(body["name"])
            if "annotations" in body:
                p.annotations = [Annotation.model_validate(x) for x in body["annotations"]]
            for item in body.get("restore", []):  # undo/redo: whole per-shot settings
                try:
                    other = p.trace(item["key"])
                except KeyError:  # deleted since the snapshot
                    continue
                other.shot = Shot.model_validate(item["shot"])
                other.popups = [Popup.model_validate(x) for x in item["popups"]]
                other.color = item.get("color")
                engine.sync_manual(p, other)
                if other.analysis:
                    engine.apply_edits(p, other)
            if "landing_frame" in body and tr.shot.landing and tr.analysis:
                tr.shot.landing = _to_reference(p, tr, tr.shot.landing, int(body["landing_frame"]))
            engine.sync_manual(p, tr)
            if tr.analysis:
                engine.apply_edits(p, tr)
        except (ValueError, AnalysisError) as e:
            raise HTTPException(422, str(e)) from None
        engine.save(p)
    return _summary(p)


@app.post("/api/projects/{pid}/reset")
def reset(pid: str) -> dict:
    """Clear the analysis, drawn line, outputs and all settings; keep the video."""
    if _busy(pid):
        raise HTTPException(409, "wait for the running analysis or render to finish")
    with engine.lock(pid):
        p = engine.reset(_project(pid))
        engine.save(p)
    for jid in [j.id for j in JOBS.values() if j.project == pid]:
        del JOBS[jid]
    return _summary(p)


@app.post("/api/projects/{pid}/traces")
def add_trace(pid: str) -> dict:
    with engine.lock(pid):
        p = _project(pid)
        try:
            engine.add_trace(p)
        except ValueError as e:
            raise HTTPException(422, str(e)) from None
        engine.save(p)
    return _summary(p)


@app.delete("/api/projects/{pid}/traces/{key}")
def remove_trace(pid: str, key: str) -> dict:
    if _busy(pid, key):
        raise HTTPException(409, "wait for this shot's analysis to finish")
    with engine.lock(pid):
        p = _project(pid)
        _trace(p, key)
        try:
            engine.remove_trace(p, key)
        except ValueError as e:
            raise HTTPException(422, str(e)) from None
        engine.save(p)
    return _summary(p)


def _to_reference(p: Project, tr: Trace, pt, frame: int) -> tuple[float, float]:
    """Video pixels on `frame` -> video pixels on the shot's reference (ball) frame."""
    s = tr.analysis["scale"]
    t = engine.transforms(p, tr)
    frame = int(np.clip(frame, 0, len(t) - 1))
    x, y = camera.apply(t[frame], [(pt[0] / s, pt[1] / s)])[0]
    return (float(x * s), float(y * s))


@app.post("/api/projects/{pid}/analyze")
def analyze(pid: str, trace: str | None = None) -> dict:
    p = _project(pid)
    tr = _trace(p, trace)
    if tr.shot.ball is None:
        raise HTTPException(422, "pick the ball first")
    if _busy(pid, tr.key):
        raise HTTPException(409, "this shot is already being analysed")
    return asdict(_enqueue(pid, "analyze", tr.key))


@app.post("/api/render")
def render_many(body: dict = Body(...)) -> list[dict]:
    jobs = []
    for pid in body.get("ids", []):
        p = _project(pid)
        if render.renderable(p):
            jobs.append(asdict(_enqueue(pid, "render")))
    return jobs


@app.get("/api/watermark")
def get_watermark() -> dict:
    return {"enabled": watermark.enabled(), "text": watermark.TEXT}


@app.post("/api/watermark/unlock")
def unlock_watermark(body: dict = Body(...)) -> dict:
    """Turning the watermark off needs the password (checked against a salted hash)."""
    if not watermark.check_password(str(body.get("password", ""))):
        time.sleep(1.0)  # slows down guessing
        raise HTTPException(403, "wrong password")
    watermark.set_enabled(False)
    return {"enabled": False, "text": watermark.TEXT}


@app.post("/api/watermark/lock")
def lock_watermark() -> dict:
    watermark.set_enabled(True)
    return {"enabled": True, "text": watermark.TEXT}


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
def preview(pid: str, index: int, ann: bool = True) -> Response:
    """Rendered frame; ann=false leaves the on-screen shapes to the editor canvas."""
    p = _project(pid)
    try:
        return _jpeg(render.preview(p, index, ann))
    except KeyError:
        raise HTTPException(404) from None


@app.get("/api/projects/{pid}/overlay/{index}")
def overlay(pid: str, index: int, trace: str | None = None) -> dict:
    """Edit handles of one shot for the UI, in preview pixels of frame `index`."""
    p = _project(pid)
    tr = _trace(p, trace)
    if not engine.has_path(tr):
        return {}
    path = engine.ShotPath(p, tr)
    t = engine.transforms(p, tr)
    inv = np.linalg.inv(t[int(np.clip(index, 0, len(t) - 1))])

    f = float(p.info["work_width"]) / float(tr.analysis.get("work_width", p.info["work_width"]))

    def to_view(pts):  # analysis px -> preview px
        return (camera.apply(inv, np.asarray(pts, dtype=np.float64)) * f).round(1).tolist()

    step = max(1, len(path.points) // 120)
    return {
        "path": to_view(path.points[::step]),
        "start": to_view(path.points[:1])[0],
        "apex": to_view(path.points[path.apex_index : path.apex_index + 1])[0],
        "landing": to_view(path.points[-1:])[0],
        "mode": path.mode,
        "scale": tr.analysis["scale"],
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
