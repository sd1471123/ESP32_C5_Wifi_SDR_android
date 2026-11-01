# C5-SDR — ESP32-C5 Wi-Fi SDR for Android

**English** · [简体中文](README.zh-CN.md) · [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

An Android front-end for the ESP32-C5 Wi-Fi SDR firmware used by
[ESP32_C5_Wifi_spectrum_analyzer](https://github.com/sd1471123/ESP32_C5_Wifi_spectrum_analyzer).
It talks the same line protocol as `espsdr.py` over USB (OTG), and draws the spectrum and
waterfall natively with `Canvas` — no Python, no Qt, no plotting library.

Author: **arara** · <https://github.com/sd1471123/>

## Build

1. Android Studio (Ladybug or newer, JDK 17) → **File → Open** → select this directory.
2. Let Gradle sync (it creates `gradlew` for you if you want the wrapper committed).
3. Plug the phone in, **Run**.

There is no Gradle wrapper in the repository on purpose (the wrapper JAR is binary);
Android Studio's bundled Gradle works fine. From the command line, with a local Gradle 8.7+:

```
gradle :app:assembleDebug
```

### Release signing

`app/build.gradle.kts` signs the release build from `keystore.properties` (not in git):

```
storeFile=keystore/sdr-release.jks
storePassword=...
keyAlias=sdr
keyPassword=...
```

Without that file `assembleRelease` simply produces an unsigned APK.

### Launcher icon

All mipmap densities and the adaptive icon are generated from `icon.png`:

```
powershell -ExecutionPolicy Bypass -File tools/make_icons.ps1
```

## Hardware

- ESP32-C5 board flashed with the ESP-SDR firmware (see the desktop project's `flash_c5.py`).
- Connect the board's **"USB" port (native USB Serial/JTAG)** to the phone with an OTG adapter.
- **Power**: the radio draws current spikes at start-up and a phone's OTG port often cannot
  supply them — the board may brown out and reboot. Use a powered OTG hub / Y-cable if the
  connection drops right after "connected".
- The UART port (CH343, VID `0x1A86`) is accepted by the device filter too, but this MVP has no
  baud-rate handling beyond the CDC line coding, so native USB is the supported path.

## What works

| Feature | Status |
| --- | --- |
| Landscape two-pane UI (control rail + spectrum), dark card theme, centre-frequency / LO rows hidden while sweeping | ✅ |
| Device detection (VID `0x303A` / `0x1A86`), USB permission flow | ✅ |
| SYNC/resync handshake, `INFO` / `CAPS` / `LIMITS?` | ✅ |
| `CAP16` capture with CRC32 verification | ✅ |
| Live spectrum: average + peak hold (Hann window, per-frame DC removal, fftshift) | ✅ |
| Auto-scaled dB axis: 70 dB window anchored to the noise floor, labelled every 20 dB | ✅ |
| Waterfall (scrolling bitmap, blue→red map on the same auto-scaled range) | ✅ |
| Center frequency input + tuning | ✅ |
| Manual gain slider (0–80) | ✅ |
| Sample rate 80…4 MS/s, FFT 256…2048, analog bandwidth 11–48 MHz, LO offset | ✅ |
| Band sweep: 2.4 GHz 7 steps / 5 GHz 39 steps, stitched with the ±10–20 MHz window | ✅ |
| Sweep display holds the previous pass in not-yet-scanned bins; peak hold persists (2 dB/sweep decay) | ✅ |
| Channel presets: every 20 MHz channel, plus the 40/80 MHz groupings used by 5 GHz APs | ✅ |
| Per-20 MHz-channel activity strip + "busiest channel" readout | ✅ |
| Detection rate per channel (% of finished sweeps 6 dB above the noise floor) | ✅ |
| Time-domain strip in live mode (block power per FFT frame) | ✅ |
| Clipping statistic (share of I/Q samples at full scale) with a warning | ✅ |
| Frame rate / RMS / noise-floor readout | ✅ |
| Level marker (tap), tune (double tap), drag to pan, pinch to zoom | ✅ |
| I/Q recording to a file picked with the system document picker (`.cfile`, signed 8-bit, ±127 = full scale) | ✅ |
| Chinese / English UI, defaulting to the system language (everything else falls back to English), switchable in the rail | ✅ |
| Immersive full screen (edge-to-edge, display cutout kept clear) with a centred clock + battery readout | ✅ |
| Channel presets sorted by channel number, 40/80 MHz groupings interleaved with the 20 MHz ones | ✅ |

## Not implemented yet

- `.npy` / `.json` metadata sidecars for recordings (only raw `.cfile` so far)
- Firmware flashing from the phone

Recording in **sweep mode** writes every step as it is captured, so the file is a sequence of
20 MHz slices rather than one continuous band — use live mode for a clean continuous capture.

## Protocol notes (why the code looks like this)

- **DTR must be asserted** (`SET_CONTROL_LINE_STATE`, bit 0) or the firmware never sends data.
- **RTS stays de-asserted** (bit 1 clear) so the board is not reset by the auto-reset circuit.
- A previous client that died mid-transfer leaves the device blocked on output, so `resync()`
  drains the port and retries `SYNC <nonce>` up to 8 times, exactly like `espsdr.py`.
- `CAP16 <n> <rate>` answers `DATA <n> <crc32> <us>` plus `n*2` bytes of signed 8-bit I/Q
  (I, Q interleaved), CRC32 = zlib compatible. Rate indices 0–5 are 80/40/20/10/8/4 MS/s.
- Samples are scaled by 1/128 to `[-1, 1)` full scale.

## Layout

```
app/src/main/java/io/github/sd1471123/sdr/
  Espsdr.kt       USB CDC + line protocol client
  Fft.kt          radix-2 FFT
  Dsp.kt          capture -> average / peak-hold spectra, block power, clipping (mirrors sdr_core.spectra)
  Bands.kt        band plans, channel + 20/40/80 MHz presets, Mode enum, SdrFrame
  SdrEngine.kt    capture thread: live + band sweep, channel activity / detection rate
  IqRecorder.kt   raw int8 I/Q writer (.cfile)
  SpectrumView.kt Canvas spectrum, time-domain strip, activity + detection bars, waterfall, marker/zoom
  MainActivity.kt USB permission, mode/parameter spinners, recording, language, UI wiring
app/src/main/res/values-zh/strings.xml   Chinese UI strings (values/ is English)
tools/make_icons.ps1                     regenerates every launcher icon from icon.png
```

Build check: `gradle :app:assembleDebug` passes with AGP 8.5.2 / Kotlin 1.9.24 (SDK 34).

## License

GPL-3.0 — see [LICENSE](LICENSE).

Copyright (C) 2026 arara &lt;https://github.com/sd1471123/&gt;

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.
