package com.studytimer.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.util.Locale

class TimerService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACT_START = "start"
        const val ACT_PAUSE = "pause"
        const val ACT_STOP = "stop"
        @Volatile var running = false
        @Volatile var paused = false
        @Volatile var finished = false
        @Volatile var totalMs = 0L
        @Volatile var elapsedMs = 0L

        fun label(m: Int): String {
            val h = m / 60; val r = m % 60
            val p = ArrayList<String>()
            if (h > 0) p.add("$h " + if (h > 1) "hours" else "hour")
            if (r > 0) p.add("$r " + if (r > 1) "minutes" else "minute")
            return p.joinToString(" ")
        }
        fun fmt(ms: Long): String {
            val s = (maxOf(ms, 0L) + 999) / 1000
            return String.format("%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager
    private lateinit var wm: WindowManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wake: PowerManager.WakeLock? = null
    private var overlay: View? = null
    private var strip: TextView? = null
    private var mp: MediaPlayer? = null
    private var totalMin = 0
    private var intervalMin = 0
    private var intervalMs = 0L
    private var nextIdx = 1
    private var lastReal = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        nm.createNotificationChannel(NotificationChannel("timer", "Timer", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("alerts", "Cheers", NotificationManager.IMPORTANCE_HIGH))
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.getDefault())
            ttsReady = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_START -> startTimer(intent.getIntExtra("total", 60), intent.getIntExtra("interval", 20))
            ACT_PAUSE -> togglePause()
            ACT_STOP -> stopAll()
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTimer(t: Int, i: Int) {
        h.removeCallbacksAndMessages(null)
        removeOverlay()
        removeStrip()
        totalMin = t; intervalMin = i
        totalMs = t * 60000L; intervalMs = i * 60000L
        elapsedMs = 0; nextIdx = 1
        running = true; paused = false; finished = false
        lastReal = SystemClock.elapsedRealtime()
        val n = notif()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        if (wake == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "studytimer:wake")
        }
        if (wake?.isHeld == false) wake?.acquire(totalMs + 60000L)
        addStrip()
        h.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (!paused) elapsedMs += now - lastReal
            lastReal = now
            updateStrip()
            while (intervalMs > 0 && nextIdx * intervalMs <= elapsedMs && nextIdx * intervalMs < totalMs) {
                val m = intervalMin * nextIdx
                show("\uD83D\uDC4F", "Well done!", "You completed ${label(m)}!", false)
                nextIdx++
            }
            if (elapsedMs >= totalMs) { finish(); return }
            h.postDelayed(this, 500)
        }
    }

    private fun togglePause() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (!paused) elapsedMs += now - lastReal
        lastReal = now
        paused = !paused
        updateStrip()
        nm.notify(1, notif())
    }

    private fun finish() {
        elapsedMs = totalMs
        running = false; finished = true
        updateStrip("\uD83C\uDFC6 Done!")
        show("\uD83C\uDFC6", "Congratulations!", "You completed ${label(totalMin)}!", true)
        val done = Notification.Builder(this, "timer")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Completed " + label(totalMin) + "!")
            .setContentText("Congratulations, great work!").build()
        nm.notify(1, done)
        h.postDelayed({ stopAll() }, 25000)
    }

    private fun stopAll() {
        running = false; paused = false; finished = false
        h.removeCallbacksAndMessages(null)
        removeOverlay()
        removeStrip()
        try { mp?.release() } catch (e: Exception) {}
        mp = null
        tts?.stop()
        if (wake?.isHeld == true) wake?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        removeOverlay()
        removeStrip()
        try { mp?.release() } catch (e: Exception) {}
        tts?.shutdown()
        if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }

    private fun notif(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val pause = PendingIntent.getService(this, 1, Intent(this, TimerService::class.java).setAction(ACT_PAUSE), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, TimerService::class.java).setAction(ACT_STOP), flags)
        val b = Notification.Builder(this, "timer")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Studying \u00B7 " + label(totalMin))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (paused) {
            b.setContentText("Paused \u00B7 " + fmt(totalMs - elapsedMs) + " left")
        } else {
            b.setContentText("Time left")
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() + (totalMs - elapsedMs))
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
        b.addAction(0, if (paused) "Resume" else "Pause", pause)
        b.addAction(0, "Stop", stop)
        return b.build()
    }

    private fun addStrip() {
        removeStrip()
        if (!Settings.canDrawOverlays(this)) return
        val d = resources.displayMetrics.density
        val t = TextView(this)
        t.textSize = 14f
        t.setTextColor(Color.WHITE)
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
        t.background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = 20 * d }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = (2 * d).toInt()
        var sx = 0f
        var sy = 0f
        var moved = false
        t.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x += dx.toInt(); lp.y += dy.toInt()
                        sx = e.rawX; sy = e.rawY
                        try { wm.updateViewLayout(t, lp) } catch (ex: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    try {
                        val i = Intent(this, MainActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        startActivity(i)
                    } catch (ex: Exception) {}
                }
            }
            true
        }
        try {
            wm.addView(t, lp)
            strip = t
            updateStrip()
        } catch (e: Exception) {}
    }

    private fun updateStrip(text: String? = null) {
        strip?.text = text ?: ((if (paused) "\u23F8 " else "\u23F1 ") + fmt(totalMs - elapsedMs))
    }

    private fun removeStrip() {
        strip?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        strip = null
    }

    private fun show(emoji: String, title: String, msg: String, final: Boolean) {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
        } catch (e: Exception) {}
        playCheer(final, msg)
        if (Settings.canDrawOverlays(this)) {
            addOverlay(emoji, title, msg, final)
        } else {
            val n = Notification.Builder(this, "alerts")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("$emoji $title").setContentText(msg).setAutoCancel(true).build()
            nm.notify(100 + nextIdx + (if (final) 50 else 0), n)
        }
    }

    private fun playCheer(final: Boolean, msg: String) {
        try { mp?.release() } catch (e: Exception) {}
        mp = null
        val f = File(filesDir, if (final) "final.m4a" else "milestone.m4a")
        if (f.exists()) {
            try {
                mp = MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    prepare()
                    setOnCompletionListener { it.release() }
                    start()
                }
                return
            } catch (e: Exception) {}
        }
        if (ttsReady) {
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "cheer")
        } else {
            try { ToneGenerator(AudioManager.STREAM_MUSIC, 90).startTone(ToneGenerator.TONE_PROP_BEEP2, 700) } catch (e: Exception) {}
        }
    }

    private fun addOverlay(emoji: String, title: String, msg: String, final: Boolean) {
        removeOverlay()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = FrameLayout(this)
        root.setBackgroundColor(0x99000000.toInt())
        root.setPadding(dp(24), dp(24), dp(24), dp(24))
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(dp(24), dp(28), dp(24), dp(24))
        card.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(24).toFloat() }
        fun tv(t: String, size: Float, color: Int, bold: Boolean): TextView {
            val x = TextView(this)
            x.text = t; x.textSize = size; x.setTextColor(color); x.gravity = Gravity.CENTER
            if (bold) x.setTypeface(null, Typeface.BOLD)
            x.setPadding(0, dp(6), 0, dp(6))
            return x
        }
        card.addView(tv(emoji, 56f, Color.BLACK, false))
        card.addView(tv(title, 26f, 0xFF16A34A.toInt(), true))
        card.addView(tv(msg, 18f, 0xFF1B1F2A.toInt(), false))
        val ok = Button(this)
        ok.text = "Keep going"; ok.isAllCaps = false; ok.setTextColor(Color.WHITE); ok.textSize = 17f
        ok.background = GradientDrawable().apply { setColor(0xFF4F46E5.toInt()); cornerRadius = dp(14).toFloat() }
        ok.setOnClickListener { removeOverlay() }
        val lpb = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpb.topMargin = dp(14)
        card.addView(ok, lpb)
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        try {
            wm.addView(root, lp)
            overlay = root
            h.postDelayed({ removeOverlay() }, if (final) 20000L else 12000L)
        } catch (e: Exception) {}
    }

    private fun removeOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        overlay = null
    }
}
