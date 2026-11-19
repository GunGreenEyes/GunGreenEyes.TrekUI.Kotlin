<#
.SYNOPSIS
  Registers (or replaces) the Windows scheduled task "TrekUI quarterly upgrade": quarterly-upgrade.ps1
  runs on the 1st of January, April, July and October at 03:00 for the current user, checks both
  TrekUI editions against the newest Android / Kotlin / AGP / Gradle releases, files upgrade tickets
  and starts a CLI to implement them. Run once per machine (either repo's copy; the last one wins).

  It wakes the machine for the run and catches up after a missed one (machine off at 03:00).
  The first registration also records today's Kotlin release as the baseline (quarterly-upgrade.ps1
  -Seed), so only Kotlin releases after today are filed.

  Run it now:  Start-ScheduledTask -TaskName 'TrekUI quarterly upgrade'
  Remove it:   Unregister-ScheduledTask -TaskName 'TrekUI quarterly upgrade' -Confirm:$false
  Log:         %LOCALAPPDATA%\TrekUI\quarterly-upgrade.log
#>
[CmdletBinding()]
param(
    [string]$Account = '',   # orchestrator login the CLI runs on; empty = the Auto login
    [string]$At = '03:00'
)
$ErrorActionPreference = 'Stop'
$name = 'TrekUI quarterly upgrade'
$script = Join-Path $PSScriptRoot 'quarterly-upgrade.ps1'
$arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`""
if ($Account) { $arguments += " -Account $Account" }

# The cmdlets have no "selected months, day N" trigger, so the task is registered from XML.
$esc = [System.Security.SecurityElement]
$start = '{0}-01-01T{1}:00' -f ((Get-Date).Year + 1), $At   # any past or future date works; the months decide
$user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$xml = @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>Quarterly: checks the TrekUI editions (TrekUI; TrekUI.Java until it is archived) against the newest stable Android API, Kotlin, AGP and Gradle; files upgrade tickets and starts a CLI to implement them (scripts/maintenance/quarterly-upgrade.ps1).</Description>
  </RegistrationInfo>
  <Triggers>
    <CalendarTrigger>
      <StartBoundary>$start</StartBoundary>
      <Enabled>true</Enabled>
      <ScheduleByMonth>
        <DaysOfMonth><Day>1</Day></DaysOfMonth>
        <Months><January /><April /><July /><October /></Months>
      </ScheduleByMonth>
    </CalendarTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>$($esc::Escape($user))</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>true</RunOnlyIfNetworkAvailable>
    <WakeToRun>true</WakeToRun>
    <ExecutionTimeLimit>PT30M</ExecutionTimeLimit>
    <Enabled>true</Enabled>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>powershell.exe</Command>
      <Arguments>$($esc::Escape($arguments))</Arguments>
    </Exec>
  </Actions>
</Task>
"@
Register-ScheduledTask -TaskName $name -Xml $xml -Force | Out-Null

& $script -Seed | Out-Null
$next = (Get-ScheduledTaskInfo -TaskName $name).NextRunTime
Write-Host "Registered '$name' (1 Jan/Apr/Jul/Oct at $At, wakes the PC, catches up when missed); next run $next."
