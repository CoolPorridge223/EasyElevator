# EasyElevator 本机 Gradle 快捷构建脚本。
#
# 职责：校验 JDK 21 → 临时设置 JAVA_HOME 与项目内 GRADLE_USER_HOME → 调用 gradlew.bat → 还原环境变量。
# 它不修改任何系统配置，也不在仓库里写死本机路径（JDK 由 -Jdk 参数或 $env:JAVA_HOME 提供）。
#
# 用法：
#   .\tools\build.ps1 -Jdk 'C:\Java\jdk-21' -Task build
#   .\tools\build.ps1 -Task runGameTest        # 乘客回归测试，自动补 -PriderTests
#
# 退出行为：Gradle 退出码非 0 时抛异常（脚本失败）；成功时静默返回。
param(
    [string]$Jdk = $env:JAVA_HOME,
    [ValidateSet('build','packageRelease','packageProject','runClient','runGameTest','runRiderClient','runCargoClient','clean')]
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
# 乘客回归测试的任务名（runGameTest / runRiderClient / runClientGameTest）只有在 `-PriderTests`
# 打开时才由 Loom 注册；不加这个属性直接调用会得到 "Task not found"。这里按任务名自动补上，
# 其余任务（build / package* / runClient / clean）不带该属性，产物与发布流程不受影响。
$priderTests = $Task -in @('runGameTest','runRiderClient','runCargoClient','runClientGameTest')
$gradleArgs = @()
if ($priderTests) { $gradleArgs += '-PriderTests' }
$gradleArgs += @($Task, '--console=plain')
Push-Location $projectRoot
try {
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $Jdk).Path
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home'
    # Gradle/javac 会把告警（例如"使用了已过时的 API"）写到 stderr，而 $ErrorActionPreference = 'Stop'
    # 会把原生命令的 stderr 当成终止错误：一旦调用方把输出接进管道（`| Select-String ...`），
    # 脚本就会在构建其实成功的情况下抛 NativeCommandError。这里只对这一次原生调用放宽，
    # 失败与否仍旧按退出码判断（下面那行 throw）。
    $ErrorActionPreference = 'Continue'
    & (Join-Path $projectRoot 'gradlew.bat') @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle task '$Task' failed (exit $LASTEXITCODE)." }
} finally {
    Pop-Location
    $env:JAVA_HOME = $oldJava
    $env:GRADLE_USER_HOME = $oldGradle
}
