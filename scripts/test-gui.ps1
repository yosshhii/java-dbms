$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$mainClasses = Join-Path $projectRoot 'out/classes'
$testClasses = Join-Path $projectRoot 'out/gui-test-classes'
$testFile = Join-Path $projectRoot 'gui-test/DatabaseWindowSmokeTest.java'

& (Join-Path $PSScriptRoot 'build.ps1')
New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
& javac --release 17 -encoding UTF-8 -cp $mainClasses -d $testClasses $testFile
if ($LASTEXITCODE -ne 0) {
    throw "GUI test compilation failed with exit code $LASTEXITCODE"
}

$classPath = $mainClasses + [System.IO.Path]::PathSeparator + $testClasses
Push-Location -LiteralPath $projectRoot
try {
    & java '-Dfile.encoding=UTF-8' -cp $classPath DatabaseWindowSmokeTest
    if ($LASTEXITCODE -ne 0) {
        throw "GUI smoke test failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
