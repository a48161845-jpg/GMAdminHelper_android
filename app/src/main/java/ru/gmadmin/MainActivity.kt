package ru.gmadmin

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var ocr: TextView
    private lateinit var crop: EditText
    private lateinit var cropP: EditText
    private lateinit var lvl: EditText
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val sp = getSharedPreferences("gm", MODE_PRIVATE)
        Store.lvl = sp.getInt("lvl", 0); Store.invert = sp.getBoolean("invert", true)
        Store.parseCrop(sp.getString("crop", null))?.let { Store.crop = it }
        Store.parseCrop(sp.getString("cropP", null))?.let { Store.cropP = it }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(28)); setBackgroundColor(0xFF0E0E13.toInt())
        }
        col.addView(TextView(this).apply {
            text = "🛡  GM Admin Helper  4.0"; textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF5B2DE0.toInt(), 0xFFC04BFF.toInt())).apply { cornerRadius = dp(18).toFloat() }
        })
        status = TextView(this).apply { textSize = 13f; setTextColor(0xFFB8B8C8.toInt()); setPadding(dp(4), dp(12), dp(4), dp(4)); setLineSpacing(0f, 1.25f) }.also { col.addView(it) }

        fun section(t: String) = col.addView(TextView(this).apply {
            text = t.uppercase(); setTextColor(0xFF9B7BFF.toInt()); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(20), 0, dp(4))
        })
        fun btn(t: String, primary: Boolean = false, f: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f; setOnClickListener { f() }
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (primary) 0xFF6C3DF4.toInt() else 0xFF22222E.toInt()) }
        }.also { col.addView(it, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) }) }
        fun label(t: String) = col.addView(TextView(this).apply { text = t; setTextColor(0xFF9A9AA8.toInt()); textSize = 12f; setPadding(dp(4), dp(12), 0, dp(2)) })
        fun field(v: String) = EditText(this).apply {
            setText(v); setTextColor(Color.WHITE); textSize = 14f
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF1A1A23.toInt()) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }.also { col.addView(it) }

        section("Первый запуск")
        btn("1. Разрешить «Поверх других приложений»") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        btn("2. Включить клавиатуру GM Admin") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        btn("3. Выбрать клавиатуру (перед открытием чата)") { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        btn("4. Служба доступности «GM Admin — тапы»") {
            Toast.makeText(this, "Найди «GM Admin — тапы» и включи. Она только делает тапы по чату.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        section("Запуск")
        btn("Запустить панель 🛡", true) {
            if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "Сначала шаг 1", Toast.LENGTH_SHORT).show(); return@btn }
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            startForegroundService(Intent(this, OverlayService::class.java))
        }
        btn("Ловец репортов и весь чат (запись экрана)", true) {
            startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 77)
        }
        btn("Настроить автооткрытие чата") {
            if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "Сначала шаг 1", Toast.LENGTH_SHORT).show(); return@btn }
            if (TapService.inst == null) { Toast.makeText(this, "Сначала включи службу доступности (шаг 4)", Toast.LENGTH_LONG).show(); return@btn }
            startForegroundService(Intent(this, OverlayService::class.java).setAction("calib"))
            Toast.makeText(this, "Открой игру (чат закрыт) — появятся подсказки", Toast.LENGTH_LONG).show()
        }
        btn("Остановить всё") { stopService(Intent(this, OverlayService::class.java)); stopService(Intent(this, CaptureService::class.java)) }

        section("Настройки")
        label("Мой уровень админки (1–4): реагирую только на формы, на которые есть права")
        lvl = field(Store.lvl.toString().takeIf { it != "0" } ?: "3")
        label("Область чата на экране, АЛЬБОМ (левый, верхний, правый, нижний край — доли экрана)")
        crop = field(Store.crop.joinToString(","))
        label("Область чата на экране, ПОРТРЕТ")
        cropP = field(Store.cropP.joinToString(","))
        val inv = CheckBox(this).apply { text = "Инвертировать картинку (лучше для светлого текста)"; isChecked = Store.invert; setTextColor(Color.WHITE) }.also { col.addView(it) }
        btn("Сохранить настройки", true) {
            Store.parseCrop(crop.text.toString())?.let { Store.crop = it }
            Store.parseCrop(cropP.text.toString())?.let { Store.cropP = it }
            Store.lvl = (lvl.text.toString().toIntOrNull() ?: 3).coerceIn(1, 4); Store.invert = inv.isChecked
            sp.edit().putString("crop", Store.crop.joinToString(",")).putString("cropP", Store.cropP.joinToString(","))
                .putInt("lvl", Store.lvl).putBoolean("invert", Store.invert).apply()
            Toast.makeText(this, "Сохранено (перезапусти ловец репортов)", Toast.LENGTH_SHORT).show()
        }

        section("Диагностика")
        btn("Показать, что видит распознавание") { ocr.text = Store.lastOcr.ifBlank { "(пока пусто)" } }
        btn("Скопировать файл логов чата") {
            val f = File(filesDir, "chatlog.txt")
            val t = if (f.exists()) f.readText().takeLast(200000) else ""
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", t))
            Toast.makeText(this, "Скопировано: " + t.length + " символов", Toast.LENGTH_SHORT).show()
        }
        ocr = TextView(this).apply { textSize = 12f; setTextColor(0xFFB8B8C8.toInt()); setPadding(dp(4), dp(10), dp(4), 0) }.also { col.addView(it) }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val ov = Settings.canDrawOverlays(this)
        val tap = TapService.inst != null
        val pts = Store.tapPoints(this) != null
        status.text = (if (ov) "✓ Поверх окон разрешено" else "✗ Нужно разрешение «Поверх других приложений»") + "\n" +
            (if (tap) "✓ Служба тапов включена" else "• Служба тапов выключена (нужна для автооткрытия чата)") + "\n" +
            (if (pts) "✓ Точки чата заданы для этой ориентации" else "• Точки чата не заданы для этой ориентации")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 77 && res == RESULT_OK && data != null)
            startForegroundService(Intent(this, CaptureService::class.java).putExtra("code", res).putExtra("data", data))
    }
}
