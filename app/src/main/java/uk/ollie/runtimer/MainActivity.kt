package uk.ollie.runtimer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var cfg: Config
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var phaseTv: TextView
    private lateinit var clockTv: TextView
    private lateinit var nextTv: TextView
    private lateinit var ring: RingView
    private lateinit var repsTv: TextView
    private lateinit var runTv: TextView
    private lateinit var totalTv: TextView
    private lateinit var skipBtn: Button
    private lateinit var mainBtn: Button
    private lateinit var finishBtn: Button
    private lateinit var hintTv: TextView
    private lateinit var presetRow: LinearLayout
    private lateinit var runOut: TextView
    private lateinit var walkOut: TextView
    private lateinit var batteryBtn: Button

    private val runColor = 0xFFE0482A.toInt()
    private val walkColor = 0xFF1E8A6E.toInt()
    private val easyColor = 0xFF5A6B8C.toInt()
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val night get() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val fg get() = if (night) 0xFFEEF1EE.toInt() else 0xFF1B1F1D.toInt()
    private val muted get() = if (night) 0xFF9AA59F.toInt() else 0xFF5F6A64.toInt()
    private val line get() = if (night) 0xFF2C332F.toInt() else 0xFFD9DDD8.toInt()
    private val bg get() = if (night) 0xFF121614.toInt() else 0xFFF3F4F1.toInt()

    private val ui = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config.load(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        batteryBtn.visibility = if (batteryRestricted()) View.VISIBLE else View.GONE
        handler.post(ui)
    }

    override fun onPause() {
        handler.removeCallbacks(ui)
        super.onPause()
    }

    // ---------- UI construction ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(size: Float, color: Int, bold: Boolean = false, cond: Boolean = false) =
        TextView(this).apply {
            textSize = size
            if (cond) {
                typeface = condensed
            } else if (bold) {
                setTypeface(typeface, Typeface.BOLD)
            }
            setTextColor(color)
        }

    private fun rounded(fill: Int, stroke: Int? = null, radius: Int = 14) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(fg)
        background = rounded(bg, line)
        stateListAnimator = null
        setPadding(dp(8), dp(14), dp(8), dp(14))
        setOnClickListener { onClick() }
    }

    private fun header(t: String) = text(15f, fg, cond = true).apply {
        text = t.uppercase()
        letterSpacing = 0.08f
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
            setBackgroundColor(bg)
        }

        root.addView(text(20f, fg, cond = true).apply { text = "RUNTIMER"; letterSpacing = 0.06f })

        phaseTv = text(42f, easyColor, cond = true).apply {
            gravity = Gravity.CENTER; letterSpacing = 0.06f; setPadding(0, dp(12), 0, 0)
        }
        root.addView(phaseTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val ringBox = FrameLayout(this)
        ring = RingView(this).apply { trackColor = line }
        ringBox.addView(ring, FrameLayout.LayoutParams(dp(280), dp(280), Gravity.CENTER))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        clockTv = text(76f, fg, cond = true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        nextTv = text(14f, muted).apply { gravity = Gravity.CENTER }
        mid.addView(clockTv); mid.addView(nextTv)
        ringBox.addView(mid, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        root.addView(ringBox, LinearLayout.LayoutParams(MATCH_PARENT, dp(300)))

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(label: String): TextView {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            val v = text(26f, fg, cond = true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
            col.addView(v)
            col.addView(text(11f, muted).apply { text = label.uppercase(); letterSpacing = 0.08f; gravity = Gravity.CENTER })
            stats.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            return v
        }
        repsTv = stat("Reps done"); runTv = stat("Time running"); totalTv = stat("Total")
        root.addView(stats, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        skipBtn = button("Skip") { TimerService.send(this, TimerService.ACTION_SKIP) }
        mainBtn = button("Start") { onMain() }.apply { textSize = 20f; typeface = condensed; setTextColor(0xFFFFFFFF.toInt()) }
        finishBtn = button("Finish") { TimerService.send(this, TimerService.ACTION_FINISH) }
        controls.addView(skipBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        controls.addView(mainBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.6f).apply { marginStart = dp(10); marginEnd = dp(10) })
        controls.addView(finishBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })

        hintTv = text(13f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        root.addView(hintTv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        batteryBtn = button("Let Runtimer run in the background (recommended)") { askBattery() }
        root.addView(batteryBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        // ---- settings ----
        root.addView(header("Intervals"))
        presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presetRow) })
        runOut = stepper(root, "Run", "Each running block") { d -> cfg.run = (cfg.run + d).coerceIn(15, 1800) }
        walkOut = stepper(root, "Walk", "Each recovery block") { d -> cfg.walk = (cfg.walk + d).coerceIn(15, 1800) }

        root.addView(header("Warm-up & cool-down"))
        toggle(root, "5-minute warm-up walk", cfg.warm) { cfg.warm = it }
        toggle(root, "5-minute cool-down walk (after Finish)", cfg.cool) { cfg.cool = it }

        root.addView(header("Cues"))
        toggle(root, "Countdown beeps (last 3 seconds)", cfg.beeps) { cfg.beeps = it }
        toggle(root, "Vibrate", cfg.vibrate) { cfg.vibrate = it }

        renderSettings()
        return ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
    }

    private fun stepper(parent: LinearLayout, label: String, sub: String, change: (Int) -> Unit): TextView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(16f, fg).apply { text = label })
        labels.addView(text(12f, muted).apply { text = sub })
        row.addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val out = text(24f, fg, cond = true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        fun step(sign: String, d: Int) = button(sign) { change(d); cfg.save(this); renderSettings() }.apply { textSize = 20f }
        row.addView(step("−", -15), LinearLayout.LayoutParams(dp(48), WRAP_CONTENT))
        row.addView(out, LinearLayout.LayoutParams(dp(72), WRAP_CONTENT))
        row.addView(step("+", 15), LinearLayout.LayoutParams(dp(48), WRAP_CONTENT))
        parent.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return out
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun toggle(parent: LinearLayout, label: String, value: Boolean, set: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            text = label
            textSize = 15f
            setTextColor(fg)
            isChecked = value
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, on -> set(on); cfg.save(this@MainActivity) }
        }
        parent.addView(sw, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun renderSettings() {
        runOut.text = fmtSecs(cfg.run)
        walkOut.text = fmtSecs(cfg.walk)
        presetRow.removeAllViews()
        for (p in PRESETS) {
            val on = p.run == cfg.run && p.walk == cfg.walk
            val b = Button(this).apply {
                text = p.name
                isAllCaps = false
                textSize = 13f
                stateListAnimator = null
                setTextColor(if (on) bg else fg)
                background = rounded(if (on) fg else bg, line, radius = 20)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                minHeight = 0; minimumHeight = 0
                setOnClickListener { cfg.run = p.run; cfg.walk = p.walk; cfg.save(this@MainActivity); renderSettings() }
            }
            presetRow.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
    }

    // ---------- behaviour ----------

    private fun onMain() {
        when (Session.phase) {
            Phase.DONE -> Session.reset()
            Phase.READY -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                cfg.save(this)
                TimerService.send(this, TimerService.ACTION_START)
            }
            else -> TimerService.send(this, TimerService.ACTION_TOGGLE)
        }
        handler.postDelayed({ render() }, 60)
    }

    private fun batteryRestricted(): Boolean =
        !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun colorFor(p: Phase) = when (p) {
        Phase.RUN -> runColor
        Phase.WALK -> walkColor
        else -> easyColor
    }

    private fun render() {
        val p = Session.phase
        val c = colorFor(p)
        phaseTv.setTextColor(c)
        ring.color = c
        mainBtn.background = rounded(c)

        if (p == Phase.READY) {
            phaseTv.text = "READY"
            clockTv.text = fmt(cfg.lenMs(if (cfg.warm) Phase.WARM else Phase.RUN))
            nextTv.text = (if (cfg.warm) "Warm-up, then " else "") + "run ${fmtSecs(cfg.run)} / walk ${fmtSecs(cfg.walk)}"
            ring.progress = 1f
            hintTv.text = "Run/walk repeats until you tap Finish. Keeps going with the screen locked."
        } else {
            val remain = Session.remainMs()
            phaseTv.text = if (Session.running || p == Phase.DONE) p.label.uppercase() else "PAUSED"
            clockTv.text = fmt(remain)
            ring.progress = if (Session.lenMs > 0) remain.toFloat() / Session.lenMs else 0f
            nextTv.text = when {
                p == Phase.DONE -> "Nice work"
                Session.next() == Phase.DONE -> "Then finish"
                else -> "Then " + Session.next().label.lowercase() +
                    if (Session.next() == Phase.COOL) "" else " " + fmt(cfg.lenMs(Session.next()))
            }
            hintTv.text = if (p == Phase.DONE)
                "${Session.reps} run/walk reps, ${fmt(Session.runMs)} spent running."
            else "Lock the phone whenever you like. Controls are in the notification too."
        }
        repsTv.text = Session.reps.toString()
        runTv.text = fmt(Session.runMs)
        totalTv.text = fmt(Session.totalMs)

        mainBtn.text = when {
            p == Phase.DONE -> "Again"
            p == Phase.READY -> "Start"
            Session.running -> "Pause"
            else -> "Resume"
        }
        skipBtn.isEnabled = Session.active
        finishBtn.isEnabled = Session.active
        skipBtn.alpha = if (Session.active) 1f else 0.4f
        finishBtn.alpha = if (Session.active) 1f else 0.4f
        finishBtn.text = if (p == Phase.COOL) "End now" else "Finish"
    }
}

/** Countdown ring drawn around the clock. */
class RingView(ctx: Context) : View(ctx) {
    var progress = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var color = 0xFF5A6B8C.toInt()
        set(v) { field = v; invalidate() }
    var trackColor = 0x22000000

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sw = width * 0.045f
        paint.strokeWidth = sw
        rect.set(sw / 2, sw / 2, width - sw / 2, height - sw / 2)
        paint.color = trackColor
        canvas.drawArc(rect, 0f, 360f, false, paint)
        paint.color = color
        canvas.drawArc(rect, -90f, 360f * progress, false, paint)
    }
}
