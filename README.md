# Voice Monitor

An always-on "hear yourself speaking" app for Android, built for language practice.

## Why

To improve spoken English you need to hear your own voice while you talk — rhythm,
stress, intonation, consonants. Existing Android mic-monitor apps are all start/stop
and all route over Bluetooth **A2DP** (150–300 ms, and some route via HFP for
telephone-bandwidth audio).

## Stage 1 (current): route diagnostic

This first build does not stream audio. It enumerates every audio input/output the
phone exposes and reports:

- whether the phone supports LE Audio
- whether your earbuds present as `BLE_HEADSET` (low latency, full bandwidth) or
  `BLUETOOTH_SCO` (classic HFP, narrowband)
- the device's reported output latency

That determines which audio path Stage 2 should request. Building the monitor before
confirming this risks shipping something that silently falls back to HFP.

## Build

Push to `main`; GitHub Actions produces `app-debug.apk` as an artifact.

Or locally: `./gradlew assembleDebug`

Requires JDK 17, Android SDK with platform 36.