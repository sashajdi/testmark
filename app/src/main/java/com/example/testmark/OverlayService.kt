package com.example.testmark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

data class Mark(val r: RectF, val letter: String)

class MarksView(c: Context) : View(c) {
    private var marks: List<Mark> = emptyList()
    private val fill = Paint().apply { color = Color.argb(60, 22, 163, 74) }
    private val line = Paint().apply {
        color = Color.rgb(22, 163, 74); strokeWidth = 6f; isAntiAlias = true
    }
    private val txt = Paint().apply {
        color = Color.rgb(22, 163, 74); textSize = 44f; isFakeBoldText = true; isAntiAlias = true
    }

    fun setMarks(m: List<Mark>) {
        marks = m
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        for (m in marks) {
            c.drawRect(m.r, fill)
            c.drawLine(m.r.left, m.r.bottom, m.r.right, m.r.bottom, line)
            c.drawText(m.letter, m.r.right + 12f, m.r.bottom, txt)
        }
    }
}

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var btn: TextView
    private lateinit var marksView: MarksView
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private val main = Handler(Looper.getMainLooper())
    private var sw = 0
    private var sh = 0
    private var busy = false
    private val clearMarks = Runnable { marksView.setMarks(emptyList()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("data")
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("c", "TestMark", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "c")
            .setContentTitle("TestMark работает")
            .setContentText("Долгое нажатие на кнопку выключает приложение")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, main)
        projection = p

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels
        sh = dm.heightPixels

        val rd = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        reader = rd
        vd = p.createVirtualDisplay(
            "tm", sw, sh, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null
        )

        setupOverlay()
        return START_NOT_STICKY
    }

    private fun setupOverlay() {
        val d = resources.displayMetrics.density
        val size = (52 * d).toInt()
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        marksView = MarksView(this)
        val mlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            mlp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(marksView, mlp)

        btn = TextView(this).apply {
            text = "✓"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 22f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(210, 22, 163, 74))
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 20
        lp.y = 400

        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = ox + dx
                        lp.y = oy + dy
                        wm.updateViewLayout(btn, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (e.eventTime - e.downTime > 700) stopSelf() else onTap()
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(btn, lp)
    }

    private fun toast(t: String) {
        main.post { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }
    }

    private fun setBtn(t: String) {
        main.post { btn.text = t }
    }

    private fun onTap() {
        if (busy) return
        busy = true
        main.removeCallbacks(clearMarks)
        marksView.setMarks(emptyList())
        btn.visibility = View.INVISIBLE
        Thread {
            Thread.sleep(350)
            var bmp: Bitmap? = null
            repeat(6) {
                if (bmp == null) {
                    bmp = capture()
                    if (bmp == null) Thread.sleep(150)
                }
            }
            main.post { btn.visibility = View.VISIBLE }
            val b = bmp
            if (b == null) {
                toast("Не удалось сделать снимок, попробуй ещё раз")
                busy = false
            } else {
                setBtn("…")
                ask(b)
            }
        }.start()
    }

    private fun capture(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val p = img.planes[0]
            val rowPad = p.rowStride - p.pixelStride * sw
            val full = Bitmap.createBitmap(sw + rowPad / p.pixelStride, sh, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            return Bitmap.createBitmap(full, 0, 0, sw, sh)
        } finally {
            img.close()
        }
    }

    private fun ask(bmp: Bitmap) {
        try {
            val prefs = getSharedPreferences("s", Context.MODE_PRIVATE)
            val key = prefs.getString("key", "") ?: ""
            val model = prefs.getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"

            val scale = 1280f / maxOf(bmp.width, bmp.height)
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            } else bmp
            val bos = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

            val prompt = "На скриншоте тест с вопросами и вариантами ответа. " +
                "Для каждого видимого вопроса выбери правильный вариант. " +
                "Верни JSON-массив объектов вида " +
                "{\"q\": номер вопроса, \"letter\": буква или цифра варианта, " +
                "\"reason\": не более 6 слов, " +
                "\"box_2d\": [ymin, xmin, ymax, xmax] строки выбранного варианта в координатах 0-1000}. " +
                "Если не уверен в вопросе, пропусти его."

            val parts = JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                    )
                )
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream.bufferedReader().readText()
            if (code !in 200..299) {
                toast("Ошибка $code. Проверь ключ и название модели")
                return
            }

            val text = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            val arr = JSONArray(text)
            val marks = ArrayList<Mark>()
            val notes = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = o.optJSONArray("box_2d") ?: continue
                val r = RectF(
                    (b.getDouble(1) / 1000.0 * sw).toFloat(),
                    (b.getDouble(0) / 1000.0 * sh).toFloat(),
                    (b.getDouble(3) / 1000.0 * sw).toFloat(),
                    (b.getDouble(2) / 1000.0 * sh).toFloat()
                )
                val letter = o.optString("letter", "?")
                marks.add(Mark(r, letter))
                notes.append(o.optString("q", "")).append(" → ").append(letter)
                    .append(": ").append(o.optString("reason", "")).append("\n")
            }
            main.post {
                marksView.setMarks(marks)
                main.postDelayed(clearMarks, 10000)
            }
            if (notes.isNotEmpty()) toast(notes.toString().trim()) else toast("Вопросов не найдено")
        } catch (e: Exception) {
            toast("Ошибка: ${e.message}")
        } finally {
            busy = false
            setBtn("✓")
        }
    }

    override fun onDestroy() {
        try { wm.removeView(btn) } catch (_: Exception) {}
        try { wm.removeView(marksView) } catch (_: Exception) {}
        vd?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
