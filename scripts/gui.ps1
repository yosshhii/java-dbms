param(
    [string]$DatabaseFile = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$classesDirectory = Join-Path $projectRoot 'out/classes'

& (Join-Path $PSScriptRoot 'build.ps1')
Push-Location -LiteralPath $projectRoot
try {
    if ([string]::IsNullOrWhiteSpace($DatabaseFile)) {
        & java '-Dfile.encoding=UTF-8' -cp $classesDirectory GuiMain
    } else {
        & java '-Dfile.encoding=UTF-8' -cp $classesDirectory GuiMain $DatabaseFile
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Application failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
