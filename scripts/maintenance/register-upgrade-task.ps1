<#
.SYNOPSIS
  Registers (or replaces) the weekly Windows scheduled task "TrekUI upgrade queue" that runs
  queue-upgrades.ps1 every Monday at 09:00 for the current user. Run once per machine.
  Remove it with: Unregister-ScheduledTask -TaskName 'TrekUI upgrade queue' -Confirm:$false
#>
$script = Join-Path $PSScriptRoot 'queue-upgrades.ps1'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`""
$trigger = New-ScheduledTaskTrigger -Weekly -DaysOfWeek Monday -At 9am
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RunOnlyIfNetworkAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 15)
Register-ScheduledTask -TaskName 'TrekUI upgrade queue' -Action $action -Trigger $trigger -Settings $settings `
    -Description 'Turns new Android/Gradle/library upgrade issues and Dependabot PRs on GitHub into TrekUI tickets.' -Force | Out-Null
Write-Host "Registered 'TrekUI upgrade queue' (Mondays 09:00, runs when missed)."
