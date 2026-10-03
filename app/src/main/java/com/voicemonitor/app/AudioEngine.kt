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
import kotlin.math.sqrt

enum class MonitorMode { AUTO, LOW_LATENCY, QUALITY }

/**
 * Captures the phone microphone and plays it back out of the earbuds, continuously,
 * with the buffer fill level actively held at a fixed target so that the delay does
 * not slowly grow over a long session.
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
        val error: String? = null,
    )

    @Volatile private var running = false
    @Volatile private var muted = false
    private var thread: Thread? = null
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

        val resolved = resolveMode(requested)
        if (resolved == null) {
            emit(current.copy(error = "No output route available. Connect earbuds."))
            return
        }

        val usingSco = resolved == MonitorMode.LOW_LATENCY
        val sampleRate = if (usingSco) 16_000 else 48_000
        val usage = if (usingSco) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val contentType = if (usingSco) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC

        savedAudioMode = audio.mode
        val routed = if (usingSco) engageScoRoute() else restoreNormalRoute()

        val label = when (resolved) {
            MonitorMode.LOW_LATENCY -> "SCO (low latency)"
            MonitorMode.QUALITY -> "A2DP (full quality)"
            MonitorMode.AUTO -> resolved.name
        }

        running = true
        current = State(running = true, mode = label, actualSampleRate = sampleRate)
        emit(current)

        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runLoop(sampleRate, usage, contentType, usingSco)
        }, "voice-monitor-audio").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveMode(requested: MonitorMode): MonitorMode? {
        if (requested == MonitorMode.QUALITY) {
            return if (audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }) MonitorMode.QUALITY else null
        }
        val hasSco = audio.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (requested == MonitorMode.LOW_LATENCY) return if (hasSco) MonitorMode.LOW_LATENCY else null
        return when {
            hasSco -> MonitorMode.LOW_LATENCY
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> MonitorMode.QUALITY
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    private fun engageScoRoute(): Boolean {
        val sco = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: return false
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val ok = runCatching { audio.setCommunicationDevice(sco) }.getOrDefault(false)
        routeChanged = ok
        if (!ok) Log.w(TAG, "setCommunicationDevice(BLE/SCO) refused, falling back")
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun restoreNormalRoute(): Boolean {
        runCatching { audio.clearCommunicationDevice() }
        audio.mode = AudioManager.MODE_NORMAL
        routeChanged = false
        return true
    }

    /**
     * Prefer the phone's own microphone over the earbud microphone.
     *
     * In communication mode Android will happily route capture to the headset mic,
     * which for true wireless earbuds sits 20cm from your ear rather than your mouth.
     * That would quietly defeat the entire purpose of the app.
     */
    @SuppressLint("MissingPermission")
    private fun phoneMic(): AudioDeviceInfo? {
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull { it.address.equals("bottom", ignoreCase = true) }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: inputs.firstOrNull { it.address?.isEmpty() == false && it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    @SuppressLint("MissingPermission")
    private fun runLoop(requestedRate: Int, usage: Int, contentType: Int, usingSco: Boolean) {
        val inChannel = AudioFormat.CHANNEL_IN_MONO
        val outChannel = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        var minIn = AudioRecord.getMinBufferSize(requestedRate, inChannel, encoding)
        var minOut = AudioTrack.getMinBufferSize(requestedRate, outChannel, encoding)
        if (minIn <= 0) minIn = requestedRate / 50 * 2
        if (minOut <= 0) minOut = requestedRate / 50 * 2
        val bufBytes = max(minIn, minOut) * 2

        var record: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            // VOICE_RECOGNITION leaves echo cancellation / AGC / noise suppression off,
            // which is what we want: those systems are tuned to strip your own voice.
            val source = MediaRecorder.AudioSource.VOICE_RECOGNITION

            record = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(requestedRate)
                        .setChannelMask(inChannel)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                emit(current.copy(error = "AudioRecord failed to initialise"))
                return
            }

            phoneMic()?.let { record.preferredDevice = it }

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(requestedRate)
                        .setChannelMask(outChannel)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val rate = record.sampleRate
            val framesPerBurst = max(1, minOut / 2 / 2)
            val targetFrames = max(framesPerBurst * 2, rate * 40 / 1000)
            val tolerance = max(framesPerBurst, rate / 1000)

            val ring = RingBuffer(targetFrames + rate / 5)
            val capture = ShortArray(bufBytes / 2)
            val playback = ShortArray(max(targetFrames + tolerance, bufBytes / 2))

            current = current.copy(actualSampleRate = rate)
            record.startRecording()
            track.play()

            val levelBuf = ShortArray(framesPerBurst)
            var sinceUpdate = 0

            while (running) {
                val n = record.read(capture, 0, capture.size)
                if (n < 0) {
                    Log.w(TAG, "capture read error $n")
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) break
                    continue
                }
                ring.write(capture, n)

                val fill = ring.available()
                // Drift control: if the buffer has crept away from target, pull it
                // straight back before the error can accumulate over minutes.
                if (fill > targetFrames + tolerance) {
                    ring.skip(fill - targetFrames)
                }

                val want = targetFrames
                val got = ring.read(playback, min(want, playback.size))
                if (got < want) {
                    java.util.Arrays.fill(playback, got, want, 0)   // brief underrun: pad with silence
                }

                if (muted) {
                    java.util.Arrays.fill(playback, 0, want, 0)      // keep the clock running while silent
                }

                track.write(playback, 0, want, AudioTrack.WRITE_BLOCKING)

                sinceUpdate++
                if (sinceUpdate >= 4) {
                    sinceUpdate = 0
                    var peak = 0
                    for (i in 0 until min(n, playback.size)) peak = max(peak, abs(playback[i].toInt()))
                    val rms = sqrt(peak.toDouble()).toFloat() / 32768f
                    current = current.copy(
                        bufferLatencyMs = fill * 1000f / rate,
                        level = min(1f, rms * 3f),
                        error = null,
                    )
                    emit(current)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "monitor loop crashed", t)
            emit(current.copy(error = t.message ?: t.javaClass.simpleName))
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
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
        runCatching { thread?.join(1500) }
        thread = null
    }

    private fun emit(s: State) {
        current = s
        listener?.onState(s)
    }

    companion object { private const val TAG = "VoiceMonitor" }
}