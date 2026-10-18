# Lets the phone reach the Mise server through Windows Defender Firewall. Asks for administrator rights.
#   powershell -ExecutionPolicy Bypass -File scripts\allow-firewall.ps1
# It: (1) allows the server's TCP port on Private networks, (2) finds rules that BLOCK python
# (created when a firewall prompt was cancelled; block rules beat allow rules), (3) checks the
# Wi-Fi/Ethernet network is marked Private rather than Public. Nothing changes without asking.
param([int]$Port = 0)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if ($Port -eq 0) {
    $Port = 8765
    $cfg = Join-Path $root "config.toml"
    if (Test-Path $cfg) {
        $m = Select-String -Path $cfg -Pattern '^\s*port\s*=\s*(\d+)' | Select-Object -First 1
        if ($m) { $Port = [int]$m.Matches[0].Groups[1].Value }
    }
}

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "Asking for administrator rights (needed to change the firewall)..."
    Start-Process powershell -Verb RunAs -ArgumentList "-ExecutionPolicy Bypass -NoExit -File `"$PSCommandPath`" -Port $Port"
    exit
}

function Ask([string]$question) {
    return (Read-Host "$question [y/N]") -in @("y", "Y", "yes", "Yes")
}

Write-Host "`n1. Allowing TCP port $Port on Private networks"
Get-NetFirewallRule -DisplayName "Mise server" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName "Mise server" -Direction Inbound -Protocol TCP -LocalPort $Port `
    -Action Allow -Profile Private | Out-Null
Write-Host "   Done."

Write-Host "`n2. Looking for rules that block Python"
$blocking = Get-NetFirewallRule -Direction Inbound -Action Block -Enabled True -ErrorAction SilentlyContinue |
    Where-Object { ($_ | Get-NetFirewallApplicationFilter).Program -like "*python*" }
if ($blocking) {
    $blocking | ForEach-Object {
        $prog = ($_ | Get-NetFirewallApplicationFilter).Program
        Write-Host "   BLOCK rule: '$($_.DisplayName)'  profile: $($_.Profile)  program: $prog"
    }
    Write-Host "   These usually come from a firewall prompt that was cancelled, and they override any allow rule."
    if (Ask "   Remove them?") { $blocking | Remove-NetFirewallRule; Write-Host "   Removed." }
} else {
    Write-Host "   None found."
}

Write-Host "`n3. Checking the network type"
$public = Get-NetConnectionProfile | Where-Object { $_.NetworkCategory -eq "Public" }
Get-NetConnectionProfile | ForEach-Object { Write-Host "   $($_.InterfaceAlias): '$($_.Name)' is $($_.NetworkCategory)" }
foreach ($p in $public) {
    Write-Host "   '$($p.Name)' is Public, so the Private-only rule above doesn't apply on it."
    if (Ask "   Is '$($p.Name)' your home network? Mark it Private?") {
        Set-NetConnectionProfile -InterfaceIndex $p.InterfaceIndex -NetworkCategory Private
        Write-Host "   Now Private."
    }
}

Write-Host "`nNow, on the phone (same Wi-Fi, mobile data off), open:  http://<the server's address>:$Port/api/v1/health"
Write-Host "It should show  `"status`":`"ok`". If it still doesn't load, another security app (Norton, McAfee, Avast,"
Write-Host "Kaspersky, ...) may have its own firewall, or the router keeps Wi-Fi devices apart (guest network / AP isolation)."
