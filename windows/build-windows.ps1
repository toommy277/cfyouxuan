$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    throw '未找到 Go。请先安装 Go 1.23 或更新版本，再重新运行此脚本。'
}

$env:GOOS = 'windows'
$env:GOARCH = 'amd64'
$env:CGO_ENABLED = '0'

go build -trimpath -buildvcs=false -ldflags '-H=windowsgui -s -w' -o (Join-Path $PSScriptRoot 'CFYouxuan.exe') .
if ($LASTEXITCODE -ne 0) { throw '编译失败。' }
Write-Host '编译完成：' (Join-Path $PSScriptRoot 'CFYouxuan.exe')
