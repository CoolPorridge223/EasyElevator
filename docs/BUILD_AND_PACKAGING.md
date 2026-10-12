# EasyElevator 工程构建与打包说明书

适用版本：模组 **2.3.0+1.20.1**，Minecraft **1.20.1**，Fabric Loader **0.19.5**，Java **17**。

> 面向**构建与发布**：环境固定项、命令速查、两种交付包、IDEA 设置与常见故障。
> 姊妹文档：[项目开发手册](PROJECT_MANUAL.md)（架构与接口）、[电梯参数手册](PARAMETERS.md)（数值）、
> [人工验收清单](TESTING.md)（进游戏验收）、[模型音效接口](ASSET_INTEGRATION.md)（替换素材）、[README](../README.md)（玩家向）。
>
> **路径约定**：本文的 `<JDK17>`、`<JDK21>` 都是占位符，请替换为**你自己机器上的对应 JDK 目录**——
> `<JDK17>` 指编译与运行 MC 1.20.1 用的完整 JDK 17，`<JDK21>` 指用来启动 Gradle 进程的完整 JDK（21 或 17 都行）。
>
> **版本线约定**：本工程用分支区分 Minecraft 版本——`main` 是 **1.21.1** 线，`mc/1.20.1` 是**本文所在的 1.20.1 线**。
> 两条线共用同一份源码与同一份文档骨架，差异集中在构建配置、资源目录名与网络层收发（见第 9 节）。
> 分支划分、backport 纪律与标签/发布约定见 [版本线与发布流程](VERSIONING.md)。
> 产物名一律带 `+<MC 版本>` 后缀，因此两条线的 JAR 不会同名相撞。

## 1. 环境与已固定的构建配置

| 项目 | 配置                           | 修改位置 |
| --- |------------------------------| --- |
| Gradle 进程用的 JDK | JDK 21（Wrapper 8.14.3 + Loom 1.11.8 的要求；JDK 17 亦可） | JAVA_HOME / IDEA Gradle JVM |
| Java 编译与运行 | **JDK 17**（`toolchain=17`、`release=17`、UTF-8） | build.gradle + `org.gradle.java.installations.paths` |
| Gradle | Wrapper 固定 8.14.3            | gradle/wrapper/gradle-wrapper.properties |
| Fabric Loom | 1.11.8 正式版                   | build.gradle |
| Minecraft | 1.20.1                       | gradle.properties 的 minecraft_version |
| Yarn 映射 | 1.20.1+build.10              | yarn_mappings |
| Fabric Loader | 0.19.5                       | loader_version |
| Fabric API | 0.92.12+1.20.1               | fabric_version |
| 模组版本 | 2.3.0+1.20.1                 | mod_version |
| 文件名前缀 | easyelevator                 | archives_base_name |
| 构建内存/并行度 | 最大堆 2 GB，最多 4 个工作线程          | org.gradle.jvmargs / org.gradle.workers.max |
| 缓存 | 启用 Gradle 构建缓存               | org.gradle.caching |
| 下载超时 | Wrapper 120 秒                 | networkTimeout |
| 下载完整性 | 固定官方 Gradle SHA-256          | distributionSha256Sum |

不需要另行安装全局 Gradle。使用项目自带的 `gradlew.bat`；不要把 Loom 或 Gradle 改为随机的最新版本。归档文件关闭文件时间戳、固定文件顺序，减少同一源码重复打包时的无意义差异。

**为什么 JDK 有两行**：Gradle 守护进程本身用 JDK 21 启动即可（Loom 1.11.8 支持），但 MC 1.20.1 的编译与运行都必须是 **Java 17**（1.20.1 的原版类文件版本是 61）。`build.gradle` 用 toolchain 声明 17，Gradle 会在你机器上寻找 JDK 17；`gradle.properties` 里这一行把路径直接告诉它：

```properties
org.gradle.java.installations.paths=D:/Program Files/Java/jdk-17
```

