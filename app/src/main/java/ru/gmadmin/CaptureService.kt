package ru.gmadmin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.media.MediaRecorder
import android.widget.Toast
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Читает выбранную область экрана (Tesseract) и ловит только две вещи: репорты («Nick[ID]: ... [К-во репорта: N]»)
 * и запросы команд в админ-чате (<ADM> ... /cmd ...). Ничего не журналирует.
 * Каждый кадр дёшево сканируются только цветные (оранжевые) строки — для них сразу запускается распознавание, поэтому
 * новый репорт или форма появляются в окне почти мгновенно. Раз в Store.fb секунд читаются все яркие строки области —
 * на случай другого цвета текста.
 */
class CaptureService : Service() {
    companion object { @Volatile var inst: CaptureService? = null }
    @Volatile var ocrOn = true
    @Volatile private var oneshot = false
    @Volatile var recording = false
    private var recStart = 0L
    private var rec: MediaRecorder? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recUri: Uri? = null
    private var fw = 0
    private var fh = 0
    private var dpi = 0
    private var shotCb: ((Uri?) -> Unit)? = null
    val ready: Boolean get() = vd != null
    fun recSeconds(): Int = if (recording) ((System.currentTimeMillis() - recStart) / 1000).toInt() else 0
    private val h = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var tess: TessBaseAPI? = null      // рус+англ: текст
    private var tessEn: TessBaseAPI? = null    // только англ: ники и ID
    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var busy = false
    private var lastSig: IntArray? = null
    @Volatile private var lastOcrAt = 0L
    private var sw = 0          // размеры записи (с учётом качества)
    private var sh = 0
    private var sc = 1f
    private var lastFull = 0L
    private var lastWhite = 0L
    private val sigs = HashMap<Long, Long>()   // подписи уже прочитанных цветных строк
    private val seen get() = Catch.seen

