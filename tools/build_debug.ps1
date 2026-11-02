# Builds the debug APK with the local toolchain paths (no Android Studio needed).
$env:JAVA_HOME = 'D:\Android\jdk17'
$env:ANDROID_HOME = 'D:\Android\sdk'
$env:GRADLE_USER_HOME = 'D:\Android\gradle-home'
& 'D:\Android\gradle-8.9\bin\gradle.bat' -p "$PSScriptRoot\.." assembleDebug @args
exit $LASTEXITCODE
