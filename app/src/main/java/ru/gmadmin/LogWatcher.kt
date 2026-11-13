package ru.gmadmin

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * Лог чата игры: читает указанный пользователем файл «на лету» (выбирается системным окном выбора файла).
 * Репорты из лога приходят мгновенно и без ошибок распознавания — экран при этом не нужен.
 */
object LogWatcher {
    private val color = Regex("\\{[0-9A-Fa-f]{6}\\}")
    private val cp1251: Charset = Charset.forName("windows-1251")

    @Volatile private var gen = 0

    private fun decode(b: ByteArray): String {
        val u = String(b, Charsets.UTF_8)
        return if (u.indexOf('�') >= 0) String(b, cp1251) else u
    }

    private fun isUri(p: String) = p.startsWith("content://")

    private fun sizeOf(c: Context, src: String): Long {
        if (isUri(src)) {
            val pfd = c.contentResolver.openFileDescriptor(Uri.parse(src), "r") ?: return -1L
            pfd.use { return it.statSize }
        }
        val f = File(src)
        return if (f.exists() && f.canRead()) f.length() else -1L
    }

    private fun readRange(c: Context, src: String, pos: Long, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        if (isUri(src)) {
            val pfd = c.contentResolver.openFileDescriptor(Uri.parse(src), "r") ?: return ByteArray(0)
            pfd.use {
                val ch = FileInputStream(it.fileDescriptor).channel
                ch.position(pos)
                val bb = ByteBuffer.allocate(n)
                while (bb.hasRemaining()) { if (ch.read(bb) < 0) break }
                return bb.array().copyOf(bb.position())
            }
        }
        RandomAccessFile(File(src), "r").use { r ->
            r.seek(pos)
            val buf = ByteArray(n)
            val got = r.read(buf)
            return if (got <= 0) ByteArray(0) else buf.copyOf(got)
        }
    }

    fun use(c: Context, path: String) {
        val app = c.applicationContext
        Cfg.set(app, "logsrc", path)
        Store.logSrc = path
        start(app)
    }

    fun off(c: Context) {
        val app = c.applicationContext
        gen++
        Cfg.set(app, "logsrc", "")
        Store.logSrc = ""
        Store.logState = "выключено"
    }

    // ---------- чтение «на лету» ----------
    fun start(c: Context) {
        val app = c.applicationContext
        val src = Cfg.get(app, "logsrc")
        val my = ++gen
        if (src.isEmpty()) { Store.logState = "файл лога не выбран"; return }
        Store.logSrc = src
        Store.logLines = 0
        val t = Thread { loop(app, src, my) }
        t.isDaemon = true; t.name = "gm-log"; t.start()
    }

    fun stop() { gen++ }

    private fun loop(c: Context, src: String, my: Int) {
        var pos = -1L
        var carry = ByteArray(0)
        while (gen == my) {
            try {
                val size = sizeOf(c, src)
                if (size < 0) {
                    Store.logState = "нет доступа к файлу — дай «Доступ ко всем файлам» или выбери папку заново"
                    Thread.sleep(3000); continue
                }
                if (pos < 0) pos = size                 // старые строки не читаем: только то, что придёт теперь
                if (size < pos) { pos = 0; carry = ByteArray(0) }   // файл начали писать заново
                if (size > pos) {
                    val n = minOf(size - pos, 1_000_000L).toInt()
                    val data = readRange(c, src, pos, n)
                    pos += data.size
                    val all = carry + data
                    var last = -1
                    for (i in all.indices.reversed()) if (all[i] == 10.toByte()) { last = i; break }
                    if (last >= 0) {
                        val text = decode(all.copyOf(last))
                        carry = all.copyOfRange(last + 1, all.size)
                        for (ln in text.split("\n")) {
                            val s = ln.trim()
                            if (s.isEmpty()) continue
                            Store.logLines++
                            synchronized(Store.logTail) { Store.logTail.add(color.replace(s, "")); while (Store.logTail.size > 8) Store.logTail.removeAt(0) }
                            try { Catch.line(s) } catch (e: Exception) { Diag.log(c, "разбор строки лога: " + e.message) }
                        }
                    } else carry = all
                    if (carry.size > 20000) carry = ByteArray(0)
                }
                Store.logState = "читаю лог · новых строк: " + Store.logLines
                Thread.sleep(300)
            } catch (e: InterruptedException) { return }
            catch (e: Exception) {
                Store.logState = "ошибка чтения: " + e.message
                try { Thread.sleep(3000) } catch (x: InterruptedException) { return }
            }
        }
    }
}
