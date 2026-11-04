package com.ajc.shottracer

import org.junit.Assert.*
import org.junit.Test

class Yuv420Test {
    private fun plane(bytes: IntArray, stride: Int, pixel: Int = 1) =
        Yuv420.Plane(ByteArray(bytes.size) { bytes[it].toByte() }, stride, pixel)

    @Test fun paddedLumaRowsPreserveBlackAndWhite() {
        val pixels = Yuv420.decode(2, 2, plane(intArrayOf(16, 235, 7, 235, 16), 3),
            plane(intArrayOf(128), 1), plane(intArrayOf(128), 1))
        assertArrayEquals(intArrayOf(0xff000000.toInt(), -1, -1, 0xff000000.toInt()), pixels.argb)
    }

    @Test fun interleavedChromaAndCropUseTheirOwnStrides() {
        val y = plane(intArrayOf(82, 82, 41, 41, 82, 82, 41, 41), 4)
        val u = plane(intArrayOf(90, 7, 240), 4, 2)
        val v = plane(intArrayOf(240, 7, 110), 4, 2)
        val all = Yuv420.decode(4, 2, y, u, v)
        assertTrue((all.argb[0] shr 16 and 255) >= 250)
        assertTrue((all.argb[0] and 255) <= 3)
        assertTrue((all.argb[2] and 255) >= 250)
        assertTrue((all.argb[2] shr 16 and 255) <= 3)
        val crop = Yuv420.decode(2, 2, y, u, v, 2, 0)
        assertArrayEquals(intArrayOf(all.argb[2], all.argb[3], all.argb[6], all.argb[7]), crop.argb)
    }

    @Test fun sensorRotationPreservesPixelLocations() {
        val input = Yuv420.Pixels(2, 3, intArrayOf(1, 2, 3, 4, 5, 6))
        val rotated = Yuv420.rotate(input, 90)
        assertEquals(3, rotated.width); assertEquals(2, rotated.height)
        assertArrayEquals(intArrayOf(5, 3, 1, 6, 4, 2), rotated.argb)
        assertArrayEquals(intArrayOf(6, 5, 4, 3, 2, 1), Yuv420.rotate(input, 180).argb)
        assertArrayEquals(intArrayOf(2, 4, 6, 1, 3, 5), Yuv420.rotate(input, 270).argb)
        assertArrayEquals(input.argb, Yuv420.rotate(rotated, 270).argb)
    }

    @Test fun neutralChromaStaysGrayAndValuesAreClamped() {
        val gray = Yuv420.decode(2, 2, plane(intArrayOf(126, 126, 126, 126), 2),
            plane(intArrayOf(128), 1), plane(intArrayOf(128), 1)).argb[0]
        assertEquals(gray shr 16 and 255, gray shr 8 and 255)
        assertEquals(gray shr 8 and 255, gray and 255)
        val black = Yuv420.decode(2, 2, plane(intArrayOf(0, 0, 0, 0), 2),
            plane(intArrayOf(128), 1), plane(intArrayOf(128), 1))
        assertTrue(black.argb.all { it == 0xff000000.toInt() })
    }
}
