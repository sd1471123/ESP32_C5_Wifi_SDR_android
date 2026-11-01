# C5-SDR — ESP32-C5 Wi-Fi SDR Android 客户端

[English](README.md) · **简体中文** · [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

本项目是 [ESP32_C5_Wifi_spectrum_analyzer](https://github.com/sd1471123/ESP32_C5_Wifi_spectrum_analyzer)
所用 ESP32-C5 Wi-Fi SDR 固件的 Android 前端。它通过 USB（OTG）与 `espsdr.py` 使用同一套行协议通信，
频谱图和瀑布图全部用 `Canvas` 原生绘制——不依赖 Python、Qt 或任何绘图库。

作者：**arara** · <https://github.com/sd1471123/>

## 编译

1. Android Studio（Ladybug 或更新版本，JDK 17）→ **File → Open** → 选择本目录。
2. 等待 Gradle 同步（若想把 wrapper 提交入库，同步后会生成 `gradlew`）。
3. 手机连上电脑，点 **Run**。

仓库刻意不放 Gradle wrapper（wrapper 的 JAR 是二进制文件），用 Android Studio 自带的 Gradle 即可。
命令行下，本地装有 Gradle 8.7+ 时：

```
gradle :app:assembleDebug
```

### Release 签名

`app/build.gradle.kts` 会读取 `keystore.properties`（不入库）为 release 包签名：

```
storeFile=keystore/sdr-release.jks
storePassword=...
keyAlias=sdr
keyPassword=...
```

缺少该文件时，`assembleRelease` 只会产出未签名的 APK。

### 启动图标

各密度 mipmap 与自适应图标都由 `icon.png` 生成：

```
powershell -ExecutionPolicy Bypass -File tools/make_icons.ps1
```

## 硬件

- 已烧录 ESP-SDR 固件的 ESP32-C5 开发板（见桌面项目里的 `flash_c5.py`）。
- 用 OTG 转接头把板子的 **"USB" 口（原生 USB Serial/JTAG）** 接到手机。
- **供电**：射频启动瞬间电流尖峰较大，手机 OTG 口常常供不上，板子会欠压重启。
  如果刚显示"已连接"就掉线，请改用带独立供电的 OTG HUB 或 Y 型线。
- 设备过滤器也接受 UART 口（CH343，VID `0x1A86`），但本 MVP 除 CDC line coding 外没有处理
  波特率，因此推荐走原生 USB。

## 已实现

| 功能 | 状态 |
| --- | --- |
| 横屏双栏界面（控制栏 + 频谱），深色卡片主题，扫描模式下隐藏中心频率 / LO 行 | ✅ |
| 设备识别（VID `0x303A` / `0x1A86`）、USB 权限流程 | ✅ |
| SYNC / resync 握手，`INFO` / `CAPS` / `LIMITS?` | ✅ |
| `CAP16` 采集并校验 CRC32 | ✅ |
| 实时频谱：平均值 + 峰值保持（Hann 窗、逐帧去直流、fftshift） | ✅ |
| 自动量程 dB 轴：以底噪为基准的 70 dB 窗口，每 20 dB 一条标注 | ✅ |
| 瀑布图（滚动位图，同一自动量程上的蓝→红映射） | ✅ |
| 中心频率输入与调谐 | ✅ |
| 手动增益滑条（0–80） | ✅ |
| 采样率 80…4 MS/s、FFT 256…2048、模拟带宽 11–48 MHz、LO 偏移 | ✅ |
| 频段扫描：2.4 GHz 7 步 / 5 GHz 39 步，按 ±10–20 MHz 窗口拼接 | ✅ |
| 扫描显示在尚未扫到的频点保留上一轮结果；峰值保持按 2 dB/轮衰减 | ✅ |
| 信道预设：全部 20 MHz 信道，外加 5 GHz AP 常用的 40/80 MHz 组合 | ✅ |
| 每 20 MHz 信道的占用度条 + "最忙信道"读数 | ✅ |
| 每信道检出率（一轮扫描中高出底噪 6 dB 的占比） | ✅ |
| 实时模式下的时域条（每帧 FFT 的块功率） | ✅ |
| 削顶统计（满量程采样点占比）与告警 | ✅ |
| 帧率 / RMS / 底噪读数 | ✅ |
| 电平游标（单击）、调谐（双击）、拖动平移、双指缩放 | ✅ |
| I/Q 录制到系统文件选择器指定的文件（`.cfile`，有符号 8 位，±127 为满量程） | ✅ |
| 中文 / 英文界面，默认跟随系统语言（其他语言回落英文），可在侧栏切换 | ✅ |
| 沉浸式全屏（边到边、避开摄像头挖孔），顶部居中显示时间与电量 | ✅ |
| 信道预设按频道号升序排列，40/80 MHz 组合与 20 MHz 交错 | ✅ |

## 尚未实现

- 录制文件的 `.npy` / `.json` 元数据伴随文件（目前只有原始 `.cfile`）
- 在手机上刷写固件

**扫描模式**下录制会把每一步抓到的数据依次写盘，因此文件是一串 20 MHz 片段而不是连续频段——
需要干净的连续采集请用实时模式。

## 协议要点（代码为何这样写）

- **必须拉高 DTR**（`SET_CONTROL_LINE_STATE` 第 0 位），否则固件不会发数据。
- **RTS 保持不拉高**（第 1 位为 0），避免自动复位电路把板子复位。
- 上一个客户端在传输中途死掉会让设备卡在输出上，因此 `resync()` 会清空端口并最多重试 8 次
  `SYNC <nonce>`，与 `espsdr.py` 完全一致。
- `CAP16 <n> <rate>` 返回 `DATA <n> <crc32> <us>`，随后是 `n*2` 字节有符号 8 位 I/Q（I、Q 交错），
  CRC32 与 zlib 兼容。速率索引 0–5 对应 80/40/20/10/8/4 MS/s。
- 采样值除以 128 缩放到 `[-1, 1)` 满量程。

## 目录结构

```
app/src/main/java/io/github/sd1471123/sdr/
  Espsdr.kt       USB CDC + 行协议客户端
  Fft.kt          基-2 FFT
  Dsp.kt          采集 -> 平均 / 峰值保持频谱、块功率、削顶统计（对应 sdr_core.spectra）
  Bands.kt        频段规划、信道与 20/40/80 MHz 预设、Mode 枚举、SdrFrame
  SdrEngine.kt    采集线程：实时 + 频段扫描、信道占用度 / 检出率
  IqRecorder.kt   原始 int8 I/Q 写入（.cfile）
  SpectrumView.kt Canvas 频谱、时域条、占用度与检出率条、瀑布、游标/缩放
  MainActivity.kt USB 权限、模式/参数下拉框、录制、语言切换、界面装配
app/src/main/res/values-zh/strings.xml   中文文案（values/ 为英文）
tools/make_icons.ps1                     由 icon.png 重新生成全部启动图标
```

编译检查：`gradle :app:assembleDebug` 在 AGP 8.5.2 / Kotlin 1.9.24（SDK 34）下通过。

## 许可证

GPL-3.0，详见 [LICENSE](LICENSE)。

Copyright (C) 2026 arara &lt;https://github.com/sd1471123/&gt;

本程序是自由软件：你可以依据自由软件基金会发布的 GNU 通用公共许可证（GNU GPL）第 3 版，
或（任选）之后的版本，重新分发和/或修改它。

本程序的分发是希望它有用，但**不提供任何担保**，甚至不提供适销性或特定用途适用性的默示担保。
详见 GNU 通用公共许可证。

> 采用 GPL-3.0 的含意：任何基于本项目的衍生作品（包括修改后重新分发）都必须同样以 GPL-3.0 开源，
> 并保留原始版权声明。
