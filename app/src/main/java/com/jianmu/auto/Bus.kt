package com.jianmu.auto

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 简单的日志总线：服务线程写日志，界面在主线程显示。 */
object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val lines = ArrayList<String>()

    @Volatile
    var listener: ((String) -> Unit)? = null

    @Synchronized
    fun log(msg: String) {
        val line = fmt.format(Date()) + " " + msg
        lines.add(line)
        while (lines.size > 200) lines.removeAt(0)
        main.post { listener?.invoke(line) }
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
