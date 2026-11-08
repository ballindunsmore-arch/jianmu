package com.jianmu.auto

import android.graphics.Bitmap

/**
 * 一张截图的分析结果：
 *  - 游戏画面所在矩形 (x0,y0,x1,y1)：去掉手机上的黑边（和 Python 脚本里的 game_rect 一致）
 *  - 游戏画面的平均亮度：弹窗会把整屏压暗（实测弹窗约 40-60，正常画面 125 以上）
 */
class Shot(bmp: Bitmap) {
    val w: Int = bmp.width
    val h: Int = bmp.height
    var x0: Int = 0
    var y0: Int = 0
    var x1: Int = 0
    var y1: Int = 0
    var brightness: Double = 0.0

    // 识图用：游戏画面裁出来、缩放到宽 720 的灰度字节（由 Matcher.prepare 填充）
    var normGray: ByteArray? = null
    var normH: Int = 0
    var scaleK: Double = 1.0

    init {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // 1) 找左右边界：逐列平均灰度 > 8 的范围（隔 4 行采样）
        val colSum = LongArray(w)
        var rowsSampled = 0
        var ry = 0
        while (ry < h) {
            val base = ry * w
            for (cx in 0 until w) {
                colSum[cx] += gray(px[base + cx]).toLong()
            }
            rowsSampled++
            ry += 4
        }
        var first = -1
        var last = -1
        for (cx in 0 until w) {
            if (colSum[cx] > 8L * rowsSampled) {
                if (first < 0) first = cx
                last = cx
            }
        }
        if (first >= 0 && (last - first + 1) > w * 0.3) {
            x0 = first
            x1 = last + 1
        } else {
            x0 = 0
            x1 = w
        }

        // 2) 找上下边界：在左右边界内逐行平均灰度 > 8
        var colsSampled = 0
        var sx = x0
        while (sx < x1) {
            colsSampled++
            sx += 4
        }
        first = -1
        last = -1
        for (yy in 0 until h) {
            var s = 0L
            var xx = x0
            while (xx < x1) {
                s += gray(px[yy * w + xx])
                xx += 4
            }
            if (s > 8L * colsSampled) {
                if (first < 0) first = yy
                last = yy
            }
        }
        if (first >= 0 && (last - first + 1) > h * 0.3) {
            y0 = first
            y1 = last + 1
        } else {
            y0 = 0
            y1 = h
        }

        // 3) 游戏画面平均亮度
        var sum = 0L
        var n = 0L
        var by = y0
        while (by < y1) {
            var bx = x0
            while (bx < x1) {
                sum += gray(px[by * w + bx])
                n++
                bx += 4
            }
            by += 4
        }
        brightness = if (n > 0) sum.toDouble() / n else 0.0
    }

    /** 游戏画面内的比例位置 -> 整屏像素坐标 */
    fun at(fx: Double, fy: Double): Pair<Float, Float> {
        val x = x0 + (x1 - x0) * fx
        val y = y0 + (y1 - y0) * fy
        return Pair(x.toFloat(), y.toFloat())
    }

    private fun gray(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }
}