    private class Job(val bmp: Bitmap, val sig: Long)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        return try { doStart(i) } catch (e: Throwable) {
            Diag.log(this, "запуск записи экрана: " + Log.getStackTraceString(e))
            try { Toast.makeText(this, "Запись экрана не запустилась: " + e.message, Toast.LENGTH_LONG).show() } catch (x: Throwable) {}
            stopSelf(); START_NOT_STICKY
        }
    }

    private fun doStart(i: Intent?): Int {
        @Suppress("DEPRECATION")
        val data = i?.getParcelableExtra<Intent>("data")
        val code = i?.getIntExtra("code", 0) ?: 0
        if (data == null) { if (mp == null) stopSelf(); return START_NOT_STICKY }
        if (mp != null) return START_NOT_STICKY            // уже работает
        inst = this
        ocrOn = i?.getBooleanExtra("ocr", true) ?: true
        oneshot = i?.getBooleanExtra("oneshot", false) ?: false     // скриншот/запись из значка: после дела сервис выключается сам
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("gm2", "Ловец репортов", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "gm2").setContentTitle("Ловец репортов включён")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(2, n)
        Cfg.apply(this)
        Store.ocrState = "запускается…"
        if (ocrOn) worker.execute { initTess() } else Store.ocrState = "выключено"
        mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        mp?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { Diag.log(this@CaptureService, "запись экрана остановлена системой (иконка записи, блокировка экрана или смена разрешения)"); stopSelf() } }, h)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        fw = dm.widthPixels / 2 * 2; fh = dm.heightPixels / 2 * 2; dpi = dm.densityDpi
        sc = Store.cscale / 100f
        sw = maxOf(240, (dm.widthPixels * sc).toInt() / 2 * 2)
        sh = maxOf(240, (dm.heightPixels * sc).toInt() / 2 * 2)
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        vd = mp?.createVirtualDisplay("gm", sw, sh, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        h.post(loop)
        val then = i?.getStringExtra("then") ?: ""
        if (then == "shot" || then == "rec") h.postDelayed({ if (then == "shot") shootWithHide() else startRec() }, 700)
        return START_NOT_STICKY
    }

    // ---------- ловец вкл/выкл ----------
    fun setOcr(on: Boolean) {
        ocrOn = on
        if (on && tess == null) { Store.ocrState = "запускается…"; worker.execute { initTess() } }
        else Store.ocrState = if (on) "работает (" + Store.lang + ")" else "выключено"
        Toast.makeText(this, if (on) "Ловец репортов включён" else "Ловец репортов выключен", Toast.LENGTH_SHORT).show()
    }

    // ---------- скриншот ----------
    /** Прячет значок, ждёт кадр и сохраняет PNG в Pictures/GMAdmin. */
    fun shootWithHide() {
        Store.hideOverlay?.invoke(true)
        h.postDelayed({
            screenshot { u ->
                Store.hideOverlay?.invoke(false)
                Toast.makeText(this, if (u != null) "Скриншот сохранён (Pictures/GMAdmin)" else "Не удалось сделать скриншот", Toast.LENGTH_SHORT).show()
                if (oneshot) h.postDelayed({ stopSelf() }, 600)
            }
        }, 350)
    }

    private fun screenshot(cb: (Uri?) -> Unit) {
        if (recording) { cb(null); return }
        shotCb = cb
        h.postDelayed({ if (shotCb === cb) { shotCb = null; cb(null) } }, 4000)
    }

    private fun grabShot(cb: (Uri?) -> Unit) {
        val img = reader?.acquireLatestImage() ?: return        // ждём следующий кадр
        shotCb = null
        var bmp: Bitmap? = null
        try {
            val p = img.planes[0]
            val wide = Bitmap.createBitmap(p.rowStride / p.pixelStride, sh, Bitmap.Config.ARGB_8888)
            wide.copyPixelsFromBuffer(p.buffer)
            bmp = if (wide.width != sw) Bitmap.createBitmap(wide, 0, 0, sw, sh) else wide
        } catch (e: Exception) { Diag.log(this, "скриншот: " + e.message) } finally { img.close() }
        val b = bmp
        if (b == null) { cb(null); return }
        worker.execute { val u = saveShot(b); h.post { cb(u) } }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun saveShot(b: Bitmap): Uri? {
        val name = "GM_" + stamp() + ".png"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name)
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GMAdmin")
                val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                if (u != null) contentResolver.openOutputStream(u)?.use { o -> b.compress(Bitmap.CompressFormat.PNG, 100, o) }
                u
            } else {
                val f = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), name)
                f.outputStream().use { o -> b.compress(Bitmap.CompressFormat.PNG, 100, o) }
                Uri.fromFile(f)
            }
        } catch (e: Exception) { Diag.log(this, "сохранение скриншота: " + e.message); null }
    }

    // ---------- запись экрана ----------
    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    /** Попытки записи от лучшего качества к самому щадящему: (макс. сторона, битрейт, кадров/с). Ошибка prepare -12 — кодек не тянет размер/битрейт. */
    private val recTries = listOf(Triple(100000, 8_000_000, 30), Triple(1920, 6_000_000, 30), Triple(1280, 4_000_000, 30), Triple(960, 2_500_000, 24), Triple(720, 1_500_000, 24))

    private fun buildRecorder(w: Int, hh: Int, br: Int, fps: Int, file: File?, uri: Uri?): MediaRecorder {
        val r = newRecorder()
        try {
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(w, hh); r.setVideoFrameRate(fps); r.setVideoEncodingBitRate(br)
            if (file != null) r.setOutputFile(file.absolutePath)
            else {
                try { recPfd?.close() } catch (e: Exception) {}
                recPfd = contentResolver.openFileDescriptor(uri!!, "w")
                r.setOutputFile(recPfd!!.fileDescriptor)
            }
            r.prepare()
            return r
        } catch (e: Exception) { try { r.release() } catch (x: Exception) {}; throw e }
    }

    /** Запись идёт в тот же виртуальный экран; пока она включена, ловец репортов на паузе. */
    fun startRec() {
        if (recording) return
        val v = vd ?: return
        val name = "GM_" + stamp() + ".mp4"
        var uri: Uri? = null
        var file: File? = null
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues()
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, name)
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GMAdmin")
                uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("нет места для файла")
            } else {
                file = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), name)
            }
        } catch (e: Exception) {
            Diag.log(this, "старт записи (файл): " + e.message)
            Toast.makeText(this, "Не удалось начать запись: " + e.message, Toast.LENGTH_LONG).show()
            return
        }
        recUri = uri ?: Uri.fromFile(file)
        val longSide = maxOf(fw, fh)
        var r: MediaRecorder? = null
        var rw = fw; var rh = fh
        for ((maxSide, br, fps) in recTries) {
            val k = if (longSide > maxSide) maxSide.toFloat() / longSide else 1f
            val w = maxOf(16, ((fw * k).toInt() / 16) * 16)     // кратно 16 — так кодеки принимают размер надёжнее
            val hh = maxOf(16, ((fh * k).toInt() / 16) * 16)
            try { r = buildRecorder(w, hh, br, fps, file, uri); rw = w; rh = hh; break }
            catch (e: Exception) { Diag.log(this, "старт записи " + w + "x" + hh + ": " + e.message) }
        }
        if (r == null) {
            try { if (uri != null) contentResolver.delete(uri, null, null) } catch (e: Exception) {}
            try { recPfd?.close() } catch (e: Exception) {}
            recPfd = null
            Toast.makeText(this, "Телефон не смог начать запись экрана (кодек не принял настройки)", Toast.LENGTH_LONG).show()
            return
        }
        try {
            rec = r
            v.resize(rw, rh, dpi)
            v.surface = r.surface
            r.start()
            recording = true; recStart = System.currentTimeMillis()
            Toast.makeText(this, "Запись началась", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Diag.log(this, "старт записи: " + e.message)
            cleanupRec()
            try { if (uri != null) contentResolver.delete(uri, null, null) } catch (x: Exception) {}
            Toast.makeText(this, "Не удалось начать запись: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun cleanupRec() {
        try { rec?.release() } catch (e: Exception) {}
        rec = null
        try { recPfd?.close() } catch (e: Exception) {}
        recPfd = null
        recording = false
        try { vd?.surface = reader?.surface; vd?.resize(sw, sh, dpi) } catch (e: Exception) {}
    }

    fun stopRec() {
        if (!recording) return
        recording = false
        try { rec?.stop() } catch (e: Exception) { Diag.log(this, "стоп записи: " + e.message) }
        cleanupRec()
        Toast.makeText(this, "Запись сохранена (Movies/GMAdmin)", Toast.LENGTH_LONG).show()
        if (oneshot) h.postDelayed({ stopSelf() }, 600)
    }

    private fun initTess() {
        try {
            val dir = File(filesDir, "tessdata"); dir.mkdirs()
            val langs = Store.lang.split("+").map { it.trim() }.filter { it.isNotEmpty() }
            for (l in langs) {
                val f = File(dir, "$l.traineddata")
                if (!f.exists()) assets.open("tessdata/$l.traineddata").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            }
            val e = File(dir, "eng.traineddata")
            if (!e.exists()) assets.open("tessdata/eng.traineddata").use { i -> e.outputStream().use { o -> i.copyTo(o) } }
            val te = TessBaseAPI()
            if (te.init(filesDir.absolutePath, "eng")) { te.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK); tessEn = te }
            val t = TessBaseAPI()
            if (t.init(filesDir.absolutePath, Store.lang)) {
                t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK); tess = t
                Store.ocrState = "работает (" + Store.lang + ")"
                Store.lastOcr = "Распознавание готово, жду текст из чата…"
            } else {
                Store.ocrState = "не запустилось"
                Store.lastOcr = "Не удалось запустить распознавание (" + Store.lang + ")"
            }
        } catch (e: Exception) {
            Store.ocrState = "нет файлов языков"
            Store.lastOcr = "Нет файлов языков в приложении: " + e.message
        }
    }

    private val loop = object : Runnable { override fun run() { scan(); h.postDelayed(this, Store.scan.toLong()) } }

    /** Оранжевый/красный текст — цвет репортов и админ-чата. Обои, небо, обводка букв и серое время под это не подходят. */
    private fun warm(r: Int, g: Int, b: Int) = r >= 190 && r - b >= 100 && g in 55..205

    /** Любой яркий текст (белый, кремовый, зелёный): только для страховочного прохода — вдруг у репортов другой цвет. */
    private fun bright(r: Int, g: Int, b: Int) = maxOf(r, g, b) >= 205 || (g >= 130 && g - r >= 50 && g - b >= 50)

    /** Светлый малонасыщенный пиксель: серо-бежевый текст обычного чата игроков (только страховочный проход). */
    private fun grayText(r: Int, g: Int, b: Int): Boolean { val mx = maxOf(r, g, b); val mi = minOf(r, g, b); return mi >= 140 && mx - mi <= 48 }

    private fun scan() {
        val cb = shotCb
        if (cb != null) { grabShot(cb); return }
        if (!ocrOn || recording || busy || tess == null) return
        val img = reader?.acquireLatestImage() ?: return
        var jobs: List<Job> = emptyList()
        try {
            val p = img.planes[0]
            val c = Store.crop
            val x0 = (c[0] * sw).toInt().coerceIn(0, sw - 4)
            val y0 = (c[1] * sh).toInt().coerceIn(0, sh - 4)
            val x1 = (c[2] * sw).toInt().coerceIn(x0 + 4, sw)
            val y1 = (c[3] * sh).toInt().coerceIn(y0 + 4, sh)
            val now = System.currentTimeMillis()
            var all = false
            if (Store.fb > 0 && now - lastFull > Store.fb * 1000L) { lastFull = now; sigs.clear(); all = true }     // страховка: перечитать всё яркое
            jobs = lineJobs(p.buffer, p.rowStride, p.pixelStride, x0, y0, x1, y1, now, all)
        } catch (e: Throwable) { Store.ocrErr = "кадр: " + e.javaClass.simpleName + " " + e.message; return } finally { img.close() }
        if (jobs.isEmpty()) { Store.ocrSkips++; return }
        busy = true
        val list = jobs
        worker.execute {
            try {
                val t = tess ?: run { for (j in list) j.bmp.recycle(); return@execute }
                val te = tessEn
                val t0 = System.currentTimeMillis()
                val dbg = StringBuilder()
                val all = StringBuilder()
                for ((idx, j) in list.withIndex()) {
                    if (System.currentTimeMillis() - t0 > 2400) {          // не копим задержку: недочитанное вернётся на следующем кадре
                        for (k in idx until list.size) { sigs.remove(list[k].sig); list[k].bmp.recycle() }
                        break
                    }
                    try {
                        t.setImage(j.bmp)
                        val ru = t.getUTF8Text() ?: ""
                        val conf = t.meanConfidence()
                        var en = ""
                        if (te != null && (conf < 75 || Ocr.needEn(ru))) { te.setImage(j.bmp); en = te.getUTF8Text() ?: "" }
                        val r = Ocr.merge(ru, en)
                        dbg.append("уверенность ").append(conf).append("%, ").append(r.note).append("\n")
                            .append("  рус+англ: ").append(ru.trim().replace("\n", " ⏎ ")).append("\n")
                            .append("  англ:     ").append(if (en.isEmpty()) "(не нужен)" else en.trim().replace("\n", " ⏎ ")).append("\n")
                            .append("  итог:     ").append(r.text.replace("\n", " ⏎ ")).append("\n")
                        all.append(r.text).append("\n")
                    } catch (e: Throwable) {
                        dbg.append("ошибка: ").append(e.javaClass.simpleName).append(" ").append(e.message).append("\n")
                        Store.ocrErr = e.javaClass.simpleName + ": " + e.message
                        sigs.remove(j.sig)
                    } finally { j.bmp.recycle() }
                }
                lastOcrAt = System.currentTimeMillis()
                Store.ocrRuns += list.size
                Store.lastOcr = dbg.toString().trim().take(4000)
                val text = all.toString()
                h.post { handle(text) }      // весь кадр разом: перенесённые строки склеиваются со своим репортом
            } catch (e: Throwable) { Store.ocrErr = e.javaClass.simpleName + ": " + e.message } finally { busy = false }
        }
    }

    /**
     * Маска чернил по области чтения → полосы строк → для каждой новой полосы чёрно-белая картинка (чёрный текст на белом, с рамкой).
     * Обычный кадр: только оранжевые строки. Страховочный (all): ещё и белые/светлые.
     * Полоса «новая», если профиль её чернил по колонкам не встречался последние 40 с (не зависит от высоты: чат прокручивается).
     */
    private fun lineJobs(buf: ByteBuffer, rs: Int, ps: Int, x0: Int, y0: Int, x1: Int, y1: Int, now: Long, all: Boolean): List<Job> {
        val cw = x1 - x0
        val ch = y1 - y0
        val m = ByteArray(cw * ch)
        val dk = if (all) ByteArray(cw * ch) else null        // чёрная обводка букв
        val gr = if (all) ByteArray(cw * ch) else null        // светлый серо-бежевый пиксель: текст, только если рядом обводка
        val hit = IntArray(ch)
        for (y in 0 until ch) {
            var o = (y0 + y) * rs + x0 * ps
            var n = 0
            val row = y * cw
            for (x in 0 until cw) {
                val r = buf.get(o).toInt() and 255
                val g = buf.get(o + 1).toInt() and 255
                val b = buf.get(o + 2).toInt() and 255
                if (warm(r, g, b)) { m[row + x] = 1; n++ }
                else if (all) {
                    if (bright(r, g, b)) { m[row + x] = 1; n++ }
                    else if (maxOf(r, g, b) <= 50) dk!![row + x] = 1
                    else if (grayText(r, g, b)) gr!![row + x] = 1
                }
                o += ps
            }
            hit[y] = n
        }
        if (all && dk != null && gr != null) for (y in 0 until ch) {
            val row = y * cw
            var n = 0
            for (x in 0 until cw) {
                if (gr[row + x].toInt() != 1) continue
                var d = false
                for (k in 1..2) {
                    if ((x - k >= 0 && dk[row + x - k].toInt() == 1) || (x + k < cw && dk[row + x + k].toInt() == 1) ||
                        (y - k >= 0 && dk[row - k * cw + x].toInt() == 1) || (y + k < ch && dk[row + k * cw + x].toInt() == 1)) { d = true; break }
                }
                if (d) { m[row + x] = 1; n++ }
            }
            hit[y] += n
        }
        val need = maxOf(4, cw / 150)
        val minH = maxOf(6, (8 * sc).toInt())
        val maxGap = maxOf(2, (3 * sc).toInt())
        val bands = ArrayList<IntArray>()
        var s = -1
        var e = -1
        for (i in 0..ch) {
            val on = i < ch && hit[i] >= need
            if (on) { if (s < 0) s = i; e = i }
            else if (s >= 0 && (i >= ch || i - e > maxGap)) { if (e - s + 1 >= minH) bands.add(intArrayOf(s, e + 1)); s = -1 }
        }
        // подпись полосы: профиль чернил по колонкам + высота (не зависит от положения: чат прокручивается)
        fun sigOf(b: IntArray): Long {
            val cnt = IntArray(cw / 4 + 1)
            for (y in b[0] until b[1]) { val row = y * cw; var x = 0; while (x < cw) { if (m[row + x].toInt() == 1) cnt[x / 4]++; x += 2 } }
            var hs = -3750763034362895579L
            for (v in cnt) hs = (hs xor minOf(v, 63).toLong()) * 1099511628211L
            return (hs xor (b[1] - b[0]).toLong()) * 1099511628211L
        }
        sigs.entries.removeAll { now - it.value > 40000 }
        val out = ArrayList<Job>()
        for (b in bands) {
            if (out.size >= 6) break                // остальное — на следующем кадре
            val hs = sigOf(b)
            if (sigs.containsKey(hs)) continue
            sigs[hs] = now
            val top = maxOf(0, b[0] - 3)
            val bot = minOf(ch, b[1] + 3)
            val bh = bot - top
            val px = IntArray(cw * bh)
            for (y in 0 until bh) { val row = (top + y) * cw; for (x in 0 until cw) px[y * cw + x] = if (m[row + x].toInt() == 1) -16777216 else -1 }
            val small = Bitmap.createBitmap(px, cw, bh, Bitmap.Config.ARGB_8888)
            val f = (2.6f / sc).coerceIn(2f, 3.5f)
            val big = Bitmap.createScaledBitmap(small, (cw * f).toInt(), (bh * f).toInt(), true)
            small.recycle()
            val pad = 12
            val fin = Bitmap.createBitmap(big.width + 2 * pad, big.height + 2 * pad, Bitmap.Config.ARGB_8888)
            val cv = Canvas(fin); cv.drawColor(-1); cv.drawBitmap(big, pad.toFloat(), pad.toFloat(), null)
            big.recycle()
            out.add(Job(fin, hs))
        }
        return out
    }

    /** Начало новой записи чата: тег, «Ник[ID» или время. Остальное — перенос предыдущей строки. */
    private val startRe = Regex("^\\s*(?:[<(\\[\u2039]|[A-Za-z][A-Za-z0-9_ .]{1,30}\\s*[\\[({]\\s*\\d)")

    private fun process(r: String, now: Long) {
        if (Catch.adm(r, now)) return
        val m = Catch.repRe.find(r)
        if (m != null) {
            val name = m.groupValues[1]; val id = m.groupValues[2]
            val msg = m.groupValues[3].trim().take(160); val n = m.groupValues[4]
            if (Catch.isNew(name, id, msg, n, now)) Catch.addReport("$name[$id]: $msg [К-во репорта: $n]")
            return
        }
        if (Store.strict || !Catch.repHint.containsMatchIn(r)) return
        val l = Catch.looseRe.find(r) ?: return
        val name = l.groupValues[1]; val id = l.groupValues[2]
        val msg = l.groupValues[3].replace(Regex("\\s*[\\[({][^\\])}]{0,24}$"), "").trim().take(160)
        if (Catch.isNew(name, id, msg, null, now)) Catch.addReport("$name[$id]: $msg")
    }

    /**
     * Разбор прочитанного: только репорты и формы админ-чата. Записи с переносами склеиваются — и внутри кадра, и между кадрами
     * (длинный репорт может прочитаться по частям): недочитанное начало ждёт продолжения до 5 секунд, потом разбирается как есть.
     */
    private fun handle(text: String) {
        val now = System.currentTimeMillis()
        Catch.seen.entries.removeAll { now - it.value > 700000 }
        val lines = text.split("\n").map { Catch.clean(it) }.filter { it.length >= 4 }
        val rec = ArrayList<String>()
        for (l in lines) {
            if (rec.isNotEmpty() && !startRe.containsMatchIn(l)) rec[rec.size - 1] = rec.last() + " " + l else rec.add(l)
        }
        if (rec.isEmpty()) return
        for (r in rec) process(r, now)
    }

    override fun onDestroy() {
        if (recording) stopRec()
        inst = null
        h.removeCallbacksAndMessages(null)
        vd?.release(); reader?.close(); mp?.stop(); worker.shutdown()
        try { tess?.recycle() } catch (e: Exception) {}
        try { tessEn?.recycle() } catch (e: Exception) {}
        Store.ocrState = "остановлено"
        super.onDestroy()
    }
}
