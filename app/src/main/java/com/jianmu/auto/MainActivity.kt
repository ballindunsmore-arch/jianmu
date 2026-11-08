package com.jianmu.auto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var etInterval: EditText
    private lateinit var etDelay: EditText
    private lateinit var etPkg: EditText

    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        status = TextView(this)
        status.textSize = 16f
        root.addView(status)

        root.addView(button("1. 打开无障碍设置（找到「建木挂机」并开启）") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(label("每轮间隔（分钟）"))
        etInterval = numberField(prefs.getInt("interval", 20).toString())
        root.addView(etInterval)

        root.addView(label("开始前等待（秒，用来切到游戏界面）"))
        etDelay = numberField(prefs.getInt("delay", 10).toString())
        root.addView(etDelay)

        root.addView(label("游戏包名（可选；填了之后，游戏不在前台就跳过本轮，避免点到别的应用）"))
        etPkg = EditText(this)
        etPkg.setSingleLine(true)
        etPkg.setText(prefs.getString("pkg", ""))
        root.addView(etPkg)

        root.addView(button("检测游戏包名（点后 8 秒内切到游戏）") {
            val svc = AutoService.instance
            if (svc == null) {
                toast("请先开启无障碍服务")
            } else {
                svc.detectPackage(8)
                toast("请在 8 秒内切到游戏，再回到本应用查看")
            }
        })

        root.addView(button("2. 开始") {
            val svc = AutoService.instance
            if (svc == null) {
                toast("请先开启无障碍服务")
            } else {
                save()
                svc.startLoop(readInterval(), readDelay(), etPkg.text.toString().trim())
            }
        })

        root.addView(button("停止") {
            AutoService.instance?.stopLoop()
        })

        logView = TextView(this)
        logView.typeface = Typeface.MONOSPACE
        logView.textSize = 12f
        root.addView(logView)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        etPkg.setText(prefs.getString("pkg", ""))
        status.text = if (AutoService.instance != null) {
            "无障碍服务：已开启"
        } else {
            "无障碍服务：未开启（先点下面第 1 步）"
        }
        logView.text = Bus.dump()
        Bus.listener = { line -> logView.append("\n" + line) }
    }

    override fun onPause() {
        super.onPause()
        save()
        Bus.listener = null
    }

    private fun save() {
        prefs.edit()
            .putInt("interval", readInterval())
            .putInt("delay", readDelay())
            .putString("pkg", etPkg.text.toString().trim())
            .apply()
    }

    private fun readInterval(): Int = etInterval.text.toString().toIntOrNull()?.coerceIn(1, 720) ?: 20

    private fun readDelay(): Int = etDelay.text.toString().toIntOrNull()?.coerceIn(0, 120) ?: 10

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        return t
    }

    private fun numberField(value: String): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setSingleLine(true)
        e.setText(value)
        return e
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setOnClickListener { onClick() }
        return b
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
