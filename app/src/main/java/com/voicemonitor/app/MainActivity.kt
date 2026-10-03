package com.voicemonitor.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.voicemonitor.app.databinding.ActivityMainBinding

/**
 * One big button. Tap once, put the phone down, talk.
 */
class MainActivity : AppCompatActivity(), MonitorBus.Observer {

    private lateinit var binding: ActivityMainBinding
    private var serviceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.modeGroup.setOnCheckedChangeListener { _, id ->
            if (!serviceRunning) {
                binding.modeHint.text = when (id) {
                    binding.rbAuto.id -> "Auto — picks the lowest-latency route available"
                    binding.rbLow.id -> "Low latency — SCO, thinner audio, ~40–80 ms"
                    else -> "Full quality — A2DP, better audio, ~150–250 ms"
                }
            }
        }

        binding.toggleButton.setOnClickListener {
            if (serviceRunning) stopService() else startService()
        }
        binding.muteButton.setOnClickListener {
            if (serviceRunning) MonitorService.toggleMute(this)
        }
    }

    private fun currentMode(): MonitorMode = when (binding.modeGroup.checkedRadioButtonId) {
        binding.rbLow.id -> MonitorMode.LOW_LATENCY
        binding.rbQuality.id -> MonitorMode.QUALITY
        else -> MonitorMode.AUTO
    }

    private fun startService() {
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!hasNotif()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        serviceRunning = true
        binding.toggleButton.text = "STOP"
        binding.toggleButton.setBackgroundColor(0xFFB3261E.toInt())
        binding.modeGroup.isEnabled = false
        MonitorService.start(this, currentMode(), false)
    }

    private fun stopService() {
        MonitorService.stop(this)
        serviceRunning = false
        binding.toggleButton.text = "START"
        binding.toggleButton.setBackgroundColor(0xFF1B6C3A.toInt())
        binding.modeGroup.isEnabled = true
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotif() =
            android.os.Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        MonitorBus.add(this)
    }

    override fun onStop() {
        super.onStop()
        MonitorBus.remove(this)
    }

    override fun onState(state: AudioEngine.State) {
        runOnUiThread {
            serviceRunning = state.running || state.mode.isNotEmpty()
            binding.status.text = state.error ?: when {
                !state.running && state.mode.isEmpty() -> "Ready"
                !state.running -> "Stopped"
                else -> "Listening — ${state.mode}"
            }
            binding.latency.text = if (state.running && state.actualSampleRate > 0) {
                "buffer %.0f ms · %d Hz".format(state.bufferLatencyMs, state.actualSampleRate)
            } else {
                "—"
            }
            binding.level.progress = (state.level * 100).toInt()

            if (state.running && state.muted) {
                binding.muteButton.text = "UNMUTE"
                binding.muteButton.isEnabled = true
            } else if (state.running) {
                binding.muteButton.text = "MUTE"
                binding.muteButton.isEnabled = true
            } else {
                binding.muteButton.text = "MUTE"
                binding.muteButton.isEnabled = false
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && hasMic()) startService()
    }
}