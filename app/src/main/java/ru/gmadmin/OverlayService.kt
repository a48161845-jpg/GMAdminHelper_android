package ru.gmadmin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.abs

class OverlayService : Service() {
    private class Card(val title: String, val color: Int, val btns: List<Pair<String, () -> Unit>>, val ttl: Long)

    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var web: WebView? = null
    private var popup: LinearLayout? = null
    private var calibView: FrameLayout? = null
    private val cardQueue = ArrayList<Card>()
    private var panelRight = true

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("gm", "GM Admin Helper", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "gm").setContentTitle("GM Admin Helper работает")
            .setSmallIcon(android.R.drawable.ic_menu_info_details).build())
        val sp = getSharedPreferences("gm", MODE_PRIVATE)
        Store.auto = sp.getBoolean("auto", Store.auto)
        addBubble()
        Store.onReport = { line -> h.post { onReport(line) } }
        Store.onAdminCmd = { who, cmd -> h.post { onAdminCmd(who, cmd) } }
        Store.onLog = { e ->
            h.post {
                val p = e.split("\t", limit = 3)
                if (p.size == 3) js("window.gmLog&&gmLog(" + JSONObject.quote(p[0]) + "," + JSONObject.quote(p[1]) + "," + JSONObject.quote(p[2]) + ")")
            }
        }
        Store.focus = { on -> h.post { setFocusable(on) } }
        Store.calibrate = { h.post { startCalib() } }
        updateBadge()
    }

    private fun js(code: String) { web?.evaluateJavascript(code, null) }

    private fun sendText(t: String, send: Boolean) { ChatInput.put(this, t, send) }

    private fun journal(kind: String, text: String) {
        if (web != null) js("window.gmJ&&gmJ(" + JSONObject.quote(kind) + "," + JSONObject.quote(text) + ")")
        else Store.pendJ.add(kind + "\t" + text)
    }

    // ===== карточки поверх игры =====

    private fun onReport(line: String) {
        vibrate(); updateBadge()
        val m = Regex("^([^\\[]+)\\[(\\d+)\\]:\\s*(.*?)\\s*\\[").find(line)
        val rep = m?.groupValues?.get(2) ?: ""
        val body = m?.groupValues?.get(3) ?: line
        val other = Regex("(?<![\\d.,])(\\d{1,3})(?![\\d]|[.,]\\d)").findAll(body).map { it.groupValues[1] }.firstOrNull { it != rep }
        val btns = ArrayList<Pair<String, () -> Unit>>()
        btns.add("Ожидайте" to {
            sendText("/ans $rep Приветствую, ожидайте.", false)
            journal("rep", "Ожидайте → " + (m?.groupValues?.get(1) ?: "") + "[$rep]")
            removePopup()
        })
        btns.add("/sp " + (other ?: rep) to { sendText("/sp " + (other ?: rep), false); removePopup() })
        if (other != null) btns.add("→ ID $other" to { removePopup(); openPanel(); js("window.gmGoto&&gmGoto('$other')") })
        btns.add("Панель" to { removePopup(); openPanel() })
        showCard("📨 " + line.take(90), 0xF0C62828.toInt(), btns, 20000)
        js("window.gmReport&&gmReport(" + JSONObject.quote(line) + ")")
    }

    /** Форма из админ-чата (/jail, /sp, /offjail …): можно принять (вписать и отправить) или отказать. */
    private fun onAdminCmd(who: String, cmd: String) {
        vibrate(); updateBadge()
        val key = "$who\t$cmd"
        val btns = ArrayList<Pair<String, () -> Unit>>()
        btns.add("✓ Принять" to {
            sendText(cmd, true)
            formDone(key, who, cmd, true)
            removePopup()
        })
        btns.add("✕ Отказать" to {
            sendText(Store.rejTpl.replace("{sender}", who), true)
            formDone(key, who, cmd, false)
            removePopup()
        })
        btns.add("Вписать" to { sendText(cmd, false); removePopup() })
        btns.add("Панель" to { removePopup(); openPanel() })
        showCard("📋 $who: $cmd", 0xF01B5E20.toInt(), btns, 30000)
        js("window.gmAdminCmd&&gmAdminCmd(" + JSONObject.quote(who) + "," + JSONObject.quote(cmd) + ")")
    }

    private fun formDone(key: String, who: String, cmd: String, ok: Boolean) {
        Store.forms.remove(key); updateBadge()
        js("window.gmFormGone&&gmFormGone(" + JSONObject.quote(who) + "," + JSONObject.quote(cmd) + ")")
        journal(if (ok) "pun" else "ot", (if (ok) "Принял форму " else "Отказал форму ") + who + ": " + cmd)
    }

    private fun vibrate() {
        try { getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (e: Exception) {}
    }

    private fun openPanel() { if (panel == null) togglePanel() }

    private fun showCard(title: String, color: Int, btns: List<Pair<String, () -> Unit>>, ttl: Long) {
        val c = Card(title, color, btns, ttl)
        if (popup != null) { if (cardQueue.size < 6) cardQueue.add(c); return }
        showNow(c)
    }

    private fun showNow(c: Card) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(c.color) }
        }
        val more = if (cardQueue.isNotEmpty()) "  (+" + cardQueue.size + ")" else ""
        box.addView(TextView(this).apply { text = c.title + more; setTextColor(Color.WHITE); textSize = 14f; maxWidth = dp(460) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        for ((t, f) in c.btns + ("✕" to { removePopup() })) {
            row.addView(TextView(this).apply {
                text = t; setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(11), dp(9), dp(11), dp(9))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x33FFFFFF) }
                setOnClickListener { f() }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        box.addView(row)
        // окно не берёт фокус — чат игры остаётся открытым
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.y = dp(24)
        try { wm.addView(box, lp) } catch (e: Exception) { return }
        popup = box
        h.postDelayed({ if (popup === box) removePopup() }, c.ttl)
    }

    private fun removePopup() {
        popup?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        popup = null
        if (cardQueue.isNotEmpty()) showNow(cardQueue.removeAt(0))
    }

    private fun updateBadge() {
        val n = Store.queue.size + Store.forms.size
        bubble?.apply {
            text = if (n > 0) n.toString() else "🛡"
            (background as? GradientDrawable)?.setColor(if (n > 0) 0xE0E53935.toInt() else 0xCC6C3DF4.toInt())
        }
    }

    // ===== плавающая кнопка =====

    private fun addBubble() {
        val b = TextView(this).apply {
            text = "🛡"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC6C3DF4.toInt()); setStroke(dp(2), 0x66FFFFFF) }
        }
        val lp = WindowManager.LayoutParams(
            dp(50), dp(50), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = 0; lp.y = dp(120)
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = sx + dx; lp.y = sy + dy; wm.updateViewLayout(b, lp); true
                }
                MotionEvent.ACTION_UP -> { if (!moved) togglePanel(); true }
                else -> false
            }
        }
        wm.addView(b, lp); bubble = b; bubbleLp = lp
    }

    // ===== панель =====

    private fun panelSize(): IntArray {
        val r = Store.real(this)
        return if (r[0] > r[1]) intArrayOf(minOf((r[0] * 0.5).toInt(), dp(470)), (r[1] * 0.9).toInt())
        else intArrayOf(r[0] - dp(8), (r[1] * 0.58).toInt())
    }

    private fun panelGravity(): Int {
        val land = Store.isLand(this)
        return if (land) Gravity.TOP or (if (panelRight) Gravity.END else Gravity.START)
        else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    private fun togglePanel() {
        if (panel != null) { closePanel(); return }
        val sz = panelSize()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF0E0E13.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF34344A.toInt()) }
            clipToOutline = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1B1B26.toInt()); setPadding(dp(12), 0, dp(4), 0)
        }
        bar.addView(TextView(this).apply { text = "🛡  GM Admin"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD) },
            LinearLayout.LayoutParams(0, -2, 1f))
        fun barBtn(t: String, f: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(0xFFCFCFE0.toInt()); textSize = 16f; gravity = Gravity.CENTER
            minWidth = dp(46); setPadding(dp(6), dp(9), dp(6), dp(9)); setOnClickListener { f() }
        }
        bar.addView(barBtn("⇄") { swapSide() })
        bar.addView(barBtn("✕") { closePanel() })
        val w = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            setBackgroundColor(0xFF0E0E13.toInt())
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(Bridge(this@OverlayService, "panel"), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, u: String) { v.evaluateJavascript("window.gmReload&&gmReload()", null) }
            }
            loadUrl("file:///android_asset/index.html")
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(40)))
        root.addView(w, LinearLayout.LayoutParams(-1, 0, 1f))
        // окно не берёт фокус: чат игры остаётся открытым, а кнопки пишут через клавиатуру GM
        val lp = WindowManager.LayoutParams(
            sz[0], sz[1], WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = panelGravity()
        lp.y = if (Store.isLand(this)) dp(6) else dp(4)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        try { wm.addView(root, lp) } catch (e: Exception) { w.destroy(); return }
        panel = root; panelLp = lp; web = w
    }

    private fun swapSide() {
        panelRight = !panelRight
        relayout()
    }

    /** Подгоняет панель и кнопку под текущий поворот экрана. */
    private fun relayout() {
        val p = panel; val lp = panelLp
        if (p != null && lp != null) {
            val sz = panelSize()
            lp.width = sz[0]; lp.height = sz[1]; lp.gravity = panelGravity()
            lp.y = if (Store.isLand(this)) dp(6) else dp(4)
            try { wm.updateViewLayout(p, lp) } catch (e: Exception) {}
        }
        val b = bubble; val bl = bubbleLp
        if (b != null && bl != null) {
            val r = Store.real(this)
            bl.x = bl.x.coerceIn(0, maxOf(0, r[0] - dp(50)))
            bl.y = bl.y.coerceIn(0, maxOf(0, r[1] - dp(50)))
            try { wm.updateViewLayout(b, bl) } catch (e: Exception) {}
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        h.postDelayed({ relayout() }, 200)
        h.postDelayed({ relayout() }, 800)
    }

    private fun setFocusable(on: Boolean) {
        val p = panel ?: return; val lp = panelLp ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try { wm.updateViewLayout(p, lp) } catch (e: Exception) {}
    }

    private fun closePanel() {
        panel?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        web?.destroy(); panel = null; web = null
    }

    // ===== настройка автооткрытия чата =====

    /** Два тапа: кнопка чата и поле ввода. Каждый тап запоминается и передаётся в игру, чтобы чат реально открылся. */
    private fun startCalib() {
        if (TapService.inst == null) {
            Toast.makeText(this, "Сначала включи службу доступности «GM Admin — тапы»", Toast.LENGTH_LONG).show()
            return
        }
        closePanel(); removePopup(); removeCalib()
        Toast.makeText(this, "Закрой чат в игре и следуй подсказкам", Toast.LENGTH_SHORT).show()
        h.postDelayed({ calibStep(0, FloatArray(4)) }, 900)
    }

    private fun removeCalib() { calibView?.let { try { wm.removeView(it) } catch (e: Exception) {} }; calibView = null }

    private fun calibStep(step: Int, acc: FloatArray) {
        val box = FrameLayout(this).apply { setBackgroundColor(0x66000000) }
        box.addView(TextView(this).apply {
            text = if (step == 0) "Шаг 1/2\nНажми на КНОПКУ ЧАТА в игре" else "Шаг 2/2\nНажми на ПОЛЕ ВВОДА чата"
            setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply { setColor(0xEE6C3DF4.toInt()); cornerRadius = dp(16).toFloat() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        box.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val r = Store.real(this)
                val x = e.rawX; val y = e.rawY
                acc[step * 2] = x / r[0]; acc[step * 2 + 1] = y / r[1]
                removeCalib()
                h.postDelayed({ TapService.inst?.tap(x, y) }, 120)      // передаём тап в игру
                if (step == 0) {
                    h.postDelayed({ calibStep(1, acc) }, 1100)
                } else {
                    val key = Store.tapKey(this)
                    getSharedPreferences("gm", MODE_PRIVATE).edit().putString(key, acc.joinToString(",")).apply()
                    Toast.makeText(this, "Готово! Чат будет открываться сам", Toast.LENGTH_LONG).show()
                }
            }
            true
        }
        try { wm.addView(box, lp); calibView = box } catch (e: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "calib") startCalib()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Store.onReport = null; Store.onAdminCmd = null; Store.onLog = null; Store.focus = null; Store.calibrate = null
        cardQueue.clear(); removeCalib()
        h.removeCallbacksAndMessages(null)
        removePopup(); closePanel(); bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        super.onDestroy()
    }
}
