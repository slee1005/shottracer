package com.ajc.shottracer

import org.junit.Assert.*
import org.junit.Test

class ClubTracingTest {
    @Test fun newProjectsDoNotDrawAutomaticClubPaths() {
        val scene=EditorScene()
        assertEquals("off",scene.visibleAutoSwing())
        assertFalse(scene.showAutoClub())
    }
    @Test fun legacyBothAndClubDefaultsAreNotAnExplicitOptIn() {
        val scene=EditorScene()
        scene.autoSwing="both"
        assertFalse(scene.showAutoClub())
        assertEquals("hands",scene.visibleAutoSwing())
        scene.autoSwing="club"
        assertFalse(scene.showAutoClub())
        assertEquals("off",scene.visibleAutoSwing())
    }
    @Test fun settingsEnableClubOnlyWhenChosenAndRespectTheMasterSwitch() {
        val scene=EditorScene()
        scene.configureAutoSwing("club"); assertTrue(scene.showAutoClub())
        scene.configureAutoSwing("both"); assertTrue(scene.showAutoClub())
        scene.swingEnabled=false; assertFalse(scene.showAutoClub())
        scene.swingEnabled=true; scene.configureAutoSwing("hands"); assertFalse(scene.showAutoClub())
        scene.configureAutoSwing("off"); assertFalse(scene.showAutoClub())
        scene.configureAutoSwing("invalid"); assertEquals("off",scene.visibleAutoSwing())
    }
}
