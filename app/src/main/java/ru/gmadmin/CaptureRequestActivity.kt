package ru.gmadmin

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Прозрачное окно: спрашивает разрешение на запись экрана и запускает CaptureService (из меню значка). */
class CaptureRequestActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
            startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 78)
        } catch (e: Exception) { Diag.log(this, "запрос записи экрана: " + e.message); finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 78 && res == RESULT_OK && data != null) {
            val then = intent.getStringExtra("then") ?: ""
            try {
                startForegroundService(Intent(this, CaptureService::class.java)
                    .putExtra("code", res).putExtra("data", data)
                    .putExtra("ocr", then == "ocr").putExtra("oneshot", then == "shot" || then == "rec").putExtra("then", then))
            } catch (e: Exception) { Diag.log(this, "старт записи экрана: " + e.message) }
        }
        finish()
    }
}
