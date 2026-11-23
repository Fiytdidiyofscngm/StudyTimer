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
    private var stripRoot: View? = null
    private var stripLp: WindowManager.LayoutParams? = null
    private var stripSmall: View? = null
    private var stripBig: View? = null
    private var stripTimeSmall: TextView? = null
    private var stripTimeBig: TextView? = null
    private var stripPause: TextView? = null
    private var sbH = 0
    private var mp: MediaPlayer? = null
    private var totalMin = 0
    private var intervalMin = 0
    private var intervalMs = 0L
    private var nextIdx = 1
    private var lastReal = 0L

    private val collapse = Runnable { setExpanded(false) }

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

    private fun armCollapse() {
        h.removeCallbacks(collapse)
        h.postDelayed(collapse, 8000)
    }

    private fun setExpanded(on: Boolean) {
        val root = stripRoot ?: return
        val lp = stripLp ?: return
        val d = resources.displayMetrics.density
        stripSmall?.visibility = if (on) View.GONE else View.VISIBLE
        stripBig?.visibility = if (on) View.VISIBLE else View.GONE
        lp.width = if (on) resources.displayMetrics.widthPixels - (28 * d).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
        lp.y = if (on) sbH + (6 * d).toInt() else (4 * d).toInt()
        try { wm.updateViewLayout(root, lp) } catch (e: Exception) {}
        h.removeCallbacks(collapse)
        if (on) h.postDelayed(collapse, 8000)
    }

    private fun addStrip() {
        removeStrip()
        if (!Settings.canDrawOverlays(this)) return
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
        sbH = if (sbId > 0) resources.getDimensionPixelSize(sbId) else dp(28)
        val blue = 0xFF3B82F6.toInt()

        fun txt(t: String, size: Float, color: Int, bold: Boolean): TextView {
            val x = TextView(this)
            x.text = t; x.textSize = size; x.setTextColor(color)
            if (bold) x.setTypeface(null, Typeface.BOLD)
            return x
        }

        // Small pill (fixed, around the camera)
        val small = LinearLayout(this)
        small.orientation = LinearLayout.HORIZONTAL
        small.gravity = Gravity.CENTER_VERTICAL
        small.setPadding(dp(12), 0, dp(14), 0)
        small.background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = 100 * d }
        val icon1 = txt("\u231B", 16f, blue, false)
        val gap = View(this)
        val tSmall = txt("", 16f, Color.WHITE, true)
        small.addView(icon1)
        small.addView(gap, LinearLayout.LayoutParams(dp(56), 1))
        small.addView(tSmall)
        small.setOnClickListener { setExpanded(true); armCollapse() }

        // Big bar (after tap)
        val big = LinearLayout(this)
        big.orientation = LinearLayout.HORIZONTAL
        big.gravity = Gravity.CENTER_VERTICAL
        big.setPadding(dp(22), dp(16), dp(16), dp(16))
        big.background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = 32 * d }
        val left = LinearLayout(this)
        left.orientation = LinearLayout.HORIZONTAL
        left.gravity = Gravity.CENTER_VERTICAL
        val icon2 = txt("\u231B", 34f, blue, false)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), 0, 0, 0)
        val tBig = txt("", 30f, Color.WHITE, true)
        val lab = txt("Study timer", 15f, 0xFF9CA3AF.toInt(), false)
        col.addView(tBig); col.addView(lab)
        left.addView(icon2); left.addView(col)
        left.setOnClickListener { setExpanded(false) }
        big.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        fun circle(t: String, fill: Int, stroke: Int?): TextView {
            val x = txt(t, 20f, Color.WHITE, true)
            x.gravity = Gravity.CENTER
            x.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                if (stroke != null) setStroke(dp(3), stroke)
            }
            return x
        }
        val pauseB = circle("\u275A\u275A", Color.TRANSPARENT, blue)
        pauseB.setTextColor(blue)
        val stopB = circle("\u2715", 0xFF2C2C2E.toInt(), null)
        pauseB.setOnClickListener { togglePause(); armCollapse() }
        stopB.setOnClickListener { stopAll() }
        val lp1 = LinearLayout.LayoutParams(dp(54), dp(54))
        lp1.leftMargin = dp(10)
        val lp2 = LinearLayout.LayoutParams(dp(54), dp(54))
        lp2.leftMargin = dp(10)
        big.addView(pauseB, lp1)
        big.addView(stopB, lp2)
        big.visibility = View.GONE

        val root = FrameLayout(this)
        root.addView(small, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, maxOf(dp(30), sbH - dp(8))))
        root.addView(big, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(4)

        try {
            wm.addView(root, lp)
            stripRoot = root; stripLp = lp
            stripSmall = small; stripBig = big
            stripTimeSmall = tSmall; stripTimeBig = tBig; stripPause = pauseB
            updateStrip()
        } catch (e: Exception) {}
    }

    private fun updateStrip(text: String? = null) {
        val t = text ?: fmt(totalMs - elapsedMs)
        stripTimeSmall?.text = t
        stripTimeBig?.text = t
        stripTimeSmall?.setTextColor(if (paused) 0xFF9CA3AF.toInt() else Color.WHITE)
        stripPause?.text = if (paused) "\u25B6\uFE0E" else "\u275A\u275A"
    }

    private fun removeStrip() {
        h.removeCallbacks(collapse)
        stripRoot?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        stripRoot = null; stripLp = null; stripSmall = null; stripBig = null
        stripTimeSmall = null; stripTimeBig = null; stripPause = null
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
