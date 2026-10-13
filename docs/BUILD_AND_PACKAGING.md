# EasyElevator 工程构建与打包说明书

适用版本：模组 **2.3.1+26.3**，Minecraft **26.3**，Fabric Loader **0.19.5**，Java **25**。

> 面向**构建与发布**：环境固定项、命令速查、两种交付包、IDEA 设置与常见故障。
> 姊妹文档：[项目开发手册](PROJECT_MANUAL.md)（架构与接口）、[电梯参数手册](PARAMETERS.md)（数值）、
> [人工验收清单](TESTING.md)（进游戏验收）、[模型音效接口](ASSET_INTEGRATION.md)（替换素材）、[README](../README.md)（玩家向）。
>
> **版本线**：分支名一律 `mc/<MC 版本>/<加载器>`。本文所在的 **`mc/26.3/fabric`** 是最新版本线；
> 另有 **1.21.1 / Fabric** 线（`main`，仓库默认分支）与 **`mc/1.20.1/fabric`** 维护线。
> 分支划分、backport 纪律与标签/产物命名约定见 [版本线与发布流程](VERSIONING.md)。
>
> **⚠️ 本线的命名体系与另外两条线不同**：Minecraft 26.1 起官方发布的就是**未混淆**代码，Fabric 随之停止
> 维护 Yarn 映射。因此本线源码用 **Mojang 官方命名**书写，构建里**没有 mappings 依赖**，插件 ID 是
> `net.fabricmc.fabric-loom`（未混淆版本专用）。`main`（1.21.1）与 `mc/1.20.1/fabric` 仍是 Yarn 命名，
> **两者的源码不能互相 cherry-pick**（命名不同，冲突无法自动解决）。
>
> **路径约定**：本线需要在 `gradle.properties` 的 `org.gradle.java.installations.paths` 里指明**本机的
> JDK 25 目录**（工具链要用它编译与运行）。这是本工程唯一一处与机器相关的配置，换机器时改这一行即可。

## 1. 环境与已固定的构建配置

| 项目 | 配置                           | 修改位置 |
| --- |------------------------------| --- |
| Java 开发环境 | 完整 JDK 25（工具链）；Gradle 进程本身跑 JDK 21 或 25 都可以 | gradle.properties 的 org.gradle.java.installations.paths / JAVA_HOME |
| Java 编译版本 | toolchain=25，release=25，UTF-8 | build.gradle |
| Gradle | Wrapper 固定 9.6.0             | gradle/wrapper/gradle-wrapper.properties |
| Fabric Loom | 1.17.21（插件 ID 用未混淆版本专用的 `net.fabricmc.fabric-loom`） | build.gradle |
| Minecraft | 26.3                       | gradle.properties 的 minecraft_version |
| 混淆映射 | **不需要**（26.1 起游戏未混淆，源码直接用官方命名） | 无 mappings 依赖 |
| Fabric Loader | 0.19.5                       | loader_version |
| Fabric API | 0.162.0+26.3              | fabric_version |
| 模组版本 | 2.3.1+26.3                        | mod_version |
| Mixin 兼容级别 | `JAVA_25` | 两个 `*.mixins.json` |
| 文件名前缀 | easyelevator                 | archives_base_name |
| 构建内存/并行度 | 最大堆 2 GB，最多 4 个工作线程          | org.gradle.jvmargs / org.gradle.workers.max |
| 缓存 | 启用 Gradle 构建缓存               | org.gradle.caching |
| 下载超时 | Wrapper 120 秒                 | networkTimeout |
| 下载完整性 | 固定官方 Gradle SHA-256          | distributionSha256Sum |

不需要另行安装全局 Gradle。使用项目自带的 `gradlew.bat`；不要把 Loom 或 Gradle 改为随机的最新版本。本线只在 `org.gradle.java.installations.paths` 写了**本机 JDK 25 的路径**（工具链要用），换机器改这一行即可，其余配置都可迁移。归档文件关闭文件时间戳、固定文件顺序，减少同一源码重复打包时的无意义差异。

