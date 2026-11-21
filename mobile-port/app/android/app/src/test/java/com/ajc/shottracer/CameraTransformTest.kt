package com.ajc.shottracer

import org.junit.Assert.*
import org.junit.Test

class CameraTransformTest {
    @Test fun rotationAndZoomMapHandlesAndRoundTripInPortraitVideo() {
        // A 90-degree pixel rotation of a 180x240 image has unequal normalized
        // off-diagonal coefficients. Treating them as equal would move the handle.
        val transform=CameraTransform(1.0,0.0,-240.0/180,.2,180.0/240,0.0,.1)
        val shot=EditShot(cameraTransforms=listOf(transform))
        val p=EditPoint(.2,.3)
        val q=shot.toFrame(p,1.0)
        assertEquals(-.2,q.x,1e-10)
        assertEquals(.25,q.y,1e-10)
        val restored=shot.toReference(q,1.0)
        assertEquals(p.x,restored.x,1e-10); assertEquals(p.y,restored.y,1e-10)
    }

    @Test fun oldProjectsContinueToUseTimestampedTranslations() {
        val shot=EditShot(camera=listOf(TimedPoint(0.0,0.0,0.0),TimedPoint(2.0,.2,-.1)))
        val p=EditPoint(.25,.75)
        assertEquals(EditPoint(.35,.7),shot.toFrame(p,1.0))
        val restored=shot.toReference(shot.toFrame(p,1.0),1.0)
        assertEquals(p.x,restored.x,1e-10); assertEquals(p.y,restored.y,1e-10)
        assertTrue(shot.cameraTransforms.isEmpty())
    }

    @Test fun fullMatricesTakePrecedenceWithoutChangingAutomaticData() {
        val base=listOf(TimedPoint(1.0,.2,.8),TimedPoint(2.0,.3,.7))
        val transform=CameraTransform(1.0,1.1,0.0,.03,0.0,1.1,-.02)
        val shot=EditShot(base=base,camera=listOf(TimedPoint(1.0,.8,.8)),cameraTransforms=listOf(transform))
        val q=shot.toFrame(EditPoint(.2,.8),1.0)
        assertEquals(.25,q.x,1e-10); assertEquals(.86,q.y,1e-10)
        assertSame(base,shot.base)
        assertEquals(transform,shot.cameraTransforms.single())
    }

    @Test fun sourceTimestampsInterpolateAndClampCameraSamples() {
        val rows=cameraSamples(doubleArrayOf(0.0,1.0,0.0,0.0,0.0,1.0,0.0,
            .3,1.06,0.0,.03,0.0,1.06,-.06))
        val mid=cameraAt(rows,.1)!!
        assertEquals(1.02,mid.m00,1e-10); assertEquals(.01,mid.m02,1e-10)
        assertEquals(-.02,mid.m12,1e-10)
        assertEquals(rows.first(),cameraAt(rows,-1.0)); assertEquals(rows.last(),cameraAt(rows,1.0))
        assertTrue(cameraSamples(doubleArrayOf(1.0,2.0)).isEmpty())
    }
}
