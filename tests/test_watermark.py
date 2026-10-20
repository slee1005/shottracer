import numpy as np

from shottracer import engine, watermark


def test_only_the_right_password_unlocks():
    assert watermark.check_password("mygolflife")
    for wrong in ("", "MyGolfLife", "mygolflife ", "golf"):
        assert not watermark.check_password(wrong)


def test_password_is_not_stored_in_plain_text():
    source = open(watermark.__file__, encoding="utf-8").read()
    assert "mygolflife" not in source


def test_on_by_default_and_remembered(tmp_path, monkeypatch):
    monkeypatch.setattr(engine, "WORKSPACE", tmp_path / "projects")
    assert watermark.enabled()
    watermark.set_enabled(False)
    assert not watermark.enabled()
    watermark.set_enabled(True)
    assert watermark.enabled()


def test_draws_text_at_the_bottom():
    frame = np.zeros((360, 640, 3), np.uint8)
    watermark.draw(frame)
    rows = np.flatnonzero(frame.max(axis=(1, 2)) > 0)
    assert len(rows) and rows.min() > 300
