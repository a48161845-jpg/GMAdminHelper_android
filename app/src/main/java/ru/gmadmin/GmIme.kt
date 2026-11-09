package ru.gmadmin

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView

/** Клавиатура GM: панель команд + буквы. Пишет прямо в поле чата и может отправлять. */
class GmIme : InputMethodService() {
    private var web: WebView? = null

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        web?.destroy()
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.setBackgroundColor(0xFF0E0E13.toInt())
        w.addJavascriptInterface(Bridge(this, "kb"), "Android")
        w.loadUrl("file:///android_asset/index.html")
        // высота зависит от ориентации (при повороте Android пересоздаёт это окно)
        val r = Store.real(this)
        val land = r[0] > r[1]
        w.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (r[1] * (if (land) 0.55 else 0.42)).toInt()
        )
        web = w
        return w
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Store.imeCommit = { t, send ->
            val ic = currentInputConnection
            if (ic != null) { ic.commitText(t, 1); if (send) pressEnter() }
        }
        Store.imeDelete = { n -> currentInputConnection?.deleteSurroundingText(n, 0) }
        Store.imeEnter = { pressEnter() }
        web?.evaluateJavascript("window.gmReload&&gmReload()", null)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Store.imeCommit = null; Store.imeDelete = null; Store.imeEnter = null
    }

    private fun pressEnter() {
        val ic = currentInputConnection ?: return
        val a = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (a != EditorInfo.IME_ACTION_NONE && a != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(a)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }
}
