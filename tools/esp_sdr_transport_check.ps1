# Check the TRANSPORT?/BAUD handshake on the real board.
param([string]$Port = "COM5", [int]$Baud = 115200)

$ErrorActionPreference = 'Stop'
$p = New-Object System.IO.Ports.SerialPort($Port, $Baud, 'None', 8, 'One')
$p.ReadTimeout = 600
$p.DtrEnable = $true
$p.RtsEnable = $false
$p.Open()
Start-Sleep -Milliseconds 300
function Drain { $p.DiscardInBuffer() }
function Send($cmd) { $p.Write($cmd + "`n") }
function Ask([string]$cmd, [int]$ms = 600) {
    Drain
    Send $cmd
    $ms2 = New-Object System.IO.MemoryStream
    $deadline = [DateTime]::Now.AddMilliseconds($ms)
    while ([DateTime]::Now -lt $deadline) {
        $n = $p.BytesToRead
        if ($n -gt 0) { $tmp = New-Object byte[] $n; $null = $p.Read($tmp, 0, $n); $ms2.Write($tmp, 0, $n) }
        if ($ms2.Length -gt 0 -and ([Text.Encoding]::ASCII.GetString($ms2.ToArray()) -match "`n")) { break }
        Start-Sleep -Milliseconds 5
    }
    return ([Text.Encoding]::ASCII.GetString($ms2.ToArray())).Trim()
}

Drain
Send "`n"; Start-Sleep -Milliseconds 300
Drain
Send "SYNC 42"; Start-Sleep -Milliseconds 400
Drain
"TRANSPORT?: " + (Ask "TRANSPORT?")
"BAUD 2000000: " + (Ask "BAUD 2000000")
"TRANSPORT? again: " + (Ask "TRANSPORT?")
"BAUD 1000000: " + (Ask "BAUD 1000000")
"TRANSPORT? after 1M: " + (Ask "TRANSPORT?")
"BAUD 3000000 (invalid): " + (Ask "BAUD 3000000")
# restore the default so later probes start clean
$null = Ask "BAUD 2000000"
$p.Close()
"OK"
