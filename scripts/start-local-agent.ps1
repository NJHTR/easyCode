[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateNotNullOrEmpty()]
    [string] $Prompt
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$mavenCommand = if ($env:EASYCODE_MAVEN_CMD) { $env:EASYCODE_MAVEN_CMD } else { 'mvn.cmd' }

if (-not (Get-Command $mavenCommand -ErrorAction SilentlyContinue)) {
    throw "Maven command '$mavenCommand' was not found. Install Maven or set EASYCODE_MAVEN_CMD to mvn.cmd."
}
if (-not (Get-Command 'java.exe' -ErrorAction SilentlyContinue)) {
    throw "java.exe was not found on PATH. Install a JDK 17+ and configure PATH."
}

$classPathFile = Join-Path $projectRoot 'target\easycode-runtime-classpath.txt'
$artifact = Join-Path $projectRoot 'target\easyCode-1.0-SNAPSHOT.jar'

Push-Location $projectRoot
try {
    & $mavenCommand '-q' '-DskipTests' 'package' 'dependency:build-classpath' `
        "-Dmdep.outputFile=$classPathFile" '-Dmdep.includeScope=runtime'
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $artifact)) {
        throw "Expected application artifact was not created: $artifact"
    }
    if (-not (Test-Path -LiteralPath $classPathFile)) {
        throw "Expected dependency classpath was not created: $classPathFile"
    }

    $dependencyClassPath = (Get-Content -LiteralPath $classPathFile -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($dependencyClassPath)) {
        throw "Generated dependency classpath is empty."
    }
    $classPath = $artifact + [IO.Path]::PathSeparator + $dependencyClassPath

    # Credentials are read by LocalAgentApplication from the process environment;
    # they never become command-line arguments or script output.
    & 'java.exe' '-cp' $classPath 'com.easycode.agent.application.LocalAgentApplication' $Prompt
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
