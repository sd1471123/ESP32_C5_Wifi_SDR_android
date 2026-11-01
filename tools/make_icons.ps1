# Regenerates every launcher icon from icon.png in the project root.
#   powershell -ExecutionPolicy Bypass -File tools/make_icons.ps1
#
# The source art is a blue plate with pure-black corners. A circular clip cannot follow
# the plate's squircle outline, so instead every row is scanned from both edges and the
# leading run of near-black pixels is repainted with the plate colour: the black corner
# patches are removed exactly, while the artwork's own black outlines stay untouched
# (they never touch the image border).
param(
    [string]$Source = (Join-Path $PSScriptRoot '..\icon.png'),
    [string]$ResDir = (Join-Path $PSScriptRoot '..\app\src\main\res'),
    [double]$ArtScale = 0.76
)

Add-Type -AssemblyName System.Drawing

$bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $Source).Path)
$ms = New-Object System.IO.MemoryStream(,$bytes)
$srcImage = [System.Drawing.Image]::FromStream($ms)
$w = $srcImage.Width
$h = $srcImage.Height

# --- plate colour: the pixel a few rows under the top edge, on the centre line
$probe = New-Object System.Drawing.Bitmap($srcImage)
$pc = $probe.GetPixel([int]($w / 2), 4)
$blue = [System.Drawing.Color]::FromArgb(255, $pc.R, $pc.G, $pc.B)
$probe.Dispose()
Write-Host ("source {0}x{1}  plate #{2:X2}{3:X2}{4:X2}" -f $w, $h, $blue.R, $blue.G, $blue.B)

# --- clean art: repaint the black corner runs with the plate colour
$clean = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$gc = [System.Drawing.Graphics]::FromImage($clean)
$gc.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$gc.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$gc.DrawImage($srcImage, 0, 0, $w, $h)
$gc.Dispose()

$rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
$data = $clean.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadWrite, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $data.Stride
$buf = New-Object byte[] ($stride * $h)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $buf, 0, $buf.Length)

$bB = $blue.B; $bG = $blue.G; $bR = $blue.R
$painted = 0
for ($y = 0; $y -lt $h; $y++) {
    $row = $y * $stride
    # from the left edge
    $x = 0
    while ($x -lt $w) {
        $i = $row + $x * 4
        if ($buf[$i] -lt 60 -and $buf[$i + 1] -lt 60 -and $buf[$i + 2] -lt 60) {
            $buf[$i] = $bB; $buf[$i + 1] = $bG; $buf[$i + 2] = $bR; $buf[$i + 3] = 255
            $x++
            $painted++
        } else { break }
    }
    # from the right edge
    $x = $w - 1
    while ($x -gt 0) {
        $i = $row + $x * 4
        if ($buf[$i] -lt 60 -and $buf[$i + 1] -lt 60 -and $buf[$i + 2] -lt 60) {
            $buf[$i] = $bB; $buf[$i + 1] = $bG; $buf[$i + 2] = $bR; $buf[$i + 3] = 255
            $x--
            $painted++
        } else { break }
    }
}
[System.Runtime.InteropServices.Marshal]::Copy($buf, 0, $data.Scan0, $buf.Length)
$clean.UnlockBits($data)
Write-Host ("repainted {0} corner pixels" -f $painted)

function Save-Icon([System.Drawing.Image]$image, [int]$size, [string]$path) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gr = [System.Drawing.Graphics]::FromImage($bmp)
    $gr.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $gr.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gr.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gr.Clear([System.Drawing.Color]::Transparent)
    $gr.DrawImage($image, 0, 0, $size, $size)
    $gr.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host ("  {0} ({1} px)" -f $path, $size)
}

# --- adaptive foreground: 108dp canvas, art inset so masks only bite the rim.
# The plate has a subtle gradient, so the backing is a heavily downscaled copy of the art
# blown back up: a smooth continuation of the plate colours with no hard seam.
function Save-Foreground([int]$size, [string]$path) {
    $art = [int]($size * $ArtScale)
    $off = [int](($size - $art) / 2)

    $scaled = New-Object System.Drawing.Bitmap($art, $art, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gs = [System.Drawing.Graphics]::FromImage($scaled)
    $gs.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $gs.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gs.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gs.DrawImage($clean, 0, 0, $art, $art)
    $gs.Dispose()

    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gr = [System.Drawing.Graphics]::FromImage($bmp)
    $gr.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $gr.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gr.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gr.Clear($blue)
    $gr.DrawImage($scaled, $off, $off, $art, $art)
    $gr.Dispose()
    $scaled.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host ("  {0} ({1} px, art {2} px)" -f $path, $size, $art)
}

$legacy = [ordered]@{ 'mipmap-mdpi' = 48; 'mipmap-hdpi' = 72; 'mipmap-xhdpi' = 96; 'mipmap-xxhdpi' = 144; 'mipmap-xxxhdpi' = 192 }
$adaptive = [ordered]@{ 'mipmap-mdpi' = 108; 'mipmap-hdpi' = 162; 'mipmap-xhdpi' = 216; 'mipmap-xxhdpi' = 324; 'mipmap-xxxhdpi' = 432 }

Write-Host 'legacy icons'
foreach ($k in $legacy.Keys) {
    $dir = Join-Path $ResDir $k
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Save-Icon $clean $legacy[$k] (Join-Path $dir 'ic_launcher.png')
    Save-Icon $clean $legacy[$k] (Join-Path $dir 'ic_launcher_round.png')
}

Write-Host 'adaptive foreground'
foreach ($k in $adaptive.Keys) {
    $dir = Join-Path $ResDir $k
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Save-Foreground $adaptive[$k] (Join-Path $dir 'ic_launcher_foreground.png')
}

$anydpi = Join-Path $ResDir 'mipmap-anydpi-v26'
New-Item -ItemType Directory -Force -Path $anydpi | Out-Null
$xml = @"
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/launcher_bg" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"@
Set-Content -Path (Join-Path $anydpi 'ic_launcher.xml') -Value $xml -Encoding UTF8
Set-Content -Path (Join-Path $anydpi 'ic_launcher_round.xml') -Value $xml -Encoding UTF8

Write-Host ''
Write-Host ("values/colors.xml needs: <color name=`"launcher_bg`">#{0:X2}{1:X2}{2:X2}</color>" -f $blue.R, $blue.G, $blue.B)

$clean.Dispose()
$srcImage.Dispose()
$ms.Dispose()
Write-Host 'done'
