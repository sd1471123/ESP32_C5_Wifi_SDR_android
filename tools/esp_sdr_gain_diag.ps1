# Quick radio sanity: CAP16 rms at several gain settings + the SPEC header gain byte.
param([string]$Port = "COM5", [int]$Baud = 115200)

$ErrorActionPreference = 'Stop'
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

Send "`n"; Start-Sleep -Milliseconds 400      # stop any leftover stream
Drain
Send "SYNC 1"; Start-Sleep -Milliseconds 300
Drain
Send "FREQ 5180"; Start-Sleep -Milliseconds 200
Drain

foreach ($g in 0, 20, 40, 60) {
    Send "GAIN MANUAL $g"; Start-Sleep -Milliseconds 250
    Drain
    Send "CAP16 4096 0"
    $raw = ReadAll 2000
    $txt = [Text.Encoding]::ASCII.GetString($raw)
    $nl = $txt.IndexOf("`n")
    if ($nl -lt 0 -or -not $txt.StartsWith("DATA")) { "gain $g : no DATA ($($txt.Substring(0, [Math]::Min(32, $txt.Length))))"; continue }
    $acc = 0.0; $mx = 0.0
    for ($i = $nl + 1; $i -lt $nl + 1 + 8192; $i++) {
        $v = [int]$raw[$i]; if ($v -ge 128) { $v -= 256 }
        $acc += [double]$v * $v
        $a = [Math]::Abs($v); if ($a -gt $mx) { $mx = $a }
    }
    $rms = [Math]::Sqrt($acc / 8192.0 / 16384.0)
    "gain $g : rms=$('{0:N4}' -f $rms) ($('{0:N1}' -f (20 * [Math]::Log10($rms + 1e-9))) dBFS)  max=|{1}|" -f $rms, $mx
}

Send "GAIN HARDWARE"; Start-Sleep -Milliseconds 250
Drain
Send "CAP16 4096 0"
$raw = ReadAll 2000
$txt = [Text.Encoding]::ASCII.GetString($raw)
$nl = $txt.IndexOf("`n")
if ($nl -ge 0 -and $txt.StartsWith("DATA")) {
    $acc = 0.0
    for ($i = $nl + 1; $i -lt $nl + 1 + 8192; $i++) {
        $v = [int]$raw[$i]; if ($v -ge 128) { $v -= 256 }
        $acc += [double]$v * $v
    }
    $rms = [Math]::Sqrt($acc / 8192.0 / 16384.0)
    "HWAGC : rms=$('{0:N4}' -f $rms) ($('{0:N1}' -f (20 * [Math]::Log10($rms + 1e-9))) dBFS)"
}
$p.Close()
