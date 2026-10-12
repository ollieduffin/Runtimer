package uk.ollie.runtimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.ceil

/**
 * Runs the interval loop as a foreground service with a partial wake lock,
 * so it keeps going (with sound) while the screen is off or locked.
 */
class TimerService : Service() {

    companion object {
        const val ACTION_START = "uk.ollie.runtimer.START"
        const val ACTION_TOGGLE = "uk.ollie.runtimer.TOGGLE"
        const val ACTION_SKIP = "uk.ollie.runtimer.SKIP"
        const val ACTION_FINISH = "uk.ollie.runtimer.FINISH"
        private const val CHANNEL = "session"
        private const val NOTIF_ID = 1

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, TimerService::class.java).setAction(action)
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private lateinit var cfg: Config
    private lateinit var tones: Tones
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastTick = 0L
    private var lastBeep = -1

    private val loop = object : Runnable {
        override fun run() {
            tick()
            if (Session.running) handler.postDelayed(this, 100)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tones = Tones(this)
        val channel = NotificationChannel(CHANNEL, "Run session", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_TOGGLE -> if (ready()) toggle()
            ACTION_SKIP -> if (ready()) { resumeIfPaused(); enter(Session.next()) }
            ACTION_FINISH -> if (ready()) finishPressed()
            else -> if (!Session.active) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ready(): Boolean {
        if (!::cfg.isInitialized || !Session.active) {
            stopSelf()
            return false
        }
        return true
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun start() {
        cfg = Config.load(this)
        Session.reset()
        Session.cool = cfg.cool
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "runtimer:session")
            .apply { acquire(4 * 60 * 60 * 1000L) }
        Session.running = true
        lastTick = now()
        enter(if (cfg.warm) Phase.WARM else Phase.RUN)
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    private fun enter(phase: Phase) {
        if (Session.phase == Phase.WALK && phase != Phase.WALK) Session.reps++
        Session.phase = phase
        if (phase == Phase.DONE) {
            complete()
            return
        }
        Session.lenMs = cfg.lenMs(phase)
        Session.endAt = now() + Session.lenMs
        Session.pausedRemain = Session.lenMs
        lastBeep = -1
        when (phase) {
            Phase.RUN -> { tones.run(); vibrate(longArrayOf(0, 300, 120, 300)) }
            Phase.WALK -> { tones.walk(); vibrate(longArrayOf(0, 500)) }
            else -> { tones.easy(); vibrate(longArrayOf(0, 400)) }
        }
        updateNotification()
    }

    private fun tick() {
        if (!Session.running) return
        val t = now()
        val dt = t - lastTick
        lastTick = t
        Session.totalMs += dt
        if (Session.phase == Phase.RUN) Session.runMs += dt
        val remain = Session.endAt - t
        val secs = ceil(remain / 1000.0).toInt()
        if (cfg.beeps && secs in 1..3 && secs != lastBeep) {
            lastBeep = secs
            tones.tick()
        }
        if (remain <= 0) enter(Session.next())
    }

    private fun toggle() {
        if (Session.running) {
            Session.pausedRemain = Session.remainMs()
            Session.running = false
            handler.removeCallbacks(loop)
        } else {
            resumeIfPaused()
        }
        updateNotification()
    }

    private fun resumeIfPaused() {
        if (Session.running) return
        Session.running = true
        Session.endAt = now() + Session.pausedRemain
        lastTick = now()
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    private fun finishPressed() {
        if (Session.phase == Phase.COOL) {   // "End now" during the cool-down
            enter(Phase.DONE)
            return
        }
        resumeIfPaused()
        if (Session.phase == Phase.RUN) Session.reps++   // a walk is counted by enter()
        Session.finishing = true
        enter(if (cfg.cool) Phase.COOL else Phase.DONE)
    }

    private fun complete() {
        Session.running = false
        Session.pausedRemain = 0
        handler.removeCallbacks(loop)
        tones.done()
        vibrate(longArrayOf(0, 200, 100, 200, 100, 450))
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun vibrate(pattern: LongArray) {
        if (!cfg.vibrate) return
        try {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }

    private fun updateNotification() {
        if (Session.active) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun action(action: String, label: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code, Intent(this, TimerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_run), label, pi).build()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val phase = Session.phase
        val title = when {
            phase == Phase.READY -> "Starting…"
            phase == Phase.WALK && Session.finishing -> "Walk (last one)"
            else -> phase.label
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_run)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(
                when (phase) {
                    Phase.RUN -> 0xFFE0482A.toInt()
                    Phase.WALK -> 0xFF1E8A6E.toInt()
                    else -> 0xFF5A6B8C.toInt()
                }
            )

        if (Session.running) {
            b.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + Session.remainMs())
                .setShowWhen(true)
                .setContentText("Reps done: ${Session.reps}")
        } else {
            b.setUsesChronometer(false)
                .setShowWhen(false)
                .setContentText("Paused · ${fmt(Session.remainMs())} left")
        }

        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (Session.active) {
            b.addAction(action(ACTION_TOGGLE, if (Session.running) "Pause" else "Resume", 1))
            b.addAction(action(ACTION_SKIP, "Skip", 2))
            b.addAction(action(ACTION_FINISH, if (phase == Phase.COOL) "End now" else "Finish", 3))
        }
        return b.build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
