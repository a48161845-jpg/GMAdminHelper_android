package ru.gmadmin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Учёт времени работы помощника по дням: сессии [начало, конец, последний пульс].
 * Никакой слежки за другими приложениями: доступа к статистике использования у помощника нет.
 */
object Playtime {
    private val lock = Any()
    private var cache = ""
    private var cacheAt = 0L

    fun invalidate() { synchronized(lock) { cacheAt = 0L } }

    // ---- сессии работы помощника ----
    private fun load(c: Context): JSONArray {
        return try {
            val s = Cfg.get(c, "ps")
            if (s.isEmpty()) JSONArray() else JSONArray(s)
        } catch (e: Exception) { JSONArray() }
    }

    private fun save(c: Context, a: JSONArray) {
        val cutoff = System.currentTimeMillis() - 9L * 24 * 3600 * 1000
        val out = JSONArray()
        for (i in 0 until a.length()) {
            val s = a.getJSONArray(i)
            if (s.getLong(0) > cutoff) out.put(s)
        }
        Cfg.set(c, "ps", out.toString())
        cacheAt = 0L
    }

    /** Незакрытые сессии (процесс убили) закрываем по последнему пульсу. */
    private fun closeOpen(a: JSONArray) {
        for (i in 0 until a.length()) {
            val s = a.getJSONArray(i)
            if (s.getLong(1) == 0L) s.put(1, s.getLong(2))
        }
    }

    fun begin(c: Context) {
        synchronized(lock) {
            val a = load(c)
            closeOpen(a)
            val now = System.currentTimeMillis()
            a.put(JSONArray().put(now).put(0L).put(now))
            save(c, a)
        }
    }

    fun beat(c: Context) {
        synchronized(lock) {
            val a = load(c)
            if (a.length() == 0) return
            val s = a.getJSONArray(a.length() - 1)
            if (s.getLong(1) == 0L) { s.put(2, System.currentTimeMillis()); save(c, a) }
        }
    }

    fun end(c: Context) {
        synchronized(lock) {
            val a = load(c)
            val now = System.currentTimeMillis()
            for (i in 0 until a.length()) {
                val s = a.getJSONArray(i)
                if (s.getLong(1) == 0L) s.put(1, now)
            }
            save(c, a)
        }
    }

    private fun dayStarts(n: Int): LongArray {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val out = LongArray(n)
        for (i in 0 until n) { out[i] = cal.timeInMillis; cal.add(Calendar.DAY_OF_YEAR, -1) }
        return out
    }

    private fun clipLen(s: Long, e: Long, ds: Long, de: Long): Long = maxOf(0L, minOf(e, de) - maxOf(s, ds))

    /** JSON: h — время работы помощника по дням (сегодня, вчера…, мс). */
    fun json(c: Context): String {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (cache.isNotEmpty() && now - cacheAt < 20000) return cache
            val starts = dayStarts(8)
            val helper = ArrayList<LongArray>()
            val a = load(c)
            for (i in 0 until a.length()) {
                val s = a.getJSONArray(i)
                var en = s.getLong(1)
                if (en == 0L) { val hb = s.getLong(2); en = if (now - hb < 90000L) now else hb }
                helper.add(longArrayOf(s.getLong(0), en))
            }
            val hArr = JSONArray()
            for (d in 0 until 8) {
                val ds = starts[d]
                val de = if (d == 0) now else starts[d - 1]
                var hs = 0L
                for (h in helper) hs += clipLen(h[0], h[1], ds, de)
                hArr.put(hs)
            }
            val o = JSONObject()
            o.put("h", hArr)
            cache = o.toString(); cacheAt = now
            return cache
        }
    }
}
