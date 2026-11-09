package ru.gmadmin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import org.json.JSONObject

class Bridge(private val c: Context, private val m: String) {
    private val h = Handler(Looper.getMainLooper())

    @JavascriptInterface fun mode(): String = m
    @JavascriptInterface fun copy(t: String) {
        (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("cmd", t))
    }
    /** Вписать в чат. true — клавиатура активна или чат можно открыть автоматически. */
    @JavascriptInterface fun type(t: String, send: Boolean): Boolean {
        val ok = Store.imeCommit != null || ChatInput.canReopen(c)
        ChatInput.put(c, t, send)
        return ok
    }
    @JavascriptInterface fun del(n: Int) { val f = Store.imeDelete ?: return; h.post { f(n) } }
    @JavascriptInterface fun enter() { val f = Store.imeEnter ?: return; h.post { f() } }
    @JavascriptInterface fun setCtx(r: String, t: String) { Store.rep = r; Store.tgt = t }
    @JavascriptInterface fun getCtx(): String = Store.rep + "|" + Store.tgt
    @JavascriptInterface fun queue(): String = Store.queue.joinToString("\n")
    @JavascriptInterface fun ack(l: String) { Store.queue.remove(l) }
    @JavascriptInterface fun forms(): String = Store.forms.joinToString("\n")
    @JavascriptInterface fun ackForm(l: String) { Store.forms.remove(l) }
    /** Записи журнала, сделанные кнопками на всплывающих карточках, пока панель была закрыта. */
    @JavascriptInterface fun takeJ(): String {
        val l = ArrayList(Store.pendJ); Store.pendJ.clear(); return l.joinToString("\n")
    }
    @JavascriptInterface fun logs(): String = Store.logs.takeLast(600).joinToString("\n")
    @JavascriptInterface fun setLvl(n: Int) {
        Store.lvl = n; c.getSharedPreferences("gm", Context.MODE_PRIVATE).edit().putInt("lvl", n).apply()
    }
    @JavascriptInterface fun setAuto(on: Boolean) { Store.auto = on }
    @JavascriptInterface fun setRej(tpl: String) { if (tpl.isNotBlank()) Store.rejTpl = tpl }
    @JavascriptInterface fun setLv(json: String) {
        try { val o = JSONObject(json); val mp = HashMap<String, Int>(); o.keys().forEach { mp[it] = o.getInt(it) }; Store.lv = mp } catch (e: Exception) {}
    }
    @JavascriptInterface fun focus(on: Boolean) { val f = Store.focus ?: return; h.post { f(on) } }
    @JavascriptInterface fun switchIme() {
        h.post { (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
    }
    /** Состояние автооткрытия чата: svc (служба доступности включена), pts (точки заданы для этой ориентации), ime (клавиатура GM активна). */
    @JavascriptInterface fun tapState(): String =
        "svc=" + (if (TapService.inst != null) 1 else 0) + ";pts=" + (if (Store.tapPoints(c) != null) 1 else 0) +
            ";ime=" + (if (Store.imeCommit != null) 1 else 0) + ";land=" + (if (Store.isLand(c)) 1 else 0)
    @JavascriptInterface fun calibrate() { val f = Store.calibrate ?: return; h.post { f() } }
    @JavascriptInterface fun openAccessibility() {
        h.post {
            try { c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
        }
    }
}
