package com.ajc.shottracer

/** Camera planes may be padded or interleaved. Never assume NV21 or contiguous rows. */
internal object Yuv420 {
    data class Plane(val bytes: ByteArray, val rowStride: Int, val pixelStride: Int) {
        fun at(x: Int, y: Int): Int = bytes[y * rowStride + x * pixelStride].toInt() and 255
    }
    data class Pixels(val width: Int, val height: Int, val argb: IntArray)

    fun decode(width: Int, height: Int, y: Plane, u: Plane, v: Plane, left: Int = 0, top: Int = 0): Pixels {
        require(width > 0 && height > 0 && left >= 0 && top >= 0)
        val pixels = IntArray(width * height)
        for (row in 0 until height) for (column in 0 until width) {
            val x = left + column; val yy = top + row
            val c = maxOf(0, y.at(x, yy) - 16)
            val d = u.at(x / 2, yy / 2) - 128; val e = v.at(x / 2, yy / 2) - 128
            val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
            pixels[row * width + column] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Pixels(width, height, pixels)
    }

    fun rotate(source: Pixels, degrees: Int): Pixels {
        require(degrees in listOf(0, 90, 180, 270))
        if (degrees == 0) return source
        val w = source.width; val h = source.height
        val width = if (degrees == 180) w else h
        val height = if (degrees == 180) h else w
        val result = IntArray(width * height)
        for (y in 0 until h) for (x in 0 until w) {
            val dx: Int; val dy: Int
            when (degrees) {
                90 -> { dx = h - 1 - y; dy = x }
                180 -> { dx = w - 1 - x; dy = h - 1 - y }
                else -> { dx = y; dy = w - 1 - x }
            }
            result[dy * width + dx] = source.argb[y * w + x]
        }
        return Pixels(width, height, result)
    }
}
