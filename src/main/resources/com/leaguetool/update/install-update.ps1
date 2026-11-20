# RiftLens-Updater: tauscht den App-Ordner gegen die entpackte neue Version und startet RiftLens neu.
# Wird von RiftLens selbst geschrieben und gestartet (UpdateInstaller). Nur ASCII, weil Windows PowerShell 5.1
# Skripte ohne BOM als ANSI liest.
#
# Der Auftrag steht in einer JSON-Datei (-ParamsFile, UTF-8): source, target, log, pending, waitPids, allowed,
# leaguetoolHome. So gehen keine Pfade ueber eine zusammengesetzte Kommandozeile.
#
# -Detach: startet das Skript ohne -Detach ueber WMI neu und kehrt sofort zurueck. So laeuft der Updater ausserhalb
# des Job-Objekts des jpackage-Starters, das beim Beenden von RiftLens alle Kindprozesse mitbeendet.
param(
    [Parameter(Mandatory = $true)][string]$ParamsFile,
    [switch]$Detach
)

$ErrorActionPreference = 'Stop'

$p = Get-Content -LiteralPath $ParamsFile -Raw -Encoding UTF8 | ConvertFrom-Json
$Source = [string]$p.source
$Target = ([string]$p.target).TrimEnd('\')
$Log = [string]$p.log
$Pending = [string]$p.pending
$WaitPids = @($p.waitPids | ForEach-Object { [int]$_ })
$Allowed = @($p.allowed | ForEach-Object { [string]$_ })
if (-not $Source -or -not $Target -or -not $Log -or $Allowed.Count -eq 0) {
    throw "Auftrag unvollstaendig: $ParamsFile"
}

function Write-Log([string]$Text) {
    try {
        Add-Content -LiteralPath $Log -Value ('{0:yyyy-MM-dd HH:mm:ss} {1}' -f (Get-Date), $Text) -Encoding UTF8
    } catch {
        # ohne Log weiter
    }
}

# Ordner ist ein RiftLens-Programmordner und enthaelt nur bekannte Eintraege (sonst nie verschieben oder loeschen).
function Test-AppFolder([string]$Dir) {
    if (-not (Test-Path -LiteralPath (Join-Path $Dir 'RiftLens.exe') -PathType Leaf)) { return $false }
    if (-not (Test-Path -LiteralPath (Join-Path $Dir 'app\league-tool-modern.jar') -PathType Leaf)) { return $false }
    $foreign = @(Get-ChildItem -LiteralPath $Dir -Force | Where-Object { $Allowed -notcontains $_.Name })
    if ($foreign.Count -gt 0) {
        Write-Log "Fremde Eintraege in ${Dir}: $(($foreign | ForEach-Object { $_.Name }) -join ', ')"
        return $false
    }
    return $true
}

# Umbenennen ohne Verschachteln: Directory.Move schlaegt fehl, wenn das Ziel existiert (Move-Item wuerde hineinschieben).
function Move-Folder([string]$From, [string]$To) {
    for ($i = 0; $i -lt 40; $i++) {
        if (Test-Path -LiteralPath $To) { return $false }
        try {
            [System.IO.Directory]::Move($From, $To)
            return $true
        } catch {
            Start-Sleep -Milliseconds 750
        }
    }
    return $false
}

function Wait-NoProcessIn([string]$Dir, [int]$Seconds) {
    $prefix = $Dir + '\'
    $until = (Get-Date).AddSeconds($Seconds)
    while ($true) {
        $running = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) })
        if ($running.Count -eq 0 -or (Get-Date) -ge $until) { return $running }
        Start-Sleep -Milliseconds 500
    }
}

function Start-App([string]$Dir) {
    $exe = Join-Path $Dir 'RiftLens.exe'
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
        Write-Log "FEHLER: $exe fehlt, RiftLens bitte von Hand starten"
        return $null
    }
    try {
        if ($p.leaguetoolHome) {
            $env:LEAGUETOOL_HOME = [string]$p.leaguetoolHome
        }
        $process = Start-Process -FilePath $exe -WorkingDirectory $Dir -PassThru
        Write-Log "Gestartet: $exe"
        return $process
    } catch {
        Write-Log "FEHLER: $exe startet nicht: $($_.Exception.Message)"
        return $null
    }
}

# Neue Version gilt als gestartet, sobald sie pending.properties entfernt (UpdateService beim Start).
# Ergebnis: ok | exited (Starter vorher beendet) | timeout (laeuft, meldet sich aber nicht)
function Wait-Started($Process) {
    if ($null -eq $Process) { return 'exited' }
    if (-not $Pending) { return 'ok' }
    $until = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $until) {
        if (-not (Test-Path -LiteralPath $Pending)) { return 'ok' }
        if ($Process.HasExited) {
            Start-Sleep -Milliseconds 500
            if (-not (Test-Path -LiteralPath $Pending)) { return 'ok' }
            return 'exited'
        }
        Start-Sleep -Milliseconds 500
    }
    return 'timeout'
}

