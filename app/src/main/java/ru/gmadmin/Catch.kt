package ru.gmadmin

import java.util.concurrent.ConcurrentHashMap

/**
 * Общий разбор строк чата: репорты и запросы команд в админ-чате.
 * Сюда попадают строки и из лог-файла игры (точный текст), и из распознавания экрана.
 */
object Catch {
    val seen = ConcurrentHashMap<String, Long>()
    private val color = Regex("\\{[0-9A-Fa-f]{6}\\}")
    val repRe = Regex("([A-Za-z][A-Za-z0-9_]{2,})\\s*[\\[({]\\s*(\\d{1,4})\\s*[\\])}]\\s*[:;.]\\s*(.{0,400}?)\\s*[\\[({][^\\])}]{3,24}[:;.]\\s*(\\d{1,3})\\s*[\\])}]")
    val looseRe = Regex("([A-Za-z][A-Za-z0-9_]{2,})\\s*[\\[({]\\s*(\\d{1,4})\\s*[\\])}]\\s*[:;.]\\s*(\\S.{2,400})")
    val repHint = Regex("(?i)р[еe]п[оo]рт|report|[кk]\\s*[-\u2013\u2014]\\s*[вb][оo]")
    /** «<ADM> Ник[ID]: текст» — допускает мелкие ошибки распознавания скобок и пробелов. */
    val admRe = Regex("[<(\\[\u2039S]?\\s*[A\u0410]\\s*[DO\u041e\u0411B0]\\s*I?\\s*[M\u041c]\\s*[>)\\]\u203A]\\s*(?:\\(\\s*\\d\\s*\\)\\s*)?([A-Za-z][A-Za-z0-9_]*(?:[ .][A-Za-z][A-Za-z0-9_]*)?)\\s*[\\[({]\\s*\\d{1,4}\\s*[\\])}]\\s*[:;.]?\\s*(.*)$")
    val admHint = Regex("[A\u0410]\\s*[DO\u041e\u0411B0]\\s*I?\\s*[M\u041c]\\s*[>)\\]\\u203A]")
    val cmdRe = Regex("/([A-Za-z_]{2,12})(?:\\s+([^\\n]{0,100}))?")

    /** true — такого ключа не было за последние win мс. */
    fun fresh(key: String, now: Long, win: Long = 15000): Boolean {
        val last = seen[key] ?: 0L
        seen[key] = now
        return now - last > win
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }.take(24)

    /**
     * true — это новый репорт, а не повтор того же самого (кадр прочитан ещё раз, ошибка распознавания в тексте или в номере).
     * С номером репорта (N) повтором считается тот же номер от того же игрока; без номера — тот же текст или тот же игрок за секунды.
     */
    fun isNew(name: String, id: String, msg: String, n: String?, now: Long): Boolean {
        val c = "c$name$id" + norm(msg)
        if (n != null) {
            if (!fresh("r$name$id$n", now, 600000)) { seen[c] = now; return false }
            return fresh(c, now, 6000)       // тот же текст сразу ещё раз — скорее всего сбой чтения номера
        }
        if (!fresh(c, now, 20000)) return false
        return fresh("m$name$id", now, 8000)
    }

    fun addReport(line: String) {
        if (line in Store.queue) return
        val now = System.currentTimeMillis()
        // «по одному»: пока идёт пауза или окно репорта открыто — новые не набираем
        if (Store.repPause > 0 && now - Store.lastAccept < Store.repPause * 1000L) return
        if (Store.oneAtATime && Store.windowOpen) return
        Store.lastAccept = now
        Store.queue.add(line)
        Inbox.stamp("r|$line")
        while (Store.queue.size > 60) { val x = Store.queue.removeAt(0); Store.answered.remove(x) }
        Inbox.save()
        Store.onQueue?.invoke()
        Store.onReport?.invoke(line)
    }

    fun addRequest(sender: String, cmd: String) {
        val q = sender + "\t" + cmd
        Store.reqs.add(q)
        Inbox.stamp("f|$q")
        while (Store.reqs.size > 40) Store.reqs.removeAt(0)
        Inbox.save()
        Store.onQueue?.invoke()
        Store.onAdminCmd?.invoke(sender, cmd)
    }

