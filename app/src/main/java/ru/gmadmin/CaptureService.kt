package ru.gmadmin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Читает область чата на экране (Tesseract, rus+eng), пишет логи, ловит репорты
 * («Nick[ID]: ... [К-во репорта: N]») и запросы команд в админ-чате (<ADM> ... /cmd ...).
 */
class CaptureService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var tess: TessBaseAPI? = null
    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var busy = false
    private var w = 0
    private var hh = 0
    private var dpi = 320
    private val seen = HashMap<String, Long>()
    private val recent = ArrayList<Pair<Set<String>, Long>>()
    private val tsFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val repRe = Regex("([A-Za-z][A-Za-z0-9_]{2,})\\s*[\\[(]\\s*(\\d{1,4})\\s*[\\])]\\s*:\\s*(.{0,160}?)\\s*\\[[^\\]]{3,22}:\\s*(\\d{1,3})\\s*\\]")
    private val tailRe = Regex("\\[[^\\]]{3,22}:\\s*\\d{1,3}\\s*\\]\\s*$")
    // метка админ-чата <ADM> (OCR может путать латиницу и кириллицу)
    private val admTag = Regex("[<(\\[]\\s*[AА]\\s*[DД]\\s*[MМ]\\s*[>)\\]]", RegexOption.IGNORE_CASE)
    // «Ник[ID]: текст»
    private val senderRe = Regex("([A-Za-z][A-Za-z0-9_]{2,})\\s*[\\[(]\\s*\\d{1,4}\\s*[\\])]\\s*:\\s*(.*)$")
    private val cmdRe = Regex("/([A-Za-z_]{2,12})(?:\\s+([^\\n]{0,100}))?")

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        @Suppress("DEPRECATION")
        val data = i?.getParcelableExtra<Intent>("data")
        val code = i?.getIntExtra("code", 0) ?: 0
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("gm2", "Ловец репортов", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "gm2").setContentTitle("Ловец репортов и логи чата включены")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(2, n)
        val sp = getSharedPreferences("gm", MODE_PRIVATE)
        Store.parseCrop(sp.getString("crop", null))?.let { Store.crop = it }
        Store.parseCrop(sp.getString("cropP", null))?.let { Store.cropP = it }
        Store.auto = sp.getBoolean("auto", Store.auto)
        Store.invert = sp.getBoolean("invert", true)
        if (Store.lvl == 0) Store.lvl = sp.getInt("lvl", 0)
        worker.execute { initTess() }
        mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        mp?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, h)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        w = dm.widthPixels; hh = dm.heightPixels
        dpi = dm.densityDpi
        reader = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 2)
        vd = mp?.createVirtualDisplay("gm", w, hh, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        h.post(loop)
        return START_NOT_STICKY
    }

    private fun initTess() {
        try {
            val dir = File(filesDir, "tessdata"); dir.mkdirs()
            for (l in listOf("rus", "eng")) {
                val f = File(dir, "$l.traineddata")
                if (!f.exists()) assets.open("tessdata/$l.traineddata").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            }
            val t = TessBaseAPI()
            if (t.init(filesDir.absolutePath, "rus+eng")) {
                t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK); tess = t
            }
        } catch (e: Exception) { Store.lastOcr = "Ошибка загрузки языков: " + e.message }
    }

    /** Экран повернули — подгоняем размер захвата, иначе распознавание читает мусор. */
    private fun checkSize() {
        val r = Store.real(this)
        if (r[0] == w && r[1] == hh) return
        val old = reader
        w = r[0]; hh = r[1]
        val nr = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 2)
        reader = nr
        try { vd?.resize(w, hh, dpi); vd?.setSurface(nr.surface) } catch (e: Exception) {}
        try { old?.close() } catch (e: Exception) {}
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        h.postDelayed({ checkSize() }, 300)
    }

    private val loop = object : Runnable { override fun run() { scan(); h.postDelayed(this, 2000) } }

    private fun prepare(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        // контраст c вокруг середины; при инверсии светлый текст с тёмной обводкой становится тёмным на светлом
        val c = 1.6f
        val k = if (Store.invert) -c else c
        val off = if (Store.invert) 255f + 128f * (c - 1f) else 128f * (1f - c)
        cm.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, off, 0f, k, 0f, 0f, off, 0f, 0f, k, 0f, off, 0f, 0f, 0f, 1f, 0f)))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    private fun scan() {
        if (busy || tess == null) return
        checkSize()
        val img = reader?.acquireLatestImage() ?: return
        val bmp: Bitmap = try {
            val p = img.planes[0]
            val pad = p.rowStride - p.pixelStride * w
            val full = Bitmap.createBitmap(w + pad / p.pixelStride, hh, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            val c = if (w > hh) Store.crop else Store.cropP
            val cx = (c[0] * w).toInt().coerceIn(0, w - 2); val cy = (c[1] * hh).toInt().coerceIn(0, hh - 2)
            val cw = ((c[2] - c[0]) * w).toInt().coerceIn(2, w - cx); val ch = ((c[3] - c[1]) * hh).toInt().coerceIn(2, hh - cy)
            val part = Bitmap.createBitmap(full, cx, cy, cw, ch)
            full.recycle()
            val big = Bitmap.createScaledBitmap(part, part.width * 2, part.height * 2, true)
            part.recycle(); big
        } catch (e: Exception) { return } finally { img.close() }
        busy = true
        worker.execute {
            try {
                val t = tess ?: return@execute
                val pre = prepare(bmp); t.setImage(pre)
                val text = t.getUTF8Text() ?: ""
                pre.recycle(); bmp.recycle()
                h.post { handle(text) }
            } catch (e: Exception) { } finally { busy = false }
        }
    }

    private fun grams(s: String): Set<String> {
        val n = s.lowercase().filter { it.isLetterOrDigit() }
        return if (n.length < 3) setOf(n) else (0..n.length - 3).map { n.substring(it, it + 3) }.toSet()
    }

    private fun isNewLine(line: String, now: Long): Boolean {
        val g = grams(line)
        recent.removeAll { now - it.second > 90000 }
        for ((s, _) in recent) {
            val inter = g.count { it in s }; val uni = g.size + s.size - inter
            if (uni > 0 && inter.toFloat() / uni >= 0.72f) return false
        }
        recent.add(g to now); return true
    }

    private fun fresh(key: String, now: Long): Boolean {
        val last = seen[key] ?: 0L; seen[key] = now; return now - last > 15000
    }

    private fun log(kind: String, line: String) {
        val e = kind + "\t" + tsFmt.format(Date()) + "\t" + line
        Store.logs.add(e); while (Store.logs.size > 3000) Store.logs.removeAt(0)
        try { File(filesDir, "chatlog.txt").appendText(e + "\n") } catch (x: Exception) {}
        Store.onLog?.invoke(e)
    }

    private fun handle(text: String) {
        Store.lastOcr = text
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value > 300000 }
        // репорты (текст мог быть разорван на строки)
        val one = text.replace("\n", " ")
        for (m in repRe.findAll(one)) {
            val before = one.substring(maxOf(0, m.range.first - 14), m.range.first)
            if (before.contains("ADM", true)) continue
            val name = m.groupValues[1]; val id = m.groupValues[2]
            val msg = m.groupValues[3].trim().take(120); val n = m.groupValues[4]
            if (!fresh("r$name$id$n", now)) continue
            val line = "$name[$id]: $msg [К-во репорта: $n]"
            Store.rep = id; Store.tgt = ""
            Store.queue.add(line); while (Store.queue.size > 20) Store.queue.removeAt(0)
            Store.onReport?.invoke(line)
        }
        // построчно: весь чат в лог + формы (команды /jail /sp /offjail …) из админ-чата
        for (raw in text.split("\n")) {
            val line = raw.trim(); if (line.length < 6) continue
            val isAdm = admTag.containsMatchIn(line)
            val kind = when { isAdm -> "adm"; tailRe.containsMatchIn(line) -> "rep"; else -> "chat" }
            if (isNewLine(line, now)) log(kind, line)
            val sm = senderRe.find(line) ?: continue
            if (!isAdm && tailRe.containsMatchIn(line)) continue            // это репорт, не форма
            val sender = sm.groupValues[1]; val body = sm.groupValues[2].trim()
            if (!isAdm && !body.startsWith("/")) continue                    // вне админ-чата — только строки, начинающиеся с команды
            val c = cmdRe.find(body) ?: continue
            val need = Store.lv[c.groupValues[1].lowercase()] ?: continue
            if (Store.lvl < need) continue                                    // реагируем только на команды, на которые есть права
            val cmd = ("/" + c.groupValues[1] + " " + c.groupValues[2]).trim()
            if (!fresh("c$sender$cmd", now)) continue
            val item = sender + "\t" + cmd
            if (!Store.forms.contains(item)) { Store.forms.add(item); while (Store.forms.size > 30) Store.forms.removeAt(0) }
            Store.onAdminCmd?.invoke(sender, cmd)
        }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        vd?.release(); reader?.close(); mp?.stop(); worker.shutdown()
        try { tess?.recycle() } catch (e: Exception) {}
        super.onDestroy()
    }
}
