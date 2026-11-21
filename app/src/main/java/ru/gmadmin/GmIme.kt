package ru.gmadmin

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.graphics.Color
import android.util.Log
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Клавиатура GM: панель команд, репортов, правил и журнала + буквы. Пишет прямо в поле чата игры.
 * Если игра закрыла поле ввода, тексты остаются в очереди (Store.out) и вписываются по одному,
 * как только поле открыто снова.
 */
class GmIme : InputMethodService() {
    private var web: WebView? = null
    private val h = Handler(Looper.getMainLooper())
    private var pct = 0

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun heightPx(p: Int): Int = (resources.displayMetrics.heightPixels * p.coerceIn(22, 75) / 100f).toInt()

    /** Меняет высоту панели на лету (обычная панель и набор букв могут быть разной высоты). */
    private fun setPct(p: Int) {
        pct = p.coerceIn(22, 75)
        web?.let {
            it.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx(pct))
            it.requestLayout()
        }
    }

    override fun onCreateInputView(): View {
        return try { buildPanel() } catch (e: Throwable) {
            Diag.log(this, "клавиатура: " + Log.getStackTraceString(e))
            TextView(this).apply {
                text = "GM: ошибка панели. Открой приложение → Настройки → Диагностика"
                setTextColor(Color.WHITE); setBackgroundColor(0xFF0B0E14.toInt()); setPadding(32, 32, 32, 32)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx(Cfg.kbHeightPercent(this@GmIme)))
            }
        }
    }

    private fun buildPanel(): View {
        val w = WebView(this)
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Diag.log(this@GmIme, "JS панели: " + m.message() + " (стр. " + m.lineNumber() + ")")
                return true
            }
        }
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccessFromFileURLs = false
        w.settings.allowUniversalAccessFromFileURLs = false
        w.settings.allowContentAccess = false
        w.isLongClickable = false
        w.setBackgroundColor(0xFF0B0E14.toInt())
        w.addJavascriptInterface(Bridge(this, "kb"), "Android")
        w.loadUrl("file:///android_asset/panel.html")
        pct = Cfg.kbHeightPercent(this)
        w.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx(pct))
        // полоса навигации под панелью в цвет панели — не выглядит как пустое место под клавиатурой
        try { window?.window?.navigationBarColor = 0xFF0B0E14.toInt() } catch (e: Exception) {}
        web = w
        return w
    }

    private val commitFn: (String, Boolean) -> Unit = { t, send ->
        try {
            val ic = currentInputConnection
            if (ic != null) { ic.commitText(t, 1); if (send) pressEnter() }
        } catch (e: Throwable) { Diag.log(this, "вставка в чат: " + e.message) }
    }

    private val flushRun = Runnable { flushNext() }
    private var flushWait = 0
    private var lastSentAt = 0L
    private var inputStartAt = 0L
    private fun scheduleFlush(d: Long) { h.removeCallbacks(flushRun); h.postDelayed(flushRun, maxOf(0L, d)) }

    /**
     * Вписывает следующий текст из очереди. Команда уходит из очереди только после того, как вписана.
     * Между командами цепочки — пауза Store.gap; сразу после открытия поля — 0,2 с на подготовку игры.
     * Никаких чтений текста поля: это синхронные вызовы в процесс игры (до 2 секунд ожидания), из-за них клавиатура зависала.
     * Если игра закрывает чат после отправки, остаток цепочки ждёт следующего открытия поля и допечатывается сам.
     */
    private fun flushNext() {
        h.removeCallbacks(flushRun)
        if (Store.imeCommit == null || Store.out.isEmpty()) return
        val now = System.currentTimeMillis()
        val settle = 200 - (now - inputStartAt)
        if (settle > 0) { scheduleFlush(settle); return }
        val since = now - lastSentAt
        if (since < Store.gap) { scheduleFlush(Store.gap - since); return }
        val ic = currentInputConnection
        if (ic == null) { if (flushWait++ < 60) scheduleFlush(150); return }
        flushWait = 0
        val item = Store.out[0]
        try { ic.commitText(item.first, 1) } catch (e: Throwable) { Diag.log(this, "вставка в чат: " + e.message); scheduleFlush(200); return }
        Store.out.removeAt(0)
        admEmpty = false
        if (item.second) { pressEnter(); lastSentAt = System.currentTimeMillis(); admEmpty = true }
        Store.onOutbox?.invoke(Store.out.isNotEmpty())
        if (item.second && Store.out.isNotEmpty()) scheduleFlush(Store.gap.toLong())
        else if (item.second) admRearm(450)
    }

    /** Стирает весь текст в поле: сначала через удаление вокруг курсора, запасной путь — «выделить всё» и Delete. */
    private fun clearAll() {
        val ic = currentInputConnection ?: return
        try {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(10000, 10000)                     // без чтения поля: просто «стереть всё вокруг курсора»
            ic.performContextMenuAction(android.R.id.selectAll)         // и на случай нестандартного поля — выделить всё и Delete
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            ic.endBatchEdit()
        } catch (e: Throwable) { Diag.log(this, "очистка поля: " + e.message) }
        admEmpty = true
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val foreign = info?.packageName != packageName          // поле игры, а не наше приложение
        inputStartAt = System.currentTimeMillis(); flushWait = 0; admEmpty = true
        Store.imeRaw = { t ->
            if (Store.admPrefix.isNotEmpty() && admArmed) { if (t.startsWith("/")) admArmed = false else admInsert() }
            currentInputConnection?.commitText(t, 1)
            admEmpty = false
        }
        Store.imeAdm = { on -> if (on) { admArmed = true; admInsert() } else admArmed = false }
        Store.imeDelete = { n -> backspace(n) }
        Store.imeEnter = { pressEnter(); admEmpty = true; admRearm(350) }
        Store.imeClear = { clearAll(); admRearm(200) }
        Store.imeResize = { p -> setPct(p) }
        Store.imeCommit = if (foreign) commitFn else null
        val fl: () -> Unit = { flushNext() }
        Store.imeFlush = if (foreign) fl else null
        setPct(Cfg.kbHeightPercent(this))
        if (foreign) admRearm(300)
        if (foreign && Store.out.isNotEmpty()) scheduleFlush(250)
        web?.evaluateJavascript("window.gmReload&&gmReload()", null)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        h.removeCallbacks(flushRun)
        Store.imeCommit = null; Store.imeRaw = null; Store.imeDelete = null; Store.imeEnter = null; Store.imeFlush = null
        Store.imeClear = null; Store.imeResize = null
    }

    /** Стирание: настоящее нажатие Delete (работает и в нестандартных полях игры; выделенный текст убирается тем же нажатием). Без чтений поля — оно не блокирует клавиатуру. */
    private fun backspace(n: Int) {
        val ic = currentInputConnection ?: return
        try {
            for (i in 0 until n.coerceIn(1, 50)) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
        } catch (e: Throwable) { Diag.log(this, "стирание: " + e.message) }
    }

    private var admArmed = false
    private var admEmpty = true          // поле считается пустым после открытия, отправки и очистки — без чтения поля
    /** Режим «писать в админ-чат»: в пустое поле сама встаёт команда (например «/a »). */
    private fun admInsert() {
        val p = Store.admPrefix
        if (p.isEmpty() || !admArmed || Store.out.isNotEmpty()) return      // пока идёт цепочка команд — не вмешиваемся
        val ic = currentInputConnection ?: return
        admArmed = false
        if (admEmpty) { ic.commitText("$p ", 1); admEmpty = false }
    }
    private fun admRearm(delay: Long) {
        if (Store.admPrefix.isEmpty()) return
        admArmed = true
        h.postDelayed({ admInsert() }, delay)
    }

    private fun pressEnter() {
        val ic = currentInputConnection ?: return
        val a = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (a != EditorInfo.IME_ACTION_NONE && a != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(a)
        else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }
}
