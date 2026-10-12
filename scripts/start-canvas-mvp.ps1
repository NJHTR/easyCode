param(
    [string]$MavenCommand = $env:EASYCODE_MAVEN_CMD
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($MavenCommand)) {
    $mavenOnPath = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
    if ($null -ne $mavenOnPath) {
        $MavenCommand = $mavenOnPath.Source
    } else {
        $ideaMaven = Get-ChildItem -Path "$env:ProgramFiles\JetBrains\IntelliJ IDEA Community Edition*\plugins\maven\lib\maven3\bin\mvn.cmd" `
            -ErrorAction SilentlyContinue | Sort-Object -Property FullName -Descending | Select-Object -First 1
        if ($null -eq $ideaMaven) {
            throw 'Maven was not found. Set EASYCODE_MAVEN_CMD to the full path of mvn.cmd.'
        }
        $MavenCommand = $ideaMaven.FullName
    }
}

Push-Location $repositoryRoot
try {
    & $MavenCommand -q -DskipTests compile dependency:build-classpath '-Dmdep.outputFile=target/canvas-mvp-classpath.txt'
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    $classpath = "target/classes;$((Get-Content 'target/canvas-mvp-classpath.txt' -Raw).Trim())"
    & java -cp $classpath com.easycode.canvas.desktop.CanvasDesktopApplication
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
