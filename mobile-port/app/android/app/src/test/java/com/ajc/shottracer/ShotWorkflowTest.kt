package com.ajc.shottracer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotWorkflowTest {
    @Test fun analysisRequiresExplicitLandingAndValidDuration() {
        val shot = EditShot(ball = EditPoint(.2, .8), landing = EditPoint(.6, .5))
        assertFalse(shot.readyToAnalyze())

        shot.landingConfirmed = true
        shot.flight = .1
        assertFalse(shot.readyToAnalyze())

        shot.flight = 1.0
        assertTrue(shot.readyToAnalyze())
        assertFalse(shot.hasTrace())

        shot.base = listOf(TimedPoint(0.0, .2, .8), TimedPoint(.1, .3, .6))
        assertTrue(shot.hasTrace())
    }
}
