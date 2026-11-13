package ru.gmadmin

import android.app.Application
import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Запоминает падения и ошибки панели, чтобы их можно было прочитать в приложении (Запуск → Ошибки). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try { Inbox.init(this) } catch (e: Throwable) {}
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { Diag.log(this, "ПАДЕНИЕ " + t.name + ": " + Log.getStackTraceString(e)) } catch (x: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }
}

object Diag {
    fun log(c: Context, s: String) {
        try {
            synchronized(this) {
                val p = c.getSharedPreferences("gmdata", Context.MODE_PRIVATE)
                val t = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())
                val n = (p.getString("diag", "") + "\n[" + t + "] " + s).takeLast(6000)
                p.edit().putString("diag", n).commit()      // commit: при падении apply() не успеет записать
            }
        } catch (e: Throwable) {}
    }
    fun get(c: Context): String = Cfg.get(c, "diag").trim()
    fun clear(c: Context) { Cfg.set(c, "diag", "") }
}
