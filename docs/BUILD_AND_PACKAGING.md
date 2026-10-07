# EasyElevator 工程构建与打包说明书

适用版本：模组 **2.2.0**，Minecraft **1.21.1**，Fabric Loader **0.19.2**，Java **21**。

> 面向**构建与发布**：环境固定项、命令速查、两种交付包、IDEA 设置与常见故障。
> 姊妹文档：[项目开发手册](PROJECT_MANUAL.md)（架构与接口）、[电梯参数手册](PARAMETERS.md)（数值）、
> [人工验收清单](TESTING.md)（进游戏验收）、[模型音效接口](ASSET_INTEGRATION.md)（替换素材）、[README](../README.md)（玩家向）。
>
> **路径约定**：本文所有 `<JDK21>` 都是占位符，请替换为**你自己机器上的 JDK 21 目录**。工程内不写死任何本机绝对路径。

## 1. 环境与已固定的构建配置

| 项目 | 配置                           | 修改位置 |
| --- |------------------------------| --- |
| Java 开发环境 | 完整 JDK 21，包括 java 和 javac    | JAVA_HOME / IDEA Gradle JVM |
| Java 编译版本 | toolchain=21，release=21，UTF-8 | build.gradle |
| Gradle | Wrapper 固定 8.14.3            | gradle/wrapper/gradle-wrapper.properties |
| Fabric Loom | 1.11.8 正式版                   | build.gradle |
| Minecraft | 1.21.1                       | gradle.properties 的 minecraft_version |
| Yarn 映射 | 1.21.1+build.3               | yarn_mappings |
| Fabric Loader | 0.19.2                       | loader_version |
| Fabric API | 0.116.17+1.21.1              | fabric_version |
| 模组版本 | 2.2.0                        | mod_version |
| 文件名前缀 | easyelevator                 | archives_base_name |
| 构建内存/并行度 | 最大堆 2 GB，最多 4 个工作线程          | org.gradle.jvmargs / org.gradle.workers.max |
| 缓存 | 启用 Gradle 构建缓存               | org.gradle.caching |
| 下载超时 | Wrapper 120 秒                | networkTimeout |
| 下载完整性 | 固定官方 Gradle SHA-256          | distributionSha256Sum |

不需要另行安装全局 Gradle。使用项目自带的 `gradlew.bat`；不要把 Loom 或 Gradle 改为随机的最新版本。项目没有写死电脑上的 JDK 路径，可迁移到其他机器。归档文件关闭文件时间戳、固定文件顺序，减少同一源码重复打包时的无意义差异。

## 2. 本机推荐方式

在工程根目录打开 PowerShell，用 `-Jdk` 指向本机的 JDK 21 目录：

```powershell
.\tools\build.ps1 -Jdk '<JDK21>' -Task build
```

省略 `-Jdk` 时脚本会依次尝试 `$env:JAVA_HOME` 与 PATH 上的 `java.exe`，两条路都找不到完整 JDK 21 时才报错并打印用法。脚本会校验 JDK `release` 文件里的 `JAVA_VERSION="21…"`（**拒绝 Java 8、JRE 与 IDE 自带的运行时**），临时设置 `JAVA_HOME`，并把下载缓存放到项目内 `.gradle-user-home`；执行完成后恢复原来的环境变量，不改系统配置。

