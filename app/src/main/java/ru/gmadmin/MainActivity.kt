package ru.gmadmin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast

/** Экран приложения: запуск, настройки, правка причин и команд — всё в одной веб-странице (assets/app.html). */
class MainActivity : Activity() {
    private var web: WebView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.apply(this)
        try { Backup.tick(this) } catch (e: Throwable) {}
        window.statusBarColor = 0xFF0B0E14.toInt()
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        // страница — только свои файлы из assets: наружу и к чужим файлам она не ходит
        w.settings.allowFileAccessFromFileURLs = false
        w.settings.allowUniversalAccessFromFileURLs = false
        w.settings.allowContentAccess = false
        w.settings.setGeolocationEnabled(false)
        w.setBackgroundColor(0xFF0B0E14.toInt())
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Diag.log(this@MainActivity, "JS приложения: " + m.message() + " (стр. " + m.lineNumber() + ")")
                return true
            }
        }
        w.addJavascriptInterface(Bridge(this, "app") { name -> handleAct(name) }, "Android")
        w.loadUrl("file:///android_asset/app.html")
        web = w
        setContentView(w)
    }

    private fun handleAct(name: String) {
        try { doAct(name) } catch (e: Throwable) {
            Diag.log(this, "действие " + name + ": " + e.message)
            Toast.makeText(this, "Не получилось: " + (e.message ?: name), Toast.LENGTH_LONG).show()
        }
    }

    private fun doAct(name: String) {
        when (name) {
            "overlayPerm" -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            "imeSettings" -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            "imePicker" -> (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            "startOverlay" -> {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Сначала разреши «Поверх других приложений»", Toast.LENGTH_SHORT).show()
                } else {
                    if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    startForegroundService(Intent(this, OverlayService::class.java))
                }
            }
            "pickCrop" -> {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Сначала разреши «Поверх других приложений»", Toast.LENGTH_SHORT).show()
                } else {
                    if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    startForegroundService(Intent(this, OverlayService::class.java).putExtra("pickCrop", true))
                    Toast.makeText(this, "Открой игру: рамка появится поверх неё", Toast.LENGTH_LONG).show()
                    moveTaskToBack(true)
                }
            }
            "pickLayout" -> {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Сначала разреши «Поверх других приложений»", Toast.LENGTH_SHORT).show()
                } else {
                    startForegroundService(Intent(this, OverlayService::class.java).putExtra("pickLayout", true))
                    Toast.makeText(this, "Открой игру: поверх неё появятся окна для перетаскивания", Toast.LENGTH_LONG).show()
                    moveTaskToBack(true)
                }
            }
            "startCapture" -> startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 77)
            "pickLog" -> {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
                pick.addCategory(Intent.CATEGORY_OPENABLE)
                pick.type = "*/*"
                pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                startActivityForResult(pick, 79)
            }
            "backupSave" -> {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE); i.type = "application/json"
                i.putExtra(Intent.EXTRA_TITLE, "gm-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".json")
                startActivityForResult(i, 81)
            }
            "backupLoad" -> {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE); i.type = "*/*"
                startActivityForResult(i, 82)
            }
            "backupDir" -> {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                startActivityForResult(i, 83)
            }
            "backupDirClear" -> { Cfg.set(this, "bkdir", ""); Cfg.set(this, "bkprev", ""); web?.evaluateJavascript("window.gmBk&&gmBk()", null) }
            "backupRun" -> Backup.run(this) { m -> runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); web?.evaluateJavascript("window.gmBk&&gmBk()", null) } }
            "stopOverlay" -> stopService(Intent(this, OverlayService::class.java))
            "stopCapture" -> stopService(Intent(this, CaptureService::class.java))
            "stopAll" -> { stopService(Intent(this, OverlayService::class.java)); stopService(Intent(this, CaptureService::class.java)) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 79 && res == RESULT_OK && data?.data != null) {
            val u = data.data!!
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { Diag.log(this, "доступ к папке: " + e.message) }
            LogWatcher.use(this, u.toString())
            Toast.makeText(this, "Лог чата выбран", Toast.LENGTH_SHORT).show()
        }
        if (req == 81 && res == RESULT_OK && data?.data != null) {
            try {
                contentResolver.openOutputStream(data.data!!)?.use { it.write(Backup.json(this).toByteArray()) }
                Toast.makeText(this, "Копия сохранена в файл", Toast.LENGTH_LONG).show()
            } catch (e: Throwable) { Diag.log(this, "сохранение копии: " + e.message); Toast.makeText(this, "Не получилось сохранить: " + e.message, Toast.LENGTH_LONG).show() }
        }
        if (req == 82 && res == RESULT_OK && data?.data != null) {
            var ok = false
            try {
                val t = contentResolver.openInputStream(data.data!!)?.bufferedReader()?.use { it.readText() } ?: ""
                ok = Backup.restore(this, t)
            } catch (e: Throwable) { Diag.log(this, "загрузка копии: " + e.message) }
            Toast.makeText(this, if (ok) "Данные восстановлены" else "Это не файл копии GM Admin Helper", Toast.LENGTH_LONG).show()
            web?.evaluateJavascript("window.gmRestored&&gmRestored(" + ok + ")", null)
        }
        if (req == 83 && res == RESULT_OK && data?.data != null) {
            val u = data.data!!
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { Diag.log(this, "папка копий: " + e.message) }
            Cfg.set(this, "bkdir", u.toString()); Cfg.set(this, "bkprev", "")
            Toast.makeText(this, "Папка для копий выбрана", Toast.LENGTH_SHORT).show()
            web?.evaluateJavascript("window.gmBk&&gmBk()", null)
        }
        if (req == 77 && res == RESULT_OK && data != null) {
            try { startForegroundService(Intent(this, CaptureService::class.java).putExtra("code", res).putExtra("data", data)) }
            catch (e: Throwable) { Diag.log(this, "старт ловца: " + e.message) }
        }
    }

    override fun onResume() {
        super.onResume()
        web?.evaluateJavascript("window.dispatchEvent(new Event('focus'))", null)
    }

    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }
}
