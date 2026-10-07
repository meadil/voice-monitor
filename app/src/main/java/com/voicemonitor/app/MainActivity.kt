package com.voicemonitor.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.voicemonitor.app.databinding.ActivityMainBinding
import java.util.concurrent.Executors

/**
 * Two modes:
 *
 *  - Monitor: tap START, the screen closes, you just talk. No UI means no CPU
 *    stolen from the audio thread, which is what previously made the audio drop
 *    out whenever the screen was lit.
 *  - Shadow: load a native-speaker track and it is mixed into the very same
 *    output stream as your microphone, so you can repeat over the top of it.
 */
class MainActivity : AppCompatActivity(), MonitorBus.Observer {

    private lateinit var binding: ActivityMainBinding
    private var serviceRunning = false
    private val worker = Executors.newSingleThreadExecutor()
    private var userSeeking = false

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
        binding.loadStatus.text = "Decoding $name…"
        worker.execute {
            val decoded = PcmDecoder.decode(applicationContext, uri)
            runOnUiThread {
                val sp = MonitorBus.shadow
                if (decoded == null || sp == null) {
                    binding.loadStatus.text = "Could not decode that file. Try mp3, m4a or wav."
                    return@runOnUiThread
                }
                sp.load(decoded, name)
                binding.loadStatus.text =
                    "${name} · %.1f s · ${decoded.sampleRate} Hz".format(decoded.durationSec)
                binding.playButton.text = "PLAY"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleButton.setOnClickListener { if (serviceRunning) stopService() else startService() }
        binding.muteButton.setOnClickListener { if (serviceRunning) MonitorService.toggleMute(this) }

        binding.pickButton.setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }
        binding.playButton.setOnClickListener {
            MonitorBus.shadow?.let { if (it.toggle()) binding.playButton.text = "PAUSE" else binding.playButton.text = "PLAY" }
        }
        binding.backButton.setOnClickListener { MonitorBus.shadow?.skipBy(-5.0) }
        binding.forwardButton.setOnClickListener { MonitorBus.shadow?.skipBy(5.0) }
        binding.loopCheck.setOnCheckedChangeListener { _, v -> MonitorBus.shadow?.loop = v }
        binding.modelVolume.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) MonitorBus.shadow?.gain = p / 100f
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) MonitorBus.shadow?.seekToFraction(p / 1000f)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { userSeeking = false }
        })
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startService() {
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        serviceRunning = true
        binding.toggleButton.text = "STOP"
        binding.toggleButton.setBackgroundColor(0xFFB3261E.toInt())
        MonitorService.start(this, MonitorMode.AUTO, false)

        // Only get out of the way in plain monitor mode. Shadowing genuinely needs
        // its transport controls on screen, so we stay — but they update slowly and
        // draw almost nothing, which keeps the CPU cost negligible.
        if (!MonitorBus.shadow?.isLoaded().orFalse()) {
            Toast.makeText(this, "Monitoring. Stop or Mute from the notification.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun Boolean?.orFalse() = this == true

    private fun stopService() {
        MonitorService.stop(this)
        serviceRunning = false
        binding.toggleButton.text = "START"
        binding.toggleButton.setBackgroundColor(0xFF1B6C3A.toInt())
    }

    override fun onStart() {
        super.onStart()
        MonitorBus.add(this)
        MonitorBus.shadow?.let { sp ->
            if (sp.isLoaded()) {
                binding.seekBar.max = 1000
                binding.loopCheck.isChecked = sp.loop
                binding.modelVolume.progress = (sp.gain * 100).toInt()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        MonitorBus.remove(this)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onState(state: AudioEngine.State) {
        runOnUiThread {
            binding.status.text = state.error ?: if (state.running) "Listening — ${state.mode}" else "Ready"
            binding.latency.text = if (state.running && state.actualSampleRate > 0) {
                "buffer %.0f ms · %d Hz · %d gaps".format(
                    state.bufferLatencyMs, state.actualSampleRate, state.underruns
                )
            } else ""
            binding.level.progress = (state.level * 100).toInt()

            val sp = MonitorBus.shadow
            if (sp != null && sp.isLoaded() && !userSeeking && sp.durationSec > 0) {
                binding.seekBar.progress = ((sp.positionSec / sp.durationSec) * 1000).toInt().coerceIn(0, 1000)
                binding.timeLabel.text = "%.1f / %.1f s".format(sp.positionSec, sp.durationSec)
                if (!sp.playing && binding.playButton.text == "PLAY") Unit
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && hasMic()) startService()
    }
}