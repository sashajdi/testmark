package com.example.testmark

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var stats: TextView

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
        fun label(t: String) = TextView(this).apply {
            text = t
            setPadding(0, pad / 2, 0, 0)
        }
        fun field(h: String, value: String, number: Boolean) = EditText(this).apply {
            hint = h
            setText(value)
            setSingleLine()
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
        }

        val title = TextView(this).apply {
            text = "TestMark"
            textSize = 26f
        }
        val info = TextView(this).apply {
            text = "Вставте ключ Gemini API й натисніть «Запустити». У вікні запису екрана " +
                "обов'язково виберіть «Весь екран». Зелена кнопка перевіряє тест, " +
                "синя «?» відповідає на ваше питання. Довге натискання вимикає кнопки."
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val keyField = field("Ключ Gemini API", prefs.getString("key", "") ?: "", false)
        val modelField = field("Модель", prefs.getString("model", "gemini-3.5-flash") ?: "gemini-3.5-flash", false)
        val sizeField = field("Розмір кнопки", prefs.getString("size", "52") ?: "52", true)
        val secField = field("Секунд показувати позначку", prefs.getString("sec", "10") ?: "10", true)
        val limitField = field("Денний ліміт запитів", prefs.getString("limit", "500") ?: "500", true)
        val start = Button(this).apply { text = "Запустити" }
        stats = TextView(this).apply { setPadding(0, pad, 0, 0) }

        start.setOnClickListener {
            prefs.edit()
                .putString("key", keyField.text.toString().trim())
                .putString("model", modelField.text.toString().trim().ifEmpty { "gemini-3.5-flash" })
                .putString("size", sizeField.text.toString().trim().ifEmpty { "52" })
                .putString("sec", secField.text.toString().trim().ifEmpty { "10" })
                .putString("limit", limitField.text.toString().trim().ifEmpty { "500" })
                .apply()
            refreshStats()
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Увімкніть «Поверх інших додатків» і поверніться", Toast.LENGTH_LONG).show()
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
        root.addView(label("Ключ"))
        root.addView(keyField)
        root.addView(label("Модель"))
        root.addView(modelField)
        root.addView(label("Розмір кнопки (від 36 до 100)"))
        root.addView(sizeField)
        root.addView(label("Скільки секунд показувати позначку"))
        root.addView(secField)
        root.addView(label("Денний ліміт запитів (для підрахунку залишку)"))
        root.addView(limitField)
        root.addView(start)
        root.addView(stats)
        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun refreshStats() {
        val p = getSharedPreferences("s", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val same = p.getString("day", "") == today
        val req = if (same) p.getInt("req", 0) else 0
        val tok = if (same) p.getLong("tok", 0L) else 0L
        val last = if (same) p.getLong("last", 0L) else 0L
        val limit = p.getString("limit", "500")?.toIntOrNull() ?: 500
        val left = (limit - req).coerceAtLeast(0)
        stats.text = "Сьогодні: $req запитів, $tok токенів.\n" +
            "Останній запит: $last токенів.\n" +
            "Орієнтовний залишок: $left з $limit запитів.\n" +
            "Точні ліміти дивіться на aistudio.google.com."
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

class AskActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val input = EditText(this).apply {
            hint = "Ваше питання або «питання 3»"
            minLines = 2
        }
        val send = Button(this).apply { text = "Запитати" }
        send.setOnClickListener {
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) {
                startService(Intent(this, OverlayService::class.java).putExtra("ask", q))
            }
            finish()
        }
        box.addView(input)
        box.addView(send)
        setContentView(box)
    }
}
