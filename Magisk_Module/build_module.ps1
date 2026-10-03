$ErrorActionPreference = "Stop"

$JAR_EXE = "C:\Program Files\Android\Android Studio\jbr\bin\jar.exe"
$MODULE_SRC = "C:\Users\Aprajit\mercor\host_module"
$OUT_DIR = "C:\Users\Aprajit\mercor\Magisk_Module"

if (!(Test-Path $OUT_DIR)) {
    New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null
}

Push-Location $MODULE_SRC

# 1. Universal Headless Remote Host Module (for GitHub/public use)
$UNIVERSAL_ZIP = "$OUT_DIR\Headless_Remote_Host.zip"
if (Test-Path $UNIVERSAL_ZIP) { Remove-Item -Force $UNIVERSAL_ZIP }
Write-Host "Packaging Universal Module: $UNIVERSAL_ZIP"
& $JAR_EXE -cMf $UNIVERSAL_ZIP module.prop service.sh customize.sh bin system META-INF wifi.conf.example

# 2. Local Configured Module (for user's ZenFone & Redmi Note 7S)
$LOCAL_ZIP = "$OUT_DIR\zenfone_remote_host.zip"
if (Test-Path $LOCAL_ZIP) { Remove-Item -Force $LOCAL_ZIP }
Write-Host "Packaging Local Configured Module: $LOCAL_ZIP"
if (Test-Path "$MODULE_SRC\wifi.conf") {
    & $JAR_EXE -cMf $LOCAL_ZIP module.prop service.sh customize.sh bin system META-INF wifi.conf
} else {
    & $JAR_EXE -cMf $LOCAL_ZIP module.prop service.sh customize.sh bin system META-INF wifi.conf.example
}

Pop-Location

# Also mirror to root for backward compatibility
Copy-Item "$OUT_DIR\zenfone_remote_host.zip" "C:\Users\Aprajit\mercor\zenfone_remote_host.zip" -Force

Write-Host "`n[SUCCESS] Magisk modules created in $($OUT_DIR):"
Get-ChildItem $OUT_DIR -Filter "*.zip" | Select-Object Name, Length, LastWriteTime
