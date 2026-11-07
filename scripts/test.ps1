$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $projectRoot 'test'
$mainClasses = Join-Path $projectRoot 'out/classes'
$testClasses = Join-Path $projectRoot 'out/test-classes'
$testFiles = @(Get-ChildItem -LiteralPath $testRoot -Filter '*.java' -File -Recurse |
    ForEach-Object { $_.FullName })

if ($testFiles.Count -eq 0) {
    throw "Java test files were not found in $testRoot"
}

& (Join-Path $PSScriptRoot 'build.ps1')
New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
& javac --release 17 -encoding UTF-8 -cp $mainClasses -d $testClasses $testFiles

if ($LASTEXITCODE -ne 0) {
    throw "Test compilation failed with exit code $LASTEXITCODE"
}

$classPath = $mainClasses + [System.IO.Path]::PathSeparator + $testClasses
& java -cp $classPath DatabaseTest

if ($LASTEXITCODE -ne 0) {
    throw "Tests failed with exit code $LASTEXITCODE"
}
