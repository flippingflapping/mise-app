# Start the Mise companion server (shows a pairing QR code).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
if (-not (Test-Path ".venv-win\Scripts\mise-server.exe")) { Write-Error "Run scripts\setup.ps1 first." }
# Make the terminal QR code render correctly.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
& .venv-win\Scripts\mise-server.exe @args
