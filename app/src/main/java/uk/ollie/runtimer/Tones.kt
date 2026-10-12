package uk.ollie.runtimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generated beep cues. Uses the "navigation guidance" audio usage, so Spotify
 * and other music apps duck briefly underneath instead of pausing.
 */
class Tones(ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val rate = 44100
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun run() = play(listOf(1046.5 to 220, 1046.5 to 220))      // two high beeps
    fun walk() = play(listOf(659.3 to 520))                      // one long, lower beep
    fun easy() = play(listOf(523.3 to 420))                      // warm-up / cool-down
    fun tick() = play(listOf(880.0 to 110), gapMs = 0, duck = false)
    fun done() = play(listOf(523.3 to 150, 659.3 to 150, 784.0 to 150, 1046.5 to 380))

    private fun play(notes: List<Pair<Double, Int>>, gapMs: Int = 90, duck: Boolean = true) {
        try {
            val gap = gapMs * rate / 1000
            val total = notes.sumOf { it.second * rate / 1000 + gap }
            val buf = ShortArray(total)
            var i = 0
            for ((freq, ms) in notes) {
                val n = ms * rate / 1000
                for (k in 0 until n) {
                    val env = minOf(1.0, k / (rate * 0.005), (n - k) / (rate * 0.012))
                    buf[i++] = (sin(2 * PI * freq * k / rate) * env * 0.7 * Short.MAX_VALUE).toInt().toShort()
                }
                i += gap
            }

            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs).build()
            if (duck) am.requestAudioFocus(focus)

            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buf.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, buf.size)
            track.play()

            val durMs = total * 1000L / rate + 200
            handler.postDelayed({
                try { track.stop() } catch (_: Exception) {}
                track.release()
                if (duck) am.abandonAudioFocusRequest(focus)
            }, durMs)
        } catch (_: Exception) {
            // Never let a sound failure stop the timer.
        }
    }
}
