from __future__ import annotations

import asyncio
import io
import mimetypes
from email.message import Message
from pathlib import Path
from urllib.error import HTTPError

import pytest
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from shottracer import desktop


def test_macos_file_responses_without_system_mime_access(monkeypatch):
    # Reproduce a fresh TestFlight process, where the file exists but cannot be read.
    for name in ("knownfiles", "inited", "_db", "suffix_map", "types_map", "encodings_map", "common_types"):
        monkeypatch.setattr(mimetypes, name, getattr(mimetypes, name))
    monkeypatch.setattr(mimetypes, "knownfiles", ["/etc/apache2/mime.types"])
    monkeypatch.setattr(mimetypes, "inited", False)
    monkeypatch.setattr(mimetypes, "_db", None)
    monkeypatch.setattr(desktop.sys, "platform", "darwin")
    monkeypatch.setattr(desktop.faulthandler, "enable", lambda *_: None)
    monkeypatch.setenv("MPLBACKEND", "Agg")

    def denied(*_):
        raise PermissionError("App Sandbox denies /etc/apache2/mime.types")

    monkeypatch.setattr(mimetypes.MimeTypes, "read", denied)
    desktop._prepare_environment(log=False)
    static = StaticFiles(directory=Path(desktop.__file__).parent / "web")
    for name, expected in (("style.css", "text/css"), ("i18n.js", "text/javascript"), ("app.js", "text/javascript")):
        response = asyncio.run(static.get_response(name, {"method": "GET", "headers": []}))
        assert response.status_code == 200
        assert response.headers["content-type"].split(";", 1)[0] == expected
    # Preview frames and video downloads also use FileResponse.
    assert FileResponse("frame.jpg").headers["content-type"] == "image/jpeg"
    assert FileResponse("render.mp4").headers["content-type"] == "video/mp4"


class _Response(io.BytesIO):
    status = 200

    def __init__(self, content: bytes, content_type: str):
        super().__init__(content)
        self.headers = Message()
        self.headers["Content-Type"] = content_type


@pytest.mark.parametrize("failure", [None, "missing", "http500", "mime", "body"])
def test_editor_self_test_checks_referenced_assets(monkeypatch, failure):
    web = Path(desktop.__file__).parent / "web"
    page = (web / "index.html").read_text().replace("{{v}}", "123")
    if failure == "missing":
        page = page.replace('<script src="/static/app.js?v=123"></script>', "")
    requests = []

    def urlopen(url, timeout):
        requests.append(url)
        assert timeout == 10
        if url == "http://127.0.0.1:9000":
            return _Response(page.encode(), "text/html")
        name = url.split("/static/", 1)[1].split("?", 1)[0]
        assert url.endswith("?v=123")  # Exercise exactly the URL the webview requests.
        if name == "app.js" and failure == "http500":
            raise HTTPError(url, 500, "Internal Server Error", {}, None)
        content_type = "text/css" if name == "style.css" else "text/javascript"
        content = (web / name).read_bytes()
        if name == "app.js":
            if failure == "mime":
                content_type = "text/plain"
            elif failure == "body":
                content = b"Internal Server Error"
        return _Response(content, content_type)

    monkeypatch.setattr(desktop.urllib.request, "urlopen", urlopen)
    if failure:
        with pytest.raises((RuntimeError, HTTPError)):
            desktop._check_editor("http://127.0.0.1:9000")
    else:
        report = desktop._check_editor("http://127.0.0.1:9000")
        assert report["editor_page"] == 200
        assert set(report["editor_assets"]) == {"style.css", "i18n.js", "app.js"}
        assert len(requests) == 4
