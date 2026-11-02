# Verify the SPEC on-chip spectrum stream on the real board:
#  - stop any stream left running, SYNC, then a clean CAP16 reference
#  - start "SPEC 0 1 1 0 <rate> <n>", check the ack line, collect ~2 s of SPC1 frames
#  - verify CRC + header fields; cross-check the level against CAP16 (both DC-removed,
#    same convention: code/2 - 84.3 = 10log10|FFT(v*hann*2^15)/n|^2, full scale = 0 dBFS)
#  - stop with an empty line, expect SPECEND status 0

param([string]$Port = "COM5", [int]$Baud = 115200, [int]$Nfft = 512, [int]$Ms = 2000, [int]$Freq = 5180)

$ErrorActionPreference = 'Stop'

$global:crcTable = New-Object 'long[]' 256
for ($n = 0; $n -lt 256; $n++) {
    $c = [long]$n
    for ($k = 0; $k -lt 8; $k++) {
        if ($c -band 1) { $c = 3988292384 -bxor ($c -shr 1) } else { $c = $c -shr 1 }
    }
    $global:crcTable[$n] = $c
}
function Crc32([byte[]]$data) {
    $crc = [long]4294967295
    foreach ($b in $data) {
        $idx = ($crc -bxor $b) -band 0xFF
        $crc = ($global:crcTable[$idx] -bxor ($crc -shr 8)) -band 4294967295
    }
    return ($crc -bxor 4294967295) -band 4294967295
}

$p = New-Object System.IO.Ports.SerialPort($Port, $Baud, 'None', 8, 'One')
$p.ReadTimeout = 800
$p.DtrEnable = $true
$p.RtsEnable = $false
$p.Open()
Start-Sleep -Milliseconds 300

function Drain { $p.DiscardInBuffer() }
function Send($cmd) { $p.Write($cmd + "`n") }

function ReadAll([int]$ms) {
    $ms2 = New-Object System.IO.MemoryStream
    $deadline = [DateTime]::Now.AddMilliseconds($ms)
    while ([DateTime]::Now -lt $deadline) {
        $n = $p.BytesToRead
        if ($n -gt 0) {
            $tmp = New-Object byte[] $n
            $null = $p.Read($tmp, 0, $n)
            $ms2.Write($tmp, 0, $n)
        } else { Start-Sleep -Milliseconds 5 }
    }
    return $ms2.ToArray()
}

function WaitLine([scriptblock]$pred, [int]$timeoutMs) {
    $deadline = [DateTime]::Now.AddMilliseconds($timeoutMs)
    while ([DateTime]::Now -lt $deadline) {
        try { $line = $p.ReadLine().Trim() } catch { $line = "" }
        if ($line -and (& $pred $line)) { return $line }
    }
    throw "timeout waiting for expected reply"
}

# A previous run may have left the infinite SPEC stream up: stop it first.
Send "`n"; Start-Sleep -Milliseconds 400
Drain
Send "SYNC 777"; Start-Sleep -Milliseconds 400
Drain
Send "CAPS"; Start-Sleep -Milliseconds 300
"CAPS: " + [Text.Encoding]::ASCII.GetString((ReadAll 400)).Trim()

Send "FREQ $Freq"; Start-Sleep -Milliseconds 300
Send "GAIN MANUAL 40"; Start-Sleep -Milliseconds 300

# ---- CAP16 reference ---------------------------------------------------------
Drain
Send "CAP16 4096 0"
$raw = ReadAll 3000
$txt = [Text.Encoding]::ASCII.GetString($raw)
$nl = $txt.IndexOf("`n")
if ($nl -lt 0 -or -not $txt.StartsWith("DATA")) { throw "no DATA header: [$($txt.Substring(0, [Math]::Min(48, $txt.Length)))]" }
$parts = $txt.Substring(0, $nl).Trim() -split '\s+'
$pay = New-Object byte[] (4096 * 2)
[Array]::Copy($raw, $nl + 1, $pay, 0, 4096 * 2)
$crcOk = ("{0:x8}" -f (Crc32 $pay)) -eq $parts[2].ToLower()
$sumI = 0.0; $sumQ = 0.0; $pow = 0.0
for ($i = 0; $i -lt 8192; $i += 2) {
    $re = [int]$pay[$i]; if ($re -ge 128) { $re -= 256 }
    $im = [int]$pay[$i + 1]; if ($im -ge 128) { $im -= 256 }
    $sumI += $re; $sumQ += $im
    $pow += [double]$re * $re + [double]$im * $im
}
$sumI /= 4096.0; $sumQ /= 4096.0
$dcRemoved = [Math]::Max(0.0, $pow - 4096.0 * ($sumI * $sumI + $sumQ * $sumQ))
$cap16Db = 10 * [Math]::Log10($pow / 8192.0 + 1e-12)
$cap16DbDc = 10 * [Math]::Log10($dcRemoved / 8192.0 + 1e-12)
"CAP16: crcOk=$crcOk  rms=$('{0:N1}' -f $cap16Db) dBFS   DC-removed: $('{0:N1}' -f $cap16DbDc) dBFS"

