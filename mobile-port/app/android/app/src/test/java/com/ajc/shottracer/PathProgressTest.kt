package com.ajc.shottracer

import org.junit.Assert.assertEquals
import org.junit.Test

class PathProgressTest {
    @Test fun distanceFollowsGeometryAndVideoAspectRatio() {
        val path = PathProgress(listOf(EditPoint(0.0, 0.0), EditPoint(.5, 0.0), EditPoint(.5, 1.0)), 200, 400)
        assertEquals(0.0, path.fraction(0.0), 1e-9)
        assertEquals(.2, path.fraction(.5), 1e-9)
        assertEquals(.6, path.fraction(.75), 1e-9)
        assertEquals(1.0, path.fraction(1.0), 1e-9)
    }

    @Test fun stationaryPathDoesNotAccumulateDistanceBeforeCompletion() {
        val point = EditPoint(.4, .4)
        val path = PathProgress(listOf(point, point), 100, 100)
        assertEquals(0.0, path.fraction(.75), 1e-9)
        assertEquals(1.0, path.fraction(1.0), 1e-9)
    }
}
