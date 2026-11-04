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
    @Test fun timelineUsesPhysicalSourceTimeAndNearestVfrFrame() {
        val shot=EditShot(start=.37,ball=EditPoint(.1,.8),landing=EditPoint(.8,.7),flight=.5,speed=4.0)
        val events=shotEvents(shot,doubleArrayOf(0.0,.1,.4,.91,1.25),1.5)
        assertEquals(2,events.size)
        assertEquals(2,events[0].frame)
        assertEquals(.87,events[1].time,1e-10)
        assertEquals(3,events[1].frame)
        assertTrue(events[1].inClip)
        assertFalse(events[1].estimated)
    }
    @Test fun timelineDistinguishesUnsetPredictedAndOutsideLanding() {
        val shot=EditShot(start=.1,ball=EditPoint(.1,.8),flight=4.0)
        val times=doubleArrayOf(0.0,.1,.4,.9)
        assertEquals(1,shotEvents(shot,times,1.0).size)
        shot.manual=true
        val landing=shotEvents(shot,times,1.0).last()
        assertTrue(landing.estimated)
        assertFalse(landing.inClip)
        assertEquals(3,landing.frame)
        assertEquals(4.1,landing.time,1e-10)
        shot.ball=null
        assertTrue(shotEvents(shot,times,1.0).isEmpty())
    }
    @Test fun puttTimelineIncludesLastFrameExposureAndIgnoresDrawingSpeed() {
        val shot=EditShot(start=.1,ball=EditPoint(.1,.8),flight=.85,speed=.5,trackingMode="putt",base=listOf(TimedPoint(.1,.1,.8),TimedPoint(.9,.5,.8)))
        val landing=shotEvents(shot,doubleArrayOf(0.0,.1,.4,.9),1.0).last()
        assertEquals(.95,landing.time,1e-10)
        assertTrue(landing.inClip)
        assertFalse(landing.estimated)
        assertEquals(3,landing.frame)
    }
    @Test fun draggingStartHoldsLandingTimeLocationAndAutomaticData() {
        val base=listOf(TimedPoint(1.0,.1,.8),TimedPoint(2.0,.4,.7),TimedPoint(3.0,.8,.7))
        val model=DoubleArray(16) { it.toDouble() }
        val shot=EditShot(start=1.0,ball=EditPoint(.1,.8),landing=EditPoint(.8,.7),flight=2.0,base=base,baseModel=model)
        val times=doubleArrayOf(0.0,.2,.5,1.0,1.25,1.7,2.8,3.0,3.5)
        assertEquals(4,retimeShot(shot,"ball",4,times))
        assertEquals(1.25,shot.start,1e-10)
        assertEquals(3.0,shot.start+shot.flight,1e-10)
        assertEquals(EditPoint(.8,.7),shot.landing)
        assertSame(base,shot.base); assertSame(model,shot.baseModel)
        retimeShot(shot,"ball",8,times)
        assertEquals(2.8,shot.start,1e-10)
        assertEquals(.2,shot.flight,1e-10)
    }
    @Test fun draggingLandingClampsToLegalFramesAndCanRecoverOutsideClip() {
        val shot=EditShot(start=.2,ball=EditPoint(.1,.8),landing=EditPoint(.8,.7),flight=4.0,manual=true)
        val times=doubleArrayOf(0.0,.1,.2,.45,.9,1.2)
        assertEquals(3,retimeShot(shot,"landing",0,times))
        assertEquals(.2,shot.start,1e-10); assertEquals(.25,shot.flight,1e-10)
        retimeShot(shot,"landing",5,times)
        assertEquals(1.2,shot.start+shot.flight,1e-10)
        assertTrue(shotEvents(shot,times,1.3).last().inClip)
        val short=EditShot(start=1.2,ball=EditPoint(.1,.8),landing=EditPoint(.8,.7),manual=true)
        assertNull(retimeShot(short,"landing",0,times))
        assertEquals(4.0,short.flight,1e-10)
    }
    @Test fun puttStartRetimeUsesOriginalObservationsAndKeepsPredictionLabel() {
        val original=listOf(TimedPoint(1.0,.1,.8),TimedPoint(2.0,.3,.8),TimedPoint(3.0,.5,.8))
        val shot=EditShot(start=1.0,ball=EditPoint(.1,.8),flight=2.0,trackingMode="putt",base=original)
        retimeShot(shot,"ball",1,doubleArrayOf(0.0,.5,1.0,1.5,2.0,2.5,3.0))
        assertEquals(.3,shot.ground(1.25).x,1e-10)
        assertEquals(.5,shot.ground(2.5).x,1e-10)
        assertSame(original,shot.base)
        val predicted=EditShot(start=0.0,ball=EditPoint(.1,.8),manual=true,flight=1.0)
        retimeShot(predicted,"landing",3,doubleArrayOf(0.0,.2,.5,.9,1.2))
        assertTrue(shotEvents(predicted,doubleArrayOf(0.0,.2,.5,.9,1.2),1.3).last().estimated)
    }
    @Test fun manualPuttWithoutAnalysisDrawsFromSelectedBallToLanding() {
        val shot=EditShot(ball=EditPoint(.2,.8),landing=EditPoint(.8,.6),flight=2.0,trackingMode="putt")
        assertTrue(shot.hasGroundPath())
        assertEquals(EditPoint(.2,.8),shot.ground(-1.0))
        assertEquals(.5,shot.ground(1.0).x,1e-10)
        assertEquals(.7,shot.ground(1.0).y,1e-10)
        assertEquals(EditPoint(.8,.6),shot.ground(3.0))
        assertTrue(shot.base.isEmpty()); assertNull(shot.baseModel)
    }
    @Test fun singleSeedPuttUsesManualEndpointsInsteadOfFrameOrigin() {
        val seed=listOf(TimedPoint(4.0,.2,.8))
        val shot=EditShot(start=4.0,ball=EditPoint(.2,.8),landing=EditPoint(.6,.8),flight=1.0,trackingMode="putt",base=seed)
        assertTrue(shot.hasGroundPath())
        assertEquals(.4,shot.ground(.5).x,1e-10)
        assertEquals(.8,shot.ground(.5).y,1e-10)
        assertSame(seed,shot.base)
    }
    @Test fun startOnlyPuttDoesNotInventAnEndpoint() {
        val shot=EditShot(ball=EditPoint(.2,.8),trackingMode="putt")
        assertFalse(shot.hasGroundPath())
        assertEquals(shot.ball,shot.ground(2.0))
        shot.base=listOf(TimedPoint(0.0,.2,.8),TimedPoint(.1,.3,.8))
        assertTrue(shot.hasGroundPath())
        shot.ball=null
        assertFalse(shot.hasGroundPath())
    }
}
