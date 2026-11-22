# 一键构建（Miuix UI）
# 用法：powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Stop'
# Android Lint 当前版本在 JDK 17 下会触发 JavaDocParser 的 removeLast() 运行时错误。
# 优先使用本机已验证的 JDK 21；保留 JAVA_HOME 回退，便于在其他开发机上运行脚本。
$jdk21Home = 'D:\DevTools\jdks\jdk-21.0.12.1+1'
if (Test-Path -LiteralPath (Join-Path $jdk21Home 'bin\java.exe')) {
    $env:JAVA_HOME = $jdk21Home
} elseif (-not $env:JAVA_HOME) {
    throw "JDK 21 not found at $jdk21Home; set JAVA_HOME to a compatible JDK before building."
}
& .\gradlew.bat :app:assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'gradle build failed' }
$apk = 'app\build\outputs\apk\release\app-release.apk'
# 产物命名跟随 app/build.gradle.kts 的 versionName
$m = Select-String -Path 'app\build.gradle.kts' -Pattern 'handleFixVersionName\s*=\s*"([^"]+)"'
$ver = if ($m) { $m.Matches[0].Groups[1].Value } else { 'unknown' }
$out = Join-Path $PSScriptRoot "outputs\Xiaomi14Ultra摄影手柄功能拓展_v$ver.apk"
New-Item -ItemType Directory -Path (Split-Path -Parent $out) -Force | Out-Null
Copy-Item -LiteralPath $apk -Destination $out -Force
Write-Host "done -> $out"
