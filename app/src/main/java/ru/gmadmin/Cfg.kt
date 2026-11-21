package ru.gmadmin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Настройки и данные (причины, команды, журнал) — одно хранилище для приложения, панели и значка. */
object Cfg {
    private fun prefs(c: Context) = c.getSharedPreferences("gmdata", Context.MODE_PRIVATE)
    fun get(c: Context, k: String): String = prefs(c).getString(k, "") ?: ""
    fun set(c: Context, k: String, v: String) { prefs(c).edit().putString(k, v).apply() }

    fun json(c: Context): JSONObject {
        return try {
            val s = get(c, "cfg")
            if (s.isEmpty()) JSONObject() else JSONObject(s)
        } catch (e: Exception) { JSONObject() }
    }

    fun apply(c: Context) {
        val o = json(c)
        Store.lvl = o.optInt("lvl", 3).coerceIn(1, 5)
        val lg = o.optString("lang", "rus+eng")
        Store.lang = if (lg.isEmpty()) "rus+eng" else lg
        Store.scan = 100            // частота проверки экрана зафиксирована: максимально быстрая, лишнюю нагрузку снимают подписи уже прочитанных строк
        Store.autoSend = o.optBoolean("auto", false)
        Store.autoReply = o.optBoolean("autoReply", true)
        Store.cscale = o.optInt("cscale2", 75).coerceIn(40, 100)      // по умолчанию «Среднее»: телефон заметно меньше греется и лагает
        Store.fb = o.optInt("fb", 10).coerceIn(0, 60)
        Store.strict = o.optBoolean("strict2", true)     // по умолчанию — только полные репорты «…[К-во репорта: N]»: без мусора
        Store.catchForms = o.optBoolean("forms", true)
        Store.autoRec = o.optBoolean("autoRec", true)
        val ara = o.opt("autoRecAny")      // переключатель в приложении хранит 0/1
        Store.autoRecAny = ara == true || (ara is Number && ara.toInt() != 0)
        Store.autoRecMin = o.optInt("autoRecMin", 20).coerceIn(1, 120)
        Store.loose = o.optBoolean("loose", true)
        Store.ocrThreads = o.optInt("ocrThreads", 2).coerceIn(1, 2)
        Store.wrapMs = o.optInt("wrapMs", 1100).coerceIn(300, 4000)
        Store.maxId = o.optInt("maxId", 1500).coerceIn(50, 9999)
        Store.ttlMin = o.optInt("ttlMin", 90).coerceIn(5, 720)
        Store.vibMs = o.optInt("vib", 250).coerceIn(0, 1000)
        Store.winDp = o.optInt("winDp", 320).coerceIn(240, 460)
        Store.gridPref = o.optInt("gridPref", 0).coerceIn(0, 6)
        Store.ansCols = o.optInt("ansCols", 2).coerceIn(1, 3)
        Store.uiScale = o.optInt("uiPct", 100).coerceIn(80, 140) / 100f
        Store.winAlpha = o.optInt("winAlpha", 100).coerceIn(40, 100)
        Store.hideMin = o.optInt("hideMin", 3).coerceIn(0, 60)
        Store.nextAuto = o.optBoolean("nextAuto", true)
        val ha = o.optString("helpAns", "").trim()
        Store.helpAns = if (ha.isEmpty()) "Ваш вопрос передан администрации, ожидайте." else ha
        Store.oneAtATime = o.optBoolean("oneOn", false)
        Store.repPause = o.optInt("pauseS", 0).coerceIn(0, 120)
        Store.gap = o.optInt("gap", 500).coerceIn(250, 1500)
        val at = o.optString("aTpl", "").trim()
        Store.aTpl = if (at.isEmpty() || at == "/a {id} нужна помощь") "/a {who} нужна помощь: {text}" else at
        val rq = o.optString("reqTpl", "").trim()
        Store.reqTpl = if (rq.contains("{cmd}")) rq else "/a {cmd}"
        Store.myNick = o.optString("admNick", "").trim()
        Store.logSrc = get(c, "logsrc")
        val r = o.optJSONObject("replies")
        val m = HashMap<String, String>()
        m["sp"] = r?.optString("sp", "") ?: ""
        m["tp"] = r?.optString("tp", "") ?: ""
        m["spawn"] = r?.optString("spawn", "") ?: ""
        m["pun"] = r?.optString("pun", "") ?: ""
        Store.replies = m
        Store.accent = when (o.optString("theme", "teal")) {
            "red" -> 0xFFE5484D; "purple" -> 0xFF8B5CF6; "blue" -> 0xFF3B82F6; "green" -> 0xFF22A55E
            "orange" -> 0xFFF08C1A; "pink" -> 0xFFEC4899; "gray" -> 0xFF64748B; else -> 0xFF14B8A6
        }.toInt()
        Store.crop = readCrop(c, o)
        Store.lv = cmdLevels(c)
    }

    /** В тексте репорта есть слово-термин из правил (ключевые слова пунктов: «дм», «зз», «тп»…). Короткие слова ищутся как целые. */
    fun termHit(c: Context, text: String): Boolean {
        val t = text.lowercase().replace('ё', 'е')
        val words = Regex("[a-zа-я0-9]+").findAll(t).map { it.value }.toSet()
        try {
            val a = org.json.JSONArray(get(c, "reasons"))
            for (i in 0 until a.length()) {
                val kw = a.optJSONObject(i)?.optJSONArray("kw") ?: continue
                for (k in 0 until kw.length()) {
                    val w = kw.optString(k).lowercase().replace('ё', 'е').trim()
                    if (w.length < 2) continue
                    if (if (w.length <= 3) words.contains(w) else t.contains(w)) return true
                }
            }
        } catch (e: Exception) {}
        return false
    }

