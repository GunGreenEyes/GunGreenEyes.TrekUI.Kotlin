# Shared helpers for scripts/avd/*.ps1 and scripts/capture/*.ps1 (dot-source this file).

<# AVD folder: ANDROID_AVD_HOME, else ANDROID_USER_HOME\avd, else ~/.android/avd. #>
function Get-AvdHome {
    if ($env:ANDROID_AVD_HOME) { return $env:ANDROID_AVD_HOME }
    if ($env:ANDROID_USER_HOME) { return (Join-Path $env:ANDROID_USER_HOME 'avd') }
    return (Join-Path (Join-Path $HOME '.android') 'avd')
}

<# AVD name for a matrix row: trekui_<id>_api<api>. #>
function Get-AvdName($row) { "trekui_$($row.id)_api$($row.api)" }

<# Result folder / run key for a matrix row: <id>-api<api>[-<orientation>]. #>
function Get-RunKey($row) {
    $k = "$($row.id)-api$($row.api)"
    if ($row.orientation -and $row.orientation -ne 'natural') { $k += "-$($row.orientation)" }
    return $k
}

<# First SDK root that contains the given relative system-image folder. #>
function Find-ImageRoot([string]$Rel, [string[]]$Roots) {
    foreach ($root in $Roots) {
        if ($root -and (Test-Path (Join-Path $root (Join-Path $Rel 'system.img')))) { return $root }
    }
    return $null
}

<# Sets key=value pairs in an AVD .ini file, replacing existing keys and appending new ones. #>
function Set-IniValues([string]$Path, [System.Collections.IDictionary]$Values) {
    $lines = [System.Collections.Generic.List[string]]::new()
    if (Test-Path $Path) { foreach ($l in [IO.File]::ReadAllLines($Path)) { $lines.Add($l) } }
    foreach ($k in $Values.Keys) {
        $idx = -1
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match "^\s*$([regex]::Escape($k))\s*=") { $idx = $i; break }
        }
        $line = "$k=$($Values[$k])"
        if ($idx -ge 0) { $lines[$idx] = $line } else { $lines.Add($line) }
    }
    [IO.File]::WriteAllLines($Path, $lines)
}

<# Reads an AVD .ini into a hashtable. #>
function Get-IniValues([string]$Path) {
    $h = @{}
    if (Test-Path $Path) {
        foreach ($l in [IO.File]::ReadAllLines($Path)) {
            if ($l -match '^\s*([^=#]+?)\s*=\s*(.*)$') { $h[$Matches[1]] = $Matches[2] }
        }
    }
    return $h
}

<# Makes sure JAVA_HOME is set for avdmanager/sdkmanager/gradle (Adoptium/Android Studio JBR fallbacks). #>
function Initialize-Java {
    if ($env:JAVA_HOME -and (Test-Path $env:JAVA_HOME)) { return }
    $candidates = @()
    foreach ($base in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Android\Android Studio")) {
        if (Test-Path $base) { $candidates += Get-ChildItem $base -Directory | Sort-Object Name -Descending | ForEach-Object { $_.FullName } }
    }
    $candidates += "$env:ProgramFiles\Android\Android Studio\jbr"
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c 'bin\java.exe')) { $env:JAVA_HOME = $c; return }
    }
}
