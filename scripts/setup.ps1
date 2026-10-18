# One-time setup of the PC tools (converter + server) on Windows.
# Run from PowerShell:  powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
# (Windows keeps its own environment in .venv-win, so it can't clash with a Linux/WSL .venv.)

if ($PSScriptRoot -match "wsl\.localhost|wsl\$") {
    Write-Warning ("This project is inside WSL ($PSScriptRoot). Windows Python works poorly on WSL paths, and a server " +
        "started inside WSL is hidden behind WSL's private 172.x network. Either copy the project to a Windows folder " +
        "(e.g. $env:USERPROFILE\mise-app) and run this script there, or run everything inside WSL with scripts/setup.sh " +
        "and turn on WSL mirrored networking - see the README section 'Running inside WSL'.")
    $answer = Read-Host "Continue anyway? [y/N]"
    if ($answer -notin @("y", "Y", "yes")) { exit 1 }
}

function Test-Python([string]$exe, [string[]]$extra) {
    if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) { return $false }
    try { $v = & $exe @extra -c "import sys; print(sys.version_info >= (3, 10))" 2>$null } catch { return $false }
    return ($v -eq "True")
}

$pyExe = $null; $pyArgs = @()
if (Test-Python "py" @("-3")) { $pyExe = "py"; $pyArgs = @("-3") }
elseif (Test-Python "python" @()) { $pyExe = "python" }
elseif (Test-Python "python3" @()) { $pyExe = "python3" }
if (-not $pyExe) {
    Write-Error "Python 3.10+ is required. Install it from https://www.python.org/downloads/ (tick 'Add python.exe to PATH'), open a new PowerShell window and re-run."
}

if (-not (Test-Path ".venv-win\Scripts\python.exe")) { & $pyExe @pyArgs -m venv .venv-win }
& .venv-win\Scripts\python.exe -m pip install --quiet --upgrade pip
& .venv-win\Scripts\python.exe -m pip install --quiet -e ".[dev]"
New-Item -ItemType Directory -Force -Path recipes, inbox | Out-Null
& .venv-win\Scripts\python.exe -c "from mise_shared.config import load_config; c = load_config(); print(f'Config file: {c.path}')"
Write-Host "Setup complete. Start the server with: scripts\server.ps1"
