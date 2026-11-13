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

    /** Вписывает следующий текст из очереди. Если предыдущий был отправлен, а поле осталось — продолжает сам. */
    private fun flushNext() {
        if (Store.imeCommit == null) return
        if (Store.out.isEmpty()) return
        val item = Store.out.removeAt(0)
        commitFn(item.first, item.second)
        Store.onOutbox?.invoke(Store.out.isNotEmpty())
        if (item.second && Store.out.isNotEmpty()) h.postDelayed({ flushNext() }, Store.gap.toLong())
    }

    /** Стирает весь текст в поле: сначала через удаление вокруг курсора, запасной путь — «выделить всё» и Delete. */
    private fun clearAll() {
        val ic = currentInputConnection ?: return
        try {
            ic.beginBatchEdit()
            val before = ic.getTextBeforeCursor(8000, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(8000, 0)?.length ?: 0
            if (before + after > 0) ic.deleteSurroundingText(before, after)
            else {
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
            ic.endBatchEdit()
        } catch (e: Throwable) { Diag.log(this, "очистка поля: " + e.message) }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val foreign = info?.packageName != packageName          // поле игры, а не наше приложение
        Store.imeRaw = { t -> currentInputConnection?.commitText(t, 1) }
        Store.imeDelete = { n -> currentInputConnection?.deleteSurroundingText(n, 0) }
        Store.imeEnter = { pressEnter() }
        Store.imeClear = { clearAll() }
        Store.imeResize = { p -> setPct(p) }
        Store.imeCommit = if (foreign) commitFn else null
        val fl: () -> Unit = { flushNext() }
        Store.imeFlush = if (foreign) fl else null
        setPct(Cfg.kbHeightPercent(this))
        if (foreign && Store.out.isNotEmpty()) h.postDelayed({ flushNext() }, 80)
        web?.evaluateJavascript("window.gmReload&&gmReload()", null)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Store.imeCommit = null; Store.imeRaw = null; Store.imeDelete = null; Store.imeEnter = null; Store.imeFlush = null
        Store.imeClear = null; Store.imeResize = null
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
