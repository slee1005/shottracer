package com.ajc.shottracer

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimelineTest {
    @Test fun variableRateFramesAreNotPresentedBeforeTheirTimestamp() {
        val pts=doubleArrayOf(0.0,.033,.12,.121,.9)
        assertEquals(0,playbackFrame(pts,-1.0))
        assertEquals(0,playbackFrame(pts,.032))
        assertEquals(1,playbackFrame(pts,.119))
        assertEquals(2,playbackFrame(pts,.12))
        assertEquals(4,playbackFrame(pts,10.0))
    }
    @Test fun decodeStallsSkipFramesInsteadOfAccumulatingDelay() {
        val pts=DoubleArray(301) { it/30.0 }
        val start=2.0
        assertEquals(63,playbackFrame(pts,start+.105))
        assertEquals(120,playbackFrame(pts,start+2.0))
        assertEquals(270,playbackFrame(pts,start+7.0))
    }
    @Test fun NonzeroSourceStartAndOneFrameClipsAreClamped() {
        assertEquals(0,playbackFrame(doubleArrayOf(1.25),0.0))
        assertEquals(0,playbackFrame(doubleArrayOf(1.25),50.0))
        assertEquals(1,playbackFrame(doubleArrayOf(1.0,1.2,1.7),1.4))
    }
}
