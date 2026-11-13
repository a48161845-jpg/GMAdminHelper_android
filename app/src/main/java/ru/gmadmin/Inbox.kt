package ru.gmadmin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Входящие: репорты и формы из админ-чата. Хранятся на диске, поэтому не пропадают ни при перезапуске помощника,
 * ни когда окно на экране закрыли или его заменило другое. Живут 90 минут или пока их не закроешь.
 */
object Inbox {
    private const val TTL = 90 * 60 * 1000L
    private var app: Context? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ts = ConcurrentHashMap<String, Long>()

    fun init(c: Context) {
        app = c.applicationContext
        load()
    }

    fun stamp(key: String) { ts[key] = System.currentTimeMillis() }

    /** Сколько ждёт ответа: репорты без ответа + формы. */
    fun pending(): Int = Store.queue.count { it !in Store.answered } + Store.reqs.size

    fun save() {
        val c = app ?: return
        val now = System.currentTimeMillis()
        // старше 90 минут — убираем
        for (l in Store.queue) if (now - (ts["r|$l"] ?: now) > TTL) { Store.queue.remove(l); Store.answered.remove(l) }
        for (q in Store.reqs) if (now - (ts["f|$q"] ?: now) > TTL) Store.reqs.remove(q)
        val o = JSONObject()
        val r = JSONArray()
        for (l in Store.queue) r.put(JSONObject().put("l", l).put("t", ts["r|$l"] ?: now).put("d", if (l in Store.answered) 1 else 0))
        val f = JSONArray()
        for (q in Store.reqs) f.put(JSONObject().put("q", q).put("t", ts["f|$q"] ?: now))
        o.put("r", r); o.put("f", f)
        val s = o.toString()
        io.execute { try { Cfg.set(c, "inbox", s) } catch (e: Throwable) {} }
    }

    private fun load() {
        val c = app ?: return
        try {
            val s = Cfg.get(c, "inbox")
            if (s.isEmpty()) return
            val o = JSONObject(s)
            val now = System.currentTimeMillis()
            val r = o.optJSONArray("r") ?: JSONArray()
            for (i in 0 until r.length()) {
                val x = r.getJSONObject(i)
                val t = x.optLong("t", now)
                if (now - t > TTL) continue
                val l = x.optString("l")
                if (l.isEmpty() || Store.queue.contains(l)) continue
                Store.queue.add(l); ts["r|$l"] = t
                if (x.optInt("d", 0) == 1) Store.answered.add(l)
                // чтобы тот же репорт, всё ещё видимый в чате, не добавился второй раз
                val m = Catch.repRe.find(l)
                if (m != null) Catch.seen["r" + m.groupValues[1] + m.groupValues[2] + m.groupValues[4]] = now
            }
            val f = o.optJSONArray("f") ?: JSONArray()
            for (i in 0 until f.length()) {
                val x = f.getJSONObject(i)
                val t = x.optLong("t", now)
                if (now - t > TTL) continue
                val q = x.optString("q")
                if (q.isEmpty() || Store.reqs.contains(q)) continue
                Store.reqs.add(q); ts["f|$q"] = t
                val p = q.split("\t")
                if (p.size == 2) Catch.seen["c" + p[0] + p[1]] = now
            }
        } catch (e: Throwable) { Diag.log(c, "входящие: " + e.message) }
    }
}
