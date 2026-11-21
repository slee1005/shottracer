"""Exercise the actual portable Swift launch deadline and distance-badge layout."""
from __future__ import annotations

import json
from pathlib import Path
import shutil
import subprocess
import sys

import pytest


SOURCE = Path(__file__).resolve().parents[1] / "app/ios/ShotTracer/TracingWorkflow.swift"


@pytest.fixture(scope="module")
def swift_workflow(tmp_path_factory):
    swift = shutil.which("swiftc")
    if sys.platform != "darwin" or swift is None:
        pytest.skip("iOS portable Swift checks require the macOS Swift toolchain")
    folder = tmp_path_factory.mktemp("ios-workflow")
    main = folder / "main.swift"
    main.write_text('''
import Foundation
import CoreGraphics
let args = CommandLine.arguments
if args[1] == "probe" {
    let stop = LaunchProbe.shouldStop(sourceElapsed: Double(args[2])!, workElapsed: Double(args[3])!, hasLaunch: args[4] == "true")
    print(stop ? "true" : "false")
} else {
    let width=Double(args[2])!, height=Double(args[3])!
    let tip=CGPoint(x:Double(args[4])!,y:Double(args[5])!)
    let box=distanceBadgeRect(at:tip,text:CGSize(width:Double(args[6])!,height:Double(args[7])!),canvas:CGSize(width:width,height:height))
    let values=[box.minX,box.minY,box.width,box.height]
    print(String(data:try! JSONSerialization.data(withJSONObject:values),encoding:.utf8)!)
}
''')
    binary = folder / "workflow"
    compiled = subprocess.run([swift, "-module-cache-path", str(folder / "cache"), str(SOURCE), str(main), "-o", str(binary)], capture_output=True, text=True)
    assert compiled.returncode == 0, compiled.stderr

    def run(*args):
        result = subprocess.run([str(binary), *map(str, args)], check=True, capture_output=True, text=True)
        return json.loads(result.stdout)

    return run


@pytest.mark.parametrize("source,work,launched,expected", [
    (0.0, 0.0, False, False),
    (1.499, 0.5, False, False),
    (1.5, 0.5, False, True),
    (0.35, 4.0, False, True),
    (0.349, 20.0, False, False),  # allow native launch lookahead on slow hardware
    (15.0, 40.0, True, False),  # an established flight is never an early failure
])
def test_early_probe_bounds_source_and_work_time(swift_workflow, source, work, launched, expected):
    assert swift_workflow("probe", source, work, str(launched).lower()) is expected


@pytest.mark.parametrize("width,height", [(640, 360), (360, 640), (1920, 1080)])
def test_badge_follows_the_tip_and_chooses_the_available_side(swift_workflow, width, height):
    y = height / 2
    left = swift_workflow("badge", width, height, width * .2, y, 70, 20)
    right = swift_workflow("badge", width, height, width * .99, y, 70, 20)
    assert left[0] > width * .2
    assert right[0] + right[2] < width * .99
    assert left[1] + left[3] / 2 == pytest.approx(y)
    assert right[1] + right[3] / 2 == pytest.approx(y)


@pytest.mark.parametrize("x,y,text_width", [(-30, -30, 70), (400, 700, 70), (1, 639, 1000), (359, 1, 70)])
def test_badge_stays_inside_portrait_video(swift_workflow, x, y, text_width):
    bx, by, width, height = swift_workflow("badge", 360, 640, x, y, text_width, 20)
    assert bx >= 0 and by >= 0
    assert bx + width <= 360
    assert by + height <= 640
