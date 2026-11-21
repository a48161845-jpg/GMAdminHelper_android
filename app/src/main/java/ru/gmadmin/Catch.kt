package ru.gmadmin

import java.util.concurrent.ConcurrentHashMap

/**
 * Общий разбор строк чата: репорты и запросы команд в админ-чате.
 * Сюда попадают строки и из лог-файла игры (точный текст), и из распознавания экрана.
 */
object Catch {
    val seen = ConcurrentHashMap<String, Long>()
    private val color = Regex("\\{[0-9A-Fa-f]{6}\\}")
    // Ник: «Имя_Фамилия» или (если «_» прочитался пробелом/точкой) «Имя Фамилия». ID и номер репорта: цифры, но O/l/I/S/B/Z допускаются — это частые ошибки распознавания цифр.
    private const val NAME = "(?:[A-Za-z][A-Za-z0-9]+[ .][A-Za-z][A-Za-z0-9]+|[A-Za-z][A-Za-z0-9_]{2,})"
    val repRe = Regex("(" + NAME + ")\\s*[\\[({]\\s*([0-9OoIl|SsBZ]{1,4})\\s*[\\])}]\\s*[:;.,]\\s*(.{0,400}?)\\s*[\\[({][^\\])}]{3,24}[:;.,]\\s*([0-9OoIl|SB]{1,3})\\s*[\\])}]")
    val looseRe = Regex("(" + NAME + ")\\s*[\\[({]\\s*([0-9OoIl|SsBZ]{1,4})\\s*[\\])}]\\s*[:;.,]\\s*(\\S.{2,400})")
    private fun fixNum(x: String) = x.map { when (it) { 'O', 'o' -> '0'; 'I', 'l', '|' -> '1'; 'S', 's' -> '5'; 'B' -> '8'; 'Z' -> '2'; else -> it } }.joinToString("")
    private fun fixName(x: String) = x.replace(' ', '_').replace('.', '_')
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

    private class Rec(val name: String, val id: String, val n: String?, val msg: String, var at: Long)
    private val recent = ArrayList<Rec>()

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
    private fun sim(a: String, b: String): Double {
        val m = maxOf(a.length, b.length)
        return if (m == 0) 1.0 else 1.0 - lev(a, b).toDouble() / m
    }
    private fun idClose(a: String, b: String) = a == b || (a.length >= 2 && b.length >= 2 && lev(a, b) <= 1)

    /** Ник, который недавно уже встречался с тем же ID (±1 знак) и почти так же написан, — возвращаем прежнее написание: ошибки распознавания не плодят «новых» игроков. */
    @Synchronized fun canon(name: String, id: String, now: Long): String {
        recent.removeAll { now - it.at > 600000 }
        val nm = name.lowercase()
        for (e in recent) if (idClose(e.id, id) && sim(nm, e.name.lowercase()) >= 0.85) return e.name
        return name
    }

    /**
     * true — это новый репорт, а не повтор того же самого (кадр прочитан ещё раз, строка заехала в область дважды,
     * ошибка распознавания в нике, ID, тексте или номере). Сравнение нечёткое: ник/ID почти те же + тот же номер репорта
     * или очень похожий текст за последние секунды.
     */
    @Synchronized fun isNew(name: String, id: String, msg: String, n: String?, now: Long): Boolean {
        recent.removeAll { now - it.at > 600000 }
        val nm = name.lowercase(); val mg = norm(msg)
        for (e in recent) {
            val nameClose = sim(nm, e.name.lowercase()) >= 0.8
            if (!nameClose) continue
            val idOk = idClose(e.id, id)
            val msgClose = mg.isEmpty() || e.msg.isEmpty() || sim(mg, e.msg) >= 0.6 || (mg.length >= 6 && e.msg.length >= 6 && mg.take(6) == e.msg.take(6))
            val age = now - e.at
            val dup = when {
                idOk && n != null && e.n != null && n == e.n -> true            // тот же номер от того же игрока
                idOk && msgClose && age < 20000 -> true                          // тот же (или почти тот же) текст — перечитанная строка
                !idOk && n != null && n == e.n && msgClose && age < 20000 -> true // ID прочитался с ошибкой
                idOk && age < 3000 -> true                                       // тот же игрок за секунды: сбой чтения
                else -> false
            }
            if (dup) { e.at = now; return false }
        }
        recent.add(Rec(name, id, n, mg, now))
        return true
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
        // команды нет в списке приложения — форму не принимаем
        if (Store.lv[c.groupValues[1].lowercase()] == null) return true
        // не хватает уровня для этой команды — на такую форму не реагируем вообще
        if ((Store.lv[c.groupValues[1].lowercase()] ?: 1) > Store.lvl) return true
        if (!Store.catchForms) return true
        val cmd = ("/" + c.groupValues[1] + " " + c.groupValues[2]).trim()
        if (fresh("c$sender$cmd", now, 20000)) addRequest(sender, cmd)
        return true
    }

