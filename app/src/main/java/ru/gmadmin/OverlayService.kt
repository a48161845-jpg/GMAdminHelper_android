package ru.gmadmin

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Значок  поверх игры. Нажатие — «веер» из трёх круглых кнопок (скриншот, запись, ловец), любое касание экрана их убирает.
 * Ещё здесь: карточки репортов и запросов из админ-чата и панель слежки с быстрыми командами.
 * Вся основная панель — в клавиатуре; здесь только то, что нужно, пока чат игры закрыт.
 */
class OverlayService : Service() {
    companion object {
        @Volatile var running = false
        private const val BG = 0xF20F131B.toInt()
        private const val LINE = 0x2EFFFFFF
        private const val DANGER = 0xFFF0616D.toInt()
        private const val OKC = 0xFF34D399.toInt()
        private const val CHIP = 0x24FFFFFF
        private const val WARN = 0xFF8A6A2C.toInt()
        /** Если в приложении ещё не отмечены команды для слежки — показываем эти. */
        private val DEF_SPEC = listOf(
            Pair("Выйти из слежки", "/spoff"), Pair("Статистика", "/stats {id}"), Pair("Оружие", "/weap {id}"), Pair("Инфо", "/getinfo {t}"),
            Pair("Починить авто", "/fixcar {id}"), Pair("Перевернуть", "/flip {id}"), Pair("ХП 100", "/hp {id} 100"),
            Pair("Заморозить", "/freeze {id}"), Pair("Разморозить", "/unfreeze {id}"), Pair("Спавн", "/spawn {id}")
        )
    }

    private val ACCENT: Int get() = Store.accent
    private class Bt(val l: String, val s: List<String>, val c: String)
    private val repTarget = HashMap<String, String>()
    private var bkTick = 0

    private class FanBtn(val icon: String, val label: String, val color: Int, val act: () -> Unit)

    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var popup: LinearLayout? = null
    private var fan: FrameLayout? = null
    private var spec: LinearLayout? = null
    private var cropRoot: FrameLayout? = null
    private var sheet: LinearLayout? = null      // окно «Ответ»: все быстрые ответы в 2 колонки
    private var bubbleSig = ""
    private var lastRepId = ""
    private var typing = false          // идёт набор в поле быстрого ответа — окно не перерисовываем
    private var draft = ""               // недописанный ответ переживает перерисовку
    private var lastAct = 0L
    private val idRe = Regex("(?<![\\d.,])(\\d{1,3})(?![\\d]|[.,]\\d)")
    private val repRe = Regex("^([^\\[]+)\\[(\\d+)\\]:\\s*(.*?)\\s*(?:\\[[^\\]]*\\])?$")

