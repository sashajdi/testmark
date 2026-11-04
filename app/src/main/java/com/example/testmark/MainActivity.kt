package com.example.testmark

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("s", Context.MODE_PRIVATE)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val title = TextView(this).apply {
            text = "TestMark"
            textSize = 26f
        }
        val info = TextView(this).apply {
            text = "1. Вставь бесплатный ключ Gemini API (aistudio.google.com).\n" +
                "2. Нажми «Запустить» и дай разрешения.\n" +
                "3. Открой тест и нажми круглую кнопку. Долгое нажатие на кнопку выключает приложение."
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val keyField = EditText(this).apply {
            hint = "Ключ Gemini API"
            setText(prefs.getString("key", ""))
            setSingleLine()
        }
        val modelField = EditText(this).apply {
            hint = "Модель"
            setText(prefs.getString("model", "gemini-2.5-flash"))
            setSingleLine()
        }
        val start = Button(this).apply { text = "Запустить" }

        start.setOnClickListener {
            val model = modelField.text.toString().trim().ifEmpty { "gemini-2.5-flash" }
            prefs.edit()
                .putString("key", keyField.text.toString().trim())
                .putString("model", model)
                .apply()
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Включи «Поверх других приложений» и вернись", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                return@setOnClickListener
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 1)
        }

        root.addView(title)
        root.addView(info)
        root.addView(keyField)
        root.addView(modelField)
        root.addView(start)
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            finish()
        }
    }
}
