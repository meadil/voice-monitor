package com.voicemonitor.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class MonitorMode { AUTO, LOW_LATENCY, QUALITY }

/**
 * Captures the phone microphone and plays it back out of the earbuds, continuously,
 * holding the buffer fill level at a fixed target so the delay does not slowly grow
 * over a long session.
 */
class AudioEngine(private val ctx: Context) {

    interface Listener {
        fun onState(state: State)
    }

    data class State(
        val running: Boolean = false,
        val mode: String = "",
        val actualSampleRate: Int = 0,
        val bufferLatencyMs: Float = 0f,
        val level: Float = 0f,
        val muted: Boolean = false,
        val underruns: Int = 0,
        val driftDrops: Int = 0,
        val error: String? = null,
    )

    @Volatile private var running = false
    @Volatile private var muted = false
    private var captureThread: Thread? = null
    private var playThread: Thread? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var routeChanged = false
    private var listener: Listener? = null
    private var current = State()

    private val audio: AudioManager
        get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun setListener(l: Listener?) { listener = l }

    @SuppressLint("MissingPermission")
    fun start(requested: MonitorMode) {
        if (running) return

        val resolved = resolveMode(requested) ?: run {
            emit(current.copy(error = "No output route available. Connect earbuds."))
            return
        }

        val usingSco = resolved == MonitorMode.LOW_LATENCY
        val preferredRate = if (usingSco) 16_000 else 48_000
        val usage = if (usingSco) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val contentType = if (usingSco) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC

        savedAudioMode = audio.mode
        if (usingSco) engageScoRoute() else restoreNormalRoute()

        val label = if (usingSco) "SCO (low latency)" else "A2DP (full quality)"
        running = true
        current = State(running = true, mode = label)
        emit(current)

        // Capture and playback run on separate threads. When both shared one thread,
        // every Bluetooth encoder stall on the capture side became an audible gap.
        val ring = RingBuffer(preferredRate / 3)   // ~330 ms of headroom

        // A freshly engaged Bluetooth route needs a moment to actually negotiate.
        // Capturing during that handshake is what made the first second sound robotic.
        if (usingSco) {
            val settleUntil = System.currentTimeMillis() + 700
            while (System.currentTimeMillis() < settleUntil) {
                runCatching { Thread.sleep(60) }
                if (!running) return
            }
        }

        playThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            playLoop(ring, preferredRate, usage, contentType)
        }, "voice-monitor-play").also { it.priority = Thread.MAX_PRIORITY; it.start() }

        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            captureLoop(ring, preferredRate, usingSco)
        }, "voice-monitor-cap").also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun resolveMode(requested: MonitorMode): MonitorMode? {
        val hasSco = audio.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val hasA2dp = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        return when (requested) {
            MonitorMode.QUALITY -> if (hasA2dp) MonitorMode.QUALITY else null
            MonitorMode.LOW_LATENCY -> if (hasSco) MonitorMode.LOW_LATENCY else null
            MonitorMode.AUTO -> when {
                hasSco -> MonitorMode.LOW_LATENCY
                hasA2dp -> MonitorMode.QUALITY
                else -> null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun engageScoRoute(): Boolean {
        val sco = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: return false
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val ok = runCatching { audio.setCommunicationDevice(sco) }.getOrDefault(false)
        routeChanged = ok
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun restoreNormalRoute() {
        runCatching { audio.clearCommunicationDevice() }
        audio.mode = AudioManager.MODE_NORMAL
        routeChanged = false
    }

    /**
     * Prefer the phone's own microphone. In communication mode Android will happily
     * capture from the headset mic, which for TWS earbuds sits 20cm from your ear
     * rather than your mouth — that would quietly defeat the whole point.
     */
    @SuppressLint("MissingPermission")
    private fun phoneMic(): AudioDeviceInfo? {
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull { it.address.equals("bottom", ignoreCase = true) }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    // ---------------------------------------------------------------- capture

    @SuppressLint("MissingPermission")
    private fun captureLoop(ring: RingBuffer, preferredRate: Int, usingSco: Boolean) {
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        var record: AudioRecord? = null
        try {
            // VOICE_RECOGNITION leaves AEC/AGC/noise-suppression off, which is what
            // we want: those are tuned to strip your own voice out of the signal.
            val candidates = intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
            )
            for (src in candidates) {
                val r = runCatching {
                    AudioRecord.Builder()
                        .setAudioSource(src)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(encoding)
                                .setSampleRate(preferredRate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(preferredRate * 2)
                        .build()
                }.getOrNull()
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                    record = r
                    break
                }
                r?.release()
            }
            val rec = record ?: run {
                emit(current.copy(error = "Could not open the microphone"))
                running = false
                return
            }

            phoneMic()?.let { rec.preferredDevice = it }

            // THE IMPORTANT PART: the microphone may hand back a different sample rate
            // than we asked for. The playback side must be built to match exactly, or
            // the audio is resampled against the clock and sounds garbled.
            val realRate = rec.sampleRate
            current = current.copy(actualSampleRate = realRate)

            val chunk = ShortArray(max(256, realRate / 100))   // ~10 ms
            rec.startRecording()
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                when {
                    n > 0 -> ring.write(chunk, n)
                    n == AudioRecord.ERROR_INVALID_OPERATION ||
                        n == AudioRecord.ERROR_BAD_VALUE -> break
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "capture crashed", t)
            emit(current.copy(error = t.message ?: t.javaClass.simpleName))
            running = false
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }

    // ---------------------------------------------------------------- playback

    private fun playLoop(ring: RingBuffer, preferredRate: Int, usage: Int, contentType: Int) {
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        var track: AudioTrack? = null
        try {
            val minOut = AudioTrack.getMinBufferSize(preferredRate, AudioFormat.CHANNEL_OUT_MONO, encoding)
                .let { if (it <= 0) preferredRate / 50 * 2 else it }

            // Build the track at the same rate the microphone is actually running at.
            // We do not know that until the capture thread has opened the device, so
            // wait briefly for it rather than guessing.
            var rate = preferredRate
            val deadline = System.currentTimeMillis() + 3000
            while (current.actualSampleRate == 0 && System.currentTimeMillis() < deadline && running) {
                Thread.sleep(20)
            }
            rate = current.actualSampleRate.takeIf { it > 0 } ?: preferredRate

            // 5 ms chunks, 30 ms target. Every millisecond of buffer here is a
            // millisecond you hear yourself late, so these are deliberately tight.
            val chunkFrames = max(64, rate / 200)
            val targetFrames = rate * TARGET_MS / 1000
            val tolerance = max(chunkFrames / 2, rate / 400)

            // A large AudioTrack buffer is simply extra latency. Keep it modest now
            // that capture and playback run on separate threads and cannot starve
            // each other, and ask the HAL for its low-latency path.
            val trackBuf = max(minOut, chunkFrames * 4 * 2)

            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(trackBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                // Ask the HAL for its lowest-latency path.
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track = t

            val tmp = ShortArray(chunkFrames)
            t.play()

            var underruns = 0
            var drops = 0
            var sinceUpdate = 0
            var starvedChunks = 0

            while (running) {
                val got = ring.read(tmp, chunkFrames)
                val fill = ring.available()

                if (got <= 0) {
                    starvedChunks++
                    if (starvedChunks > 3) {
                        // Genuinely out of audio. A short burst of silence is the least
                        // bad option — much better than DC-hold padding or a hard skip.
                        java.util.Arrays.fill(tmp, 0, chunkFrames, 0)
                        t.write(tmp, 0, chunkFrames, AudioTrack.WRITE_BLOCKING)
                        underruns++
                    }
                    continue
                }
                starvedChunks = 0

                // If the buffer has crept above target, discard the excess from the
                // oldest end. Done here, before playback, so no chunk is ever cut in
                // the middle and nothing has to be padded afterwards.
                val excess = fill - targetFrames
                if (excess > tolerance) {
                    ring.skip(excess / 2)
                    drops++
                }

                // Write exactly what we have. Never pad, never repeat the last sample:
                // both of those distort the signal. AudioTrack's own buffer absorbs
                // the chunk-size variation, and pacing comes from WRITE_BLOCKING.
                t.write(tmp, 0, got, AudioTrack.WRITE_BLOCKING)

                sinceUpdate++
                // Throttle hard. Every emit used to trigger a notification rebuild,
                // a UI thread hop and string formatting, all at ~50Hz, which starved
                // this audio thread. 10Hz is plenty for a level meter.
                if (sinceUpdate >= 10) {
                    sinceUpdate = 0
                    var peak = 0
                    // Every 4th sample is plenty for a visual meter and costs a quarter
                    // of the CPU on the thread that must not miss deadlines.
                    var i = 0
                    while (i < got) {
                        val v = abs(tmp[i].toInt())
                        if (v > peak) peak = v
                        i += 4
                    }
                    current = current.copy(
                        bufferLatencyMs = ring.available() * 1000f / rate,
                        level = min(1f, (peak / 32768f) * 4f),
                        underruns = underruns,
                        driftDrops = drops,
                        error = null,
                    )
                    emit(current)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "playback crashed", t)
            emit(current.copy(error = t.message ?: t.javaClass.simpleName))
            running = false
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            if (routeChanged) {
                runCatching { audio.clearCommunicationDevice() }
                routeChanged = false
            }
            runCatching { audio.mode = savedAudioMode }
            running = false
            current = current.copy(running = false, level = 0f)
            emit(current)
        }
    }

    fun setMuted(m: Boolean) {
        muted = m
        emit(current.copy(muted = m))
    }

    fun toggleMute(): Boolean {
        muted = !muted
        emit(current.copy(muted = muted))
        return muted
    }

    fun stop() {
        running = false
        runCatching { captureThread?.join(1200) }
        runCatching { playThread?.join(1200) }
        captureThread = null
        playThread = null
    }

    private fun emit(s: State) {
        current = s
        listener?.onState(s)
    }

    companion object {
        private const val TAG = "VoiceMonitor"
        private const val TARGET_MS = 30
    }
}