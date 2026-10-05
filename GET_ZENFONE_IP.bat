@echo off
title ZenCast - Device Status and IP Lookup
powershell -NoProfile -ExecutionPolicy Bypass -Command "
Write-Host '=================================================' -ForegroundColor Cyan
Write-Host '          ZenCast Host & IP Inspector            ' -ForegroundColor Cyan
Write-Host '=================================================' -ForegroundColor Cyan

$adbOut = & adb devices -l 2>`$null
$found = `$false
foreach (`$line in `$adbOut) {
    if (`$line -match '([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+):5555.*(X00TD|Zenfone|cherish)') {
        Write-Host '[✓] ZenFone ADB Connected: ' -NoNewline -ForegroundColor Green
        Write-Host `$matches[1] -ForegroundColor Yellow
        `$found = `$true
    }
}

$candidates = @('192.168.1.129', '10.19.221.204')
foreach (`$ip in `$candidates) {
    try {
        `$tcp = New-Object System.Net.Sockets.TcpClient
        `$ar = `$tcp.BeginConnect(`$ip, 27182, `$null, `$null)
        if (`$ar.AsyncWaitHandle.WaitOne(300)) {
            `$tcp.EndConnect(`$ar)
            Write-Host '[✓] ZenCast Streaming Host Active: ' -NoNewline -ForegroundColor Green
            Write-Host `$ip -ForegroundColor Yellow
            `$tcp.Close()
            `$found = `$true
            break
        }
        `$tcp.Close()
    } catch {}
}

if (-not `$found) {
    Write-Host '[!] ZenFone not detected yet. Probing local network...' -ForegroundColor Yellow
}
Write-Host '=================================================' -ForegroundColor Cyan
"
pause
