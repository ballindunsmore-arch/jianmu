package com.jianmu.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class Hit(val x: Float, val y: Float, val score: Double)

/**
 * 模板匹配。思路和 Python 脚本一致：
 *  1) 去掉黑边，只取游戏画面，缩放到宽 720（模板都是 MuMu 720x1280 原尺寸截的）；
 *  2) 只在画面下半部分找（快捷栏、关闭键都在下半部分），更快也更不容易误识别；
 *  3) 简体、繁体模板都试，取相似度最高的。
 */
class Matcher(private val ctx: Context) {

    private val cache = HashMap<String, List<Mat>>()
    private val scales = doubleArrayOf(0.92, 0.96, 1.0, 1.04, 1.08)

    private fun templates(name: String): List<Mat> {
        val cached = cache[name]
        if (cached != null) return cached
        val list = ArrayList<Mat>()
        val files = ctx.assets.list("templates") ?: emptyArray()
        for (f in files) {
            if (!f.startsWith(name) || !f.endsWith(".png")) continue
            ctx.assets.open("templates/$f").use { input ->
                val bmp = BitmapFactory.decodeStream(input)
                if (bmp != null) {
                    val rgba = Mat()
                    Utils.bitmapToMat(bmp, rgba)
                    val gray = Mat()
                    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                    rgba.release()
                    bmp.recycle()
                    list.add(gray)
                }
            }
        }
        cache[name] = list
        return list
    }

    /** 把截图裁成游戏画面并缩放到宽 720，灰度字节存进 Shot */
    fun prepare(bmp: Bitmap, s: Shot) {
        val gw = s.x1 - s.x0
        val gh = s.y1 - s.y0
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val roi = Mat(rgba, Rect(s.x0, s.y0, gw, gh))
        val gray = Mat()
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGBA2GRAY)
        val k = gw / 720.0
        val nh = Math.max(1, Math.round(gh / k).toInt())
        val norm = Mat()
        Imgproc.resize(gray, norm, Size(720.0, nh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val bytes = ByteArray(720 * nh)
        norm.get(0, 0, bytes)
        s.normGray = bytes
        s.normH = nh
        s.scaleK = k
        norm.release()
        gray.release()
        roi.release()
        rgba.release()
    }

    /** 在下半部分找模板 name；找到且相似度 >= threshold 返回整屏坐标，否则 null */
    fun find(s: Shot, name: String, threshold: Double): Hit? {
        val bytes = s.normGray ?: return null
        val nh = s.normH
        val top = nh / 2
        val full = Mat(nh, 720, CvType.CV_8UC1)
        full.put(0, 0, bytes)
        val region = full.submat(top, nh, 0, 720)

        var best: Hit? = null
        var bestScore = 0.0
        for (tpl in templates(name)) {
            for (sc in scales) {
                val resized = sc != 1.0
                val t = if (resized) {
                    val r = Mat()
                    Imgproc.resize(tpl, r, Size(), sc, sc, Imgproc.INTER_AREA)
                    r
                } else {
                    tpl
                }
                if (t.rows() < region.rows() && t.cols() < region.cols()) {
                    val res = Mat()
                    Imgproc.matchTemplate(region, t, res, Imgproc.TM_CCOEFF_NORMED)
                    val mm = Core.minMaxLoc(res)
                    if (mm.maxVal > bestScore) {
                        bestScore = mm.maxVal
                        val cx = mm.maxLoc.x + t.cols() / 2.0
                        val cy = top + mm.maxLoc.y + t.rows() / 2.0
                        best = Hit(
                            (s.x0 + cx * s.scaleK).toFloat(),
                            (s.y0 + cy * s.scaleK).toFloat(),
                            mm.maxVal
                        )
                    }
                    res.release()
                }
                if (resized) t.release()
            }
        }
        region.release()
        full.release()
        return if (best != null && bestScore >= threshold) best else null
    }
}