    private val beat = object : Runnable { override fun run() { Playtime.beat(this@OverlayService); h.postDelayed(this, 30000) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        // «Указать на экране» из настроек приложения: приложение уходит назад, поверх игры открывается рамка области чтения
        if (i?.getBooleanExtra("pickCrop", false) == true) h.postDelayed({ showCropEditor() }, 500)
        if (i?.getBooleanExtra("pickLayout", false) == true) h.postDelayed({ showLayoutEditor() }, 500)
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        Cfg.apply(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("gm", "GM Admin Helper", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "gm").setContentTitle("GM Admin Helper работает")
            .setSmallIcon(android.R.drawable.ic_menu_info_details).build())
        addBubble()
        LogWatcher.start(this)
        Playtime.begin(this)
        h.postDelayed(beat, 30000)
        Store.onReport = { line -> h.post { arrive("r|$line", "Репорт", line.take(80)) } }
        Store.onAdminCmd = { who, cmd -> h.post { arrive("f|$who\t$cmd", "Форма от $who", cmd.take(80)) } }
        Store.onOutbox = { _ -> h.post { refreshBubble() } }
        Store.onQueue = { h.post { refreshBubble() } }
        Store.onRefresh = { h.post { if (popup != null && !typing) showInbox() } }
        Store.onSpectate = { t -> h.post { if (t == null) { Store.specRep = ""; stopAutoRec(); removeSpec() } else showSpectate(t) } }
        Store.hideOverlay = { hide ->
            h.post {
                bubble?.visibility = if (hide) View.INVISIBLE else View.VISIBLE
                spec?.visibility = if (hide) View.INVISIBLE else View.VISIBLE
                if (hide) { removePopup(); removeSheet(); removeFan(); removeCrop(false) }
            }
        }
        h.post(ticker)
    }

    private val ticker = object : Runnable { override fun run() { refreshBubble(); if (++bkTick % 60 == 0) { try { Backup.tick(this@OverlayService) } catch (e: Throwable) {} }; h.postDelayed(this, 1000) } }

    // ---------- вспомогательные «рисовалки» ----------
    private fun bg(color: Int, r: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(color); if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun txt(t: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pill(t: String, fill: Int, f: () -> Unit): TextView = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(12), dp(9), dp(12), dp(9)); background = bg(fill, 12)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { f() }
    }

    /** Прокручиваемая область с потолком по высоте: окна не превращаются в «полный экран», особенно в ландшафте игры. */
    private class MaxHScroll(c: android.content.Context, private val maxH: Int) : android.widget.ScrollView(c) {
        override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)) }
        init { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
    }
    private fun landscape(): Boolean { val dm = resources.displayMetrics; return dm.widthPixels > dm.heightPixels }
    /** Ширина окон: в портрете до 420 dp, в ландшафте — до 60% экрана (кнопок в ряду больше, строк меньше). */
    private fun winW(): Int { val dm = resources.displayMetrics; return if (landscape()) min((dm.widthPixels * 0.5f).toInt(), dp(Store.winDp + 60)) else min(dm.widthPixels - dp(24), dp(Store.winDp)) }
    private fun gridCols(): Int = if (Store.gridPref > 0) Store.gridPref else if (landscape()) 4 else 3
    private fun maxH(): Int = (resources.displayMetrics.heightPixels * (if (landscape()) 0.66f else 0.6f)).toInt()

    // ---------- автозапись при слежке ----------
    private var autoRecOn = false
    private var autoRecAt = 0L
    private fun startAutoRec() {
        val cs = CaptureService.inst
        if (cs == null || !cs.ready || cs.recording) return       // захват не запущен — молча пропускаем
        cs.startRec()
        if (cs.recording) {
            autoRecOn = true; val stamp = System.currentTimeMillis(); autoRecAt = stamp
            h.postDelayed({ if (autoRecAt == stamp) stopAutoRec() }, Store.autoRecMin * 60_000L)   // страховка: не писать вечно
        }
    }
    private fun stopAutoRec() {
        if (!autoRecOn) return
        autoRecOn = false
        CaptureService.inst?.let { if (it.recording) it.stopRec() }
    }

    /** Полное выключение ловли репортов и записи экрана (и обратно — с запросом разрешения). */
    private fun toggleCatcher() {
        if (CaptureService.inst != null) {
            autoRecOn = false
            try { stopService(Intent(this, CaptureService::class.java)) } catch (e: Exception) { Diag.log(this, "стоп ловца: " + e.message) }
            Toast.makeText(this, "Ловля репортов и запись экрана выключены", Toast.LENGTH_SHORT).show()
        } else capture("ocr")
    }

    /** Маленькая кнопка для всплывающих окон. */
    private fun mini(t: String, fill: Int, f: () -> Unit): TextView = TextView(this).apply {
        text = t; textSize = 12f * Store.uiScale; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(6), dp(7), dp(6), dp(7)); background = bg(fill, 9)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { f() }
    }

    private fun refreshBubble() {
        val b = bubble ?: return
        val cs = CaptureService.inst
        val rs = if (cs != null && cs.recording) cs.recSeconds() else -1
        val pending = Inbox.pending()
        val sig = "$rs|$pending|${Store.out.isNotEmpty()}|$ACCENT"
        if (sig == bubbleSig) return
        bubbleSig = sig
        b.textSize = if (rs >= 0) 10f else 14f; b.typeface = Typeface.DEFAULT_BOLD
        b.text = when {
            rs >= 0 -> "REC\n%d:%02d".format(rs / 60, rs % 60)
            Store.out.isNotEmpty() -> "..."
            pending > 0 -> pending.toString()
            else -> "GM"
        }
        val fill = when { rs >= 0 -> 0xF0B03A46.toInt(); pending > 0 -> 0xF0C2475A.toInt(); else -> 0xE6141A28.toInt() }
        b.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill); setStroke(dp(2), if (rs >= 0 || pending > 0) 0xFFFFFFFF.toInt() else ACCENT) }
    }

    /** Отправка цепочки в чат игры; если поле закрыто — копия первого в буфер и подсказка. */
    private fun send(items: List<String>, force: Boolean = false) {
        if (items.isEmpty()) return
        val ok = Store.enqueue(items, force || Store.autoSend)
        if (!ok) {
            try { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("cmd", items[0])) } catch (e: Exception) {}
            Toast.makeText(this, "Открой поле чата — вставлю", Toast.LENGTH_SHORT).show()
        }
    }

    private fun vibrate() {
        if (Store.vibMs <= 0) return
        try { getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(Store.vibMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)) } catch (e: Exception) {}
    }

    // ---------- входящие: репорты, формы, нечитаемые строки ----------
    private var curKey = ""
    private var alertN = 0

    /** Ключ вида «r|строка репорта» или «f|ник<TAB>команда». */
    private fun items(): List<String> {
        val out = ArrayList<String>()
        for (l in Store.queue) if (l !in Store.answered) out.add("r|$l")
        for (q in Store.reqs) out.add("f|$q")
        return out
    }

    private fun arrive(key: String, title: String, text: String) {
        vibrate(); refreshBubble()
        if (sheet != null) return            // пока открыто окно «Ответ», его не перекрываем
        if (popup == null) curKey = key      // уже открытое окно не подменяем: просто обновится счётчик
        if (typing && popup != null) return  // пока пишешь ответ, окно не трогаем; новый репорт виден на значке
        showInbox()
    }

    /** Всплывающее уведомление поверх игры: даже если окно успели закрыть, о репорте или форме знаешь. */
    private fun headsUp(title: String, text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("gm_alert", "Репорты и формы", NotificationManager.IMPORTANCE_HIGH))
            val n = Notification.Builder(this, "gm_alert").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text).setAutoCancel(true).setTimeoutAfter(8000).build()
            nm.notify(100 + (alertN++ % 6), n)
        } catch (e: Throwable) {}
    }

    private val vioNick = HashMap<String, String>()
    private val nickOf = HashMap<String, String>()          // ID автора репорта → ник (для окна слежки)

    private fun savedXY(key: String): IntArray? {
        val p = Cfg.get(this, key).split(",").mapNotNull { it.trim().toIntOrNull() }
        return if (p.size == 2) intArrayOf(p[0], p[1]) else null
    }

    /** Место окна, выбранное руками (редактор расположения или перетаскивание): иначе — внизу по центру. */
    private fun placeSaved(lp: WindowManager.LayoutParams, key: String, defY: Int) {
        val s = savedXY(key)
        if (s == null) { lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.x = 0; lp.y = defY; return }
        val dm = resources.displayMetrics
        // координаты — от левого верхнего угла всего экрана
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = s[0].coerceIn(0, max(0, dm.widthPixels - lp.width)); lp.y = s[1].coerceIn(0, max(0, dm.heightPixels - dp(90)))
    }

    /** Окно двигается за «ручку» (шапку): после первого сдвига переходит на абсолютные координаты и запоминает место. */
    private fun makeDraggable(handle: View, win: View, lp: WindowManager.LayoutParams, save: (IntArray) -> Unit) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    val loc = IntArray(2); win.getLocationOnScreen(loc)
                    sx = loc[0]; sy = loc[1]; tx = e.rawX; ty = e.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (!moved && abs(dx) + abs(dy) < dp(6)) return@setOnTouchListener true
                    if (!moved) { moved = true; lp.gravity = Gravity.TOP or Gravity.START; lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS }
                    val dm = resources.displayMetrics
                    lp.x = (sx + dx).coerceIn(0, max(0, dm.widthPixels - win.width))
                    lp.y = (sy + dy).coerceIn(0, max(0, dm.heightPixels - win.height))
                    try { wm.updateViewLayout(win, lp) } catch (x: Exception) {}
                    lastAct = System.currentTimeMillis(); true
                }
                MotionEvent.ACTION_UP -> { if (moved) save(intArrayOf(lp.x, lp.y)); true }
                else -> true
            }
        }
    }

    private fun copyText(label: String, v: String) {
        try { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, v)) } catch (e: Exception) {}
        Toast.makeText(this, "Скопировано: $v", Toast.LENGTH_SHORT).show()
    }

    /** Ряд «Копировать»: каждое значение (ник, ID автора, ID нарушителя…) копируется касанием. */
    private fun addCopyRow(parent: LinearLayout, items: List<Pair<String, String>>) {
        val vals = items.filter { it.second.isNotEmpty() }
        if (vals.isEmpty()) return
        val sc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, 0) }
        row.addView(txt("Копия:", 11f, 0x99FFFFFF.toInt()).apply { setPadding(0, 0, dp(6), 0) })
        for ((l, v) in vals) row.addView(mini("$l $v", CHIP) { copyText(l, v) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        sc.addView(row)
        parent.addView(sc, LinearLayout.LayoutParams(-1, -2))
    }

    private fun repKey(rep: String, line: String): String {
        val n = Regex(":\\s*(\\d{1,3})\\s*\\]\\s*$").find(line)?.groupValues?.get(1) ?: ""
        return "rep|" + rep + "|" + n
    }

    /** Принятая форма наказания: в конце подпись того, кто её подал: «| A.Danielle». */
    private fun signForm(cmd: String, who: String): String =
        if (who.isNotEmpty() && Levels.punRe.containsMatchIn(cmd) && !cmd.contains(" | ")) cmd.trimEnd() + " | " + Cfg.sigOf(who) else cmd

    /** Если на команду не хватает прав — вместо команды уходит запрос в админ-чат (шаблон из настроек, по умолчанию «/a {cmd}»). */
    private fun gate(cmd: String): String {
        val n = cmd.removePrefix("/").trim().split(" ")[0].lowercase()
        val need = Store.lv[n] ?: Levels.map[n] ?: 1
        return if (Store.lvl < need) Store.reqTpl.replace("{cmd}", cmd) else cmd
    }
    private fun gated(cmd: String) = gate(cmd) != cmd

    /** Быстрые ответы на репорт: как в приложении; «Слежу» всегда первым. */
    private fun answerList(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val sp = Store.replies["sp"] ?: ""
        if (sp.isNotEmpty()) out.add(Pair("Слежу", sp))
        try {
            val s = Cfg.get(this, "answers")
            if (s.isNotEmpty()) {
                val a = JSONArray(s)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val l = o.optString("l"); val t = o.optString("t")
                    if (l.isNotEmpty() && t.isNotEmpty() && out.none { it.second == t }) out.add(Pair(l, t))
                }
                return out.take(20)
            }
        } catch (e: Exception) { Diag.log(this, "ответы: " + e.message) }
        return out
    }

    private fun dropItem(key: String) {
        when (key.substring(0, 1)) {
            "r" -> { Store.answered.add(key.substring(2)) }
            "f" -> { val q = key.substring(2); Store.reqs.remove(q) }
        }
        Inbox.save(); Store.onQueue?.invoke(); refreshBubble()
    }

    /** Окно с очередью: одно компактное окно на все новые репорты и формы. После ответа окно пропадает. */
    private fun showInbox() {
        val list = items()
        if (list.isEmpty()) { removePopup(); return }
        var idx = list.indexOf(curKey)
        if (idx < 0) idx = 0
        curKey = list[idx]
        removePopup(); removeSheet()
        val key = curKey
        val kind = key.substring(0, 1)
        val payload = key.substring(2)
        val accent = if (kind == "f") OKC else ACCENT
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(9)); background = bg(BG, 14, LINE)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(this).apply { background = bg(accent, 3) }, LinearLayout.LayoutParams(dp(3), dp(14)).apply { rightMargin = dp(7) })
        val titleView = txt("", 14f, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        head.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        if (list.size > 1) {
            head.addView(txt("‹", 19f, 0xCCFFFFFF.toInt()).apply { setPadding(dp(10), 0, dp(5), 0); setOnClickListener { typing = false; draft = ""; curKey = list[(idx - 1 + list.size) % list.size]; showInbox() } })
            head.addView(txt((idx + 1).toString() + "/" + list.size, 11f, 0xAAFFFFFF.toInt()))
            head.addView(txt("›", 19f, 0xCCFFFFFF.toInt()).apply { setPadding(dp(5), 0, dp(10), 0); setOnClickListener { typing = false; draft = ""; curKey = list[(idx + 1) % list.size]; showInbox() } })
        }
        head.addView(txt("✕", 15f, 0xAAFFFFFF.toInt()).apply {
            setPadding(dp(8), dp(2), dp(2), dp(2))
            setOnClickListener {      // закрыть этот репорт/форму без ответа — дальше следующий
                typing = false; draft = ""; dropItem(key); curKey = ""; removePopup()
                h.post { if (Store.nextAuto && spec == null && sheet == null && popup == null && items().isNotEmpty()) showInbox() }
            }
        })
        box.addView(head)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (kind == "f") buildForm(content, titleView, payload, key) else buildReport(content, titleView, payload, key)
        box.addView(MaxHScroll(this, maxH()).apply { addView(content) }, LinearLayout.LayoutParams(-1, -2))
        val lp = WindowManager.LayoutParams(winW(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.y = dp(20)
        placeSaved(lp, "wpop", dp(20))
        makeDraggable(head, box, lp) { Cfg.set(this, "wpop", it[0].toString() + "," + it[1]) }
        box.alpha = Store.winAlpha / 100f
        try { wm.addView(box, lp); popup = box; Store.windowOpen = true } catch (e: Throwable) { Diag.log(this, "окно входящих: " + e.message) }
        lastAct = System.currentTimeMillis()
        // прячем только после 3 минут без касаний и не во время набора
        h.postDelayed(object : Runnable { override fun run() {
            if (popup !== box) return
            if (Store.hideMin <= 0 || typing || System.currentTimeMillis() - lastAct < Store.hideMin * 60_000L) h.postDelayed(this, 15000) else removePopup()
        } }, 15000)
    }

    /** Окно «Ответ»: все быстрые ответы в 2 колонки, строк сколько нужно (прокрутка вниз), внизу — свой ответ. */
    private fun showAnswerSheet(rep: String, title: String, onBack: () -> Unit, onSent: () -> Unit) {
        removeSheet()
        val dm = resources.displayMetrics
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(9)); background = bg(BG, 14, LINE)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(txt("‹", 20f, 0xCCFFFFFF.toInt()).apply { setPadding(0, 0, dp(10), 0); setOnClickListener { removeSheet(); onBack() } })
        head.addView(txt("Ответ · $title", 14f, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(txt("✕", 15f, 0xAAFFFFFF.toInt()).apply { setPadding(dp(8), dp(2), dp(2), dp(2)); setOnClickListener { removeSheet() } })
        box.addView(head)
        var tgt = rep
        if (rep.isEmpty()) {          // автор неизвестен (слежка без репорта) — вписываем ID игрока сами
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val idf = EditText(this).apply {
                hint = "ID игрока"; setHintTextColor(0x66FFFFFF); setTextColor(Color.WHITE); textSize = 13f
                background = bg(CHIP, 9); setPadding(dp(10), dp(7), dp(10), dp(7)); maxLines = 1
                inputType = InputType.TYPE_CLASS_NUMBER; imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
            idf.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { tgt = s?.toString()?.trim() ?: "" }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            idf.setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN && !typing) {
                    typing = true; lastAct = System.currentTimeMillis()
                    setFocusable(box, true); idf.requestFocus()
                    idf.postDelayed({ try { imm.showSoftInput(idf, InputMethodManager.SHOW_IMPLICIT) } catch (e: Exception) {} }, 120)
                }
                false
            }
            idf.setOnEditorActionListener { _, _, _ ->
                typing = false; try { imm.hideSoftInputFromWindow(idf.windowToken, 0) } catch (e: Exception) {}
                idf.clearFocus(); setFocusable(box, false); true
            }
            box.addView(idf, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }

        val list = answerList()
        val acols = Store.ansCols.coerceIn(1, 3)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(2)) }
        if (list.isEmpty()) col.addView(txt("Ответов нет — добавь в приложении: Команды → Ответы.", 12f, 0x99FFFFFF.toInt()).apply { setPadding(0, dp(8), 0, dp(4)) })
        var i = 0
        while (i < list.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until acols) {
                val a = list.getOrNull(i + c)
                val lpc = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(5); if (c > 0) leftMargin = dp(5) }
                if (a == null) { row.addView(View(this), lpc); continue }
                val cell = TextView(this).apply {
                    text = a.first; textSize = 12.5f * Store.uiScale; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(9), dp(8), dp(9), dp(8)); minHeight = dp(36); background = bg(CHIP, 9)
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    setOnClickListener {
                        if (tgt.isEmpty()) { Toast.makeText(this@OverlayService, "Впиши ID игрока", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                        send(listOf("/ans $tgt ${a.second}"))
                        removeSheet(); onSent()
                    }
                }
                row.addView(cell, lpc)
            }
            col.addView(row); i += acols
        }
        val sv = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        sv.addView(col)
        val rows = (list.size + acols - 1) / acols
        val hPx = min(rows * dp(41) + dp(8), (dm.heightPixels * 0.42f).toInt()).coerceAtLeast(dp(44))
        box.addView(sv, LinearLayout.LayoutParams(-1, hPx))
        addReplyField(box, { tgt }, { sheet }) { removeSheet(); onSent() }

        val lp = WindowManager.LayoutParams(winW(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.y = dp(20)
        placeSaved(lp, "wpop", dp(20))
        makeDraggable(head, box, lp) { Cfg.set(this, "wpop", it[0].toString() + "," + it[1]) }
        box.alpha = Store.winAlpha / 100f
        try { wm.addView(box, lp); sheet = box; Store.windowOpen = true } catch (e: Throwable) { Diag.log(this, "окно ответов: " + e.message) }
        lastAct = System.currentTimeMillis()
    }

    private fun removeSheet() {
        typing = false
        sheet?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        sheet = null
        if (popup == null) Store.windowOpen = false
    }

    // ---------- настраиваемые кнопки окон (приложение → Команды → Кнопки окон) ----------
    private fun defBtns(kind: String): List<Bt> = if (kind == "rep") listOf(
        Bt("Слежка", listOf("@ans:sp", "/sp {id}"), "ac"), Bt("→ ТП", listOf("@ans:tp", "/goto {id}"), ""),
        Bt("Спавн", listOf("@ans:spawn", "/spawn {id}"), ""), Bt("На помощь", listOf("@help", "@ans " + Store.helpAns), "wr")
    ) else listOf(
        Bt("Выйти", listOf("/spoff"), "er"), Bt("Стата", listOf("/stats {id}"), ""), Bt("Оружие", listOf("/weap {id}"), ""),
        Bt("Починить+перев.", listOf("/fixcar {id}", "/flip {id}"), ""), Bt("ХП 100", listOf("/hp {id} 100"), ""),
        Bt("Заморозить", listOf("/freeze {id}"), ""), Bt("На помощь", listOf("@help"), "wr")
    )

    private fun btnList(kind: String): List<Bt> {
        try {
            val s = Cfg.get(this, "btns")
            if (s.isNotEmpty()) {
                val a = JSONObject(s).optJSONArray(kind)
                if (a != null) {
                    val out = ArrayList<Bt>()
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        val st = o.optJSONArray("s"); val steps = ArrayList<String>()
                        if (st != null) for (j in 0 until st.length()) steps.add(st.optString(j))
                        if (o.optString("l").isNotEmpty()) out.add(Bt(o.optString("l"), steps, o.optString("c")))
                    }
                    return out
                }
            }
        } catch (e: Exception) { Diag.log(this, "кнопки окон: " + e.message) }
        return emptyList()      // чистая установка: кнопок нет, пока не добавлены в приложении
    }

    /** Свои кнопки + встроенные, которые есть всегда: «На помощь» (репорт и слежка) и «Выйти» (слежка). */
    private fun withBuiltIn(kind: String): List<Bt> {
        val l = ArrayList(btnList(kind))
        fun has(p: (String) -> Boolean) = l.any { b -> b.s.any { p(it.trim()) } }
        if (kind == "spec" && !has { it.startsWith("/spoff") || it == "@spoff" }) l.add(0, Bt("Выйти", listOf("@spoff"), "er"))
        if (!has { it == "@help" }) l.add(Bt("На помощь", if (kind == "rep") listOf("@help", "@ans " + Store.helpAns) else listOf("@help"), "wr"))
        return l
    }

    private fun colorOf(b: Bt, firstCmd: String): Int = when (b.c) {
        "ac" -> ACCENT; "er" -> DANGER; "ok" -> OKC; "wr" -> 0xFFC27A2C.toInt()
        else -> if (firstCmd.isNotEmpty() && gated(firstCmd)) WARN else CHIP
    }

    private fun stepCmd(st: String, tgt: String, rep: String) = st.replace("{id}", tgt).replace("{t}", tgt).replace("{rep}", rep).replace("{nickname}", tgt)

    /** Выполнить кнопку: цепочка шагов (команды, автоответ автору, «на помощь») уходит в чат игры по порядку. */
    private fun runButton(b: Bt, tgt: String, rep: String, fromReport: Boolean, nick: String = "", text: String = "") {
        val items = ArrayList<String>()
        var force = false; var sp = false; var exit = false
        for (raw in b.s) {
            val st = raw.trim()
            if (st.isEmpty() || st == "@done") continue
            if (st == "@spoff") { items.add("/spoff"); exit = true; continue }
            if (st == "@help") {
                // в админ-чат: ник[ID] того, кто просит помощи, и суть. Если это репорт — просит автор (rep), иначе тот, за кем следим.
                val id = if (fromReport && rep.isNotEmpty()) rep else if (tgt.isNotEmpty()) tgt else rep
                if (id.isEmpty()) { Toast.makeText(this, "Нет ID", Toast.LENGTH_SHORT).show(); continue }
                items.add(helpLine(nick, id, text)); force = true; continue
            }
            if (st.startsWith("@ans")) {
                val rest = st.removePrefix("@ans")
                val key = rest.startsWith(":")
                val text = if (key) (Store.replies[rest.substring(1).trim()] ?: "") else rest.trim()
                if (text.isEmpty() || rep.isEmpty() || (key && !Store.autoReply)) continue
                items.add("/ans $rep $text"); continue
            }
            val cmd = stepCmd(st, tgt, rep)
            if (Regex("\\{[a-z]+\\}").containsMatchIn(cmd)) { Toast.makeText(this, "Нет ID для: $st", Toast.LENGTH_SHORT).show(); continue }
            if (cmd.startsWith("/sp ", true)) sp = true
            if (cmd.startsWith("/spoff", true)) exit = true
            items.add(gate(cmd))
        }
        if (items.isEmpty()) return
        // слежка — самое важное: если цепочка оборвётся (игра закрыла чат), потеряется не она, а ответ игроку
        if (sp && items.none { it.startsWith("/spoff", true) }) {
            val first = items.filter { it.startsWith("/sp ", true) }
            if (first.isNotEmpty()) { val rest = items.filterNot { it.startsWith("/sp ", true) }; items.clear(); items.addAll(first); items.addAll(rest) }
        }
        if (fromReport && sp) Store.nextRep = rep
        send(items, force)
        if (exit) { Store.specRep = ""; stopAutoRec(); removeSpec() }
        // чужой ID в репорте + термин из правил (ДМ, ЗЗ, ТП…) — запись экрана включается сама
        if (fromReport && sp && Store.autoRec) {
            val foreign = idRe.findAll(text).any { it.groupValues[1] != rep }
            if (foreign && (Store.autoRecAny || Cfg.termHit(this, text))) startAutoRec()
        }
    }

    private fun setFocusable(v: View?, on: Boolean) {
        val lp = v?.layoutParams as? WindowManager.LayoutParams ?: return
        val nf = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.flags = if (on) lp.flags and nf.inv() else lp.flags or nf
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        try { wm.updateViewLayout(v, lp) } catch (e: Exception) {}
    }

    /** Поле «свой ответ»: пишешь прямо в окне и жмёшь «Отправить» — уходит /ans. Окно становится фокусируемым только на время набора. */
    private fun addReplyField(parent: LinearLayout, rep: () -> String, root: () -> View?, onSent: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(7), 0, 0) }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val et = EditText(this).apply {
            hint = "Свой ответ…"; setHintTextColor(0x66FFFFFF); setTextColor(Color.WHITE); textSize = 13f
            background = bg(CHIP, 9); setPadding(dp(10), dp(7), dp(10), dp(7))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            maxLines = 3; setText(draft)
        }
        fun finish() { typing = false; try { imm.hideSoftInputFromWindow(et.windowToken, 0) } catch (e: Exception) {}; et.clearFocus(); setFocusable(root(), false) }
        fun go() {
            val t = et.text.toString().trim()
            if (t.isEmpty()) return
            if (rep().isEmpty()) { Toast.makeText(this, "Впиши ID игрока", Toast.LENGTH_SHORT).show(); return }
            send(listOf("/ans ${rep()} $t"))
            draft = ""; et.setText(""); finish(); onSent()
        }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { draft = s?.toString() ?: ""; lastAct = System.currentTimeMillis() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        et.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN && !typing) {
                typing = true; lastAct = System.currentTimeMillis()
                setFocusable(root(), true)
                et.requestFocus()
                et.postDelayed({ try { imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT) } catch (e: Exception) {} }, 120)
            }
            false
        }
        et.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND || a == EditorInfo.IME_ACTION_DONE) { go(); true } else false }
        row.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(mini("Отправить", ACCENT) { go() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(5) })
        row.addView(txt("⌄", 17f, 0xAAFFFFFF.toInt()).apply { setPadding(dp(9), 0, dp(2), 0); setOnClickListener { finish() } })
        parent.addView(row)
    }

    private fun markRepDone(rep: String) {
        if (rep.isEmpty()) return
        var ch = false
        for (l in Store.queue) {
            if (l in Store.answered) continue
            if (repRe.find(l)?.groupValues?.get(2) == rep) { Store.answered.add(l); ch = true }
        }
        if (ch) { Inbox.save(); Store.onQueue?.invoke(); refreshBubble() }
    }

    private fun addGrid(parent: LinearLayout, items: List<Triple<String, Int, () -> Unit>>, cols: Int) {
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, 0) }
            for (c in 0 until cols) {
                val item = items.getOrNull(i + c) ?: break         // пустых ячеек нет: неполная строка делится поровну
                val lpc = LinearLayout.LayoutParams(0, -2, 1f).apply { if (c > 0) leftMargin = dp(5) }
                row.addView(mini(item.first, item.second, item.third), lpc)
            }
            parent.addView(row)
            i += cols
        }
    }

    private fun buildReport(box: LinearLayout, titleView: TextView, line: String, key: String) {
        val m = repRe.find(line)
        val name = m?.groupValues?.get(1)?.trim() ?: "?"
        val rep = m?.groupValues?.get(2) ?: ""
        val body = m?.groupValues?.get(3) ?: line
        // нарушитель — другой ID из текста репорта; отвечаем автору (rep), следим за нарушителем (t)
        val ids = idRe.findAll(body).map { it.groupValues[1] }.filter { it != rep }.distinct().toList()
        val t = repTarget[key] ?: (ids.firstOrNull() ?: rep)
        titleView.text = "$name [$rep] ⧉"
        if (rep.isNotEmpty() && name != "?") nickOf[rep] = name
        if (rep.isNotEmpty()) lastRepId = rep
        box.addView(txt(body.take(160), 12.5f * Store.uiScale, 0xDDFFFFFF.toInt()).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        if (ids.size > 1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, 0) }
            row.addView(txt("Цель:", 11f, 0x99FFFFFF.toInt()).apply { setPadding(0, 0, dp(6), 0) })
            for (id in ids.take(4)) {
                row.addView(mini(id, if (id == t) ACCENT else CHIP) { repTarget[key] = id; showInbox() }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(5) })
            }
            box.addView(row)
        }

        // ники в тексте репорта (скорее всего нарушитель); если он один и ID один — запоминаем для окна слежки
        val cands = Regex("[A-Za-z][A-Za-z0-9_]{2,24}").findAll(body).map { it.value }.filter { !it.equals(name, true) }.distinct().take(2).toList()
        if (cands.size == 1 && ids.size == 1) vioNick[ids[0]] = cands[0]
        // копирование ников/ID спрятано: нажми на имя в шапке, чтобы показать или скрыть
        val copyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        addCopyRow(copyBox, listOf("Ник" to name.takeIf { it != "?" }.orEmpty(), "ID" to rep) +
            (if (t != rep) listOf("ID нарушителя" to t) else emptyList()) + cands.take(1).map { "Ник" to it })
        box.addView(copyBox)
        titleView.setOnClickListener { copyBox.visibility = if (copyBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE }

        fun done(count: Boolean = true) {
            if (line !in Store.answered) {
                Store.answered.add(line); Inbox.save(); Store.onQueue?.invoke(); refreshBubble()
                if (count) Journal.add(this, "rep", "Ответ $name[$rep]", null, repKey(rep, line), 600000L)
            }
            typing = false; draft = ""; curKey = ""
            removePopup(); removeSheet()
            // следующий репорт — после того, как успеет открыться окно слежки (если кнопка его запускала), чтобы окна не налезли друг на друга
            h.post { if (Store.nextAuto && spec == null && sheet == null && popup == null && items().isNotEmpty()) showInbox() }
        }
        // одна сетка кнопок: «Ответить ▸» (открывает все ответы), свои кнопки и встроенные
        val items = ArrayList<Triple<String, Int, () -> Unit>>()
        items.add(Triple("Ответить ▸", ACCENT, {
            removePopup()
            showAnswerSheet(rep, name, { showInbox() }) { done() }
        }))
        for (b in withBuiltIn("rep")) {
            val first = b.s.map { it.trim() }.firstOrNull { it.startsWith("/") }?.let { stepCmd(it, t, rep) } ?: ""
            items.add(Triple(b.l, colorOf(b, first), { runButton(b, t, rep, true, name, body); done() }))
        }
        addGrid(box, items, gridCols())
    }

    private fun buildForm(box: LinearLayout, titleView: TextView, q: String, key: String) {
        val p = q.split("\t")
        val who = p.getOrElse(0) { "?" }
        val cmd = p.getOrElse(1) { q }
        titleView.text = "$who просит"
        val need = Store.lv[cmd.removePrefix("/").trim().split(" ")[0].lowercase()] ?: 1
        val can = Store.lvl >= need
        val signed = signForm(cmd, who)
        box.addView(txt(signed, 13f, 0xDDFFFFFF.toInt()).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        if (!can) box.addView(txt("Не хватает прав: нужен $need ур. Форму можно только закрыть.", 11f, 0xFFE0A23B.toInt()).apply { setPadding(0, dp(3), 0, 0) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, 0) }
        if (can) {
            row.addView(mini("✓ Принять", OKC) {
                Store.enqueue(listOf(signed), Store.autoSend && !cmd.endsWith(" "))
                if (Levels.punRe.containsMatchIn(cmd)) Journal.add(this, "pun", signed.take(90), null, "pun|" + signed.lowercase().replace(Regex("\\s+"), " "), 120000L)
                dropItem(key); curKey = ""; showInbox()
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        row.addView(mini(if (can) "✕ Отказать" else "Закрыть", CHIP) { dropItem(key); curKey = ""; showInbox() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { if (can) leftMargin = dp(5) })
        box.addView(row)
    }

    private fun removePopup() { typing = false; Store.windowOpen = false; popup?.let { try { wm.removeView(it) } catch (e: Exception) {} }; popup = null }

    // ---------- панель слежки ----------
    /** Команды панели слежки: те, что отмечены в приложении («в окне слежки»); если ничего не отмечено — набор по умолчанию. */
    private fun specCmds(): List<Pair<String, String>> {
        try {
            val s = Cfg.get(this, "cmds")
            if (s.isNotEmpty()) {
                val a = JSONArray(s)
                var flagged = false
                for (i in 0 until a.length()) if (a.getJSONObject(i).has("sp")) { flagged = true; break }
                if (flagged) {
                    val out = ArrayList<Pair<String, String>>()
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        if (o.optInt("sp", 0) != 1) continue
                        val t = o.optString("t")
                        if (t.contains("{time}") || t.contains("{other}") || o.optString("pun").isNotEmpty()) continue
                        out.add(Pair(o.optString("l"), t))
                    }
                    return out
                }
            }
        } catch (e: Exception) { Diag.log(this, "команды слежки: " + e.message) }
        return DEF_SPEC
    }

    /** Строка «на помощь» по шаблону: {who} = ник[ID], {nick}, {id}, {text} = суть репорта. */
    private fun helpLine(nick: String, id: String, text: String): String {
        val who = if (nick.isNotEmpty()) "$nick[$id]" else id
        val t = text.replace(Regex("\\s+"), " ").trim().take(90)
        var r = Store.aTpl.replace("{who}", who).replace("{nick}", nick).replace("{text}", t)
            .replace("{id}", id).replace("{t}", id).replace("{nickname}", nick)
        if (t.isEmpty()) r = r.replace(Regex("\\s*[:\\-–]\\s*$"), "")
        return r.replace(Regex("\\s{2,}"), " ").trim()
    }

    private fun fill(tpl: String, target: String) = tpl.replace("{id}", target).replace("{t}", target).replace("{nickname}", target)

    private fun showSpectate(target: String) {
        removeSpec()
        val rep = Store.specRep
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(7), dp(10), dp(9)); background = bg(BG, 14, LINE)
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val svh = arrayOfNulls<View>(1)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val specTitle = txt("Слежка · $target ⧉", 14f, Color.WHITE, true).apply { setPadding(dp(2), dp(2), dp(4), dp(2)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        head.addView(specTitle, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(txt("▾", 15f, 0xAAFFFFFF.toInt()).apply {
            setPadding(dp(12), dp(2), dp(12), dp(2))
            setOnClickListener { svh[0]?.let { v -> v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE; text = if (v.visibility == View.VISIBLE) "▾" else "▸" } }
        })
        head.addView(txt("✕", 15f, 0xAAFFFFFF.toInt()).apply { setPadding(dp(8), dp(2), dp(2), dp(2)); setOnClickListener { removeSpec() } })
        box.addView(head)

        val copyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        addCopyRow(copyBox, listOf("Ник" to (vioNick[target] ?: ""), "ID" to target, "Автор" to (nickOf[rep] ?: "")))
        body.addView(copyBox)
        specTitle.setOnClickListener { copyBox.visibility = if (copyBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        // «Ответить ▸» есть всегда: автор берётся из репорта, а если слежка без репорта — ID вписывается в окне ответа
        val items = ArrayList<Triple<String, Int, () -> Unit>>()
        items.add(Triple("Ответить ▸", ACCENT, {
            val an = if (rep.isEmpty()) "игрок" else (nickOf[rep] ?: rep)
            showAnswerSheet(rep, an, { }) { if (rep.isNotEmpty()) markRepDone(rep) }
        }))
        for (b in withBuiltIn("spec")) {
            val first = b.s.map { it.trim() }.firstOrNull { it.startsWith("/") }?.let { stepCmd(it, target, rep) } ?: ""
            val c = if (b.s.any { it.trim().startsWith("/spoff") }) DANGER else colorOf(b, first)
            items.add(Triple(b.l, c, { runButton(b, target, rep, false) }))
        }
        addGrid(body, items, gridCols())
        val sv = MaxHScroll(this, maxH()).apply { addView(body) }
        svh[0] = sv
        box.addView(sv, LinearLayout.LayoutParams(-1, -2))
        val w = winW()
        val lp = WindowManager.LayoutParams(w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.y = dp(12)
        placeSaved(lp, "wspec", dp(12))
        makeDraggable(head, box, lp) { Cfg.set(this, "wspec", it[0].toString() + "," + it[1]) }
        box.alpha = Store.winAlpha / 100f
        try { wm.addView(box, lp); spec = box } catch (e: Throwable) { Diag.log(this, "панель слежки: " + e.message) }
    }

    private fun removeSpec() { spec?.let { try { wm.removeView(it) } catch (e: Exception) {} }; spec = null }

    // ---------- веер из трёх кнопок ----------
    private fun fanButtons(): List<FanBtn> {
        val cs = CaptureService.inst
        val rec = cs != null && cs.recording
        val ocr = cs != null && cs.ocrOn
        val pend = Inbox.pending()
        val lead = if (pend > 0) listOf(FanBtn("✉", "Репорты $pend", 0xFFC2475A.toInt()) { curKey = ""; showInbox() }) else emptyList()
        return lead + listOf(
            FanBtn("▣", "Скриншот", ACCENT) { capture("shot") },
            FanBtn(if (rec) "■" else "●", if (rec) "Стоп записи" else "Запись", if (rec) DANGER else 0xFFB8566B.toInt()) { capture(if (rec) "recstop" else "rec") },
            FanBtn("▭", "Область чтения", 0xFF3B82F6.toInt()) { showCropEditor() },
            FanBtn("◎", if (cs != null) "Ловец: вкл" else "Ловец: выкл", if (cs != null) OKC else 0xFF56607A.toInt()) { toggleCatcher() }
        )
    }

    private fun showFan() {
        removeFan(); removePopup(); removeSheet()
        val b = bubble ?: return
        val loc = IntArray(2); b.getLocationOnScreen(loc)
        val cxs = loc[0] + b.width / 2f
        val cys = loc[1] + b.height / 2f
        val dm = resources.displayMetrics
        val root = FrameLayout(this)
        root.setBackgroundColor(0x40000000)
        // любое касание экрана — веер исчезает (кнопки перехватывают касание сами)
        root.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) removeFan(); true }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        try { wm.addView(root, lp); fan = root } catch (e: Throwable) { Diag.log(this, "веер: " + e.message); return }

        root.post {
            val rl = IntArray(2); root.getLocationOnScreen(rl)
            val cx = cxs - rl[0]; val cy = cys - rl[1]
            val rw = max(root.width, dm.widthPixels).toFloat(); val rh = max(root.height, dm.heightPixels).toFloat()
            val radius = dp(86).toFloat()
            // веер раскрывается в сторону экрана: от левого края — вправо, от правого — влево; у верха/низа — наклоняется
            var base = if (cx < rw / 2f) 0.0 else 180.0
            if (cy < radius + dp(50)) base += if (base == 0.0) 38.0 else -38.0
            else if (cy > rh - radius - dp(50)) base += if (base == 0.0) -38.0 else 38.0
            val fbs = fanButtons()
            val step = if (fbs.size > 3) 40.0 else 52.0
            val angles = DoubleArray(fbs.size) { base + (it - (fbs.size - 1) / 2.0) * step }
            fbs.forEachIndexed { i, fb ->
                val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                v.addView(txt(fb.icon, 24f).apply {
                    gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fb.color); setStroke(dp(2), 0x66FFFFFF) }
                }, LinearLayout.LayoutParams(dp(56), dp(56)))
                v.addView(txt(fb.label, 11f, Color.WHITE, true).apply {
                    background = bg(0xCC000000.toInt(), 8); setPadding(dp(7), dp(2), dp(7), dp(2)); maxLines = 1
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                v.setOnClickListener { removeFan(); fb.act() }
                v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val vw = v.measuredWidth; val vh = v.measuredHeight
                val a = Math.toRadians(angles[i])
                val px = (cx + radius * cos(a)).toFloat() - vw / 2f
                val py = (cy + radius * sin(a)).toFloat() - dp(28)
                val tx = px.coerceIn(dp(6).toFloat(), rw - vw - dp(6))
                val ty = py.coerceIn(dp(6).toFloat(), rh - vh - dp(6))
                root.addView(v, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = tx.toInt(); topMargin = ty.toInt() })
                // вылет из значка
                v.translationX = cx - (tx + vw / 2f); v.translationY = cy - (ty + dp(28)); v.scaleX = 0.3f; v.scaleY = 0.3f; v.alpha = 0f
                v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                    .setStartDelay(i * 40L).setDuration(240).setInterpolator(OvershootInterpolator(1.5f)).start()
            }
        }
    }

    private fun removeFan() { fan?.let { try { wm.removeView(it) } catch (e: Exception) {} }; fan = null }

    private fun capture(what: String) {
        val cs = CaptureService.inst
        if (cs == null || !cs.ready) {
            if (what == "recstop") return
            // разрешение на запись экрана спрашивается один раз за запуск
            try {
                startActivity(Intent(this, CaptureRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("then", what))
            } catch (e: Exception) { Diag.log(this, "меню значка: " + e.message) }
            return
        }
        when (what) {
            "shot" -> cs.shootWithHide()
            "rec" -> cs.startRec()
            "recstop" -> cs.stopRec()
            "ocr" -> cs.setOcr(!cs.ocrOn)
        }
    }

    // ---------- область чтения: рамка поверх игры ----------
    private fun showCropEditor() {
        removeCrop(); removeFan(); removePopup()
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        val root = FrameLayout(this)
        val cv = CropView(this, ACCENT)
        root.addView(cv, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(14), dp(10), dp(14), dp(12)); background = bg(BG, 18, LINE) }
        bar.addView(txt("Область чтения", 14f, Color.WHITE, true))
        bar.addView(txt("Растяни рамку по тому месту, где появляются репорты и админ-чат. Тяни за углы и края, середину — двигать, пустое место — нарисовать заново.", 11f, 0xAAFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(8)) }, LinearLayout.LayoutParams(min(dm.widthPixels - dp(60), dp(420)), -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill("✓ Сохранить", OKC) {
            val loc = IntArray(2); cv.getLocationOnScreen(loc)
            val q = cv.rect()
            val rw = dm.widthPixels.toFloat(); val rh = dm.heightPixels.toFloat()
            val l = (loc[0] + q.left) / rw; val t = (loc[1] + q.top) / rh; val r = (loc[0] + q.right) / rw; val b = (loc[1] + q.bottom) / rh
            Cfg.setCrop(this, l, t, r, b)
            Toast.makeText(this, "Область чтения сохранена", Toast.LENGTH_SHORT).show()
            removeCrop()
        }, LinearLayout.LayoutParams(0, -2, 1.3f))
        row.addView(pill("↺ Сброс", CHIP) {
            val z = Cfg.DEF_CROP.split(",").map { it.toFloat() }
            val loc = IntArray(2); cv.getLocationOnScreen(loc)
            cv.setRect(z[0] * dm.widthPixels - loc[0], z[1] * dm.heightPixels - loc[1], z[2] * dm.widthPixels - loc[0], z[3] * dm.heightPixels - loc[1])
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        row.addView(pill("✕", CHIP) { removeCrop() }, LinearLayout.LayoutParams(dp(52), -2).apply { leftMargin = dp(6) })
        bar.addView(row, LinearLayout.LayoutParams(-1, -2))
        val barLp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16); topMargin = dp(16) }
        root.addView(bar, barLp)
        // панель уходит на ту половину экрана, где нет рамки, чтобы не закрывать её
        cv.onChange = {
            val g = if (cv.rect().centerY() > cv.height / 2f) Gravity.TOP else Gravity.BOTTOM
            if (barLp.gravity and Gravity.VERTICAL_GRAVITY_MASK != g) { barLp.gravity = g or Gravity.CENTER_HORIZONTAL; bar.layoutParams = barLp }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        if (android.os.Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        try { wm.addView(root, lp); cropRoot = root } catch (e: Throwable) { Diag.log(this, "рамка области: " + e.message); Toast.makeText(this, "Нет разрешения «Поверх других приложений»", Toast.LENGTH_LONG).show(); return }
        bubble?.visibility = View.INVISIBLE
        root.post {
            val loc = IntArray(2); cv.getLocationOnScreen(loc)
            val c = Store.crop
            cv.setRect(c[0] * dm.widthPixels - loc[0], c[1] * dm.heightPixels - loc[1], c[2] * dm.widthPixels - loc[0], c[3] * dm.heightPixels - loc[1])
            cv.onChange?.invoke()
        }
    }

    private fun removeCrop(showBubble: Boolean = true) {
        cropRoot?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        if (cropRoot != null && showBubble) bubble?.visibility = View.VISIBLE
        cropRoot = null
    }

    // ---------- редактор расположения: тащишь значок и окна пальцем туда, где удобно ----------
    private var layoutRoot: FrameLayout? = null

    private fun removeLayoutEditor() {
        layoutRoot?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        if (layoutRoot != null) bubble?.visibility = View.VISIBLE
        layoutRoot = null
    }

    private fun showLayoutEditor() {
        removeLayoutEditor(); removeCrop(); removeFan(); removePopup(); removeSpec()
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        val W = dm.widthPixels; val H = dm.heightPixels
        val root = FrameLayout(this).apply { setBackgroundColor(0x88000000.toInt()) }
        val w = min(W - dp(24), dp(560)); val gh = dp(190)
        val bs = dp(46)
        class G(val key: String, val v: TextView, val lp: FrameLayout.LayoutParams)
        val gs = ArrayList<G>()
        fun ghost(key: String, label: String, gw: Int, ghh: Int, x0: Int, y0: Int, round: Boolean, color: Int) {
            val v = TextView(this).apply {
                text = label; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = bg(color, if (round) 40 else 16, 0xFFFFFFFF.toInt())
            }
            val lp = FrameLayout.LayoutParams(gw, ghh, Gravity.TOP or Gravity.START).apply { leftMargin = x0.coerceIn(0, max(0, W - gw)); topMargin = y0.coerceIn(0, max(0, H - ghh)) }
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            v.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.leftMargin; sy = lp.topMargin; tx = e.rawX; ty = e.rawY; v.alpha = 0.7f; true }
                    MotionEvent.ACTION_MOVE -> {
                        lp.leftMargin = (sx + e.rawX - tx).toInt().coerceIn(0, max(0, W - gw)); lp.topMargin = (sy + e.rawY - ty).toInt().coerceIn(0, max(0, H - ghh))
                        v.layoutParams = lp; true
                    }
                    else -> { v.alpha = 1f; true }
                }
            }
            root.addView(v, lp); gs.add(G(key, v, lp))
        }
        val bl = bubbleLp
        val sp = savedXY("wpop"); val ss = savedXY("wspec")
        ghost("wpop", "Окно репорта\nи ответа", w, gh, sp?.get(0) ?: (W - w) / 2, sp?.get(1) ?: (H - gh - dp(70)), false, 0xCC1F6F8B.toInt())
        ghost("wspec", "Окно слежки", w, gh, ss?.get(0) ?: (W - w) / 2, ss?.get(1) ?: (H - gh - dp(130)), false, 0xCC7A4B9E.toInt())
        ghost("bpos", "GM", bs, bs, bl?.x ?: 0, bl?.y ?: dp(120), true, ACCENT)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(14), dp(10), dp(14), dp(12)); background = bg(BG, 18, LINE) }
        bar.addView(txt("Расположение", 14f, Color.WHITE, true))
        bar.addView(txt("Перетащи значок и окна пальцем туда, где они не мешают. Окна появятся верхним левым углом в этом месте.", 11f, 0xAAFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(8)) }, LinearLayout.LayoutParams(min(W - dp(60), dp(420)), -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill("✓ Сохранить", OKC) {
            for (g in gs) {
                val v = g.lp.leftMargin.toString() + "," + g.lp.topMargin
                Cfg.set(this, g.key, v)
                if (g.key == "bpos") bubbleLp?.let { l -> l.gravity = Gravity.TOP or Gravity.START; l.x = g.lp.leftMargin; l.y = g.lp.topMargin; try { bubble?.let { wm.updateViewLayout(it, l) } } catch (e: Exception) {} }
            }
            Toast.makeText(this, "Расположение сохранено", Toast.LENGTH_SHORT).show(); removeLayoutEditor()
        }, LinearLayout.LayoutParams(0, -2, 1.3f))
        row.addView(pill("↺ Сброс", CHIP) {
            Cfg.set(this, "wpop", ""); Cfg.set(this, "wspec", "")
            Toast.makeText(this, "Окна вернутся вниз по центру", Toast.LENGTH_SHORT).show(); removeLayoutEditor()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        row.addView(pill("✕", CHIP) { removeLayoutEditor() }, LinearLayout.LayoutParams(dp(52), -2).apply { leftMargin = dp(6) })
        bar.addView(row, LinearLayout.LayoutParams(-1, -2))
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        if (android.os.Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        try { wm.addView(root, lp); layoutRoot = root } catch (e: Throwable) { Diag.log(this, "редактор расположения: " + e.message); Toast.makeText(this, "Нет разрешения «Поверх других приложений»", Toast.LENGTH_LONG).show(); return }
        bubble?.visibility = View.INVISIBLE
    }

    // ---------- значок ----------
    private fun addBubble() {
        val size = dp(46)
        val b = TextView(this).apply {
            text = "GM"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE); alpha = 0.92f
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = 0; lp.y = dp(120)
        try {
            val p = Cfg.get(this, "bpos").split(",").mapNotNull { it.trim().toIntOrNull() }
            if (p.size == 2) {
                val dm = resources.displayMetrics
                lp.x = p[0].coerceIn(0, max(0, dm.widthPixels - size)); lp.y = p[1].coerceIn(0, max(0, dm.heightPixels - size))
            }
        } catch (e: Exception) {}
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) { lp.x = sx + dx; lp.y = sy + dy; try { wm.updateViewLayout(b, lp) } catch (x: Exception) {} }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) showFan() else snapToEdge(b, lp, size)
                    true
                }
                else -> false
            }
        }
        try { wm.addView(b, lp); bubble = b; bubbleLp = lp; refreshBubble() } catch (e: Throwable) { Diag.log(this, "значок: " + e.message); Toast.makeText(this, "Нет разрешения «Поверх других приложений»", Toast.LENGTH_LONG).show() }
    }

    /** После перетаскивания значок прилипает к ближайшему краю экрана и запоминает место. */
    private fun snapToEdge(b: View, lp: WindowManager.LayoutParams, size: Int) {
        val dm = resources.displayMetrics
        val toX = if (lp.x + size / 2 < dm.widthPixels / 2) 0 else dm.widthPixels - size
        val y = lp.y.coerceIn(0, max(0, dm.heightPixels - size))
        lp.y = y
        val from = lp.x
        ValueAnimator.ofInt(from, toX).apply {
            duration = 180; interpolator = DecelerateInterpolator()
            addUpdateListener { lp.x = it.animatedValue as Int; try { wm.updateViewLayout(b, lp) } catch (x: Exception) {} }
            start()
        }
        Cfg.set(this, "bpos", "$toX,$y")
    }

    override fun onDestroy() {
        running = false
        LogWatcher.stop()
        Store.hideOverlay = null; h.removeCallbacks(ticker)
        Store.onReport = null; Store.onAdminCmd = null; Store.onOutbox = null; Store.onQueue = null; Store.onRefresh = null; Store.onSpectate = null
        h.removeCallbacks(beat); Playtime.end(this)
        removePopup(); removeSheet(); removeFan(); removeSpec(); removeCrop(); removeLayoutEditor(); bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        super.onDestroy()
    }
}
