param([string]$Jdk = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $Jdk) { throw 'Set JAVA_HOME to a JDK 21 or newer, or pass -Jdk <directory>.' }
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    New-Item -ItemType Directory -Force build/logic-test | Out-Null
    # 纯 Java、不需要 Minecraft 类路径的检查：状态机/运动时间线 + 选站面板排布 + 楼层与运行状态显示。
    & (Join-Path $Jdk 'bin/javac.exe') --release 21 -encoding UTF-8 -d build/logic-test src/main/java/org/DJB/easyelevator/logic/ElevatorParameters.java src/main/java/org/DJB/easyelevator/logic/ElevatorController.java src/main/java/org/DJB/easyelevator/logic/MotionTimeline.java src/main/java/org/DJB/easyelevator/logic/PanelLayout.java src/main/java/org/DJB/easyelevator/logic/FloorIndicator.java src/main/java/org/DJB/easyelevator/logic/ElevatorStatus.java src/test/java/org/DJB/easyelevator/logic/ElevatorControllerTest.java src/test/java/org/DJB/easyelevator/logic/PanelLayoutTest.java src/test/java/org/DJB/easyelevator/logic/FloorIndicatorTest.java src/test/java/org/DJB/easyelevator/logic/ElevatorStatusTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Logic test compilation failed.' }
    & (Join-Path $Jdk 'bin/java.exe') -cp build/logic-test org.DJB.easyelevator.logic.ElevatorControllerTest
    if ($LASTEXITCODE -ne 0) { throw 'State machine tests failed.' }
    & (Join-Path $Jdk 'bin/java.exe') -cp build/logic-test org.DJB.easyelevator.logic.PanelLayoutTest
    if ($LASTEXITCODE -ne 0) { throw 'Panel layout tests failed.' }
    & (Join-Path $Jdk 'bin/java.exe') -cp build/logic-test org.DJB.easyelevator.logic.FloorIndicatorTest
    if ($LASTEXITCODE -ne 0) { throw 'Floor indicator tests failed.' }
    & (Join-Path $Jdk 'bin/java.exe') -cp build/logic-test org.DJB.easyelevator.logic.ElevatorStatusTest
    if ($LASTEXITCODE -ne 0) { throw 'Elevator status tests failed.' }
} finally { Pop-Location }
