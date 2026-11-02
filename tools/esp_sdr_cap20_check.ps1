# Verify CAP20 (10-bit) decoding against CAP16 (8-bit) on the real board.
# Raw-byte safe: the whole reply is read into a buffer, the "DATA ..." header is located
# inside it, and the payload is verified with the CRC32 from the header before decoding.
# If the packing (I in bits 0-9, Q in bits 10-19, LSB first) were wrong, the decoded RMS
# would land around -5 dBFS instead of the real noise/AGC level.

param([string]$Port = "COM5", [int]$Baud = 115200, [int]$Samples = 4096)

$ErrorActionPreference = 'Stop'

# ---- CRC32 (zlib, same polynomial the firmware uses) -----------------------
# PowerShell longs are signed, so "unsigned shift right" is done with division by 2.
$global:crcTable = New-Object 'long[]' 256
for ($n = 0; $n -lt 256; $n++) {
    $c = [long]$n
    for ($k = 0; $k -lt 8; $k++) {
        if ($c -band 1) { $c = 3988292384 -bxor ($c -shr 1) } else { $c = $c -shr 1 }
    }
    $global:crcTable[$n] = $c
}
function Crc32([byte[]]$data) {
    # NB: hex literals above 0x7FFFFFFF wrap to negative Int32 in Windows PowerShell 5.1,
    # so every 32-bit constant here is written in decimal.
    $crc = [long]4294967295
    foreach ($b in $data) {
        $idx = ($crc -bxor $b) -band 0xFF
        $crc = ($global:crcTable[$idx] -bxor ($crc -shr 8)) -band 4294967295
    }
    return ($crc -bxor 4294967295) -band 4294967295
}
# self test: CRC32("123456789") must be CBF43926
$st = [Text.Encoding]::ASCII.GetBytes("123456789")
$selfTest = Crc32 $st
"crc self-test: 0x{0:x8} (expect cbf43926)  table1=0x{1:x8}  table128=0x{2:x8}" -f $selfTest, $global:crcTable[1], $global:crcTable[128]
if ("{0:x8}" -f $selfTest -ne "cbf43926") { throw "CRC32 self-test failed" }

$p = New-Object System.IO.Ports.SerialPort($Port, $Baud, 'None', 8, 'One')
$p.ReadTimeout = 800
$p.DtrEnable = $true
$p.RtsEnable = $false
$p.Open()
Start-Sleep -Milliseconds 300

function Drain { $p.DiscardInBuffer() }
function Send($cmd) { $p.Write($cmd + "`n") }

# Read everything for a while, return the raw byte array.
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

