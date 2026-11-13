$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $projectRoot 'src'
$documentationDirectory = Join-Path $projectRoot 'out/docs'
$sourceFiles = @(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -File -Recurse |
    ForEach-Object { $_.FullName })

if ($sourceFiles.Count -eq 0) {
    throw "Java source files were not found in $sourceRoot"
}

New-Item -ItemType Directory -Path $documentationDirectory -Force | Out-Null
& javadoc -quiet '-Xdoclint:all,-missing' -encoding UTF-8 -docencoding UTF-8 `
    -d $documentationDirectory $sourceFiles

if ($LASTEXITCODE -ne 0) {
    throw "Javadoc generation failed with exit code $LASTEXITCODE"
}

Write-Output "Javadoc generated in $documentationDirectory"
