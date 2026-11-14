package com.ajc.shottracer

import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceInputTest {
    @Test fun convertsDistanceAndAcceptsLegacyUnits() {
        assertEquals("137.16", distanceInput("150 yd", "m"))
        assertEquals("150", distanceInput("137,16 m", "yds"))
        assertEquals("yds", shotMeasurement("150 야드")?.unit)
        assertEquals("30.48", distanceInput("100 ft", "m"))
        assertEquals("150", distanceInput("150", "m"))
    }
}
