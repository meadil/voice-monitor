package com.voicemonitor.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.voicemonitor.app.databinding.ActivityMainBinding

/**
 * Stage 1 diagnostic.
 *
 * This app deliberately does NOT stream audio yet. Its only job is to find out
 * which audio routes Android will actually give us on this phone + these earbuds,
 * so that Stage 2 does not silently fall back to telephone-quality HFP audio.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val audio: AudioManager
        get() = getSystemService(AUDIO_SERVICE) as AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scanButton.setOnClickListener { scan() }
        binding.copyButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("diag", binding.output.text.toString()))
            binding.copyButton.text = "COPIED"
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) scan() else report("Grant BLUETOOTH_CONNECT to continue.")
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun report(line: String) {
        Log.i(TAG, line)
        binding.output.append(line + "\n\n")
    }

    private fun scan() {
        binding.output.text = ""
        if (!hasPermission()) {
            report("Grant BLUETOOTH_CONNECT to continue.")
            binding.scanButton.setOnClickListener { requestPerms() }
            return
        }

        report("=== DEVICE ===")
        report("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        report("Manufacturer: ${Build.MANUFACTURER} ${Build.MODEL}")

        report("=== LE AUDIO SUPPORT ===")
        report("FEATURE_BLUETOOTH_LE:        ${has(PM_BT_LE)}")
        report("FEATURE_BLUETOOTH_LE_AUDIO: ${has(PM_BT_LE_AUDIO)}")

        report("=== OUTPUT DEVICES ===")
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { d ->
            report(describe(d, "OUT"))
        }

        report("=== INPUT DEVICES ===")
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { d ->
            report(describe(d, "IN "))
        }

        report("=== AVAILABLE COMMUNICATION DEVICES ===")
        val comm = audio.availableCommunicationDevices
        if (comm.isEmpty()) {
            report("(none — this means the earbuds are not exposing a mic/HFP profile)")
        }
        comm.forEach { d -> report(describe(d, "COM")) }

        report("=== CURRENT COMMUNICATION DEVICE ===")
        report(describe(audio.communicationDevice, "NOW"))

        report("=== OUTPUT LATENCY ===")
        report("reported output latency: ${audio.getProperty(AudioManager.PROPERTY_OUTPUT_LATENCY)} ms")
    }

    private fun requestPerms() {
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
    }

    private fun has(feature: String) =
        runCatching { packageManager.hasSystemFeature(feature) }.getOrDefault(false)

    private fun describe(d: AudioDeviceInfo?, tag: String): String {
        if (d == null) return "$tag (none)"
        val rates = d.sampleRates.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "?"
        val outCh = d.channelCounts.count { it == AudioDeviceInfo.CHANNEL_COUNT_OUT }
        return buildString {
            append("$tag ${typeName(d.type)}\n")
            append("      product='${d.productName}' addr=${d.address}\n")
            append("      id=${d.id} sampleRates=$rates outChannels=$outCh")
            if (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                append("\n      >>> LE AUDIO HEADSET <<<")
            }
            if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                append("\n      (classic HFP — narrowband telephone audio)")
            }
        }
    }

    private fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        else -> "TYPE_$t"
    }

    companion object {
        private const val TAG = "VoiceMonitorDiag"
        private const val PM_BT_LE = "android.hardware.bluetooth_le"
        private const val PM_BT_LE_AUDIO = "android.hardware.bluetooth_le_audio"
    }
}