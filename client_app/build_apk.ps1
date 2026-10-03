$ErrorActionPreference = "Stop"

$androidJar = "C:\Users\Aprajit\AppData\Local\Android\Sdk\platforms\android-37.0\android.jar"
$buildToolsDir = "C:\Users\Aprajit\AppData\Local\Android\Sdk\build-tools\36.0.0"
$aapt2 = "$buildToolsDir\aapt2.exe"
$d8 = "$buildToolsDir\d8.bat"
$apksigner = "$buildToolsDir\apksigner.bat"
$zipalign = "$buildToolsDir\zipalign.exe"
$javac = "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe"

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

$rootDir = "C:\Users\Aprajit\mercor\client_app"
$buildDir = "$rootDir\build"
$genDir = "$buildDir\gen"
$classesDir = "$buildDir\classes"
$compiledResDir = "$buildDir\compiled_res"

if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path $genDir, $classesDir, $compiledResDir | Out-Null

Write-Host "[1/5] Compiling Android resources with AAPT2..."
& $aapt2 compile --dir "$rootDir\res" -o "$compiledResDir\resources.zip"

Write-Host "[2/5] Linking resources and generating R.java..."
$unalignedApk = "$buildDir\unaligned.apk"
& $aapt2 link -I $androidJar `
    --manifest "$rootDir\AndroidManifest.xml" `
    --java $genDir `
    -o $unalignedApk `
    --auto-add-overlay `
    "$compiledResDir\resources.zip"

Write-Host "[3/5] Compiling Java sources with javac..."
$javaSources = @(Get-ChildItem -Path "$rootDir\src", $genDir -Filter "*.java" -Recurse | Select-Object -ExpandProperty FullName)
& $javac -source 17 -target 17 -encoding UTF-8 -cp $androidJar -d $classesDir $javaSources

Write-Host "[4/5] Converting class files to classes.dex with D8..."
$classFiles = @(Get-ChildItem -Path $classesDir -Filter "*.class" -Recurse | Select-Object -ExpandProperty FullName)
& $d8 --release --output $buildDir --lib $androidJar $classFiles

Write-Host "[5/5] Packaging and signing APK..."
$jarExe = "C:\Program Files\Android\Android Studio\jbr\bin\jar.exe"
Push-Location $buildDir
& $jarExe -uf $unalignedApk classes.dex
Pop-Location

$alignedApk = "$buildDir\aligned.apk"
& $zipalign -p -f 4 $unalignedApk $alignedApk

$finalApk = "C:\Users\Aprajit\mercor\ZenCast.apk"
$keystore = "$rootDir\debug.keystore"
$keytool = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"

if (-not (Test-Path $keystore)) {
    & $keytool -genkeypair -v -keystore $keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
}

if (Test-Path $finalApk) { Remove-Item -Force $finalApk }
& $apksigner sign --ks $keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out $finalApk $alignedApk

Write-Host "`n[SUCCESS] ZenCast.apk built successfully!"
Get-Item $finalApk
