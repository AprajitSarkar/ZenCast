$env:CGO_ENABLED = "0"
$env:GOOS = "linux"
$env:GOARCH = "arm64"
Push-Location "C:\Users\Aprajit\mercor\host_module\daemon"
go build -ldflags "-s -w" -o "..\bin\zen_daemon" main.go
Pop-Location
Get-Item "C:\Users\Aprajit\mercor\host_module\bin\zen_daemon"
