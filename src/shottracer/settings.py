"""Project settings shared by the CLI, the web UI and the renderer (JSON-serialisable)."""

from __future__ import annotations

import uuid
from typing import Literal

from pydantic import BaseModel, Field, model_validator

Point = tuple[float, float]
Corner = Literal["top-left", "top-right", "bottom-left", "bottom-right"]


class Shot(BaseModel):
    mode: Literal["flight", "putt"] = "flight"
    ball_frame: int = 0
    ball: Point | None = None  # video pixels on `ball_frame`
    landing: Point | None = None  # video pixels on `ball_frame` (flight only)
    impact: int | None = None
    shot: Literal["chip", "pitch", "full", "drive"] = "full"
    horizon: float | None = None
    flight_time: float | None = None  # user override (seconds)
    height: float = Field(1.0, ge=0.2, le=3.0)  # user apex height scale
    manual: list[Point] | None = None  # hand-drawn path, video pixels on `manual_frame`
    manual_frame: int = 0


class Style(BaseModel):
    preset: Literal["classic", "tv", "neon", "comet", "minimal"] = "classic"
    color: str = "#FF2D2D"
    core_color: str = "#FFFFFF"
    width: float = Field(1.0, ge=0.2, le=5.0)
    glow: float = Field(1.0, ge=0.0, le=3.0)
    tail: float | None = Field(None, ge=0.1)  # seconds of visible trail (comet); None = full line
    ball_head: bool = True
    landing_marker: bool = True
    cutout: bool = True  # draw the tracer behind the golfer
    hold: float | None = 0.0  # seconds the line stays after landing; None = until the end


class SlowMo(BaseModel):
    enabled: bool = False
    factor: float = Field(3.0, ge=1.0, le=8.0)
    before: float = Field(0.4, ge=0.0, le=5.0)  # seconds before impact
    after: float = Field(1.2, ge=0.0, le=10.0)  # seconds after impact


PopupKind = Literal["distance", "carry", "speed", "apex", "hangtime", "club", "player", "text"]


class Popup(BaseModel):
    kind: PopupKind = "distance"
    label: str = "DISTANCE"
    value: str = ""  # empty hangtime = measured flight time
    anchor: Literal["landing", "apex", "start", "top-left", "top-right", "bottom-left", "bottom-right"] = "landing"
    delay: float = 0.0  # seconds after the anchor event (impact for screen anchors)
    duration: float | None = None  # None = until the end
    color: str = "#111111"
    accent: str = "#FF2D2D"


class Logo(BaseModel):
    path: str | None = None
    corner: Corner = "bottom-right"
    size: float = Field(0.12, ge=0.02, le=0.5)  # fraction of the frame width
    opacity: float = Field(0.85, ge=0.0, le=1.0)


class Swing(BaseModel):
    enabled: bool = False
    source: Literal["clubhead", "hands"] = "clubhead"
    color: str = "#3AA0FF"
    width: float = Field(1.0, ge=0.2, le=5.0)
    gradient: bool = True  # fade older parts of the path


Status = Literal["new", "analysing", "ready", "error"]
MAX_TRACES = 4


def new_key() -> str:
    return uuid.uuid4().hex[:6]


class Trace(BaseModel):
    """One shot traced in the video: its own ball, analysis, line and pop-ups."""

    key: str = Field(default_factory=new_key)  # stable id; names its work directory
    status: Status = "new"
    error: str | None = None
    shot: Shot = Shot()
    popups: list[Popup] = []
    color: str | None = None  # line color; None = the style color
    analysis: dict | None = None


class Project(BaseModel):
    id: str
    name: str
    source: str
    info: dict = {}
    status: Status = "new"  # aggregate of the traces
    error: str | None = None
    traces: list[Trace] = Field(default_factory=lambda: [Trace()], min_length=1, max_length=MAX_TRACES)
    style: Style = Style()
    slowmo: SlowMo = SlowMo()
    logo: Logo = Logo()
    swing: Swing = Swing()

    @model_validator(mode="before")
    @classmethod
    def _single_shot_projects(cls, data):
        """Projects saved before multi-shot support held one shot at the top level."""
        if isinstance(data, dict) and "traces" not in data and ("shot" in data or "analysis" in data):
            data = dict(data)
            legacy = {k: data.pop(k) for k in ("shot", "analysis", "popups") if k in data}
            legacy.update(key="legacy", status=data.get("status", "new"), error=data.get("error"))
            data["traces"] = [legacy]
        return data

    def trace(self, key: str | None = None) -> Trace:
        if key is None:
            return self.traces[0]
        for tr in self.traces:
            if tr.key == key:
                return tr
        raise KeyError(key)