# Request a capture, cut header + payload out of the raw stream, verify CRC.
# Reads until the full header + payload have arrived (a fixed window can stop 1 byte short).
function Capture([string]$cmd, [int]$payloadLen) {
    Drain
    Send $cmd
    $ms2 = New-Object System.IO.MemoryStream
    $deadline = [DateTime]::Now.AddSeconds(3)
    $nl = -1
    while ([DateTime]::Now -lt $deadline) {
        $n = $p.BytesToRead
        if ($n -gt 0) {
            $tmp = New-Object byte[] $n
            $null = $p.Read($tmp, 0, $n)
            $ms2.Write($tmp, 0, $n)
            if ($nl -lt 0 -and $ms2.Length -ge 40) {
                $peek = [Text.Encoding]::ASCII.GetString($ms2.ToArray(), 0, [Math]::Min(64, $ms2.Length))
                $nl = $peek.IndexOf("`n")
            }
            if ($nl -ge 0 -and $ms2.Length -ge ($nl + 1 + $payloadLen)) { break }
        } else { Start-Sleep -Milliseconds 5 }
    }
    $raw = $ms2.ToArray()
    $hdrText = [Text.Encoding]::ASCII.GetString($raw, 0, [Math]::Min($raw.Length, 64))
    if ($nl -lt 0 -or -not $hdrText.StartsWith("DATA")) { throw "no DATA header: [$($hdrText.Substring(0, [Math]::Min(40, $hdrText.Length)))]" }
    $parts = $hdrText.Substring(0, $nl).Trim() -split '\s+'
    $got = [int]$parts[1]
    $crc = [Convert]::ToInt64($parts[2], 16)
    $pay = New-Object byte[] $payloadLen
    [Array]::Copy($raw, $nl + 1, $pay, 0, $payloadLen)
    $actual = Crc32 $pay
    $ok = ("{0:x8}" -f $actual) -eq $parts[2].ToLower()
    if (-not $ok) {
        Write-Host "  DBG: raw=$($raw.Length)B hdr='[$($hdrText.Substring(0, $nl + 1).Trim())]' want=$parts[2] got=$('{0:x8}' -f $actual)"
        for ($off = -2; $off -le 2; $off++) {
            $start = $nl + 1 + $off
            if ($start -lt 0 -or $start + $payloadLen -gt $raw.Length) { continue }
            $try = New-Object byte[] $payloadLen
            [Array]::Copy($raw, $start, $try, 0, $payloadLen)
            $c2 = Crc32 $try
            Write-Host ("  DBG: offset {0,3} -> crc {1:x8}{2}" -f $off, $c2, $(if ("{0:x8}" -f $c2 -eq $parts[2].ToLower()) { "  <== MATCH" } else { "" }))
        }
        Write-Host ("  DBG: bytes around boundary: " + (($raw[($nl - 2)..($nl + 12)] | ForEach-Object { '{0:x2}' -f $_ }) -join ' '))
        foreach ($len in 5120, 8192, 10239, 10240, 10241) {
            if ($nl + 1 + $len -gt $raw.Length) { continue }
            $try = New-Object byte[] $len
            [Array]::Copy($raw, $nl + 1, $try, 0, $len)
            $c2 = Crc32 $try
            Write-Host ("  DBG: len {0,6} -> crc {1:x8}{2}" -f $len, $c2, $(if ("{0:x8}" -f $c2 -eq $parts[2].ToLower()) { "  <== MATCH" } else { "" }))
        }
    }
    return @{ got = $got; payload = $pay; crcOk = $ok }
}

Drain
Send "`n"; Start-Sleep -Milliseconds 150
Drain
Send "SYNC 777"; Start-Sleep -Milliseconds 400
Drain
Send "FREQ 2437"; Start-Sleep -Milliseconds 300
Send "GAIN MANUAL 40"; Start-Sleep -Milliseconds 300

# ---- CAP16 ----
$c16 = Capture "CAP16 $Samples 0" ($Samples * 2)
$i16 = New-Object double[] ($Samples * 2)
for ($i = 0; $i -lt $Samples * 2; $i++) {
    $b = [int]$c16.payload[$i]
    if ($b -ge 128) { $b -= 256 }          # PowerShell bytes are unsigned
    $i16[$i] = $b / 128.0
}
$r16 = 0.0; foreach ($x in $i16) { $r16 += $x * $x }; $r16 = [Math]::Sqrt($r16 / $i16.Length)
"CAP16: got=$($c16.got)  crcOk=$($c16.crcOk)  rms=$('{0:N4}' -f $r16)  ($('{0:N1}' -f (20 * [Math]::Log10($r16 + 1e-9))) dBFS)"

# ---- CAP20 ----
$c20 = Capture "CAP20 $Samples 0" ($Samples * 20 / 8)
$i20 = New-Object double[] ($Samples * 2)
$acc = [long]0; $held = 0; $k = 0
for ($j = 0; $j -lt $Samples; $j++) {
    while ($held -lt 20) { $acc = $acc -bor (($c20.payload[$k] -bor 0) -shl $held); $k++; $held += 8 }
    $word = [int]($acc -band 0xFFFFF)
    $acc = $acc -shr 20
    $held -= 20
    $i10 = [int]($word -band 0x3FF); if ($i10 -ge 512) { $i10 -= 1024 }
    $q10 = [int](($word -shr 10) -band 0x3FF); if ($q10 -ge 512) { $q10 -= 1024 }
    $i20[2 * $j] = $i10 / 512.0
    $i20[2 * $j + 1] = $q10 / 512.0
}
$r20 = 0.0; foreach ($x in $i20) { $r20 += $x * $x }; $r20 = [Math]::Sqrt($r20 / $i20.Length)
"CAP20: got=$($c20.got)  crcOk=$($c20.crcOk)  rms=$('{0:N4}' -f $r20)  ($('{0:N1}' -f (20 * [Math]::Log10($r20 + 1e-9))) dBFS)"
"delta = $('{0:N2}' -f (20 * [Math]::Log10($r20 / $r16))) dB"

$p.Close()