    /** Команды, заданные в приложении (раздел «Команды»): имя → уровень. Формы админ-чата принимаются только для них. */
    fun cmdLevels(c: Context): Map<String, Int> {
        val out = HashMap<String, Int>()
        try {
            val a = org.json.JSONArray(get(c, "cmds"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val m = Regex("^/?([A-Za-z_]{2,12})").find(o.optString("t", "").trim()) ?: continue
                val n = m.groupValues[1].lowercase()
                val lv = o.optInt("lv", 1).coerceIn(1, 5)
                val old = out[n]
                if (old == null || lv < old) out[n] = lv
            }
        } catch (e: Exception) {}
        return out
    }

    const val DEF_CROP = "0.19,0.0,0.67,0.25"

    /** Область чтения хранится отдельным ключом (её меняет оверлей); старое значение из настроек подхватывается один раз. */
    private fun readCrop(c: Context, o: JSONObject): FloatArray {
        val own = get(c, "crop")
        val raw = if (own.isNotEmpty()) own else o.optString("crop", DEF_CROP)
        val p = raw.split(",").mapNotNull { it.trim().toFloatOrNull() }
        return if (p.size == 4 && p.all { it in 0f..1f } && p[2] > p[0] && p[3] > p[1]) p.toFloatArray()
        else DEF_CROP.split(",").map { it.toFloat() }.toFloatArray()
    }

    fun cropString(): String = Store.crop.joinToString(",") { "%.3f".format(java.util.Locale.US, it) }

    fun setCrop(c: Context, l: Float, t: Float, r: Float, b: Float) {
        val a = floatArrayOf(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        if (a[2] - a[0] < 0.02f || a[3] - a[1] < 0.02f) return
        Store.crop = a
        set(c, "crop", cropString())
    }

    /** «Artem_Danielle» → «A.Danielle»: первая буква имени, точка, фамилия. */
    fun sigOf(nick: String): String {
        val p = nick.split("_").filter { it.isNotEmpty() }
        return if (p.size >= 2) p[0].substring(0, 1).uppercase() + "." + p.drop(1).joinToString("_") else nick
    }

    fun kbHeightPercent(c: Context): Int = json(c).optInt("kbh", 40).coerceIn(25, 75)
    /** Высота при наборе букв (вкладка ABC): ниже обычной панели. */
    fun kbTypeHeightPercent(c: Context): Int = json(c).optInt("kbt", 36).coerceIn(22, 60)

    fun setAuto(c: Context, v: Boolean) {
        val o = json(c)
        o.put("auto", v)
        set(c, "cfg", o.toString())
        Store.autoSend = v
    }
}

/**
 * Журнал: хранится в памяти, на диск пишется в фоне — добавление записи больше не тормозит отправку команд в чат.
 */
object Journal {
    private val lock = Any()
    private var mem: JSONArray? = null
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun load(c: Context): JSONArray {
        mem?.let { return it }
        val a = try {
            val s = Cfg.get(c, "jr")
            if (s.isEmpty()) JSONArray() else JSONArray(s)
        } catch (e: Exception) { JSONArray() }
        mem = a
        return a
    }

    private fun persist(c: Context) {
        val snap = mem?.toString() ?: return
        io.execute { try { Cfg.set(c, "jr", snap) } catch (e: Throwable) {} }
    }

    fun list(c: Context): String {
        synchronized(lock) { return load(c).toString() }
    }

    fun add(c: Context, kind: String, text: String, id: String? = null, key: String? = null, win: Long = 0L) {
        synchronized(lock) {
            var a = load(c)
            val now = Date()
            if (!key.isNullOrEmpty() && win > 0) {
                // такая же запись только что уже была (двойное нажатие, тот же репорт из панели и из окна) — второй раз не считаем
                var i = a.length() - 1
                var n = 0
                while (i >= 0 && n < 300) {
                    val o = a.optJSONObject(i)
                    if (o != null && o.optString("key") == key && now.time - o.optLong("ts", 0L) < win) return
                    i--; n++
                }
            }
            val o = JSONObject()
            o.put("id", if (!id.isNullOrEmpty()) id else now.time.toString() + (0..999).random().toString())
            o.put("d", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now))
            o.put("t", SimpleDateFormat("HH:mm", Locale.US).format(now))
            o.put("k", kind)
            o.put("l", text)
            if (!key.isNullOrEmpty()) { o.put("key", key); o.put("ts", now.time) }
            a.put(o)
            if (a.length() > 20000) {
                val out = JSONArray()
                for (i in a.length() - 20000 until a.length()) out.put(a.get(i))
                a = out; mem = out
            }
            persist(c)
        }
    }

    /** Заменить весь журнал (восстановление из копии). */
    fun replace(c: Context, json: String) {
        synchronized(lock) {
            mem = try { JSONArray(json) } catch (e: Exception) { JSONArray() }
            persist(c)
        }
    }

    fun del(c: Context, id: String) {
        synchronized(lock) {
            val a = load(c)
            val out = JSONArray()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optString("id") != id) out.put(o)
            }
            mem = out
            persist(c)
        }
    }
}
