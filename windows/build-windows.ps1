$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$go = Get-Command go -ErrorAction SilentlyContinue
if (-not $go) {
    Write-Error "未找到 Go。请安装 Go 1.23.2 或兼容版本后重试。"
}

Write-Host "正在构建 GUI 版 CFYouxuan.exe ..."
go build -trimpath -ldflags="-H windowsgui" -o CFYouxuan.exe .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "构建完成：$PSScriptRoot\CFYouxuan.exe"
