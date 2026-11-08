package com.jianmu.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.opencv.android.OpenCVLoader

/**
 * 无障碍服务：定时截图（Android 11+）、点击。
 * 每一轮流程（和 Python 脚本的简化模式一致）：
 *   收取一键 -> 若弹窗（整屏变暗）点底部居中关闭 -> 派遣一键 -> 若弹窗点底部居中关闭
 */
class AutoService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoService? = null

        private const val DARK_LIMIT = 85.0                  // 低于它认为有弹窗盖着
        private val COLLECT = Pair(0.8875, 0.8125)           // 「收取一键」在游戏画面里的位置（比例）
        private val DISPATCH = Pair(0.740, 0.8125)           // 「派遣一键」
        private val CLOSE = Pair(0.50, 0.884)                // 弹窗：底部居中（奖励弹窗的空白处 / 派遣结果的「确定」）
        private const val THRESHOLD = 0.70                   // 按钮识别阈值
        private const val CLOSE_THRESHOLD = 0.82             // 建筑详情面板关闭键的阈值（图案简单，要更严）
    }

    private val shotExecutor = Executors.newSingleThreadExecutor()
    private var worker: Thread? = null

    @Volatile
    private var running = false
    private var lastShotAt = 0L

    private var cvReady = false
    private var matcher: Matcher? = null
    private val good = HashMap<String, Pair<Double, Double>>()   // 上次识别成功的位置（占游戏画面比例）

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        cvReady = try {
            OpenCVLoader.initLocal()
        } catch (e: Throwable) {
            false
        }
        if (cvReady) matcher = Matcher(this)
        val mode = if (cvReady) "OpenCV 已加载，使用图像识别" else "OpenCV 加载失败，使用固定位置点击"
        Bus.log("无障碍服务已连接；$mode")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理事件，只用到截图、手势和读取当前前台应用
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopLoop()
        instance = null
        return super.onUnbind(intent)
    }

    // ---------------- 对外接口 ----------------

    @Synchronized
    fun startLoop(intervalMin: Int, delaySec: Int, pkg: String) {
        if (running) {
            Bus.log("已经在运行")
            return
        }
        running = true
        worker = Thread {
            try {
                Bus.log("$delaySec 秒后开始，请现在切到游戏主界面（快捷栏展开）")
                sleepMs(delaySec * 1000L)
                while (running) {
                    try {
                        round(pkg)
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Exception) {
                        Bus.log("出错：" + e.message)
                    }
                    val wait = intervalMin * 60_000L + Random.nextLong(-20_000L, 20_000L)
                    Bus.log("下一轮 " + String.format("%.1f", wait / 60000.0) + " 分钟后")
                    sleepMs(maxOf(30_000L, wait))
                }
            } catch (e: InterruptedException) {
                // 被停止
            } finally {
                running = false
                Bus.log("已停止")
            }
        }
        worker?.start()
    }

    @Synchronized
    fun stopLoop() {
        running = false
        worker?.interrupt()
    }

    /** 几秒后读取当前前台应用的包名，存到设置里（用来确认游戏在前台再点击，避免点到别的应用） */
    fun detectPackage(delaySec: Int) {
        Handler(Looper.getMainLooper()).postDelayed({
            val p = currentPackage()
            if (p == null || p == packageName) {
                Bus.log("没检测到游戏（当前还在本应用），请重试并在倒计时内切到游戏")
            } else {
                getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("pkg", p).apply()
                Bus.log("检测到前台应用包名：$p（已保存，回到本应用可见）")
            }
        }, delaySec * 1000L)
    }

    // ---------------- 一轮流程 ----------------

    private fun round(pkg: String) {
        if (pkg.isNotEmpty()) {
            val cur = currentPackage()
            if (cur != pkg) {
                Bus.log("前台应用是 $cur，不是游戏，跳过本轮")
                return
            }
        }
        dismissPopups()
        val steps = listOf(
            Triple("collect", "收取一键", COLLECT),
            Triple("dispatch", "派遣一键", DISPATCH)
        )
        for (step in steps) {
            val p = locate(step.first, step.second, step.third)
            if (p == null) {
                Bus.log("没找到${step.second}，跳过本轮")
                return
            }
            val x = p.first + jitter(4)
            val y = p.second + jitter(4)
            val ok = tap(x, y)
            val note = if (ok) "" else " 手势失败"
            Bus.log("点击${step.second} (${x.toInt()},${y.toInt()})$note")
            sleepMs(500)
            waitAndDismiss()
        }
        Bus.log("本轮完成")
    }

    /**
     * 找按钮的整屏坐标。有 OpenCV：先识别；没识别到就依次尝试
     * 关建筑详情面板 / 关弹窗 / 按上次成功的位置（没有就按固定位置）。没有 OpenCV：直接按固定位置。
     */
    private fun locate(name: String, label: String, fixed: Pair<Double, Double>): Pair<Float, Float>? {
        for (attempt in 0 until 3) {
            val s = shot() ?: return null
            if (s.brightness < DARK_LIMIT) {
                dismissPopups()
                continue
            }
            val m = matcher
            if (!cvReady || m == null) {
                return s.at(fixed.first, fixed.second)
            }
            val hit = m.find(s, name, THRESHOLD)
            if (hit != null) {
                val gw = (s.x1 - s.x0).toDouble()
                val gh = (s.y1 - s.y0).toDouble()
                good[name] = Pair((hit.x - s.x0) / gw, (hit.y - s.y0) / gh)
                Bus.log("识别到$label，相似度 " + String.format("%.2f", hit.score))
                return Pair(hit.x, hit.y)
            }
            val close = m.find(s, "close", CLOSE_THRESHOLD)
            if (close != null) {
                tap(close.x, close.y)
                Bus.log("关闭建筑详情面板")
                sleepMs(1000)
                continue
            }
            if (attempt >= 1) {
                val g = good[name]
                val r = g ?: fixed
                Bus.log("没识别到$label，按" + (if (g != null) "上次" else "固定") + "位置点击")
                return s.at(r.first, r.second)
            }
            sleepMs(1500)   // 可能正在过渡动画，稍等再试
        }
        return null
    }

    /** 画面被压暗说明有弹窗盖着：点底部居中关闭，最多重试几次 */
    private fun dismissPopups(tries: Int = 4): Boolean {
        var closed = false
        for (i in 0 until tries) {
            val s = shot() ?: return closed
            if (s.brightness >= DARK_LIMIT) return closed
            val p = s.at(CLOSE.first, CLOSE.second)
            tap(p.first + jitter(6), p.second + jitter(4))
            Bus.log("检测到弹窗（亮度 ${s.brightness.toInt()}），点击关闭")
            closed = true
            sleepMs(1500)
        }
        return closed
    }

    /** 点完按钮后等弹窗淡入出现：最多等 maxMs，出现了就关掉 */
    private fun waitAndDismiss(maxMs: Long = 4000L) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < maxMs) {
            sleepMs(800)
            val s = shot() ?: return
            if (s.brightness < DARK_LIMIT) {
                dismissPopups()
                return
            }
        }
    }

    // ---------------- 底层：截图 / 点击 / 等待 ----------------

    private fun jitter(n: Int): Float = Random.nextInt(-n, n + 1).toFloat()

    private fun sleepMs(ms: Long) {
        var left = ms
        while (left > 0 && running) {
            val step = minOf(left, 500L)
            Thread.sleep(step)
            left -= step
        }
        if (!running) throw InterruptedException()
    }

    private fun shot(): Shot? {
        for (attempt in 0 until 3) {
            // takeScreenshot 有调用间隔限制，保证两次间隔 >= 1.1 秒
            val gap = System.currentTimeMillis() - lastShotAt
            if (gap < 1100L) sleepMs(1100L - gap)
            lastShotAt = System.currentTimeMillis()
            val bmp = screenshotBitmap()
            if (bmp != null) {
                val s = Shot(bmp)
                val m = matcher
                if (cvReady && m != null) {
                    try {
                        m.prepare(bmp, s)
                    } catch (e: Throwable) {
                        Bus.log("识图预处理失败：" + e.message)
                    }
                }
                bmp.recycle()
                return s
            }
            sleepMs(1200)
        }
        return null
    }

    private fun screenshotBitmap(): Bitmap? {
        var result: Bitmap? = null
        val latch = CountDownLatch(1)
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            shotExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val buffer = screenshot.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        if (hw != null) {
                            result = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                        }
                        buffer.close()
                    } catch (e: Exception) {
                        Bus.log("截图解析失败：" + e.message)
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    Bus.log("截图失败，错误码 $errorCode")
                    latch.countDown()
                }
            }
        )
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        if (!dispatchGesture(gesture, callback, null)) return false
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    @Suppress("DEPRECATION")
    private fun currentPackage(): String? {
        val root = rootInActiveWindow ?: return null
        val name = root.packageName?.toString()
        try {
            root.recycle()
        } catch (e: Exception) {
        }
        return name
    }
}
