package ru.gmadmin

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Резервная копия всех данных (правила, команды, ответы, кнопки окон, настройки, журнал) одним файлом. */
object Backup {
    private val keys = listOf("cfg", "rules", "reasons", "cmds", "answers", "btns")
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false

    fun json(c: Context): String {
        val o = JSONObject()
        o.put("app", "gm-admin-helper")
        o.put("v", 5)
        o.put("ts", System.currentTimeMillis())
        for (k in keys) {
            val s = Cfg.get(c, k)
            if (s.isNotEmpty()) try { o.put(k, if (s.trimStart().startsWith("[")) JSONArray(s) else JSONObject(s)) } catch (e: Exception) {}
        }
        try { o.put("jr", JSONArray(Journal.list(c))) } catch (e: Exception) {}
        val cr = Cfg.get(c, "crop"); if (cr.isNotEmpty()) o.put("crop", cr)
        return o.toString()
    }

    fun restore(c: Context, text: String): Boolean {
        return try {
            val o = JSONObject(text.trim())
            if (o.optString("app") != "gm-admin-helper") return false
            for (k in keys) if (o.has(k)) Cfg.set(c, k, o.get(k).toString())
            if (o.has("jr")) Journal.replace(c, o.get("jr").toString())
            if (o.has("crop")) Cfg.set(c, "crop", o.optString("crop"))
            Cfg.apply(c)
            true
        } catch (e: Exception) { false }
    }

    fun hours(c: Context): Int = Cfg.json(c).optInt("bkH", 0).coerceIn(0, 720)
    fun dir(c: Context): String = Cfg.get(c, "bkdir")
    fun last(c: Context): Long = Cfg.get(c, "bklast").toLongOrNull() ?: 0L

    fun info(c: Context): String {
        val o = JSONObject()
        o.put("dir", dir(c)); o.put("last", last(c)); o.put("hours", hours(c))
        o.put("path", try { c.getExternalFilesDir(null)?.absolutePath + "/backups" } catch (e: Throwable) { "" })
        return o.toString()
    }

    /** Вызывается раз в минуту: пора ли делать автоматическую копию. */
    fun tick(c: Context) {
        val h = hours(c)
        if (h <= 0) return
        if (System.currentTimeMillis() - last(c) < h * 3600000L) return
        run(c)
    }

    /** Копия сейчас: в выбранную папку, а если её нет — в папку приложения (Android/data/ru.gmadmin/files/backups). */
    fun run(c: Context, cb: ((String) -> Unit)? = null) {
        if (busy) return
        busy = true
        val app = c.applicationContext
        val text = json(app)
        io.execute {
            var msg: String
            try {
                val name = "gm-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                val d = dir(app)
                if (d.isNotEmpty()) {
                    val tree = Uri.parse(d)
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val prev = Cfg.get(app, "bkprev")
                    if (prev.isNotEmpty()) try { DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(prev)) } catch (e: Throwable) {}
                    val doc = DocumentsContract.createDocument(app.contentResolver, parent, "application/json", name)
                        ?: throw RuntimeException("не удалось создать файл в папке")
                    app.contentResolver.openOutputStream(doc)?.use { it.write(text.toByteArray()) }
                    Cfg.set(app, "bkprev", doc.toString())
                    msg = "Копия сохранена в выбранную папку"
                } else {
                    val dd = File(app.getExternalFilesDir(null), "backups"); dd.mkdirs()
                    File(dd, "$name.json").writeText(text)
                    dd.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
                    msg = "Копия сохранена: " + dd.absolutePath
                }
                Cfg.set(app, "bklast", System.currentTimeMillis().toString())
            } catch (e: Throwable) {
                Diag.log(app, "автокопия: " + e.message)
                msg = "Не получилось сделать копию: " + (e.message ?: "")
            }
            busy = false
            cb?.invoke(msg)
        }
    }
}
