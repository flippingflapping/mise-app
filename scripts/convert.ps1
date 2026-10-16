# Convert raw recipes in inbox\ into structured JSON in recipes\.  Examples:
#   scripts\convert.ps1 run          scripts\convert.ps1 status          scripts\convert.ps1 retry
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
if (-not (Test-Path ".venv\Scripts\convert.exe")) { Write-Error "Run scripts\setup.ps1 first." }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
& .venv\Scripts\convert.exe @args