    // время в начале строки («14:53:08 …») и куски интерфейса игры, которые попадают в кадр (таймеры, БП, груз, счётчики)
    private val stamp = Regex("^\\s*[\\[(]?\\s*\\d{1,2}\\s*[:.]\\s*\\d{2}(?:\\s*[:.]\\s*\\d{2})?\\s*[\\])]?\\s*")
    private val hud = listOf(
        Regex("(?i)осталось\\s*:?\\s*\\d+\\s*[мmм]?\\s*\\d*\\s*[сc°]?"),
        Regex("(?i)сезонн\\p{L}*\\s*[бb][пn]"),
        Regex("(?i)т[её]мн\\p{L}*\\s*груз\\p{L}*"),
        Regex("\\b\\d{1,3}\\s*ДН\\.?\\s*\\d{0,2}\\s*Ч\\.?"),
        Regex("(?i)(?<![\\p{L}\\d])[cс5s]\\s*:\\s*\\d{2,3}\\.\\d\\b")
    )

    /** Строка чата без времени в начале и без кусков интерфейса игры. */
    fun clean(raw: String): String {
        var s = stamp.replace(raw.trim(), "")
        for (r in hud) s = r.replace(s, " ")
        return s.replace(Regex("\\s{2,}"), " ").trim()
    }

    private val answeredRe = Regex("(?i)^\\s*[оo0]\\s*[тt]\\s*[вbpр]\\s*[еe]\\s*[тt]")
    private val idRe = Regex("[\\[({]\\s*(\\d{1,4})\\s*[\\])}]")
    private val headRe = Regex("^[^\\[]+\\[(\\d+)\\]")

    private fun markAnswered(body: String) {
        val id = idRe.find(body)?.groupValues?.get(1) ?: return
        var ch = false
        for (l in Store.queue) {
            if (l in Store.answered) continue
            if (headRe.find(l)?.groupValues?.get(1) == id) { Store.answered.add(l); ch = true }
        }
        if (ch) { Inbox.save(); Store.onQueue?.invoke(); Store.onRefresh?.invoke() }
    }

    /** Форма из админ-чата. Показываем все (и те, на что у меня нет прав — с пометкой), кроме собственных. */
    private val admAns = Regex("(?i)[<(\\[\u2039]?\\s*A\\s*D\\s*M\\s*[>)\\]\u203A]\\s*\\S+\\s+[оo0]\\s*[тt]\\s*[вb]\\s*[еe]\\s*[тt]\\S*\\s+\\S*?[\\[({]\\s*(\\d{1,4})\\s*[\\])}]")

    fun adm(s: String, now: Long): Boolean {
        val an = admAns.find(s)
        if (an != null) return true      // чужой ответ на репорт: репорт остаётся в списке, снимаю его только я сам
        val a = admRe.find(s) ?: return false
        val sender = a.groupValues[1].replace(Regex("[ .]"), "_")
        if (Store.myNick.isNotEmpty() && sender.equals(Store.myNick, true)) return true
        val body = a.groupValues[2]
        // «<ADM> Админ ответил Игрок[ID]: …» — на этот репорт уже ответили, у себя его снимаем с очереди
        if (answeredRe.containsMatchIn(body)) return true
        val c = cmdRe.find(body) ?: return true
        if (Store.lv[c.groupValues[1].lowercase()] == null && !body.trim().startsWith("/")) return true
        // не хватает уровня для этой команды — на такую форму не реагируем вообще
        if ((Store.lv[c.groupValues[1].lowercase()] ?: 1) > Store.lvl) return true
        if (!Store.catchForms) return true
        val cmd = ("/" + c.groupValues[1] + " " + c.groupValues[2]).trim()
        if (fresh("c$sender$cmd", now, 20000)) addRequest(sender, cmd)
        return true
    }

    /** Одна строка чата из лог-файла. true — это был репорт или запрос. */
    fun line(raw: String): Boolean {
        val now = System.currentTimeMillis()
        if (seen.size > 600) seen.entries.removeAll { now - it.value > 700000 }
        val s = color.replace(raw, "").trim()
        if (s.length < 6) return false
        if (adm(s, now)) return true
        val m = repRe.find(s)
        if (m != null) {
            val name = m.groupValues[1]; val id = m.groupValues[2]
            val msg = m.groupValues[3].trim().take(160); val n = m.groupValues[4]
            if (isNew(name, id, msg, n, now)) addReport("$name[$id]: $msg [К-во репорта: $n]")
            return true
        }
        val l = if (Store.strict) null else looseRe.find(s)
        if (l != null && repHint.containsMatchIn(s)) {
            val name = l.groupValues[1]; val id = l.groupValues[2]
            val msg = l.groupValues[3].replace(Regex("\\s*[\\[({][^\\])}]{0,24}$"), "").trim().take(160)
            if (isNew(name, id, msg, null, now)) addReport("$name[$id]: $msg")
            return true
        }
        return false
    }
}