# Alte Version zurueck an ihren Platz. Was dort liegt, ist die gerade eingesetzte neue Version.
function Restore-Backup {
    if (-not $script:backup -or -not (Test-Path -LiteralPath $script:backup)) { return }
    if (Test-Path -LiteralPath $Target) {
        $failed = $Target + '.failed-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
        if (-not (Move-Folder $Target $failed)) {
            Write-Log "FEHLER: $Target laesst sich nicht entfernen, alte Version liegt in $($script:backup)"
            return
        }
        Remove-Item -LiteralPath $failed -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (Move-Folder $script:backup $Target) {
        Write-Log 'Alte Version wiederhergestellt'
    } else {
        Write-Log "FEHLER: Alte Version liegt weiter in $($script:backup), bitte von Hand zurueckbenennen"
    }
}

if ($Detach) {
    $self = $MyInvocation.MyCommand.Path
    $exe = Join-Path $PSHOME 'powershell.exe'
    $command = '"{0}" -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "{1}" -ParamsFile "{2}"' -f $exe, $self, $ParamsFile
    $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }
    $result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine = $command
        CurrentDirectory = (Split-Path -Parent $self)
        ProcessStartupInformation = $startup
    }
    if ($result.ReturnValue -ne 0) {
        Write-Log "FEHLER: Updater konnte nicht gestartet werden (Win32_Process.Create = $($result.ReturnValue))"
        exit 2
    }
    Write-Log "Updater gestartet (PID $($result.ProcessId))"
    exit 0
}

Write-Log "Update: $Source -> $Target"
$script:backup = $null
try {
    # 1. Warten, bis RiftLens (Starter und Java-Prozess) beendet ist
    $deadline = (Get-Date).AddSeconds(90)
    foreach ($id in $WaitPids) {
        while ((Get-Process -Id $id -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 300
        }
    }
    $running = @(Wait-NoProcessIn $Target ([int][Math]::Max(1, ($deadline - (Get-Date)).TotalSeconds)))
    if ($running.Count -gt 0) {
        Write-Log "FEHLER: Aus $Target laufen noch Programme ($(($running | ForEach-Object { $_.Name }) -join ', ')), Update abgebrochen"
        exit 1
    }

    # 2. Nur einen reinen RiftLens-Ordner austauschen (RiftLens hat das schon geprueft; hier noch einmal direkt davor)
    if (-not (Test-AppFolder $Target)) {
        Write-Log "FEHLER: $Target ist kein reiner RiftLens-Ordner, Update abgebrochen"
        $null = Start-App $Target
        exit 1
    }
    if (-not (Test-Path -LiteralPath (Join-Path $Source 'RiftLens.exe') -PathType Leaf)) {
        Write-Log "FEHLER: Neue Version fehlt in $Source, Update abgebrochen"
        $null = Start-App $Target
        exit 1
    }

    # 3. Alten Ordner beiseitelegen. Ein vorhandenes .old wird nur geloescht, wenn es ein alter RiftLens-Ordner ist.
    $candidate = $Target + '.old'
    if (Test-Path -LiteralPath $candidate) {
        $removed = $false
        if (Test-AppFolder $candidate) {
            try {
                Remove-Item -LiteralPath $candidate -Recurse -Force
                $removed = $true
            } catch {
                Write-Log "Hinweis: $candidate laesst sich nicht entfernen"
            }
        }
        if (-not $removed) {
            $candidate = $Target + '.old-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
        }
    }
    if (-not (Move-Folder $Target $candidate)) {
        Write-Log "FEHLER: $Target ist gesperrt (offener Explorer oder Konsole im Ordner?), Update abgebrochen"
        $null = Start-App $Target
        exit 1
    }
    $script:backup = $candidate

    # 4. Neue Version an die alte Stelle (geht Verschieben nicht, z. B. auf ein anderes Laufwerk: kopieren)
    try {
        try {
            [System.IO.Directory]::Move($Source, $Target)
        } catch {
            if (Test-Path -LiteralPath $Target) { throw }
            Copy-Item -LiteralPath $Source -Destination $Target -Recurse
            Remove-Item -LiteralPath $Source -Recurse -Force -ErrorAction SilentlyContinue
        }
        if (-not (Test-Path -LiteralPath (Join-Path $Target 'app\league-tool-modern.jar') -PathType Leaf)) {
            throw 'App-Jar fehlt nach dem Einsetzen'
        }
    } catch {
        Write-Log "FEHLER beim Einsetzen der neuen Version: $($_.Exception.Message) - alte Version wird wiederhergestellt"
        Restore-Backup
        $null = Start-App $Target
        exit 1
    }
    Write-Log 'Neue Version eingesetzt'

    # 5. Neue Version starten und abwarten, ob sie hochkommt; sonst die alte zurueckholen
    $started = Wait-Started (Start-App $Target)
    if ($started -eq 'exited') {
        Write-Log 'FEHLER: Neue Version startet nicht, alte Version wird wiederhergestellt'
        $null = Wait-NoProcessIn $Target 30
        Restore-Backup
        $null = Start-App $Target
        exit 1
    }
    if ($started -eq 'timeout') {
        Write-Log "Hinweis: Neue Version laeuft, hat sich aber nicht gemeldet. Alte Version bleibt in $($script:backup)"
        exit 0
    }
    Remove-Item -LiteralPath $script:backup -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $script:backup) {
        Write-Log "Hinweis: $($script:backup) konnte nicht entfernt werden"
    }
    Write-Log 'Fertig'
} catch {
    Write-Log "FEHLER: $($_.Exception.Message)"
    if ($script:backup -and -not (Test-Path -LiteralPath (Join-Path $Target 'RiftLens.exe'))) {
        Restore-Backup
    }
    $null = Start-App $Target
    exit 1
}
