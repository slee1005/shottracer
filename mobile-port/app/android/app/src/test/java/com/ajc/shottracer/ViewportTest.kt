package com.ajc.shottracer

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportTest {
    @Test fun fittedVideoAlwaysRecentresWhenZoomReturnsToOne() {
        assertEquals(0f,viewportPan(300f,640f,1f),0f)
        assertEquals(0f,viewportPan(-300f,360f,1f),0f)
    }
    @Test fun landscapeVideoCanPanOnBothAxesAndStopsAtEdges() {
        assertEquals(320f,viewportPan(1000f,640f,2f),0f)
        assertEquals(-180f,viewportPan(-1000f,360f,2f),0f)
        assertEquals(90f,viewportPan(90f,360f,2f),0f)
    }
    @Test fun portraitAndViewportResizeUseTheCurrentFittedExtent() {
        assertEquals(200f,viewportPan(200f,360f,3f),0f)
        assertEquals(640f,viewportPan(2000f,640f,3f),0f)
        assertEquals(160f,viewportPan(200f,160f,3f),0f)
    }
}
