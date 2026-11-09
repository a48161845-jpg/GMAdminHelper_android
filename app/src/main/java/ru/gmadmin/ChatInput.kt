package ru.gmadmin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Единая точка «вписать текст в чат игры».
 * 1) Чат открыт и клавиатура GM активна — вписываем сразу.
 * 2) Чат закрылся (в игре он закрывается сам после отправки) — если настроено автооткрытие,
 *    тапаем кнопку чата и поле ввода, ждём клавиатуру и вписываем.
 * 3) Не получилось — копируем в буфер и говорим об этом.
 */
object ChatInput {
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var opening = false

    fun canReopen(c: Context): Boolean = TapService.inst != null && Store.tapPoints(c) != null

    fun put(c: Context, text: String, send: Boolean, cb: ((Boolean) -> Unit)? = null) {
        val ctx = c.applicationContext
        h.post { run(ctx, text, send, cb) }
    }

    private fun run(c: Context, text: String, send: Boolean, cb: ((Boolean) -> Unit)?) {
        val f = Store.imeCommit
        if (f != null) { f(text, send || Store.auto); cb?.invoke(true); return }
        val svc = TapService.inst
        val pts = Store.tapPoints(c)
        if (svc == null || pts == null || opening) { fallback(c, text); cb?.invoke(false); return }
        opening = true
        svc.tap(pts[0], pts[1])
        h.postDelayed({ svc.tap(pts[2], pts[3]) }, 450)
        var tries = 0
        val check = object : Runnable {
            override fun run() {
                val g = Store.imeCommit
                if (g != null) {
                    opening = false
                    g(text, send || Store.auto); cb?.invoke(true)
                } else if (++tries < 16) {
                    h.postDelayed(this, 150)
                } else {
                    opening = false
                    fallback(c, text); cb?.invoke(false)
                }
            }
        }
        h.postDelayed(check, 900)
    }

    fun fallback(c: Context, text: String) {
        try {
            (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("cmd", text))
            Toast.makeText(c, "Чат закрыт — текст скопирован, вставь в поле", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {}
    }
}
