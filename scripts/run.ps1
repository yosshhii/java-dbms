$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$classesDirectory = Join-Path $projectRoot 'out/classes'

& (Join-Path $PSScriptRoot 'build.ps1')
& java -cp $classesDirectory Main

if ($LASTEXITCODE -ne 0) {
    throw "Application failed with exit code $LASTEXITCODE"
}
