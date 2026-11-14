package com.ajc.shottracer

import org.junit.Assert.*
import org.junit.Test

class PopupLayoutTest {
    @Test fun delayedAfterShotPopupAlwaysHasAtLeastOneSecond() {
        val shot=EditShot(start=1.0,flight=2.0)
        val popup=EditPopup(anchor="landing",delay=3.0)
        val window=popupWindow(shot,popup)!!
        assertEquals(6.0,window.begins,1e-10)
        assertEquals(7.0,window.ends,1e-10)
        assertTrue(window.contains(6.5))
        assertFalse(window.contains(5.0))
    }
    @Test fun explicitDurationAndVideoEndStayIndependentOfAfterShotLimit() {
        val shot=EditShot(start=1.0,flight=2.0)
        val timed=EditPopup(anchor="landing",delay=3.0,hide="time",duration=.5)
        assertEquals(6.5,popupWindow(shot,timed)!!.ends,1e-10)
        timed.hide="end"
        assertTrue(popupWindow(shot,timed)!!.contains(1000.0))
    }
    @Test fun previewChoosesFirstVisibleSourceFrameInsteadOfNearestEarlierFrame() {
        val shot=EditShot(start=.3,flight=2.0)
        val popup=EditPopup(anchor="top-left")
        val times=doubleArrayOf(0.0,.1,.4,.63,1.0)
        assertEquals(2,popupFrame(shot,popup,times,0))
        assertEquals(3,popupFrame(shot,popup,times,3))
    }
    @Test fun popupOutsideClipDoesNotInventAVisiblePreviewFrame() {
        val shot=EditShot(start=.3,flight=4.0)
        val popup=EditPopup(anchor="landing")
        assertNull(popupFrame(shot,popup,doubleArrayOf(0.0,.1,.4,1.0),0))
        popup.anchor="top-left"; popup.hide="time"; popup.duration=.1
        assertNull(popupFrame(shot,popup,doubleArrayOf(0.0,.1,.5,1.0),0))
    }
    @Test fun repeatedAnchorsStackWithoutHidingEarlierCards() {
        val first=placePopup(20.0,20.0,160.0,44.0,640.0,360.0,emptyList(),false)
        val second=placePopup(20.0,20.0,160.0,44.0,640.0,360.0,listOf(first),false)
        assertFalse(first.overlaps(second,4.0))
        assertTrue(second.y>first.y)
        val bottom=placePopup(20.0,310.0,160.0,44.0,640.0,360.0,emptyList(),true)
        val above=placePopup(20.0,310.0,160.0,44.0,640.0,360.0,listOf(bottom),true)
        assertTrue(above.y<bottom.y); assertFalse(bottom.overlaps(above,4.0))
        val edge=placePopup(1000.0,-100.0,160.0,44.0,640.0,360.0,emptyList(),false)
        assertTrue(edge.x+edge.width<=636); assertTrue(edge.y>=4)
    }
}
