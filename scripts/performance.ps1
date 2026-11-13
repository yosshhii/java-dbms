$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$benchmarkFile = Join-Path $projectRoot 'benchmark/PerformanceTest.java'
$mainClasses = Join-Path $projectRoot 'out/classes'
$benchmarkClasses = Join-Path $projectRoot 'out/benchmark-classes'

& (Join-Path $PSScriptRoot 'build.ps1')
New-Item -ItemType Directory -Path $benchmarkClasses -Force | Out-Null
& javac --release 17 -encoding UTF-8 -cp $mainClasses -d $benchmarkClasses $benchmarkFile

if ($LASTEXITCODE -ne 0) {
    throw "Benchmark compilation failed with exit code $LASTEXITCODE"
}

$classPath = $mainClasses + [System.IO.Path]::PathSeparator + $benchmarkClasses
& java -cp $classPath PerformanceTest

if ($LASTEXITCODE -ne 0) {
    throw "Benchmark failed with exit code $LASTEXITCODE"
}
