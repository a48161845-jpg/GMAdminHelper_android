package ru.gmadmin

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.concurrent.CopyOnWriteArrayList

/** Общее состояние между панелью, клавиатурой и ловцом репортов. */
object Store {
    @Volatile var rep = ""
    @Volatile var tgt = ""
    @Volatile var lvl = 0
    @Volatile var auto = false                       // «слать сразу» (Enter после вставки)
    @Volatile var rejTpl = "/a {sender}, форма отклонена"
    @Volatile var lv: Map<String, Int> = emptyMap()
    @Volatile var lastOcr = ""
    @Volatile var invert = true
    /** Область чата (доли экрана): альбомная и портретная ориентация. */
    @Volatile var crop = floatArrayOf(0.20f, 0.0f, 0.78f, 0.30f)
    @Volatile var cropP = floatArrayOf(0.0f, 0.0f, 1.0f, 0.25f)

    val queue = CopyOnWriteArrayList<String>()       // репорты, ещё не разобранные
    val forms = CopyOnWriteArrayList<String>()       // формы из админ-чата: "ник\tкоманда"
    val pendJ = CopyOnWriteArrayList<String>()       // записи в журнал, сделанные без открытой панели: "вид\tтекст"
    val logs = CopyOnWriteArrayList<String>()

    @Volatile var onReport: ((String) -> Unit)? = null
    @Volatile var onAdminCmd: ((String, String) -> Unit)? = null
    @Volatile var onLog: ((String) -> Unit)? = null
    @Volatile var imeCommit: ((String, Boolean) -> Unit)? = null
    @Volatile var imeDelete: ((Int) -> Unit)? = null
    @Volatile var imeEnter: (() -> Unit)? = null
    @Volatile var focus: ((Boolean) -> Unit)? = null
    @Volatile var calibrate: (() -> Unit)? = null

    /** Реальный размер экрана в текущей ориентации (ширина, высота). */
    @Suppress("DEPRECATION")
    fun real(c: Context): IntArray {
        val dm = DisplayMetrics()
        (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    fun isLand(c: Context): Boolean { val r = real(c); return r[0] > r[1] }

    fun cropFor(c: Context): FloatArray = if (isLand(c)) crop else cropP

    fun tapKey(c: Context): String = if (isLand(c)) "tap_L" else "tap_P"

    /** Точки автооткрытия чата в пикселях: кнопка чата (x,y) и поле ввода (x,y) для текущей ориентации. */
    fun tapPoints(c: Context): FloatArray? {
        val v = c.getSharedPreferences("gm", Context.MODE_PRIVATE).getString(tapKey(c), null)
            ?.split(",")?.mapNotNull { it.trim().toFloatOrNull() } ?: return null
        if (v.size != 4) return null
        val r = real(c)
        return floatArrayOf(v[0] * r[0], v[1] * r[1], v[2] * r[0], v[3] * r[1])
    }

    fun parseCrop(s: String?): FloatArray? =
        s?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == 4 && it[2] > it[0] && it[3] > it[1] }?.toFloatArray()
}
