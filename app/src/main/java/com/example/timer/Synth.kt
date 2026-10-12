package com.example.timer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Мягкие звуки для выхода из медитации. Синтезируются на лету — в APK нет аудиофайлов. */
object Synth {
    val NAMES = arrayOf("Тибетская чаша", "Мягкий колокольчик", "Низкий гонг", "Хрустальный перезвон")
    private const val SR = 22050

    // base — основная частота, r/a/d — обертоны: множитель частоты, громкость, скорость затухания,
    // atk — время нарастания (сек), strikes — удары: [смещение сек, множитель частоты, громкость]
    private class Spec(
        val base: Double, val r: DoubleArray, val a: DoubleArray, val d: DoubleArray,
        val atk: Double, val strikes: Array<DoubleArray>, val tauScale: Double
    )

    private val SPECS = arrayOf(
        Spec(196.0, doubleArrayOf(1.0, 1.005, 2.71, 5.16), doubleArrayOf(1.0, 0.8, 0.45, 0.2),
            doubleArrayOf(1.0, 1.0, 1.8, 3.0), 0.02, arrayOf(doubleArrayOf(0.0, 1.0, 1.0)), 1.0),
        Spec(659.0, doubleArrayOf(1.0, 2.76, 5.4), doubleArrayOf(1.0, 0.35, 0.15),
            doubleArrayOf(1.0, 2.0, 3.5), 0.005, arrayOf(doubleArrayOf(0.0, 1.0, 1.0)), 0.6),
        Spec(110.0, doubleArrayOf(1.0, 2.32, 3.9, 5.6), doubleArrayOf(1.0, 0.6, 0.3, 0.15),
            doubleArrayOf(1.0, 1.3, 1.8, 2.5), 0.08, arrayOf(doubleArrayOf(0.0, 1.0, 1.0)), 1.0),
        Spec(880.0, doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0, 0.15),
            doubleArrayOf(1.0, 2.5), 0.005,
            arrayOf(doubleArrayOf(0.0, 1.0, 0.8), doubleArrayOf(0.45, 1.25, 0.7), doubleArrayOf(0.9, 1.5, 0.9)), 0.5)
    )

    private fun render(idx: Int, secs: Int, vol: Float): ShortArray {
        val s = SPECS[idx]
        val n = SR * secs
        val buf = FloatArray(n)
        val tau0 = max(0.6, secs / 4.0) * s.tauScale
        for (st in s.strikes) {
            val start = (st[0] * SR).toInt()
            val f0 = s.base * st[1]
            for (k in s.r.indices) {
                val w = 2 * PI * f0 * s.r[k] / SR
                val amp = s.a[k] * st[2]
                val tau = tau0 / s.d[k] * SR
                var i = start
                while (i < n) {
                    val t = (i - start).toDouble()
                    val env = exp(-t / tau)
                    if (env < 0.0005) break
                    val atk = min(1.0, t / (s.atk * SR))
                    buf[i] += (amp * env * atk * sin(w * t)).toFloat()
                    i++
                }
            }
        }
        var peak = 0f
        for (x in buf) peak = max(peak, abs(x))
        val g = if (peak > 0f) vol * 0.9f / peak else 0f
        val fade = min(n, (0.3 * SR).toInt())
        return ShortArray(n) { i ->
            var x = buf[i] * g
            if (i > n - fade) x *= (n - i).toFloat() / fade
            (x * 32767).toInt().toShort()
        }
    }

    /** Играет звук secs секунд и блокирует поток. Прерывается через Thread.interrupt(). */
    fun play(idx: Int, secs: Int, vol: Float) {
        val data = render(idx.coerceIn(0, SPECS.size - 1), secs.coerceIn(1, 60), vol)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(SR)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack(attrs, fmt, data.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
        try {
            track.write(data, 0, data.size)
            track.play()
            Thread.sleep(secs.coerceIn(1, 60) * 1000L)
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }
}
