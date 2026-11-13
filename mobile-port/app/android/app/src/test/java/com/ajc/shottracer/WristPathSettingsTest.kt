package com.ajc.shottracer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WristPathSettingsTest {
    @Test fun legacyCombinedModeKeepsWrists() {
        val scene = EditorScene().apply { autoSwing = "both"; clubTracingEnabled = true }
        assertTrue(scene.autoWristsEnabled)
    }

    @Test fun legacyClubOnlyDoesNotEnableWrists() {
        val scene = EditorScene().apply { autoSwing = "club"; clubTracingEnabled = true }
        assertFalse(scene.autoWristsEnabled)
    }
}
