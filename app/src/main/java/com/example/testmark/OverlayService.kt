package com.example.testmark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

class SelectView(c: Context, private val bmp: Bitmap, private val onDone: (Bitmap?) -> Unit) : View(c) {
    private var x0 = 0f
    private var y0 = 0f
    private var x1 = 0f
    private var y1 = 0f
    private var dragging = false
    private val scrim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val border = Paint().apply {
        color = Color.rgb(66, 133, 244); style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true
    }
    private val hint = Paint().apply {
        color = Color.WHITE; textSize = 42f; isAntiAlias = true; setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }
    private val dst = RectF()

    override fun onDraw(c: Canvas) {
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawBitmap(bmp, null, dst, null)
        c.drawRect(dst, scrim)
        if (dragging) {
            val l = minOf(x0, x1)
            val t = minOf(y0, y1)
            val r = maxOf(x0, x1)
            val b = maxOf(y0, y1)
            c.save()
            c.clipRect(l, t, r, b)
            c.drawBitmap(bmp, null, dst, null)
            c.restore()
            c.drawRect(l, t, r, b, border)
        } else {
            c.drawText("Обведіть завдання пальцем", 40f, 160f, hint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; y0 = e.y; x1 = e.x; y1 = e.y
                dragging = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                x1 = e.x; y1 = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                x1 = e.x; y1 = e.y
                dragging = false
                val l = minOf(x0, x1).coerceIn(0f, width.toFloat())
                val t = minOf(y0, y1).coerceIn(0f, height.toFloat())
                val r = maxOf(x0, x1).coerceIn(0f, width.toFloat())
                val b = maxOf(y0, y1).coerceIn(0f, height.toFloat())
                if (r - l < 40f || b - t < 40f) {
                    onDone(null)
                } else {
                    val kx = bmp.width / width.toFloat()
                    val ky = bmp.height / height.toFloat()
                    val x = (l * kx).toInt().coerceIn(0, bmp.width - 1)
                    val y = (t * ky).toInt().coerceIn(0, bmp.height - 1)
                    val w = ((r - l) * kx).toInt().coerceIn(1, bmp.width - x)
                    val h = ((b - t) * ky).toInt().coerceIn(1, bmp.height - y)
                    onDone(Bitmap.createBitmap(bmp, x, y, w, h))
                }
            }
            MotionEvent.ACTION_CANCEL -> onDone(null)
        }
        return true
    }
}

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var marksView: MarksView
    private lateinit var checkBtn: TextView
    private lateinit var askBtn: TextView
    private val buttons = ArrayList<TextView>()
    private var panel: View? = null
    private var selectView: View? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private val main = Handler(Looper.getMainLooper())
    private var sw = 0
    private var sh = 0
    private var busy = false
    private val clearMarks = Runnable { marksView.setMarks(emptyList()) }
    private val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

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
            .setContentTitle("TestMark працює")
            .setContentText("Довге натискання на кнопку вимикає застосунок")
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

    private fun fullScreenParams(touchable: Boolean): WindowManager.LayoutParams {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, f, PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    private fun setupOverlay() {
        val d = resources.displayMetrics.density
        val prefs = getSharedPreferences("s", Context.MODE_PRIVATE)
        val dp = (prefs.getString("size", "52")?.toIntOrNull() ?: 52).coerceIn(36, 100)
        val size = (dp * d).toInt()

        marksView = MarksView(this)
        wm.addView(marksView, fullScreenParams(false))

        checkBtn = makeButton("✓", Color.argb(210, 22, 163, 74), 400, size) { startCapture(false) }
        askBtn = makeButton("?", Color.argb(210, 66, 133, 244), 400 + size + 24, size) { startCapture(true) }
    }

    private fun makeButton(label: String, color: Int, startY: Int, size: Int, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 22f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
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
        lp.y = startY

        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        tv.setOnTouchListener { _, e ->
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
                        wm.updateViewLayout(tv, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (e.eventTime - e.downTime > 700) stopSelf() else onClick()
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(tv, lp)
        buttons.add(tv)
        return tv
    }

    private fun toast(t: String) {
        main.post { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }
    }

    private fun startCapture(select: Boolean) {
        if (busy) return
        busy = true
        main.removeCallbacks(clearMarks)
        marksView.setMarks(emptyList())
        hidePanel()
        buttons.forEach { it.visibility = View.INVISIBLE }
        Thread {
            Thread.sleep(350)
            var bmp: Bitmap? = null
            repeat(6) {
                if (bmp == null) {
                    bmp = capture()
                    if (bmp == null) Thread.sleep(150)
                }
            }
            main.post { buttons.forEach { it.visibility = View.VISIBLE } }
            val b = bmp
            if (b == null) {
                toast("Не вдалося зробити знімок. Перезапустіть застосунок і у вікні запису екрана виберіть «Весь екран»")
                busy = false
            } else if (select) {
                main.post {
                    busy = false
                    showSelect(b)
                }
            } else {
                main.post { checkBtn.text = "…" }
                runTest(b)
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

    private fun showSelect(frozen: Bitmap) {
        hideSelect()
        val sv = SelectView(this, frozen) { crop ->
            main.post {
                hideSelect()
                if (crop != null) runAnswer(crop)
            }
        }
        wm.addView(sv, fullScreenParams(true))
        selectView = sv
    }

    private fun hideSelect() {
        selectView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        selectView = null
    }

    private fun pill(t: String, color: Int, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = t
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 24 * d
                setColor(color)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = (10 * d).toInt()
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun showPanel(t: String) {
        main.post {
            hidePanel()
            val d = resources.displayMetrics.density
            val pd = (14 * d).toInt()
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pd, pd, pd, pd)
                setBackgroundColor(Color.argb(240, 28, 28, 30))
            }
            val tv = TextView(this).apply {
                text = t
                setTextColor(Color.WHITE)
                textSize = 16f
                maxLines = 14
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, pd, 0, 0)
            }
            row.addView(pill("Копіювати", Color.rgb(66, 133, 244)) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("answer", t))
                Toast.makeText(this, "Скопійовано", Toast.LENGTH_SHORT).show()
            })
            row.addView(pill("Закрити", Color.rgb(90, 90, 95)) { hidePanel() })
            box.addView(tv)
            box.addView(row)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.BOTTOM
            wm.addView(box, lp)
            panel = box
        }
    }

    private fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
    }

    private fun b64(bmp: Bitmap): String {
        val scale = 1280f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun bodyFor(prompt: String, bmp: Bitmap, json: Boolean): JSONObject {
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", b64(bmp))
                )
            )
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        if (json) {
            body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        }
        return body
    }

    private fun errText(c: Int): String = when (c) {
        429 -> "Ліміт запитів вичерпано. Зачекайте хвилину або спробуйте завтра"
        500, 503 -> "Сервери Google перевантажені. Спробуйте ще раз через хвилину"
        404 -> "Модель не знайдена. Змініть назву моделі в налаштуваннях застосунку"
        400, 403 -> "Помилка $c. Перевірте ключ у налаштуваннях"
        else -> "Помилка $c"
    }

    private fun post(body: JSONObject): JSONObject? {
        val prefs = getSharedPreferences("s", Context.MODE_PRIVATE)
        val key = prefs.getString("key", "") ?: ""
        val model = prefs.getString("model", "gemini-3.5-flash") ?: "gemini-3.5-flash"
        var lastCode = 0
        for (attempt in 1..3) {
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
            val resp = stream?.bufferedReader()?.readText() ?: ""
            lastCode = code
            if (code in 200..299) return JSONObject(resp)
            if (code == 503 || code == 500 || code == 429) {
                Thread.sleep(2000L * attempt)
            } else {
                break
            }
        }
        toast(errText(lastCode))
        return null
    }

    private fun textOf(r: JSONObject): String {
        val parts = r.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
        return sb.toString()
    }

    private fun updateStats(r: JSONObject) {
        val tokens = r.optJSONObject("usageMetadata")?.optLong("totalTokenCount", 0L) ?: 0L
        val p = getSharedPreferences("s", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val same = p.getString("day", "") == today
        p.edit()
            .putString("day", today)
            .putInt("req", (if (same) p.getInt("req", 0) else 0) + 1)
            .putLong("tok", (if (same) p.getLong("tok", 0L) else 0L) + tokens)
            .putLong("last", tokens)
            .apply()
    }

    private fun runAnswer(crop: Bitmap) {
        busy = true
        main.post { askBtn.text = "…" }
        Thread {
            try {
                val prompt = "На зображенні фрагмент екрана із завданням або питанням. " +
                    "Дай коротку відповідь українською, не більше 40 слів, без вступів. " +
                    "Якщо є варіанти відповіді, спершу назви правильний варіант, потім дуже коротке пояснення