第一次构建需要联网下载 Minecraft、Fabric 与 Gradle 依赖；后续会复用缓存。如果脚本被 Windows 执行策略阻止，可仅对本次进程调用：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build.ps1 -Jdk '<JDK21>' -Task build
```

## 3. 通用命令

其他电脑将路径改为其 JDK 21 目录：

```powershell
$env:JAVA_HOME = 'C:\Java\jdk-21'
.\gradlew.bat --version
.\gradlew.bat build
```

`--version` 中检查 Gradle=8.14.3，Launcher JVM/Daemon JVM 使用 Java 21。`build` 会编译客户端与服务端、处理资源，再生成重映射的正式 JAR。

**关于测试**：java 插件自动创建的 `test` / `testClasses` 任务已在 `build.gradle` 里关闭（工程内没有 `src/test`），所以 `build` 只做编译与打包。另有一套**可选开启**的乘客回归测试（`src/gametest`：12 个 `@GameTest` + 1 个客户端冒烟测试），只有显式加 `-PriderTests` 才会被 Loom 创建成 `easyelevator-test` 测试模组，**永远不会进发行包**：

```powershell
# 服务端 GameTest（12 项乘客回归）
.\tools\build.ps1 -Jdk '<JDK21>' -Task runGameTest
# 真实客户端里自动跑一遍乘坐流程
.\tools\build.ps1 -Jdk '<JDK21>' -Task runRiderClient
```

`tools/build.ps1` 会对这两个任务自动补上 `-PriderTests`。不加 `-PriderTests` 时构建行为与产物完全不变。日常验收仍以 [人工验收清单](TESTING.md) 为准。

| 目标 | 脚本参数 | 对应 Gradle 任务 |
| --- | --- | --- |
| 编译、生成 JAR | `-Task build` | `build` |
| 发布压缩包 | `-Task packageRelease` | `packageRelease` |
| 可编辑工程压缩包 | `-Task packageProject` | `packageProject` |
| 开发游戏客户端 | `-Task runClient` | `runClient` |
| 乘客回归测试（自动带 `-PriderTests`） | `-Task runGameTest` / `-Task runRiderClient` | `runGameTest` / `runRiderClient` |
| 清理旧构建输出 | `-Task clean` | `clean` |

2.2.0 发布包含乘客自主移动与网络延迟补偿，保留 2.1.x 的照明与玻璃效果。正式 JAR 的 `fabric.mod.json` 版本应为 `2.2.0`；发布包与工程包也必须包含本版文档。

改完代码后的验收方式见 [人工验收清单](TESTING.md)：需要真人进游戏逐项确认，没有可替代的离线检查。

## 4. 给玩家的发布包

```powershell
.\tools\build.ps1 -Jdk '<JDK21>' -Task packageRelease
```

输出 `build/distributions/easyelevator-2.2.0-release.zip`，内容：

- `mods/easyelevator-2.2.0.jar`：玩家安装的模组。
- `sources/easyelevator-2.2.0-sources.jar`：阅读用源码，不是安装文件。
- `docs/`、README.md、LICENSE.txt：搭建、参数、替换素材、验收说明。

也可以直接取 `build/libs/easyelevator-2.2.0.jar`。不要发布 `build/devlibs` 中未重映射的开发 JAR。

在 Minecraft **1.21.1 + Fabric Loader 0.19.2** 的实例中，将正式 JAR 和匹配 1.21.1 的 Fabric API 放入 `mods`。**2.2.0 改变了乘客移动协议（`rider_move`），服务端与所有客户端必须一起升级，不能混用 2.1.x 或更早版本的客户端。** 更新时删除 mods 中旧版 EasyElevator JAR，避免重复加载。

## 5. 给开发者的完整工程包

```powershell
.\tools\build.ps1 -Jdk '<JDK21>' -Task packageProject
```

输出 `build/distributions/easyelevator-2.2.0-project.zip`。顶层目录是 EasyElevator，包含源代码、模型/贴图/语言资源、文档、构建脚本、Gradle Wrapper 及其 JAR。

打包采用白名单（`build.gradle` 的 `packageProject`），不包含 `.idea`、`.gradle`、`.gradle-user-home`、`.tools`、`build`、日志或其他游戏存档。**不要手工压缩整个工程目录**，否则会带入体积很大的缓存。工程 ZIP 不含下载依赖；对方首次构建仍需联网。

`packageProject` 只归档，不运行任何检查。正式交付建议执行：

```powershell
$env:JAVA_HOME = '<JDK21>'
.\gradlew.bat packageRelease packageProject
```

只改模组版本时修改 `gradle.properties` 的 `mod_version`，产物名称和 fabric.mod.json 会自动更新；同步更新 README 和 docs 下全部文档的适用版本、产物名称与发布说明。

## 6. IntelliJ IDEA 设置

1. 解压工程，打开包含 `settings.gradle` 的根目录，按 Gradle 工程导入。
2. Project SDK 选择 JDK 21。
3. Settings → Build, Execution, Deployment → Build Tools → Gradle：Distribution 选择 Wrapper，Gradle JVM 选择同一个 JDK 21。
4. Gradle 刷新后运行 `build`；开发启动运行 `runClient`。
5. 不要在构建文件内填写本机绝对 JDK 路径，也不要把 `.idea` 放入交付压缩包。

## 7. 常见问题

| 现象 | 处理 |
| --- | --- |
| 默认 java 是 1.8，无法启动 Gradle | 使用带 `-Jdk` 的脚本；确认是完整 JDK 21 |
| Loom 插件或依赖无法下载 | 检查访问 Fabric Maven、Maven Central、Mojang、Gradle 下载源的网络；恢复后原命令重试 |
| 新环境第一次构建较慢 | 等待依赖下载和映射处理完成，不要同时启动多个构建 |
| 内存不足 | 先关闭多余开发进程；必要时将 `-Xmx2G` 改为 `-Xmx3G` |
| 想排查详细失败原因 | `.\gradlew.bat build --stacktrace --console=plain` |
| 混入或客户端类加载错误 | 检查游戏/Loader/API版本及是否混装两个 EasyElevator 版本 |
| 修改贴图没生效 | 检查资源路径，重新打包或在开发客户端重载资源 |
| 出现旧版本的 JAR | build/libs 可保留旧文件，按当前 mod_version 选择；需要时先 clean 再 build |

日志与人工验收见 [TESTING.md](TESTING.md)。