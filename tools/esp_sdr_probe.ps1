# ESP-SDR line-protocol probe: asks a connected board what it can do.
# Same handshake the Android app uses: DTR asserted, RTS cleared, SYNC nonce.
#
#   powershell -ExecutionPolicy Bypass -File tools\esp_sdr_probe.ps1 -Port COM5
#
# Works with the board plugged into a PC (USB Serial/JTAG or a UART bridge).

param(
    [string]$Port = "COM5",
    [int]$Baud = 115200
)

$ErrorActionPreference = 'Stop'

$p = New-Object System.IO.Ports.SerialPort($Port, $Baud, 'None', 8, 'One')
$p.ReadTimeout = 800
$p.DtrEnable = $true       # the firmware never streams without DTR
$p.RtsEnable = $false      # RTS must stay low or the auto-reset circuit reboots the board

try {
    $p.Open()
} catch {
    Write-Error "cannot open $Port : $($_.Exception.Message)"
    exit 1
}
Start-Sleep -Milliseconds 300

# a previous client that died mid-transfer leaves the device blocked on output,
# so drain the port and resync exactly like Espsdr.resync() in the app
$p.Write("`n")
Start-Sleep -Milliseconds 150
$p.DiscardInBuffer()

function Ask($cmd, $delayMs = 450) {
    $p.DiscardInBuffer()
    $p.Write($cmd + "`n")
    Start-Sleep -Milliseconds $delayMs
    $line = (($p.ReadExisting() -split "`r?`n") | Where-Object { $_ -ne '' }) -join '  |  '
    if (-not $line) { $line = '(no answer)' }
    "{0,-12} => {1}" -f $cmd, $line
}

# resync: retry SYNC until the board echoes the nonce (up to 8 tries, like Espsdr.resync())
$nonce = (Get-Random -Maximum 0xFFFFFF)
$synced = $false
for ($i = 0; $i -lt 8 -and -not $synced; $i++) {
    $p.DiscardInBuffer()
    $p.Write("SYNC $nonce`n")
    Start-Sleep -Milliseconds 300
    if ($p.ReadExisting() -match "SYNC $nonce") { $synced = $true }
}
if ($synced) { "SYNC         => ok (nonce $nonce)" } else { "SYNC         => no echo (check DTR/cabling)" }
Ask "INFO"
Ask "CAPS"
Ask "LIMITS?"
Ask "RANGE?"
Ask "TRANSPORT?"
Ask "VERSION?"
Ask "GAIN?"
Ask "GPIO?"

$p.Close()
