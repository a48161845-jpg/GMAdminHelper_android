package ru.gmadmin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/** Мост между веб-панелью (клавиатура / экран приложения) и приложением. */
class Bridge(private val c: Context, private val m: String, private val onAct: ((String) -> Unit)? = null) {
    private val h = Handler(Looper.getMainLooper())

    /** Ключи, которые можно сохранять из веб-части (больше ничего страница записать не может). */
    private val dataKeys = setOf("cfg", "rules", "reasons", "cmds", "answers", "btns", "wpop", "wspec")

    @JavascriptInterface fun mode(): String = m
    /** Текст из буфера обмена (ник, ID, форма) — чтобы вставлять в поля клавиатуры. */
    @JavascriptInterface fun paste(): String = try {
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c)?.toString()?.take(300) ?: ""
    } catch (e: Throwable) { "" }
    @JavascriptInterface fun copy(t: String) {
        try { (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("cmd", t)) } catch (e: Throwable) { Diag.log(c, "буфер: " + e.message) }
    }

    // ---- данные (правила, команды, настройки) ----
    @JavascriptInterface fun getData(k: String): String = if (k in dataKeys) Cfg.get(c, k) else ""
    @JavascriptInterface fun setData(k: String, v: String) { if (k in dataKeys) Cfg.set(c, k, v) }
    @JavascriptInterface fun cfgChanged() { Cfg.apply(c) }
    @JavascriptInterface fun setAuto(v: Boolean) { Cfg.setAuto(c, v) }

    /** Резервная копия: все данные одной строкой (то же, что пишется в файл). */
    @JavascriptInterface fun exportAll(): String = Backup.json(c)
    @JavascriptInterface fun importAll(json: String): Boolean = Backup.restore(c, json)
    @JavascriptInterface fun backupInfo(): String = Backup.info(c)

    // ---- журнал ----
    @JavascriptInterface fun jList(): String = Journal.list(c)
    @JavascriptInterface fun jAdd(k: String, l: String) { Journal.add(c, k, l) }
    /** Запись с id, созданным в панели: панель показывает её сразу, а удалять потом можно по тому же id. */
    @JavascriptInterface fun jAddId(id: String, k: String, l: String) { Journal.add(c, k, l, id) }
    /** То же, но с ключом: такая же запись за последние win мс не добавляется (двойное нажатие, тот же репорт из панели и окна). */
    @JavascriptInterface fun jAddK(id: String, k: String, l: String, key: String, win: Long) { Journal.add(c, k, l, id, key, win) }
    @JavascriptInterface fun jDel(id: String) { Journal.del(c, id) }

    // ---- вывод в чат игры ----
    /** Цепочка текстов. 1 — поле чата открыто, первый вписан сразу; 2 — ждут открытия поля. */
    @JavascriptInterface fun typeSeq(json: String, send: Boolean): Int {
        val items = ArrayList<String>()
        try {
            val a = JSONArray(json)
            for (i in 0 until a.length()) items.add(a.getString(i))
        } catch (e: Exception) { return 0 }
        if (items.isEmpty()) return 0
        val ready = Store.imeFlush != null
        h.post { Store.enqueue(items, send) }
        return if (ready) 1 else 2
    }
    @JavascriptInterface fun outCount(): Int = Store.out.size
    @JavascriptInterface fun cancelOut() { Store.out.clear(); Store.onOutbox?.invoke(false) }

    /** Стереть весь текст в поле чата и сбросить очередь ожидающих команд. */
    @JavascriptInterface fun clearField() {
        Store.out.clear()
        h.post { Store.imeClear?.invoke(); Store.onOutbox?.invoke(false) }
    }
    /** Высота панели клавиатуры, % экрана (разная для панели и для набора букв). */
    @JavascriptInterface fun resize(pct: Int) { val f = Store.imeResize ?: return; h.post { f(pct) } }

    /** Набор букв клавиатуры ABC: пишет в любое активное поле. */
    @JavascriptInterface fun raw(t: String): Boolean { val f = Store.imeRaw ?: return false; h.post { f(t) }; return true }
    @JavascriptInterface fun del(n: Int) { val f = Store.imeDelete ?: return; h.post { f(n) } }
    @JavascriptInterface fun enter() { val f = Store.imeEnter ?: return; h.post { f() } }

    // ---- репорты и запросы ----
    /** Репорты; ответ уже отправлен — строка с меткой «✓<TAB>». Панель их не теряет, пока не закроешь. */
    @JavascriptInterface fun queue(): String = Store.queue.joinToString("\n") { (if (it in Store.answered) "\u2713\t" else "") + it }
    @JavascriptInterface fun ack(l: String) { Store.queue.remove(l); Store.answered.remove(l); Inbox.save(); Store.onQueue?.invoke() }
    @JavascriptInterface fun repDone(l: String) { if (Store.queue.contains(l)) { Store.answered.add(l); Inbox.save(); Store.onQueue?.invoke() } }
    @JavascriptInterface fun reqs(): String = Store.reqs.joinToString("\n")
    @JavascriptInterface fun reqAck(cmd: String) { Store.reqs.removeAll { it.endsWith("\t" + cmd) }; Inbox.save(); Store.onQueue?.invoke() }

    @JavascriptInterface fun switchIme() {
        h.post { (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
    }

    // ---- экран приложения ----
    @JavascriptInterface fun act(name: String) { val f = onAct ?: return; h.post { f(name) } }
    @JavascriptInterface fun status(): String {
        val o = JSONObject()
        o.put("overlay", try { Settings.canDrawOverlays(c) } catch (e: Throwable) { false })
        o.put("ime", try {
            (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.any { it.packageName == c.packageName }
        } catch (e: Throwable) { false })
        o.put("imeSel", try {
            (Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith(c.packageName + "/")
        } catch (e: Throwable) { false })
        o.put("bubble", OverlayService.running)
        o.put("catcher", CaptureService.inst != null)
        o.put("ocr", Store.ocrState)
        o.put("runs", Store.ocrRuns)
        o.put("skips", Store.ocrSkips)
        o.put("pend", Inbox.pending())
        o.put("ver", try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (e: Throwable) { "" })
        return o.toString()
    }
    @JavascriptInterface fun lastOcr(): String = Store.lastOcr + (if (Store.ocrErr.isNotEmpty()) "\n\nПоследняя ошибка: " + Store.ocrErr else "")

    // ---- область чтения (её же меняет оверлей на экране игры) ----
    @JavascriptInterface fun cropGet(): String { Cfg.apply(c); return Cfg.cropString() }
    @JavascriptInterface fun cropSet(v: String): Boolean {
        val p = v.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (p.size != 4 || p[2] - p[0] < 0.02f || p[3] - p[1] < 0.02f) return false
        Cfg.setCrop(c, p[0], p[1], p[2], p[3]); return true
    }

    // ---- лог чата игры ----
    @JavascriptInterface fun logUse(p: String) { LogWatcher.use(c, p) }
    @JavascriptInterface fun logOff() { LogWatcher.off(c) }
    @JavascriptInterface fun logInfo(): String {
        val o = JSONObject()
        o.put("state", Store.logState)
        o.put("src", Store.logSrc)
        o.put("lines", Store.logLines)
        val t = JSONArray()
        synchronized(Store.logTail) { for (s in Store.logTail) t.put(s) }
        o.put("tail", t)
        return o.toString()
    }

    @JavascriptInterface fun diag(): String = Diag.get(c)
    @JavascriptInterface fun diagClear() { Diag.clear(c) }

    // ---- время работы помощника ----
    @JavascriptInterface fun play(): String = try { Playtime.json(c) } catch (e: Throwable) { Diag.log(c, "время работы: " + e.message); "{}" }
}