    /** Текст похож на мусор распознавания: много странных знаков, нет ни одной буквы, одна буква подряд много раз. */
    private fun garbage(msg: String): Boolean {
        val t = msg.replace(" ", "")
        if (t.isEmpty()) return true
        val ok = t.count { it.isLetterOrDigit() || it in ".,!?-:;()'\"/+@#%*" }
        if (ok < t.length * 0.8) return true
        if (t.none { it.isLetter() }) return true
        if (Regex("(.)\\1{5,}").containsMatchIn(t)) return true
        return false
    }

    /** Похоже на настоящий репорт: нормальный ник, ID в разумных пределах, текст не мусор. Отсекает обрывки интерфейса и ошибки распознавания. */
    fun plausible(name: String, id: String, msg: String): Boolean {
        if (name.length !in 4..32) return false
        if (name.count { it.isLetter() } < 4) return false
        val n = id.toIntOrNull() ?: return false
        if (n > Store.maxId) return false
        return !garbage(msg)
    }

    private val nickShape = Regex("^[A-Za-z][A-Za-z0-9]+_[A-Za-z0-9_]{2,}$")
    /** Строка заканчивается скобкой-хвостом, пусть и покалеченной распознаванием: «[К-во репорта: 1», «{K-bo penopta: 1}»… Обычный чат так не заканчивается. */
    private val tailish = Regex("[\\[({]\\s*[^\\])}]{2,30}[\\])}]?\\s*$")

    /** Разбор одной записи чата (строка из лога или склеенная из экрана). conf — уверенность распознавания, %. true — это был репорт или запрос. */
    fun parse(s: String, now: Long, loose: Boolean = false, conf: Int = 100): Boolean {
        if (adm(s, now)) return true
        if (conf < 38) return false                   // прочитано почти наугад
        val m = repRe.find(s)
        if (m != null) {
            val id = fixNum(m.groupValues[2])
            val name = canon(fixName(m.groupValues[1]), id, now)
            val msg = m.groupValues[3].trim().take(160); val n = fixNum(m.groupValues[4])
            if (!plausible(name, id, msg)) return true
            if (isNew(name, id, msg, n, now)) addReport("$name[$id]: $msg [К-во репорта: $n]")
            return true
        }
        val l = looseRe.find(s) ?: return false
        // «тёплая» (оранжевая) строка без разобранного хвоста тоже считается репортом, если ник и ID правдоподобны; белый текст — только с хвостом
        val hint = repHint.containsMatchIn(s)
        if (!loose && (Store.strict || !hint)) return false
        if (loose && !hint && (!Store.loose || conf < 60 || !tailish.containsMatchIn(s))) return false      // без следа хвоста и уверенного чтения — не репорт
        val id = fixNum(l.groupValues[2])
        val name = canon(fixName(l.groupValues[1]), id, now)
        val msg = l.groupValues[3].replace(Regex("\\s*[\\[({][^\\])}]{0,24}$"), "").trim().take(160)
        if (!nickShape.matches(name) || !plausible(name, id, msg)) return false
        if (isNew(name, id, msg, null, now)) addReport("$name[$id]: $msg")
        return true
    }

    /** Одна строка чата из лог-файла. true — это был репорт или запрос. */
    fun line(raw: String): Boolean {
        val now = System.currentTimeMillis()
        if (seen.size > 600) seen.entries.removeAll { now - it.value > 700000 }
        val s = color.replace(raw, "").trim()
        if (s.length < 6) return false
        return parse(s, now)
    }
}
