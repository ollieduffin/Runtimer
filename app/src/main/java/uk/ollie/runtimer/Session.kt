package uk.ollie.runtimer

import android.content.Context
import android.os.SystemClock

enum class Phase(val label: String) {
    READY("Ready"), WARM("Warm up"), RUN("Run"), WALK("Walk"), COOL("Cool down"), DONE("Done")
}

data class Preset(val id: String, val name: String, val run: Int, val walk: Int)

val PRESETS = listOf(
    Preset("w1", "Week 1", 60, 90),
    Preset("w2", "Week 2", 90, 120),
    Preset("w3", "Week 3", 90, 90),
    Preset("w3b", "Week 3 (3 min)", 180, 180),
    Preset("w4", "Week 4", 180, 90),
    Preset("w4b", "Week 4 (5 min)", 300, 150),
    Preset("w5", "Week 5", 300, 180),
    Preset("w6", "Week 6", 480, 180),
)

/** User settings, persisted in SharedPreferences. Durations in seconds. */
class Config(
    var run: Int,
    var walk: Int,
    var warm: Boolean,
    var cool: Boolean,
    var beeps: Boolean,
    var vibrate: Boolean,
    var gps: Boolean,
    var miles: Boolean,
) {
    fun lenMs(phase: Phase): Long {
        val secs = when (phase) {
            Phase.WARM -> WARM_S
            Phase.COOL -> COOL_S
            Phase.RUN -> run
            Phase.WALK -> walk
            else -> 0
        }
        return secs * 1000L
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putInt("run", run).putInt("walk", walk)
            .putBoolean("warm", warm).putBoolean("cool", cool)
            .putBoolean("beeps", beeps).putBoolean("vibrate", vibrate)
            .putBoolean("gps", gps).putBoolean("miles", miles)
            .apply()
    }

    companion object {
        const val WARM_S = 300
        const val COOL_S = 300

        fun load(ctx: Context): Config {
            val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            return Config(
                p.getInt("run", 60), p.getInt("walk", 90),
                p.getBoolean("warm", true), p.getBoolean("cool", true),
                p.getBoolean("beeps", true), p.getBoolean("vibrate", true),
                p.getBoolean("gps", true), p.getBoolean("miles", true),
            )
        }
    }
}

/** Live session state, owned by TimerService and read by the UI. */
object Session {
    var phase = Phase.READY
    var lenMs = 0L
    var endAt = 0L          // elapsedRealtime when the current block ends (while running)
    var pausedRemain = 0L   // remaining ms while paused
    var running = false
    var reps = 0
    var runMs = 0L
    var totalMs = 0L
    var finishing = false
    var cool = true         // whether this session ends with a cool-down

    // GPS distance (metres) and time split by phase
    var distM = 0.0
    var runDistM = 0.0
    var walkDistM = 0.0
    var walkMs = 0L
    var blockDistM = 0.0
    var blockMs = 0L
    var gpsOn = false       // true once at least one good GPS segment has been counted
    val runSplits = mutableListOf<Pair<Double, Long>>()   // (metres, ms) for each run block

    val active: Boolean get() = phase != Phase.READY && phase != Phase.DONE

    fun remainMs(): Long =
        if (running) (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) else pausedRemain

    fun next(): Phase = when (phase) {
        Phase.WARM -> Phase.RUN
        Phase.RUN -> Phase.WALK
        Phase.WALK -> if (finishing) (if (cool) Phase.COOL else Phase.DONE) else Phase.RUN
        else -> Phase.DONE
    }

    fun reset() {
        phase = Phase.READY; lenMs = 0; endAt = 0; pausedRemain = 0; running = false
        reps = 0; runMs = 0; totalMs = 0; finishing = false
        distM = 0.0; runDistM = 0.0; walkDistM = 0.0; walkMs = 0; blockDistM = 0.0; blockMs = 0
        gpsOn = false; runSplits.clear()
    }
}

private const val METRES_PER_MILE = 1609.344

fun fmtDist(m: Double, miles: Boolean): String =
    if (miles) "%.2f mi".format(m / METRES_PER_MILE) else "%.2f km".format(m / 1000.0)

/** Pace as m:ss per mile or km; a dash when there isn't enough distance to be meaningful. */
fun fmtPace(ms: Long, m: Double, miles: Boolean): String {
    if (m < 30 || ms <= 0) return "–"
    val unit = if (miles) METRES_PER_MILE else 1000.0
    val secs = ((ms / 1000.0) / (m / unit)).toInt()
    return "%d:%02d%s".format(secs / 60, secs % 60, if (miles) "/mi" else "/km")
}

fun fmt(ms: Long): String {
    val s = ((ms + 999) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmtSecs(s: Int): String = "%d:%02d".format(s / 60, s % 60)
