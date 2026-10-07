package com.voicemonitor.app

import kotlin.math.abs
import kotlin.math.min

/**
 * Transport + real-time mixer for the "native speaker" model track in shadowing mode.
 *
 * The model audio is resampled on the fly with linear interpolation and added
 * directly into the same sample block as the microphone, so the two signals are
 * sample-locked and you hear them as one performance.
 */
class ShadowPlayer {

    @Volatile var pcm: ShortArray = ShortArray(0); private set
    @Volatile var srcRate: Int = 16_000; private set
    @Volatile var gain: Float = 0.6f
    @Volatile var loop: Boolean = true
    @Volatile var playing: Boolean = false
        private set

    @Volatile var loadedName: String? = null; private set

    /** Fractional read position, in source samples. */
    private var pos = 0.0
    private var engineRate = 16_000

    fun load(d: PcmDecoder.Decoded, name: String?) {
        pcm = d.pcm
        srcRate = d.sampleRate
        loadedName = name
        pos = 0.0
        playing = false
    }

    fun setEngineRate(rate: Int) {
        if (rate > 0) engineRate = rate
    }

    val durationSec: Float get() = if (srcRate > 0) pcm.size.toFloat() / srcRate else 0f
    val positionSec: Float get() = (pos / srcRate).toFloat()

    fun seekToFraction(f: Float) {
        pos = (f.coerceIn(0f, 1f) * pcm.size).toDouble()
    }

    fun skipBy(seconds: Double) {
        if (pcm.isEmpty()) return
        pos = (pos + seconds * srcRate).coerceIn(0.0, (pcm.size - 1).toDouble())
        if (pos >= pcm.size - 1) pos = 0.0
    }

    fun play() { if (pcm.isNotEmpty()) playing = true }
    fun pause() { playing = false }
    fun toggle(): Boolean { playing = !playing && pcm.isNotEmpty(); return playing }

    fun isLoaded(): Boolean = pcm.isNotEmpty()

    /**
     * Add [frames] worth of model audio into [dst], which already holds microphone
     * audio. Called on the playback thread only.
     */
    fun mixInto(dst: ShortArray, frames: Int) {
        val src = pcm
        if (src.isEmpty() || frames <= 0) return
        if (!playing || gain <= 0f) return

        val step = srcRate.toDouble() / engineRate.toDouble()
        val last = src.size - 1

        for (i in 0 until frames) {
            val idx = pos.toInt()
            if (idx >= last) {
                if (loop) pos = 0.0
                else { playing = false; return }
            }
            val frac = (pos - idx).toFloat()
            val a = src[idx.coerceIn(0, last)].toInt()
            val b = src[(idx + 1).coerceIn(0, last)].toInt()
            val sample = a + (b - a) * frac

            var v = dst[i].toInt() + (sample * gain).toInt()
            // Soft clip rather than hard wrap, so a loud overlap distorts gently
            // instead of turning into noise.
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE
            dst[i] = v.toShort()
            pos += step
        }
    }
}