JDK 17 若装在 Gradle 能自动发现的位置（系统默认安装目录、`JDK17` 之类的环境变量），可以删掉这一行。这也是本工程唯一允许出现本机绝对路径的地方（换成你自己机器上的路径即可）。

## 2. 本机推荐方式

在工程根目录打开 PowerShell，把 `JAVA_HOME` 指向一个完整 JDK（21 或 17 都行，见上一节），然后调用 Wrapper：

```powershell
$env:JAVA_HOME = '<JDK21>'
.\gradlew.bat build --console=plain
```

`JAVA_HOME` 必须是**完整 JDK**（含 `javac`）：只装 JRE、只有 `java.exe` 在 PATH 上、或 IDE 自带的精简运行时，都会在编译阶段直接失败。工程不校验你的 JDK 路径，也不修改任何系统配置；关掉这个终端 `JAVA_HOME` 即失效。

第一次构建需要联网下载 Minecraft 1.20.1、Yarn 映射与 Fabric 依赖；后续会复用缓存（默认在用户目录的 `.gradle`，工程内只有 `.gradle/` 与 `build/` 两个缓存目录，都不进版本库）。

如果 PowerShell 执行策略拦截了 `gradlew.bat`，可只对本次进程放宽：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\gradlew.bat build
```

## 3. 通用命令

```powershell
$env:JAVA_HOME = '<JDK21>'
.\gradlew.bat --version
.\gradlew.bat build
```

`--version` 中检查 Gradle=8.14.3。`build` 会编译客户端与服务端、处理资源，再生成重映射的正式 JAR。

**关于测试**：java 插件自动创建的 `test` / `testClasses` 任务已在 `build.gradle` 里关闭（工程内没有 `src/test`），所以 `build` 只做编译与打包。工程内**没有常驻自动化测试**，验收一律进游戏按 [人工验收清单](TESTING.md) 逐项确认。

| 目标 | 对应 Gradle 任务 |
| --- | --- |
| 编译、生成 JAR | `.\gradlew.bat build` |
| 发布压缩包 | `.\gradlew.bat packageRelease` |
| 可编辑工程压缩包 | `.\gradlew.bat packageProject` |
| 开发游戏客户端 | `.\gradlew.bat runClient` |
| 开发游戏服务端（冒烟：验证 Mixin 注入与数据包加载） | `.\gradlew.bat runServer` |
| 离线数据生成（当前无 provider，不产出文件） | `.\gradlew.bat runDatagen` |
| 清理旧构建输出 | `.\gradlew.bat clean` |

2.3.0 给重载轿厢加上 **27 格货舱**：动态载客上限（空舱 20 人 → 满载 6 人）、随货量长出的最多 6 个可碰撞货箱，
以及一个原版容器菜单；重载轿厢的速度同时改为普通梯的 2/3。正式 JAR 的 `fabric.mod.json` 版本应为 `2.3.0+1.20.1`；
发布包与工程包也必须包含本版文档。

改完代码后的验收方式见 [人工验收清单](TESTING.md)：需要真人进游戏逐项确认，没有可替代的离线检查。
**本版移植时最值得先跑的是 `runServer`**：它能在几十秒内验证模组加载、两个 required Mixin 的注入、
注册表与数据包（配方 / 掉落表 / 标签）是否真的能被 1.20.1 解析，不需要人开客户端。

## 4. 给玩家的发布包

```powershell
$env:JAVA_HOME = '<JDK21>'
.\gradlew.bat packageRelease
```

输出 `build/distributions/easyelevator-2.3.0+1.20.1-release.zip`，内容：

- `mods/easyelevator-2.3.0+1.20.1.jar`：玩家安装的模组。
- `sources/easyelevator-2.3.0+1.20.1-sources.jar`：阅读用源码，不是安装文件。
- `docs/`、README.md、LICENSE.txt：搭建、参数、替换素材、验收说明。

也可以直接取 `build/libs/easyelevator-2.3.0+1.20.1.jar`。不要发布 `build/devlibs` 中未重映射的开发 JAR。

在 Minecraft **1.20.1 + Fabric Loader 0.19.5** 的实例中，将正式 JAR 和匹配 1.20.1 的 Fabric API（0.92.x）放入 `mods`。**2.2.0 改变了乘客移动协议（`rider_move`），服务端与所有客户端必须一起升级，不能混用 2.1.x 或更早版本的客户端。** **2.3.0 又新增了货舱菜单类型（`easyelevator:cargo`）与重载轿厢的同步字段，同样要求服务端与所有客户端一起升级**：旧客户端既看不到货箱、算不出动态限载，也无法同步货舱槽位。更新时删除 mods 中旧版 EasyElevator JAR，避免重复加载。

**1.20.1 与 1.21.1 的 JAR 互不通用**：`fabric.mod.json` 里的 `minecraft` 依赖已收窄为 `~1.20.1`，装错实例时 Fabric Loader 会直接拒绝加载（而不是进游戏后才崩在 Mixin 里）。

## 5. 给开发者的完整工程包

```powershell
$env:JAVA_HOME = '<JDK21>'
.\gradlew.bat packageProject
```

输出 `build/distributions/easyelevator-2.3.0+1.20.1-project.zip`。顶层目录是 EasyElevator，包含源代码、模型/贴图/语言资源、文档、构建脚本与 Gradle Wrapper 及其 JAR。

打包采用白名单（`build.gradle` 的 `packageProject`），只收录 `src/**`、`docs/**`、Gradle Wrapper、构建脚本与顶层说明文件，不包含 `.idea`、`.gradle`、`.gradle-user-home`、`build`、日志或其他游戏存档。**不要手工压缩整个工程目录**，否则会带入体积很大的缓存。工程 ZIP 不含下载依赖；对方首次构建仍需联网。

`packageProject` 只归档，不运行任何检查。正式交付建议执行：

```powershell
$env:JAVA_HOME = '<JDK21>'
.\gradlew.bat packageRelease packageProject
```

只改模组版本时修改 `gradle.properties` 的 `mod_version`，产物名称和 fabric.mod.json 会自动更新；同步更新 README 和 docs 下全部文档的适用版本、产物名称与发布说明。

## 6. IntelliJ IDEA 设置

1. 解压工程，打开包含 `settings.gradle` 的根目录，按 Gradle 工程导入。
2. Project SDK 选择 **JDK 17**（与 toolchain 一致）；Gradle JVM 用 JDK 21 也可以。
3. Settings → Build, Execution, Deployment → Build Tools → Gradle：Distribution 选择 Wrapper，Gradle JVM 选择完整 JDK，并在 Gradle JDK 之外确认 toolchain 能找到 JDK 17（见第 1 节）。
4. Gradle 刷新后运行 `build`；开发启动运行 `runClient`。
5. 不要在构建文件内填写本机绝对 JDK 路径（`org.gradle.java.installations.paths` 是唯一例外），也不要把 `.idea` 放入交付压缩包。

## 7. 常见问题

| 现象 | 处理 |
| --- | --- |
| 默认 java 是 1.8，无法启动 Gradle | 先把 `JAVA_HOME` 指向完整 JDK，再调用 `.\gradlew.bat` |
| 报 "No matching toolchains found for Java 17" | JDK 17 没装或 Gradle 找不到：安装 JDK 17，或在 gradle.properties 里写好 `org.gradle.java.installations.paths` |
| Loom 插件或依赖无法下载 | 检查访问 Fabric Maven、Maven Central、Mojang、Gradle 下载源的网络；恢复后原命令重试 |
| 新环境第一次构建较慢 | 等待依赖下载和映射处理完成，不要同时启动多个构建 |
| 内存不足 | 先关闭多余开发进程；必要时将 `-Xmx2G` 改为 `-Xmx3G` |
| 想排查详细失败原因 | `.\gradlew.bat build --stacktrace --console=plain` |
| 混入或客户端类加载错误 | 检查游戏/Loader/API版本及是否混装两个 EasyElevator 版本 |
| 修改贴图没生效 | 检查资源路径，重新打包或在开发客户端重载资源 |
| 出现旧版本的 JAR | build/libs 可保留旧文件，按当前 mod_version 选择；需要时先 clean 再 build |

日志与人工验收见 [TESTING.md](TESTING.md)。

## 8. 已知问题：1.20.1 上自定义几何不显示（未解决）

**症状**：方块模型（门框、立柱、轨道）正常，方块实体/实体的**文字**（门楣层号）正常，原版实体（生物）正常，
碰撞箱正常——但 `BoxMesh` 画的全部自定义几何（轿厢外壳与内饰、楼层门门扇、观光玻璃）**一个都看不见**，
日志里没有任何 ERROR/Exception。

**已经排除的原因**（每一条都做过对照实验，实验方法一并记下，便于以后复用）：

| 假设 | 实验 | 结论 |
| --- | --- | --- |
| 渲染器没被调用 | 在 `CabinRenderer.render`/`LandingDoorRenderer.render` 里打印调用日志 | 都**被调用**了，参数与方块状态正常 |
| 矩阵/坐标算错 | 用 `Vector4f.mul(matrix)` 换算顶点，打印相机相对坐标 | 正确（轿厢在相机前方 ~11 格、包围盒 = 实际位置） |
| 顶点颜色透明被 cutout 丢弃 | 打印写入缓冲的 `color` | `0xffffffff`（不透明） |
| 贴图丢失/带透明 | 直接查看 `cabin.png`（128×128、4×4 图集、RGBA、完全不透明）；把测试方块换成**原版石头贴图**再画一次 | 换原版贴图一样不显示 → **与模组贴图无关** |
| 实体渲染通道坏了 | 在同一次运行里生成 3 只**原版牛** | 牛正常显示 → 实体通道本身没问题 |
| 图层被背面剔除 | 测试方块改用 `getEntityCutoutNoCull`（含改用**直接绘制路径**再测一次） | 一样不显示 → 与剔除无关 |
| 写入的缓冲没人画 | ① 每次切层前显式 `drawCurrentLayer()`；② 完全绕开 `Immediate`，改成原版的直接绘制路径（`Tessellator` + `RenderLayer.draw(BufferBuilder, VertexSorter)`，内部即 `end() → startDrawing() → BufferRenderer.drawWithGlobalProgram() → endDrawing()`） | **两种都不改变症状**（改动均已回滚） |
| 顶点数据本身坏了 | 在渲染器里写一个 **字面坐标**、**完全不用矩阵也不用 `BoxMesh`** 的 3×3 方块面 | 一样不显示 → **与 `BoxMesh`/矩阵无关**，从我们的渲染器里写出的顶点就是到不了屏幕 |
| 图层没被预注册 | 改用原版**预注册**的图集实体层 `TexturedRenderLayers.getEntityCutout()` 画同一个测试面 | 一样不显示 |

**源码层面的机制**（`genSources` 反编译后确认，是理解这个问题的关键）：

```java
// WorldRenderer.renderEntities（1.20.1）：实体循环结束后只画这些
immediate.drawCurrentLayer();                                        // 只画"当前图层"
immediate.draw(RenderLayer.getEntitySolid(BLOCK_ATLAS_TEXTURE));     // 以及 4 个预注册的图集实体层
immediate.draw(RenderLayer.getEntityCutout(BLOCK_ATLAS_TEXTURE));
immediate.draw(RenderLayer.getEntityCutoutNoCull(BLOCK_ATLAS_TEXTURE));
immediate.draw(RenderLayer.getEntitySmoothCutout(BLOCK_ATLAS_TEXTURE));
```

1.21 的 `VertexConsumerProvider$Immediate` 给**每个图层**各留一个缓冲；1.20.1 只由 `BufferBuilderStorage`
预建少数内置图层的缓冲（图集实体层、附魔光效、水罩、床/箱子/告示牌等），**自定义贴图的图层一律落在同一个
共享 fallback 缓冲上**。按 `Immediate.getBuffer` 的源码，切到新图层时会把上一个（未预注册的）图层
`draw` 掉，因此"一帧内混用多个自定义图层"理论上仍然能画出来——但实测从不出现。
注意 `RenderLayer.draw()` 只在 `buffer.isBuilding()` 时才真正绘制，且 `Immediate.draw(RenderLayer)`
对"既不是当前图层、缓冲又是 fallback"的图层会**直接返回**。

**实验方法（可复用）**：临时在 `EasyelevatorClient` 的客户端刻回调里，用**集成服务端**把玩家传送到观察位
（`server.getPlayerManager().getPlayer(uuid).teleport(...)`；直接改客户端坐标会被服务端拉回），
再用 `ScreenshotRecorder.saveScreenshot(client.runDirectory, "x.png", client.getFramebuffer(), t -> {})`
（注意：截图落在 `run/screenshots/` 而不是 `run/`）自动截图，然后用图片直接看效果。
配合"往渲染器里塞一个 16×11×16 的测试方块"可以快速区分"渲染路径坏了"还是"绘制代码坏了"。

**下一步建议（尚未执行）**：用临时 Mixin 钩住 `VertexConsumerProvider$Immediate`
的 `getBuffer` / `drawCurrentLayer` / `draw(RenderLayer)`，打印**图层名、`buffer.isBuilding()`、顶点数、是否 fallback**，
即可判定"我们的图层到底有没有被画、画的时候缓冲里有多少顶点"。这是目前唯一能把问题钉死的手段。
另一条可行性更高的路线：**把轿厢/门扇改用原版模型渲染**（`ModelPart`/`TexturedModelData`，或把几何烘成方块模型
再用 `BlockModelRenderer`）——原版牛的模型走的是同一条 `Immediate.getBuffer` 路径且**在本机实测正常**，
说明"模型渲染"这条链路是好的；代价是要重写几何数据表，而且逐面补光（`CabinLighting`）可能无法完全保留。


## 9. 1.20.1 线与 main（1.21.1）的差异清单

两条线共享同一份源码，只有下面这些位置按 MC 版本分叉。**从 `main` cherry-pick 逻辑改动到本分支时，先看这份清单**——
冲突只会出现在这里，其余文件应当逐字相同。

> ⚠️ **移植时最容易漏的一类错误：Mixin 里的字符串选择器**。
> `@Inject(method = "名字")` 与 `@Redirect(target = "Lnet/...;方法(描述符)")` 都是**字符串**，
> 目标版本里若没有这个名字（或它被改名/搬家），**编译期与 Mixin 注解处理器都不会报错**——
> 注解处理器只是查不到映射、于是 refmap 里少一条，运行到 APPLY 阶段才抛
> `InvalidInjectionException: could not find any targets matching '...'`，客户端直接启动崩溃。
> 本线移植时 `CabinCrosshairMixin` 就是这样崩的：1.21 的 `GameRenderer.findCrosshairTarget` 在 1.20.1 并不存在。
>
> **自查办法**（每次移植完、打包后各做一次，几秒钟）：
> ```powershell
> # 编译产物里每个 mixin 选择器都必须有对应的 method_xxxx 条目，缺条目就是名字不存在
> & jar xf build/libs/easyelevator-2.3.0+1.20.1.jar easyelevator-refmap.json client-easyelevator-refmap.json
> Get-Content client-easyelevator-refmap.json, easyelevator-refmap.json -Raw
> ```
> 更可靠的验证是真正把客户端跑起来：**通用 Mixin 只在服务端跑 `runServer` 就能覆盖，
> 而客户端 Mixin（`easyelevator.client.mixins.json`）必须启动客户端才会应用**——
> 服务端冒烟通过不代表客户端那份配置没问题。

| 位置 | 1.20.1 线 | 1.21.1 线（main） |
| --- | --- | --- |
| `gradle.properties` | MC 1.20.1 / yarn build.10 / loader 0.19.5 / FAPI 0.92.12 / `mod_version=2.3.0+1.20.1` / JDK 17 工具链路径 | MC 1.21.1 / 对应 yarn / loader 0.19.2 / FAPI 0.116.17 / `mod_version=2.3.0` |
| `build.gradle` | `targetJavaVersion = 17` | `targetJavaVersion = 21` |
| `fabric.mod.json` | `"java": ">=17"`，`"minecraft": "~1.20.1"` | `">=21"` 与对应版本 |
| 两个 `*.mixins.json` | `compatibilityLevel: JAVA_17` | `JAVA_21` |
| 数据包目录 | `data/easyelevator/recipes/`、`loot_tables/`、`data/minecraft/tags/blocks/`（**复数**，1.21 起改为单数） | 单数目录名 |
| 配方 `result` 字段 | `{"item": "easyelevator:cabin", "count": 1}` | `{"id": "...", "count": 1}`（1.20.5 起改用组件风格） |
| `network/Packets.java` 与 `client/ClientPackets.java` | 通道式收发：`Identifier + PacketByteBuf`，`PayloadCodec` 是本工程的接口 | payload 式收发：`CustomPayload` + `PayloadTypeRegistry` + `PacketCodec` |
| `network/Id.java`、`Payload.java`、`PayloadCodec.java` | 存在（1.20.1 侧的替代品，形状与 1.21 的对应类型一致） | 不存在（用原版的 `CustomPayload.Id` 等） |
| `LandingDoorBlockEntity` | `writeNbt(NbtCompound)` / `public readNbt(NbtCompound)` | 多一个 `RegistryWrapper.WrapperLookup` 参数 |
| 方块 | 无 `getCodec()`；`onBreak` 返回 `void`；`onUse` 多一个 `Hand` 参数；`getOutlineShape` / `getCollisionShape` / `scheduledTick` / `onStateReplaced` 是 **public** | 有 `MapCodec`；`onBreak` 返回 `BlockState`；这些方法为 `protected` |
| 实体 | `updateTrackedPositionAndAngles(..., int, boolean)` 多一个插值参数；`initDataTracker()` 无参 + `dataTracker.startTracking`；实体类型经 `FabricEntityTypeBuilder` 构建 | 签名不同（`DataTracker.Builder`、无参 `build()`） |
| 物品提示 | `appendTooltip(ItemStack, World, List, TooltipContext)` | `appendTooltip(ItemStack, TooltipContext, List, TooltipType)` |
| 客户端控件与界面 | `ClickableWidget.renderButton`；`Screen.renderBackground(DrawContext)`（无鼠标参数，且原版不再替你调用它）；`HandledScreen.render` 自己会调 `drawBackground` | `renderWidget`；四参 `renderBackground` |
| 客户端 Mixin 注入点 | `CabinCrosshairMixin` 注入 `GameRenderer.updateTargetedEntity(float)` 的 **TAIL**，改写公开字段 `MinecraftClient.crosshairTarget`（1.20.1 没有 `findCrosshairTarget`） | 注入 `GameRenderer.findCrosshairTarget(Entity,double,double,float)` 的 **RETURN**，改写其返回值 |
| 触及距离来源 | `MinecraftClient.interactionManager.getReachDistance()`（float） | `findCrosshairTarget` 的 `blockInteractionRange` 参数（读玩家属性） |
| 渲染顶点 | `VertexConsumer.vertex(Matrix4f, ...)` / `normal(Matrix3f, ...)`（取 `MatrixStack.Entry.getPositionMatrix()/getNormalMatrix()`） | 直接收 `MatrixStack.Entry` |
| 资源包管理器 | `getNames()` / `getEnabledNames()` | `getIds()` / `getEnabledIds()` |
| Java 版本差异 | 不能用 Java 21 的 `List.getFirst/getLast`、`Math.clamp` | 可以用 |
