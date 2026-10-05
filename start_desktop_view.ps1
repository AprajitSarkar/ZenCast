# Auto-detects ZenFone IP and launches Scrcpy Desktop View (1080p Virtual Display)
Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "     ZenCast - Scrcpy Desktop View Launcher      " -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

$zenIp = $null

# 1. Check existing ADB connections
$adbOut = adb devices -l 2>$null
foreach ($line in $adbOut) {
    if ($line -match "([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+):5555.*(X00TD|Zenfone|cherish)") {
        $zenIp = $matches[1]
        Write-Host "[✓] Found active ADB ZenFone connection at: $zenIp" -ForegroundColor Green
        break
    }
}

# 2. If not found, probe discovery port 27182
if (-not $zenIp) {
    Write-Host "[*] Probing network for ZenCast Host..." -ForegroundColor Yellow
    $candidates = @("192.168.1.129", "10.19.221.204")

    foreach ($ip in $candidates) {
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $ar = $tcp.BeginConnect($ip, 27182, $null, $null)
            if ($ar.AsyncWaitHandle.WaitOne(300)) {
                $tcp.EndConnect($ar)
                $zenIp = $ip
                $tcp.Close()
                Write-Host "[✓] Discovered ZenFone Host at: $zenIp" -ForegroundColor Green
                break
            }
            $tcp.Close()
        } catch {
            # continue
        }
    }
}

# 3. Fallback to default
if (-not $zenIp) {
    $zenIp = "192.168.1.129"
    Write-Host "[!] Could not probe host. Defaulting to: $zenIp" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "[*] Connecting ADB to $zenIp:5555..." -ForegroundColor Cyan
adb connect "$($zenIp):5555"

Write-Host "[*] Launching Scrcpy 1080p Desktop Display on $zenIp..." -ForegroundColor Green
Write-Host "Command: scrcpy --tcpip=$($zenIp):5555 --new-display=1920x1080/160" -ForegroundColor Gray
Write-Host "-------------------------------------------------" -ForegroundColor Gray

scrcpy --tcpip="$($zenIp):5555" --new-display=1920x1080/160
