package com.ajc.shottracer

import org.junit.Assert.*
import org.junit.Test

class EditorTimingTest {
    @Test fun overlappingShotSlowMotionWindowsMerge() {
        val scene = EditorScene()
        scene.shots.clear()
        scene.shots.add(EditShot(start = 2.0, ball = EditPoint(.2, .8)))
        scene.shots.add(EditShot(start = 3.0, ball = EditPoint(.3, .8)))
        val windows = slowIntervals(scene, 10.0)
        assertEquals(1, windows.size)
        assertEquals(1.6, windows[0].first, 1e-10)
        assertEquals(4.2, windows[0].second, 1e-10)
        assertEquals(1.0, sourceTime(1.0, windows, 3.0), 1e-10)
        assertEquals(4.2, sourceTime(9.4, windows, 3.0), 1e-10)
        assertEquals(5.2, sourceTime(10.4, windows, 3.0), 1e-10)
    }
    @Test fun lateShotWindowClipsToSourceDuration() {
        val scene = EditorScene()
        scene.shot.start = 9.8; scene.shot.ball = EditPoint(.1, .8)
        val windows = slowIntervals(scene, 10.0)
        assertEquals(10.0, windows.last().second, 1e-10)
        assertEquals(3.25, sourceTime(3.25, emptyList(), 3.0), 1e-10)
    }
    @Test fun puttEditsKeepObservedGroundPathAndExactLanding() {
        val shot=EditShot(start=1.0,ball=EditPoint(.1,.8),flight=2.0,trackingMode="putt",base=listOf(TimedPoint(1.0,.1,.8),TimedPoint(2.0,.3,.8),TimedPoint(3.0,.5,.8)))
        val original=shot.base.toList()
        assertEquals(.3,shot.ground(1.0).x,1e-10)
        assertEquals(.8,shot.ground(1.0).y,1e-10)
        shot.landing=EditPoint(.7,.9); shot.flight=4.0
        assertEquals(.7,shot.ground(4.0).x,1e-10)
        assertEquals(.9,shot.ground(4.0).y,1e-10)
        assertEquals(original,shot.base)
    }
}