# ---- SPEC stream -------------------------------------------------------------
Drain
Send "SPEC 0 1 1 0 0 $Nfft"
$ack = WaitLine { param($l) $l.StartsWith("SPEC ") -or $l.StartsWith("ERR") } 3000
if ($ack.StartsWith("ERR")) { throw "SPEC refused: $ack" }
"SPEC ack: [$ack]"

$raw = ReadAll $Ms
# stop now, so nothing is lost while we decode; retry once if the report is missed.
# NB: the frame CRC is immediately followed by "SPECEND ..." with no newline in between,
# so the report arrives glued to the tail of a binary line — match with -match.
function StopStream {
    Send "`n"
    try { return WaitLine { param($l) $l -match "SPECEND (\d+)" } 2500 } catch { return $null }
}
$end = StopStream
if (-not $end) {
    Drain
    $end = StopStream
}
if (-not $end) {
    # diagnosis: show whatever lines do arrive
    for ($i = 0; $i -lt 5; $i++) { try { "DBG line: [$($p.ReadLine().Trim().Substring(0, 40))]" } catch { break } }
    throw "no SPECEND after stop"
}

"stream bytes: $($raw.Length)"
$txt = [Text.Encoding]::ASCII.GetString($raw)
$frameLen = 32 + $Nfft
$count = 0; $crcFails = 0
$last = New-Object double[] $Nfft
$pos = 0
while ($true) {
    $idx = $txt.IndexOf("SPC1", $pos)
    if ($idx -lt 0 -or $idx + $frameLen -gt $raw.Length) { break }
    $log2n = $raw[$idx + 26]
    $fmt = $raw[$idx + 27]
    $want = [long]$raw[$idx + $frameLen - 4] -bor ([long]$raw[$idx + $frameLen - 3] -shl 8) -bor ([long]$raw[$idx + $frameLen - 2] -shl 16) -bor ([long]$raw[$idx + $frameLen - 1] -shl 24)
    $crc = Crc32 ($raw[$idx..($idx + $frameLen - 5)])
    if ($log2n -eq [Math]::Log($Nfft, 2) -and $fmt -eq 2 -and $crc -eq $want) {
        for ($j = 0; $j -lt $Nfft; $j++) { $last[$j] = [double]$raw[$idx + 28 + $j] }
        $count++
    } else {
        $crcFails++
    }
    $pos = $idx + 1
}
"decoded frames: $count  crc/header fails: $crcFails"
if ($count -eq 0) { throw "no valid SPC1 frames" }

# newest frame: codes -> app dBFS scale, Parseval rms estimate (rms^2 = sum(p)/3)
$sumPow = 0.0; $peak = -999.0; $peakBin = 0; $codeSum = 0.0
for ($j = 0; $j -lt $Nfft; $j++) {
    $db = $last[$j] / 2.0 - 84.3
    $sumPow += [Math]::Pow(10.0, $db / 10.0)
    $codeSum += $last[$j]
    if ($db -gt $peak) { $peak = $db; $peakBin = $j }
}
$specDb = 10 * [Math]::Log10($sumPow / 3.0)
$pkFreq = $Freq + $(if ($peakBin -lt $Nfft / 2) { $peakBin * 80.0 / $Nfft } else { ($peakBin - $Nfft) * 80.0 / $Nfft })
"SPEC newest: mean code=$('{0:N1}' -f ($codeSum / $Nfft))  peak code=$([int](($last | Measure-Object -Maximum).Maximum)) at bin $peakBin ($('{0:N2}' -f $pkFreq) MHz, DC bin = $([int]($peakBin -eq 0)))"
"SPEC Parseval rms: $('{0:N1}' -f $specDb) dBFS   vs CAP16 DC-removed: $('{0:N1}' -f $cap16DbDc) dBFS   delta: $('{0:N1}' -f ($specDb - $cap16DbDc)) dB"

# coarse ASCII shape: display column c holds wire bin (c + n/2) % n (fftshift), 64 columns
$cols = 64
$row = New-Object double[] $cols
for ($c = 0; $c -lt $cols; $c++) {
    for ($j = [int][Math]::Floor($c * $Nfft / $cols); $j -lt [int][Math]::Floor(($c + 1) * $Nfft / $cols); $j++) {
        $v = $last[($j + $Nfft / 2) % $Nfft]
        if ($v -gt $row[$c]) { $row[$c] = $v }
    }
}
$lo = ($row | Measure-Object -Minimum).Minimum
$hi = ($row | Measure-Object -Maximum).Maximum
for ($c = 0; $c -lt $cols; $c++) {
    $bar = [int](($row[$c] - $lo) / ($hi - $lo + 1e-9) * 40)
    # NB: PowerShell variables are case-insensitive — do not name this $freq ($Freq is the param)
    $f = $Freq - 40.0 + ($c + 0.5) * 80.0 / $cols
    "{0,7:N1} MHz |{1}| {2,5:N1}" -f $f, ('#' * $bar), $row[$c]
}

"end: $end"
$p.Close()
if ($count -gt 0 -and $end -match "SPECEND 0 ") { "OK" } else { "CHECK FAILED" }
