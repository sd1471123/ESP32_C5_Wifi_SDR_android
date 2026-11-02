# Run several full sweeps and print the detection rates at thresholds 6 and 12 dB,
# like the app's detectionRates() vs the desktop's act > 12.
param([string]$Port = "COM5", [int]$Baud = 115200, [int]$Nfft = 256, [int]$Samples = 16380, [int]$Sweeps = 5)

$ErrorActionPreference = 'Stop'
$p = New-Object System.IO.Ports.SerialPort($Port, $Baud, 'None', 8, 'One')
$p.ReadTimeout = 1500
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
        if ($n -gt 0) { $tmp = New-Object byte[] $n; $null = $p.Read($tmp, 0, $n); $ms2.Write($tmp, 0, $n) }
        elseif ([DateTime]::Now -lt $deadline) { Start-Sleep -Milliseconds 4 }
    }
    return $ms2.ToArray()
}
function Capture16([int]$lo) {
    Drain
    Send "FREQ $lo"
    Start-Sleep -Milliseconds 120
    Drain
    Send "CAP16 $Samples 0"
    $raw = ReadAll 4000
    $txt = [Text.Encoding]::ASCII.GetString($raw)
    $nl = $txt.IndexOf("`n")
    if ($nl -lt 0 -or -not $txt.StartsWith("DATA")) { throw "LO ${lo}: no DATA" }
    $pay = New-Object byte[] ($Samples * 2)
    [Array]::Copy($raw, $nl + 1, $pay, 0, $Samples * 2)
    return $pay
}

function Fft([double[]]$re, [double[]]$im) {
    $n = $re.Length
    $j = 0
    for ($i = 1; $i -lt $n; $i++) {
        $bit = $n -shr 1
        while ($j -band $bit) { $j = $j -bxor $bit; $bit = $bit -shr 1 }
        $j = $j -bor $bit
        if ($i -lt $j) {
            $t = $re[$i]; $re[$i] = $re[$j]; $re[$j] = $t
            $t = $im[$i]; $im[$i] = $im[$j]; $im[$j] = $t
        }
    }
    for ($len = 2; $len -le $n; $len = $len * 2) {
        $ang = -2 * [Math]::PI / $len
        $wr = [Math]::Cos($ang); $wi = [Math]::Sin($ang)
        for ($i = 0; $i -lt $n; $i += $len) {
            $cr = 1.0; $ci = 0.0
            for ($k = 0; $k -lt ($len / 2); $k++) {
                $ur = $re[$i + $k]; $ui = $im[$i + $k]
                $ar = $re[$i + $k + $len / 2]; $ai = $im[$i + $k + $len / 2]
                $vr = $ar * $cr - $ai * $ci
                $vi = $ar * $ci + $ai * $cr
                $re[$i + $k] = $ur + $vr; $im[$i + $k] = $ui + $vi
                $re[$i + $k + $len / 2] = $ur - $vr; $im[$i + $k + $len / 2] = $ui - $vi
                $ncr = $cr * $wr - $ci * $wi; $ci = $cr * $wi + $ci * $wr; $cr = $ncr
            }
        }
    }
}

$fs = 80.0
$df = $fs / $Nfft
$half = $Nfft / 2
$win = New-Object double[] $Nfft
$winSum = 0.0
for ($i = 0; $i -lt $Nfft; $i++) { $win[$i] = 0.5 * (1 - [Math]::Cos(2 * [Math]::PI * $i / ($Nfft - 1))); $winSum += $win[$i] }

$r0 = 2400.0; $r1 = 2500.0
$n = [int](($r1 - $r0) / $df) + 1
$sum = New-Object double[] $n
$cnt = New-Object double[] $n
$max = New-Object double[] $n
for ($i = 0; $i -lt $n; $i++) { $max[$i] = [double]::NegativeInfinity }

$los = @()
$lo = $r0 - 10
while ($lo -le $r1 + 10) { $los += $lo; $lo += 20 }

$chans = @(0..12 | ForEach-Object { 2412 + $_ * 5 })
$hits6 = @(0) * 13
$hits12 = @(0) * 13
$sweepsDone = 0

Drain
Send "`n"; Start-Sleep -Milliseconds 300
Drain
Send "SYNC 7"; Start-Sleep -Milliseconds 400
Drain
Send "GAIN MANUAL 40"; Start-Sleep -Milliseconds 200
Send "BANDWIDTH 0"; Start-Sleep -Milliseconds 200
Drain

for ($sw = 1; $sw -le $Sweeps; $sw++) {
    foreach ($L in $los) {
        $pay = Capture16 $L
        $frames = [int]($Samples / $Nfft)
        for ($f = 0; $f -lt $frames; $f++) {
            $re = New-Object double[] $Nfft
            $im = New-Object double[] $Nfft
            for ($i = 0; $i -lt $Nfft; $i++) {
                $off = ($f * $Nfft + $i) * 2
                $a = [int]$pay[$off]; if ($a -ge 128) { $a -= 256 }
                $b = [int]$pay[$off + 1]; if ($b -ge 128) { $b -= 256 }
                $re[$i] = $a / 128.0 * $win[$i]
                $im[$i] = $b / 128.0 * $win[$i]
            }
            Fft $re $im
            for ($jj = 0; $jj -lt $Nfft; $jj++) {
                $fRel = ($jj - $half) * $df
                $a = [Math]::Abs($fRel)
                if ($a -lt 10.0 -or $a -gt 20.0) { continue }
                $p2 = ($re[$jj] * $re[$jj] + $im[$jj] * $im[$jj]) / ($winSum * $winSum)
                $db = 10 * [Math]::Log10($p2 + 1e-30)
                $idx = [int][Math]::Round(($L + $fRel - $r0) / $df)
                if ($idx -lt 0 -or $idx -ge $n) { continue }
                $sum[$idx] += [Math]::Pow(10, $db / 10)
                $cnt[$idx] += 1
                if ($db -gt $max[$idx]) { $max[$idx] = $db }
            }
        }
    }
    # sweep done: floor from this sweep's mean, bars from this sweep's max
    $mean = New-Object double[] $n
    for ($i = 0; $i -lt $n; $i++) { $mean[$i] = if ($cnt[$i] -gt 0) { 10 * [Math]::Log10($sum[$i] / $cnt[$i]) } else { [double]::NegativeInfinity } }
    $valid = $mean | Where-Object { $_ -gt -1e30 } | Sort-Object
    $floor = $valid[[int]($valid.Count * 0.2)]
    for ($c = 0; $c -lt 13; $c++) {
        $best = [double]::NegativeInfinity
        for ($i = 0; $i -lt $n; $i++) {
            if ([Math]::Abs(($r0 + $i * $df) - $chans[$c]) -gt 8.0) { continue }
            if ($max[$i] -gt $best) { $best = $max[$i] }
        }
        $act = [Math]::Max(0.0, $best - $floor)
        if ($act -gt 6) { $hits6[$c]++ }
        if ($act -gt 12) { $hits12[$c]++ }
    }
    $sweepsDone++
    # reset per-sweep accumulators like the app
    for ($i = 0; $i -lt $n; $i++) { $sum[$i] = 0; $cnt[$i] = 0; $max[$i] = [double]::NegativeInfinity }
    "sweep $sw done"
}

"threshold 6 dB:  " + ((0..12 | ForEach-Object { "ch$($_+1)=$([int](100 * $hits6[$_] / $sweepsDone))%" }) -join " ")
"threshold 12 dB: " + ((0..12 | ForEach-Object { "ch$($_+1)=$([int](100 * $hits12[$_] / $sweepsDone))%" }) -join " ")
$p.Close()
