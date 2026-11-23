package com.studytimer.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var sub: TextView
    private lateinit var totalEt: EditText
    private lateinit var intEt: EditText
    private lateinit var pauseBtn: Button
    private val status = HashMap<String, TextView>()
    private var rec: MediaRecorder? = null
    private var recSlot = ""
    private var recBtn: Button? = null
    private var player: MediaPlayer? = null
    private val indigo = 0xFF4F46E5.toInt()
    private val gray = 0xFF6B7280.toInt()
    private val ink = 0xFF1B1F2A.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, color: Int = indigo, onClick: () -> Unit = {}): Button {
        val b = Button(this)
        b.text = text; b.isAllCaps = false; b.setTextColor(Color.WHITE); b.textSize = 15f
        b.background = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun label(t: String): TextView {
        val x = TextView(this)
        x.text = t; x.textSize = 14f; x.setTextColor(gray)
        x.setPadding(0, dp(10), 0, dp(4))
        return x
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(16), dp(18), dp(16))
        c.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(14)
        c.layoutParams = lp
        return c
    }

    private fun numberField(def: String): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(def); e.textSize = 18f; e.setTextColor(ink)
        return e
    }

    private fun presets(target: EditText, vals: List<Int>): LinearLayout {
        val row = LinearLayout(this)
        for (v in vals) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), dp(4), dp(3), dp(4))
            row.addView(btn("$v", gray) { target.setText(v.toString()) }, lp)
        }
        return row
    }

    private fun voiceBlock(title: String, slot: String): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = title; t.textSize = 15f; t.setTextColor(ink); t.setTypeface(null, Typeface.BOLD)
        t.setPadding(0, dp(10), 0, 0)
        col.addView(t)
        val st = TextView(this)
        st.textSize = 13f; st.setTextColor(gray)
        status[slot] = st
        col.addView(st)
        val row = LinearLayout(this)
        val rb = btn("\u23FA Record")
        rb.setOnClickListener { toggleRecord(slot, rb) }
        val pb = btn("\u25B6 Play", gray) { playFile(slot) }
        val xb = btn("Reset", gray) { File(filesDir, "$slot.m4a").delete(); refresh() }
        for (b in listOf(rb, pb, xb)) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), dp(4), dp(3), dp(4))
            row.addView(b, lp)
        }
        col.addView(row)
        return col
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(0xFFF6F7FB.toInt())
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(24))
        scroll.addView(root)

        val head = TextView(this)
        head.text = "\uD83D\uDCDA Study Timer"; head.textSize = 24f; head.setTextColor(ink)
        head.setTypeface(null, Typeface.BOLD)
        head.setPadding(0, 0, 0, dp(12))
        root.addView(head)

        val c1 = card()
        clock = TextView(this)
        clock.text = "Ready"; clock.textSize = 46f; clock.setTextColor(indigo)
        clock.gravity = Gravity.CENTER; clock.setTypeface(null, Typeface.BOLD)
        sub = TextView(this)
        sub.textSize = 15f; sub.setTextColor(gray); sub.gravity = Gravity.CENTER
        c1.addView(clock); c1.addView(sub)
        val r1 = LinearLayout(this)
        val start = btn("Start") { startTimer() }
        pauseBtn = btn("Pause", gray) { send(TimerService.ACT_PAUSE) }
        val stop = btn("Stop", 0xFFDC2626.toInt()) { send(TimerService.ACT_STOP) }
        for (b in listOf(start, pauseBtn, stop)) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), dp(10), dp(3), 0)
            r1.addView(b, lp)
        }
        c1.addView(r1)
        root.addView(c1)

        val c2 = card()
        totalEt = numberField("60")
        intEt = numberField("20")
        c2.addView(label("Total study time (minutes)"))
        c2.addView(totalEt)
        c2.addView(presets(totalEt, listOf(25, 45, 60, 90, 120)))
        c2.addView(label("Cheer me every (minutes)"))
        c2.addView(intEt)
        c2.addView(presets(intEt, listOf(5, 10, 15, 20, 30)))
        root.addView(c2)

        val c3 = card()
        val vt = TextView(this)
        vt.text = "\uD83C\uDFA4 Your cheering voice"; vt.textSize = 17f; vt.setTextColor(ink)
        vt.setTypeface(null, Typeface.BOLD)
        c3.addView(vt)
        val hint = TextView(this)
        hint.text = "Record up to 15 seconds in your own voice (or a loved one's). It plays with each card."
        hint.textSize = 13f; hint.setTextColor(gray)
        c3.addView(hint)
        c3.addView(voiceBlock("Every interval (\"Well done!\")", "milestone"))
        c3.addView(voiceBlock("When all time is finished (\"Congratulations!\")", "final"))
        root.addView(c3)

        setContentView(scroll)
        refresh()
    }

    override fun onResume() { super.onResume(); h.post(ui) }
    override fun onPause() { super.onPause(); h.removeCallbacks(ui); stopRec() }

    private val ui = object : Runnable {
        override fun run() {
            if (TimerService.running || TimerService.finished) {
                clock.text = TimerService.fmt(TimerService.totalMs - TimerService.elapsedMs)
                sub.text = if (TimerService.finished) "Completed \uD83C\uDFC6" else if (TimerService.paused) "Paused" else "Studying\u2026"
                pauseBtn.text = if (TimerService.paused) "Resume" else "Pause"
            } else {
                clock.text = "Ready"; sub.text = ""; pauseBtn.text = "Pause"
            }
            h.postDelayed(this, 500)
        }
    }

    private fun refresh() {
        for ((slot, tv) in status)
            tv.text = if (File(filesDir, "$slot.m4a").exists()) "\u2705 Using your voice"
            else "\uD83E\uDD16 Using the phone's voice (record yours to replace)"
    }

    private fun send(action: String) {
        startService(Intent(this, TimerService::class.java).setAction(action))
    }

    private fun startTimer() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" for Study Timer, then come back and press Start", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val t = totalEt.text.toString().toIntOrNull() ?: 0
        if (t < 1) { Toast.makeText(this, "Enter total minutes", Toast.LENGTH_SHORT).show(); return }
        var i = intEt.text.toString().toIntOrNull() ?: t
        if (i < 1 || i >= t) i = t
        val svc = Intent(this, TimerService::class.java).setAction(TimerService.ACT_START)
            .putExtra("total", t).putExtra("interval", i)
        startForegroundService(svc)
    }

    private val autoStop = Runnable { stopRec() }

    private fun toggleRecord(slot: String, b: Button) {
        if (rec != null) { stopRec(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        try {
            val f = File(filesDir, "$slot.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            rec = r; recSlot = slot; recBtn = b
            b.text = "\u23F9 Stop"
            h.postDelayed(autoStop, 15000)
        } catch (e: Exception) {
            rec = null
            Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRec() {
        val r = rec ?: return
        h.removeCallbacks(autoStop)
        try { r.stop() } catch (e: RuntimeException) { File(filesDir, "$recSlot.m4a").delete() }
        r.release()
        rec = null
        recBtn?.text = "\u23FA Record"
        refresh()
    }

    private fun playFile(slot: String) {
        val f = File(filesDir, "$slot.m4a")
        if (!f.exists()) { Toast.makeText(this, "Nothing recorded yet", Toast.LENGTH_SHORT).show(); return }
        try { player?.release() } catch (e: Exception) {}
        try {
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath); prepare()
                setOnCompletionListener { it.release() }
                start()
            }
        } catch (e: Exception) {}
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 2 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "Microphone allowed. Tap Record again.", Toast.LENGTH_SHORT).show()
    }
}
