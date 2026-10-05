param(
    [string]$Jdk = $env:JAVA_HOME,
    [ValidateSet('build','packageRelease','packageProject','runClient','runGameTest','clean')]
    [string]$Task = 'build'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $Jdk) {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $Jdk = Split-Path -Parent (Split-Path -Parent $javaCommand.Source) }
}
if (-not $Jdk -or -not (Test-Path -LiteralPath (Join-Path $Jdk 'bin/javac.exe'))) {
    throw 'JDK 21 required. Example: ./tools/build.ps1 -Jdk C:/Java/jdk-21 -Task packageRelease'
}
$releaseFile = Join-Path $Jdk 'release'
if (-not (Test-Path -LiteralPath $releaseFile) -or -not (Select-String -Path $releaseFile -Pattern '^JAVA_VERSION="21([.\-"]|$)' -Quiet)) {
    throw 'Use a full JDK 21 for this project (not Java 8, a JRE, or the IDE bundled runtime).'
}
$oldJava = $env:JAVA_HOME
$oldGradle = $env:GRADLE_USER_HOME
Push-Location $projectRoot
try {
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $Jdk).Path
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home'
    # Gradle/javac 会把告警（例如"使用了已过时的 API"）写到 stderr，而 $ErrorActionPreference = 'Stop'
    # 会把原生命令的 stderr 当成终止错误：一旦调用方把输出接进管道（`| Select-String ...`），
    # 脚本就会在构建其实成功的情况下抛 NativeCommandError。这里只对这一次原生调用放宽，
    # 失败与否仍旧按退出码判断（下面那行 throw）。
    $ErrorActionPreference = 'Continue'
    & (Join-Path $projectRoot 'gradlew.bat') $Task --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle task '$Task' failed (exit $LASTEXITCODE)." }
} finally {
    Pop-Location
    $env:JAVA_HOME = $oldJava
    $env:GRADLE_USER_HOME = $oldGradle
}