## 2. 本机推荐方式

在工程根目录打开 PowerShell，把 `JAVA_HOME` 指向本机的 JDK 21（或 25），然后调用 Wrapper：

```powershell
$env:JAVA_HOME = '<JDK21 或 JDK25>'
.\gradlew.bat build --console=plain
```

`JAVA_HOME` 必须是**完整 JDK**（含 `javac`）：只装 JRE、只有 `java.exe` 在 PATH 上、或 IDE 自带的精简运行时，都会在启动 Wrapper 阶段直接失败。它只用来跑 Gradle 进程；**编译与运行 Minecraft 26.3 用的是工具链指定的 JDK 25**（见 `gradle.properties` 的 `org.gradle.java.installations.paths`），两者可以不是同一个 JDK。工程不修改任何系统配置；关掉这个终端 `JAVA_HOME` 即失效。

第一次构建需要联网下载 Minecraft、Fabric 与 Gradle 依赖；后续会复用缓存（默认在用户目录的 `.gradle`，工程内只有 `.gradle/` 与 `build/` 两个缓存目录，都不进版本库）。

如果 PowerShell 执行策略拦截了 `gradlew.bat`，可只对本次进程放宽：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\gradlew.bat build
```

## 3. 通用命令

其他电脑将路径改为其 JDK 21 目录：

```powershell
$env:JAVA_HOME = 'C:\Java\jdk-21'
.\gradlew.bat --version
.\gradlew.bat build
```

`--version` 中检查 Gradle=9.6.0；Launcher JVM/Daemon JVM 跑 JDK 21 或 25 都可以（只负责启动 Gradle），**编译与运行 Minecraft 用的是工具链指定的 JDK 25**。`build` 会编译客户端与服务端、处理资源，再直接产出正式 JAR——本线用非 remap 的 Loom，没有 `remapJar` 这一步（见第 9.1 节）。

**关于测试**：java 插件自动创建的 `test` / `testClasses` 任务已在 `build.gradle` 里关闭（工程内没有 `src/test`），所以 `build` 只做编译与打包。工程内**没有常驻自动化测试**，验收一律进游戏按 [人工验收清单](TESTING.md) 逐项确认。

| 目标 | 对应 Gradle 任务 |
| --- | --- |
| 编译、生成 JAR | `.\gradlew.bat build` |
| 发布压缩包 | `.\gradlew.bat packageRelease` |
| 可编辑工程压缩包 | `.\gradlew.bat packageProject` |
| 开发游戏客户端 | `.\gradlew.bat runClient` |
| 离线数据生成（当前无 provider，不产出文件） | `.\gradlew.bat runDatagen` |
| 清理旧构建输出 | `.\gradlew.bat clean` |

2.3.0 给重载轿厢加上 **27 格货舱**：动态载客上限（空舱 20 人 → 满载 6 人）、随货量长出的最多 6 个可碰撞货箱，
以及一个原版容器菜单；重载轿厢的速度同时改为普通梯的 2/3。2.3.1 修掉三处 26.3 实测问题（面板文字全不显示、
物品渲染成紫黑方块、载人上行一顿一顿，见第 9.7 / 9.8 / 10.1 节）。正式 JAR 的 `fabric.mod.json` 版本应为
`2.3.1+26.3`；发布包与工程包也必须包含本版文档。

改完代码后的验收方式见 [人工验收清单](TESTING.md)：需要真人进游戏逐项确认，没有可替代的离线检查。

## 4. 给玩家的发布包

```powershell
$env:JAVA_HOME = '<JDK21>'
.\gradlew.bat packageRelease
```

输出 `build/distributions/easyelevator-2.3.1+26.3-release.zip`，内容：

- `mods/easyelevator-2.3.1+26.3.jar`：玩家安装的模组。
- `sources/easyelevator-2.3.1+26.3-sources.jar`：阅读用源码，不是安装文件。
- `docs/`、README.md、LICENSE.txt：搭建、参数、替换素材、验收说明。

也可以直接取 `build/libs/easyelevator-2.3.1+26.3.jar`。不要发布 `build/devlibs` 中未重映射的开发 JAR。

在 Minecraft **26.3 + Fabric Loader 0.19.5** 的实例中，将正式 JAR 和匹配 26.3 的 Fabric API（0.162.0+26.3）放入 `mods`。**2.2.0 改变了乘客移动协议（`rider_move`），服务端与所有客户端必须一起升级，不能混用 2.1.x 或更早版本的客户端。** **2.3.0 又新增了货舱菜单类型（`easyelevator:cargo`）与重载轿厢的同步字段，同样要求服务端与所有客户端一起升级**：旧客户端既看不到货箱、算不出动态限载，也无法同步货舱槽位。更新时删除 mods 中旧版 EasyElevator JAR，避免重复加载。

**本线（26.3）与 1.21.1 / 1.20.1 的 JAR 互不通用**：源码用 Mojang 官方命名（不是 Yarn），`fabric.mod.json` 的 `minecraft` 依赖也已收窄为 `~26.3`，装错实例时 Fabric Loader 会直接拒绝加载（而不是进游戏后才崩在 Mixin 里）。

## 5. 给开发者的完整工程包

```powershell
$env:JAVA_HOME = '<JDK21>'
.\gradlew.bat packageProject
```

输出 `build/distributions/easyelevator-2.3.1+26.3-project.zip`。顶层目录是 EasyElevator，包含源代码、模型/贴图/语言资源、文档、构建脚本与 Gradle Wrapper 及其 JAR。

打包采用白名单（`build.gradle` 的 `packageProject`），只收录 `src/**`、`docs/**`、Gradle Wrapper、构建脚本与顶层说明文件，不包含 `.idea`、`.gradle`、`.gradle-user-home`、`build`、日志或其他游戏存档。**不要手工压缩整个工程目录**，否则会带入体积很大的缓存。工程 ZIP 不含下载依赖；对方首次构建仍需联网。

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
| 默认 java 是 1.8，无法启动 Gradle | 先把 `JAVA_HOME` 指向完整 JDK 21，再调用 `.\gradlew.bat` |
| Loom 插件或依赖无法下载 | 检查访问 Fabric Maven、Maven Central、Mojang、Gradle 下载源的网络；恢复后原命令重试 |
| 新环境第一次构建较慢 | 等待依赖下载和映射处理完成，不要同时启动多个构建 |
| 内存不足 | 先关闭多余开发进程；必要时将 `-Xmx2G` 改为 `-Xmx3G` |
| 想排查详细失败原因 | `.\gradlew.bat build --stacktrace --console=plain` |
| 混入或客户端类加载错误 | 检查游戏/Loader/API版本及是否混装两个 EasyElevator 版本 |
| 修改贴图没生效 | 检查资源路径，重新打包或在开发客户端重载资源 |
| 出现旧版本的 JAR | build/libs 可保留旧文件，按当前 mod_version 选择；需要时先 clean 再 build |

## 8. 26.3 的渲染架构（extract → submit）迁移要点

26.3 把渲染器改成**两段式**，这是本次升级最大的一处结构性变化：

| 1.21.1 写法 | 26.3 写法 |
| --- | --- |
| `EntityRenderer<T>`，覆写 `render(T, yaw, delta, PoseStack, MultiBufferSource, light)` | `EntityRenderer<T, S extends EntityRenderState>`，实现 `createRenderState()` / `extractRenderState(T, S, float)` / `submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)` |
| `BlockEntityRenderer<T>`，覆写 `render(T, delta, PoseStack, MultiBufferSource, light, overlay)` | `BlockEntityRenderer<T, S extends BlockEntityRenderState>`，实现 `createRenderState()` / `extractRenderState(T, S, float, Vec3, CrumblingOverlay)` / `submit(...)` |
| `getTextureLocation(T entity)` | `getTextureLocation(S state)` |
| `shouldRenderOffScreen(T)` | `shouldRenderOffScreen()`（无参）+ `shouldRender(T, Vec3)` |
| `MultiBufferSource.getBuffer(layer)` 后直接写顶点 | `collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> …)`；文字走 `submitText` / `submitTextBackground` |
| `RenderLayer.getEntityCutout(id)` | `RenderTypes.entityCutout(Identifier)`（工厂搬到了 `net.minecraft.client.renderer.rendertype` 包） |

**手写几何的最小可用范式**（取自 26.3 的 `AbstractEndPortalRenderer`）：

```java
submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(TEXTURE), (pose, buffer) ->
        buffer.addVertex(pose, x, y, z).setColor(argb).setUv(u, v)
              .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz));
```

顶点 API 的形状（`addVertex/setColor/setUv/setOverlay/setLight/setNormal`）在 **Mojang 版的 1.21.1 就已经是这样**
（Yarn 才叫 `vertex/color/texture/…`），所以本工程 `BoxMesh` 的顶点写法**不用改**，改的只是"从哪拿 buffer"；
`addVertex(PoseStack.Pose, …)` 直接吃 `PoseStack.last()`，因此 `BoxMesh` 可以整体复用。

**连带的设计约束**：`submit` 只拿得到渲染状态（`S`），拿不到实体/方块实体本身。凡绘制需要的数据
（朝向、门进度、货物内容、视觉 Y 偏移……）都必须在 `extractRenderState` 里拷进状态类；
原本在 `render` 里现算的东西（例如 `CabinMotion.renderY`）应挪到 `extractRenderState` 里算好存进状态。

## 9. 1.21.1 → 26.3 差异清单（本线移植实录）

本线是从 1.21.1 线升上来的，下面每一条都是**实际踩过并修好**的（括号里是踩坑方式）。

### 9.1 命名与构建（最大的一处变化）

| 主题 | 1.21.1 线 | 26.3 线 |
| --- | --- | --- |
| 源码命名 | **Yarn** | **Mojang 官方命名**（26.1 起游戏未混淆，Fabric 停止维护 Yarn） |
| 映射依赖 | `mappings "net.fabricmc:yarn:…:v2"` | **不需要任何 mappings** |
| Loom 插件 ID | `fabric-loom` | **`net.fabricmc.fabric-loom`**（未混淆版本专用） |
| 重映射任务 | 有 `remapJar` / `remapSourcesJar` | **没有**，`packageRelease` 直接取 `jar` / `sourcesJar` |
| 模组依赖配置 | `modImplementation` / `modRuntimeOnly` | **没有**，用普通 `implementation` / `runtimeOnly` |
| Gradle / Loom | 8.14.3 / 1.11.8 | **9.6.0 / 1.17.21** |
| Java | 21 | **25**（工具链路径写在 `gradle.properties`） |
| Mixin 兼容级别 | `JAVA_21` | `JAVA_25` |

### 9.2 通用 API

| 1.21.1 | 26.3 |
| --- | --- |
| `ResourceLocation` | **`Identifier`**（Mojang 采用了 Yarn 的名字） |
| `Level.isClientSide`（字段） | `isClientSide()`（方法） |
| `getMinBuildHeight()` / `getMaxBuildHeight()` | `getMinY()` / `getMaxY()` |
| `ClickType` | `ContainerInput` |
| `InteractionResultHolder<T>` | **已删除**：`Item.use(...)` 直接返回 `InteractionResult` |
| `Entity.interact(Player, Hand)` | 多一个命中位置参数：`interact(Player, Hand, Vec3)` |
| `Entity.canBeCollidedWith()` | `canBeCollidedWith(Entity)` |
| `Entity.lerpTo(x,y,z,yaw,pitch,steps)` | `lerpPositionAndRotationStep(steps,x,y,z,yaw,pitch)`（参数顺序也变了） |
| `Entity.teleport(level,x,y,z,yaw,pitch,relSet)` | `teleport(PositionMoveRotation, Set<Relative>)` |
| `Entity.spawnAtLocation(Item\|ItemStack)` | `spawnAtLocation(ServerLevel, ItemLike\|ItemStack…)` |
| `Player.displayClientMessage(Component, boolean)` | `Player.sendSystemMessage(Component)`；带 actionbar 标志的**只在 `ServerPlayer`** 上 |
| `Container.addListener/removeListener` | **已删除**（`Container` 只剩 `startOpen/stopOpen(ContainerUser)`） |
| `ServerboundMovePlayerPacket` 各子类 | 多一个 `boolean horizontalCollision` |
| 伤害处理 | 新增抽象方法 `Entity.hurtServer(ServerLevel, DamageSource, float)` |

### 9.3 存档 / NBT（改动最密集的一块）

26.3 把实体与方块实体的存档读写换成 **`ValueInput` / `ValueOutput` + 全 Optional** 风格：

| 1.21.1 | 26.3 |
| --- | --- |
| `readAdditionalSaveData(CompoundTag)` | `readAdditionalSaveData(ValueInput)` |
| `addAdditionalSaveData(CompoundTag)` | `addAdditionalSaveData(ValueOutput)` |
| `saveAdditional(CompoundTag, HolderLookup.Provider)` | `saveAdditional(ValueOutput)`（注册表查询参数没了） |
| `loadAdditional(CompoundTag, HolderLookup.Provider)` | `loadAdditional(ValueInput)` |
| `nbt.getInt(k)`（缺失得 0） | `nbt.getIntOr(k, 0)`；`nbt.getInt(k)` 现在返回 `Optional<Integer>` |
| `nbt.getString/getBoolean/getLong/getFloat/getDouble` | 同上，均有 `xxxOr(k, 默认值)` 版本 |
| `nbt.contains(k, type)` | `nbt.contains(k)`（只剩单参）；判断"由谁来决定缺失语义"改用 `getXxx(k).isPresent()` / `child(k)` |
| `nbt.getCompound(k)` | `nbt.child(k)`（Optional）/ `nbt.childOrEmpty(k)` |
| `nbt.getList(k, type)` + `ListTag.getCompound(i)` | `nbt.childrenList(k)`（Optional）/ `childrenListOrEmpty(k)` + **`ValueInputList.stream()`**（列表只暴露 `stream()/isEmpty()`） |
| 写列表：`new ListTag()` + `nbt.put(k, list)` | `nbt.childrenList(k).addChild()`（返回子 `ValueOutput`） |
| 读写物品栏：手写循环 | `ContainerHelper.saveAllItems(ValueOutput, NonNullList)` / `loadAllItems(ValueInput, NonNullList)` |

### 9.4 方块与物品注册（编译期查不出来，只会在运行时炸）

| 现象 | 原因与修法 |
| --- | --- |
| 类加载期 `NullPointerException: Block id not set` | 26.3 起 `BlockBehaviour.Properties` 必须 `.setId(ResourceKey.create(Registries.BLOCK, id))` |
| 类加载期 `NullPointerException: Item id not set` | `Item.Properties` 同理必须 `.setId(ResourceKey.create(Registries.ITEM, id))` |
| `simpleCodec(...)` / `codec()` 找不到 | **方块 codec 已被原版整体移除**：`CODEC` 字段与 `codec()` 覆写要整段删掉 |
| `onRemove(BlockState,Level,BlockPos,BlockState,boolean)` 找不到 | 改名为 `affectNeighborsAfterRemoval(BlockState, ServerLevel, BlockPos, boolean)`；它只在"方块类型真的变了"时调用，形参已是 `ServerLevel`，旧版的 `!next.is(this)` 与 `isClientSide()` 判断都不再需要 |

### 9.5 Fabric API 变化

| 1.21.1 | 26.3 |
| --- | --- |
| `net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup` | **模块被移除**，替代是 `fabric-creative-tab-api-v1`：`net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab` |
| `PayloadTypeRegistry.playS2C()` / `playC2S()` | `clientboundPlay()` / `serverboundPlay()` |
| `EntityRendererRegistry.register(...)` / `BlockEntityRendererRegistry.register(...)`（`fabric-rendering-v1`） | **已标记 `@Deprecated`**（0.162.0 起），改用原版 `EntityRenderers.register(...)` / `BlockEntityRenderers.register(...)` |

> **渲染器注册为什么要改回原版**：26.3 里原版这两个 `register` 是 `private static`，但
> `fabric-transitive-access-wideners-v1` 用 `transitive-accessible` 把它们放开了，因此模组可以直接调用。
> 两种写法写的是**同一份可变注册表**（`EntityRenderers.PROVIDERS`，一个 `Object2ObjectOpenHashMap`），
> 差别只在于 Fabric 的旧接口会先把条目缓存在自己的 map 里、等原版初始化时再灌进去。调用时机不变：
> 都必须在渲染调度器创建之前，即在客户端模组初始化（`onInitializeClient`）里。

### 9.6 数据包：战利品表格式

26.3 把条目上的**条件从"列表"改成了"单数 + 组合条件"**，`data/<ns>/loot_table/`（单数目录）里的文件都要跟着改：

| 1.21.1 | 26.3 |
| --- | --- |
| `"conditions": [ {...}, {...} ]` | `"condition": { ... }`（**只有一个条件**）；多个条件要写成 `{"type":"minecraft:all_of","terms":[...]}` |
| `{"condition":"minecraft:block_state_property","block":…,"properties":{…}}` | `{"type":"minecraft:match_block","blocks":…,"state":{…}}` |
| `{"condition":"minecraft:survives_explosion"}` | `{"type":"minecraft:survives_explosion"}`（`condition` 键本身也改名为 `type`） |

> **踩坑方式**：这一处编译期查不出来，也不会让服务端起不来——错误的战利品表只在**解析时**打一条
> `Couldn't parse loot table …` 警告，然后该方块掉落物为空（破坏方块什么都不掉）。验收时随手挖一块
> 轨道/呼叫按钮确认有掉落即可。用**不在注册表里的 id** 去离线试解析会得到误导性的报错，判断"表本身对不对"
> 要看真实服务端日志，或把表里的命名空间换成原版方块后再解析。

### 9.7 资源包：物品模型要多一份"物品模型定义"

1.21.4 起（26.3 同样）物品模型是**两段式**的，`models/item/<id>.json` 不再被自动发现：

| 1.21.1 | 26.3 |
| --- | --- |
| 只需 `assets/<ns>/models/item/<id>.json`，原版按物品 id 隐式去取 | **还要** `assets/<ns>/items/<id>.json` 显式把物品指向模型（"物品模型定义"）：`{"model":{"type":"minecraft:model","model":"<ns>:item/<id>"}}` |

> **踩坑方式**：漏了 `items/` 这一份，编译、打包、进游戏都不报错，**物品直接变成紫黑格子**（原版 missing model）——
> 物品栏、手上、创造模式标签页图标全是紫黑方块（本模组 26.3 线就踩过这一处）。方块（世界里的模型）不受影响，
> 因为 `blockstates/` 那条路没变，所以"方块好好的、物品全紫黑"正是这个病的特征。
> 对照物：解包 26.3 客户端 jar 后看 `assets/minecraft/items/*.json`（`minecraft:item/stick` 那份就是最小例子）。
> 本模组每件物品（四个轿厢 + 呼叫按钮 + 轨道）都要有自己的一份。

### 9.8 服务端移动校验开始把"实体碰撞"算进去（本线最隐蔽的一处）

26.3 的 `ServerGamePacketListenerImpl.isEntityCollidingWithAnythingNew` 改用
`getPreMoveCollisions(...)`，而这个方法把 **`getEntityCollisions`（实体碰撞）与方块碰撞拼在一起**；
**1.21.1 的同名检查只吃方块碰撞**。轿厢的空心外壳（地板、侧壁、门扇、重载舱的货箱）正是本模组通过
`mixin/EntityViewMixin` 注入的**实体**碰撞，于是"乘客相对轿厢向下修正一点点"在 26.3 会被判成
**撞进了新的碰撞**：

```
客户端：乘客脚底贴面 → 那一次实体碰撞查询漏掉支撑面（AABB 的严格不等号）→ 本刻掉一格重力 0.0784
   ↓  把"自己算出的绝对 Y"原样发出去
服务端：向下修正穿透了轿厢地板/箱顶 → isEntityCollidingWithAnythingNew = true → 走"静默传送"分支
   ↓  这条路径不打 moved wrongly / moved too quickly，日志里一个字都没有
模组：传送挂起 → ServerPlayNetworkHandlerMixin.easyelevator$canCarry() 判假
   → AbstractCabinEntity.tickPassengers() 让本刻 canMove 返回 false → 状态机转 BLOCKED
   ↓
表现为：电梯上行"走一下停一下"，而且只有载人时出现、只有 26.3 出现、日志里查不到任何告警
```

**修法**：把乘客夹在**脚下那块支撑面**（地板面，重载舱还可能是货箱顶面）以上，即在
`ClientPlayerEntity#sendPosition` 的开头（`Minecraft.tick()` 的顺序是"实体物理 → `player.sendChanges()`"，
因此这里正好是**物理之后、`getY()` 被读进移动包之前**）调一次
`AbstractCabinEntity.liftRiderToSupport`。支撑面直接从 `collisionBoxes()` 取，因此子类追加的几何
（`PowerfulCabinEntity.collisionBoxesStatic()` 里的货箱）自动参与。这样报给服务端的 Y 与它自己算出的位置
一致、位移为零，既不触发"新实体碰撞"也不产生待确认传送；顺带把"偶尔陷进地板/箱顶"这个 1.21.1 就有的
抖动也修掉了。只抬不压，跳跃、站在更高一层箱顶、下落都不受影响。

> **排查手法（值得复用）**：这一处编译期、静态阅读、日志告警都指不出来。最后的定位靠的是**两侧同时打点**：
> 服务端在 `RiderMove.apply` 打印 `y / seenY / cabinY / serverY / adjustedY / downBy`，
> 客户端在 `CabinMotion.beginPlayerTick` 打印 `riding / fresh / inCabin / snapOk / age` 与
> tick 末尾的 `rel = 玩家Y − 轿厢Y`。一对照就看到"`riding=true` 但 `rel` 从 0.200000 掉到 0.121551"，
> 即"随厢平移执行了、但玩家自己的物理没被支撑面接住"。定案后所有 `TEMP-DIAG` 代码已全部移除。

## 10. 26.3 的 GUI 也是 extract 架构（客户端迁移的第二处结构性变化）

26.3 不止改了世界渲染器：**GUI 控件也从"直接画"改成了"先抽取再提交"**。本线迁移时踩到的原始报错：

```
错误: 找不到符号  符号: 类 GuiGraphics
        protected void renderWidget(GuiGraphics ctx,int mouseX,int mouseY,float delta) { … }
错误: ElevatorScreen.FlatButton 不是抽象的, 并且未覆盖 AbstractButton 中的抽象方法
        extractContents(GuiGraphicsExtractor,int,int,float)
```

结论与做法：

| 1.21.1 | 26.3 |
| --- | --- |
| `GuiGraphics`（控件与屏幕都用它直接画） | **`GuiGraphicsExtractor`**（抽取阶段），真正的绘制在后面的提交阶段 |
| 覆写 `renderWidget(GuiGraphics, int, int, float)` | 实现 `AbstractButton.extractContents(GuiGraphicsExtractor, int, int, float)`（抽象方法，不实现就无法编译） |
| `Screen.renderBackground(DrawContext)` / `AbstractContainerScreen.renderBg(...)` | 同样按"抽取 → 提交"改写，签名以编译报错为准 |

**迁移建议**：不要试图把旧调用逐个改名，**直接读 26.3 里同类原版实现照抄结构**——解包
`minecraft-clientOnly-<hash>-26.3-sources.jar` 后看 `net/minecraft/client/gui/screens/`、
`net/minecraft/client/gui/components/`（例如 `AbstractButton`、`Button`、`AbstractContainerScreen`
及其子类），把"控件怎么抽取内容"这一段骨架抄过来，再把本模组原来的绘制内容填进去。
这与第 8 节的渲染器改写是同一个思路：**先把数据抽进状态/抽取器，再统一提交**。

### 10.1 文字颜色从"可省 alpha"变成了"必须带 alpha"

这是 extract 架构里最容易漏、而且**编译期完全看不出来**的一处：文字会静默消失，填充色块却照常绘制，
于是现象是"面板和按钮都在，一个字都没有"。

| | 1.21.1 | 26.3 |
| --- | --- | --- |
| 文字颜色 | `GuiGraphics.drawString/drawCenteredString` 收 `0xRRGGBB` 也行——`Font.adjustColor` 会把 alpha 为 0 的颜色补成 `0xFF000000 \| rgb` | `GuiGraphicsExtractor.text(...)` 第一句就是 `if (ARGB.alpha(color) != 0)`：**alpha 为 0 的颜色整段丢弃**，连一个字都不提交；`adjustColor` 已随旧文字管线一起删除 |
| 半透明 RBG 整数 | `0xC9DAEA` 被当成不透明的浅蓝 | 被当成 `alpha=0xC9` 的**半透明**浅蓝（变淡，不是消失） |
| 只填充的 `fill(...)` | 不补 alpha | **也不补**（没有 alpha 闸），行为不变 |

结论：**凡是画文字的颜色一律写 8 位 `0xAARRGGBB`**（要透明自己给 alpha）。本模组当时的症状是面板标题、
状态行、页数行与楼层键上的数字全部不见——因为它们写的是 6 位 `0xFFFFFF` / `0xD5E2EE` / `0x77828F` 之类。
判定方法很直接：`0xRRGGBB` 的 alpha 字节恒为 0 → 消失；`0xNNRRGGBB`（NN≠0）只是变淡。

## 11. Mixin 注入点：编译期完全查不出来的那一类

混入的 `method = "…"` 是**字符串**（`@Shadow` 的字段名同理），编译期只当它是一段文本：目标方法被改名、搬家或
改了参数表，`gradlew build` 依然会成功。而两个 `*.mixins.json` 都是 `required: true` + `defaultRequire: 1`，
注入落空的**唯一**表现是运行时抛 `InvalidInjectionException` → 客户端/服务端启动直接崩。

因此**升级 MC 版本后必须逐个核对注入点**。本线 1.21.1 → 26.3 实际踩到的一处：

| 1.21.1 | 26.3 |
| --- | --- |
| `GameRenderer.pick(Entity, double, double, float)`（准星射线，返回 `HitResult`） | `GameRenderer` 里**已经没有** `pick`；准星射线搬到本机玩家身上：`LocalPlayer.raycastHitResult(float, Entity)`，原静态实现变成私有的 `LocalPlayer.pick(Entity, double, double, float)` |

> **怎么选新的注入点**：`raycastHitResult` 是 26.3 里"本机玩家准星射线"的**唯一入口**
> （只有 `Minecraft.pick(float)` 调它，结果随后写进 `Minecraft.hitResult` 与 `crosshairPickEntity`）；
> 而私有静态 `pick` 在"手里拿着带 `ATTACK_RANGE` 组件的物品"时**可能根本不被调用**。
> 移植时先挂外层入口，别挂内部辅助方法——否则会少覆盖一条分支，而且下次重构还会再断一次。

**核对方法**（本工程不依赖反编译源码，用 `javap` 查真实 26.3 jar 即可）：

```powershell
# .mc263 / .mc263client 是把 26.3 的服务端 / 客户端 jar 解开的目录
javap -p -classpath ".mc263client;.mc263" net.minecraft.client.player.LocalPlayer | Select-String raycastHitResult
```

> 客户端类只在 `.mc263client` 里、公共类只在 `.mc263` 里，**两个目录都要放进 `-classpath`**
> （Windows 用 `;` 分隔），否则会报 `找不到符号`。
> 需要看方法体时加 `-c`（本线就是靠 `javap -c` 读出 `Minecraft.pick` 到底把结果写进哪个字段的）。

日志与人工验收见 [TESTING.md](TESTING.md)。
