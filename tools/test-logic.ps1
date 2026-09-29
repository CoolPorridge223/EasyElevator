param([string]$Jdk = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $Jdk) { throw 'Set JAVA_HOME to a JDK 21 or newer, or pass -Jdk <directory>.' }
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    New-Item -ItemType Directory -Force build/logic-test | Out-Null
    & (Join-Path $Jdk 'bin/javac.exe') --release 21 -encoding UTF-8 -d build/logic-test src/main/java/org/DJB/easyelevator/logic/ElevatorParameters.java src/main/java/org/DJB/easyelevator/logic/ElevatorController.java src/main/java/org/DJB/easyelevator/logic/MotionTimeline.java src/test/java/org/DJB/easyelevator/logic/ElevatorControllerTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Logic test compilation failed.' }
    & (Join-Path $Jdk 'bin/java.exe') -cp build/logic-test org.DJB.easyelevator.logic.ElevatorControllerTest
    if ($LASTEXITCODE -ne 0) { throw 'Logic tests failed.' }
} finally { Pop-Location }
