param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [switch]$NoBuild,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$runtimeDirectory = Join-Path $PSScriptRoot '.run'
$logDirectory = Join-Path $PSScriptRoot 'logs'
$pidFile = Join-Path $runtimeDirectory 'zhida.pid'
$configFile = Join-Path $PSScriptRoot 'config\model-config.properties'
$configExample = Join-Path $PSScriptRoot 'config\model-config.properties.example'

function Stop-PreviousInstances {
    $allProcesses = @(Get-CimInstance Win32_Process)
    $projectProcessIds = [System.Collections.Generic.HashSet[int]]::new()

    if (Test-Path -LiteralPath $pidFile -PathType Leaf) {
        $storedPidText = (Get-Content -LiteralPath $pidFile -Raw).Trim()
        $storedPid = 0
        if ([int]::TryParse($storedPidText, [ref]$storedPid)) {
            $storedProcess = $allProcesses | Where-Object { $_.ProcessId -eq $storedPid } | Select-Object -First 1
            $storedCommandLine = [string]$storedProcess.CommandLine
            if ($null -ne $storedProcess -and
                $storedProcess.Name -in @('java.exe', 'javaw.exe') -and
                $storedCommandLine.IndexOf($PSScriptRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                [void]$projectProcessIds.Add($storedPid)
            }
        }
    }

    $jarMarker = Join-Path $PSScriptRoot 'target\zhida-'
    $mavenProjectMarker = "-Dmaven.multiModuleProjectDirectory=$PSScriptRoot"
    foreach ($candidate in $allProcesses) {
        if ($candidate.Name -notin @('java.exe', 'javaw.exe')) {
            continue
        }
        $candidateCommandLine = [string]$candidate.CommandLine
        $isPackagedApplication = $candidateCommandLine.IndexOf($jarMarker, [StringComparison]::OrdinalIgnoreCase) -ge 0
        $isMavenApplication = $candidateCommandLine.IndexOf($mavenProjectMarker, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
            $candidateCommandLine.IndexOf('spring-boot:run', [StringComparison]::OrdinalIgnoreCase) -ge 0
        if ($isPackagedApplication -or $isMavenApplication) {
            [void]$projectProcessIds.Add([int]$candidate.ProcessId)
        }
    }

    $foundDescendant = $true
    while ($foundDescendant) {
        $foundDescendant = $false
        foreach ($candidate in $allProcesses) {
            if ($projectProcessIds.Contains([int]$candidate.ParentProcessId) -and
                $projectProcessIds.Add([int]$candidate.ProcessId)) {
                $foundDescendant = $true
            }
        }
    }

    if ($projectProcessIds.Count -gt 0) {
        $ids = @($projectProcessIds) | Sort-Object -Descending
        Write-Host "Stopping previous Zhida processes: $($ids -join ', ')..." -ForegroundColor Yellow
        foreach ($processId in $ids) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        }
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

function Resolve-JavaExecutable {
    if ($env:JAVA_HOME) {
        $javaFromEnvironment = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $javaFromEnvironment -PathType Leaf) {
            return $javaFromEnvironment
        }
    }

    $knownJavaHome = 'D:\jdk'
    $knownJava = Join-Path $knownJavaHome 'bin\java.exe'
    if (Test-Path -LiteralPath $knownJava -PathType Leaf) {
        $env:JAVA_HOME = $knownJavaHome
        $env:Path = "$knownJavaHome\bin;$env:Path"
        return $knownJava
    }

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -eq $javaCommand) {
        throw 'Java was not found. Install JDK 17 or set JAVA_HOME before running this script.'
    }
    return $javaCommand.Source
}

if (-not (Test-Path -LiteralPath $configFile -PathType Leaf)) {
    New-Item -ItemType Directory -Path (Split-Path $configFile) -Force | Out-Null
    Copy-Item -LiteralPath $configExample -Destination $configFile
    Write-Host 'Created config\model-config.properties. Add API keys there and run this script again.' -ForegroundColor Yellow
    exit 1
}

New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
Stop-PreviousInstances

$javaExecutable = Resolve-JavaExecutable
$javawExecutable = Join-Path (Split-Path $javaExecutable) 'javaw.exe'
if (Test-Path -LiteralPath $javawExecutable -PathType Leaf) {
    $javaExecutable = $javawExecutable
}
if (-not $NoBuild) {
    Write-Host 'Building Zhida...' -ForegroundColor Cyan
    & "$PSScriptRoot\mvnw.cmd" -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE."
    }
}

$jar = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'target') -Filter 'zhida-*.jar' -File |
    Where-Object { $_.Name -notlike '*.original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $jar) {
    throw 'No application JAR was found. Run without -NoBuild first.'
}

$applicationLog = Join-Path $logDirectory 'zhida.log'
$javaArguments = @(
    '-jar',
    ('"{0}"' -f $jar.FullName),
    '--server.address=127.0.0.1',
    "--server.port=$Port",
    ('--logging.file.name="{0}"' -f $applicationLog)
)

Write-Host "Starting Zhida on http://127.0.0.1:$Port ..." -ForegroundColor Cyan
$process = Start-Process `
    -FilePath $javaExecutable `
    -ArgumentList $javaArguments `
    -WorkingDirectory $PSScriptRoot `
    -WindowStyle Hidden `
    -PassThru
Set-Content -LiteralPath $pidFile -Value $process.Id -Encoding ascii

$healthUri = "http://127.0.0.1:$Port/api/providers"
$started = $false
Add-Type -AssemblyName System.Net.Http
$healthHandler = [System.Net.Http.HttpClientHandler]::new()
$healthHandler.UseProxy = $false
$healthClient = [System.Net.Http.HttpClient]::new($healthHandler)
$healthClient.Timeout = [TimeSpan]::FromSeconds(2)
try {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Milliseconds 500
        if ($process.HasExited) {
            break
        }
        try {
            $healthResponse = $healthClient.GetAsync($healthUri).GetAwaiter().GetResult()
            if ($healthResponse.IsSuccessStatusCode) {
                $started = $true
                break
            }
        }
        catch {
            # The application is still starting.
        }
    }
}
finally {
    $healthClient.Dispose()
    $healthHandler.Dispose()
}

if (-not $started) {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    Write-Host "Startup failed. See $applicationLog." -ForegroundColor Red
    if (Test-Path -LiteralPath $applicationLog) {
        Get-Content -LiteralPath $applicationLog -Tail 30
    }
    exit 1
}

Write-Host "Zhida is running (PID $($process.Id)): http://127.0.0.1:$Port" -ForegroundColor Green
Write-Host "Log: $applicationLog"
Write-Host 'Run .\start.ps1 again to replace this instance.'

if (-not $NoBrowser) {
    Start-Process "http://127.0.0.1:$Port"
}
