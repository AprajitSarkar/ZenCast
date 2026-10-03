$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$JAVAC = "$env:JAVA_HOME\bin\javac.exe"
$BUILD_TOOLS = "C:\Users\Aprajit\AppData\Local\Android\Sdk\build-tools\36.0.0"
$AAPT2 = "$BUILD_TOOLS\aapt2.exe"
$D8 = "$BUILD_TOOLS\d8.bat"
$APKSIGNER = "$BUILD_TOOLS\apksigner.bat"
$ANDROID_JAR = "C:\Users\Aprajit\AppData\Local\Android\Sdk\platforms\android-37.0\android.jar"

$CLIENT_DIR = "C:\Users\Aprajit\mercor\client_app"
$BUILD_DIR = "$CLIENT_DIR\build"
$GEN_DIR = "$BUILD_DIR\gen"
$CLASSES_DIR = "$BUILD_DIR\classes"
$COMPILED_RES = "$BUILD_DIR\compiled_res"

# Clean
if (Test-Path $BUILD_DIR) { Remove-Item -Recurse -Force $BUILD_DIR }
New-Item -ItemType Directory -Force -Path $GEN_DIR | Out-Null
New-Item -ItemType Directory -Force -Path $CLASSES_DIR | Out-Null
New-Item -ItemType Directory -Force -Path $COMPILED_RES | Out-Null

Write-Host "1. Compiling resources with AAPT2..."
& $AAPT2 compile --dir "$CLIENT_DIR\res" -o "$COMPILED_RES\resources.zip"
if ($LASTEXITCODE -ne 0) { throw "AAPT2 compile failed" }

Write-Host "2. Linking resources and generating R.java..."
& $AAPT2 link -I $ANDROID_JAR --manifest "$CLIENT_DIR\AndroidManifest.xml" `
    --java $GEN_DIR -o "$BUILD_DIR\unaligned.apk" `
    "$COMPILED_RES\resources.zip" --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "AAPT2 link failed" }

Write-Host "3. Compiling Java sources..."
$javaFiles = Get-ChildItem -Path "$CLIENT_DIR\src", $GEN_DIR -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName
& $JAVAC -source 1.8 -target 1.8 -encoding UTF-8 -cp $ANDROID_JAR -d $CLASSES_DIR $javaFiles
if ($LASTEXITCODE -ne 0) { throw "Javac failed" }

Write-Host "4. Dexing classes with D8..."
$classFiles = Get-ChildItem -Path $CLASSES_DIR -Filter "*.class" -Recurse | Select-Object -ExpandProperty FullName
& $D8 --min-api 26 --output $BUILD_DIR $classFiles
if ($LASTEXITCODE -ne 0) { throw "D8 dexing failed" }

Write-Host "5. Packaging classes.dex into APK..."
Set-Location $BUILD_DIR
# Use jar or 7z or powershell archive to add classes.dex into unaligned.apk
& "C:\Program Files\Android\Android Studio\jbr\bin\jar.exe" -uf "$BUILD_DIR\unaligned.apk" classes.dex
if ($LASTEXITCODE -ne 0) { throw "Jar package failed" }

Write-Host "6. Signing APK with debug keystore..."
$debugKeystore = "$env:USERPROFILE\.android\debug.keystore"
$OUTPUT_APK = "C:\Users\Aprajit\mercor\ZenCast.apk"
Copy-Item "$BUILD_DIR\unaligned.apk" $OUTPUT_APK -Force

& $APKSIGNER sign --ks $debugKeystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android $OUTPUT_APK
if ($LASTEXITCODE -ne 0) { throw "Signing failed" }

Write-Host "[SUCCESS] ZenCast.apk built and signed: $OUTPUT_APK"
Get-Item $OUTPUT_APK | Select-Object Name, Length, LastWriteTime
