$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $projectRoot 'src'
$classesDirectory = Join-Path $projectRoot 'out/classes'
$sourceFiles = @(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -File -Recurse |
    ForEach-Object { $_.FullName })

if ($sourceFiles.Count -eq 0) {
    throw "Java source files were not found in $sourceRoot"
}

New-Item -ItemType Directory -Path $classesDirectory -Force | Out-Null
& javac --release 17 -encoding UTF-8 -d $classesDirectory $sourceFiles

if ($LASTEXITCODE -ne 0) {
    throw "Compilation failed with exit code $LASTEXITCODE"
}

Write-Output "Compiled $($sourceFiles.Count) source file(s) to $classesDirectory"
