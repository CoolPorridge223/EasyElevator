param([string]$Jdk = $env:JAVA_HOME)
# NOTE (ASCII-only on purpose): Windows PowerShell 5.1 parses .ps1 files as ANSI unless they have a
# UTF-8 BOM, so a Chinese comment in a BOM-less script makes it fail with "unexpected token".
# Any editor that rewrites this file without a BOM would silently break it, so the comments here
# stay ASCII. The Chinese explanation of what these suites cover lives in docs/TESTING.md.
$ErrorActionPreference = 'Stop'
if (-not $Jdk) { throw 'Set JAVA_HOME to a JDK 21 or newer, or pass -Jdk <directory>.' }
$javac = Join-Path $Jdk 'bin/javac.exe'
$java = Join-Path $Jdk 'bin/java.exe'
foreach ($tool in @($javac, $java)) { if (-not (Test-Path -LiteralPath $tool)) { throw "Not a JDK: $Jdk" } }

# Pure-Java checks that need no Minecraft classpath: state machine / motion timeline, station panel
# layout, floor indicator, run status, sliding-leaf UV, and the telescopic cabin door layout.
$logicSources = @(
    'ElevatorParameters', 'ElevatorController', 'MotionTimeline', 'PanelLayout',
    'FloorIndicator', 'ElevatorStatus', 'LeafUv', 'SlidingDoor', 'FramedLeaf'
)
$tests = @('ElevatorControllerTest', 'PanelLayoutTest', 'FloorIndicatorTest', 'ElevatorStatusTest',
    'LeafUvTest', 'SlidingDoorTest', 'FramedLeafTest')
$sources = @()
foreach ($name in $logicSources) { $sources += "src/main/java/org/DJB/easyelevator/logic/$name.java" }
foreach ($name in $tests) { $sources += "src/test/java/org/DJB/easyelevator/logic/$name.java" }

Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    New-Item -ItemType Directory -Force build/logic-test | Out-Null
    # Native tools write warnings to stderr and $ErrorActionPreference = 'Stop' treats that as a
    # terminating error, which used to report "compilation failed" while hiding the real javac
    # output. Relax it for the two native calls only; exit codes are still checked one by one.
    $ErrorActionPreference = 'Continue'
    & $javac --release 21 -encoding UTF-8 -d build/logic-test @sources
    if ($LASTEXITCODE -ne 0) { throw 'Logic test compilation failed (see the javac output above).' }
    foreach ($test in $tests) {
        & $java -cp build/logic-test "org.DJB.easyelevator.logic.$test"
        if ($LASTEXITCODE -ne 0) { throw "$test failed (see the output above)." }
    }
} finally { Pop-Location }
