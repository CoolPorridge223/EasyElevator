# EasyElevator 项目开发手册

> 适用模组版本 **2.2.0** ｜ Minecraft **1.21.1** ｜ Fabric Loader **0.19.2** ｜ Fabric API **0.116.17+1.21.1** ｜ Java **21**
>
> 本文是**接手者/贡献者**的架构与接口手册：讲清「代码怎么分层、谁调用谁、每个关键函数做什么、参数在哪里改、对外接口是什么」。
> 运行参数的逐项数值、构建打包、游戏内验收步骤与模型音效替换步骤分别见文末的姊妹文档，本文不再重复抄录。

---

## 目录

1. 项目总览
2. 整体设计框架
3. 文件引用关系（包依赖 / 逐文件职责 / 资源引用）
4. 函数调用关系（主链路 / tick 时序 / 状态机分支 / 联锁 / 面板 / 渲染）
5. 关键类与关键函数参考
6. 接口介绍（Environment / 事件 API / 网络协议 / 数据接口）
7. 参数介绍（核心常量、派生公式、联动修改清单）
8. 存档格式与版本兼容
9. 构建与运行
10. 扩展开发指南（常见改动任务速查）
11. 常见故障定位
12. 附录：注册 ID / 翻译键 / 常量速查

---

## 1. 项目总览

### 1.1 项目定位

EasyElevator 是一个**沿垂直轨道运行的电梯**模组。一件物品放置一扇 3×3 的楼层门，一件物品在轨道上生成一台 3×3×3 的空心轿厢；轿厢在轨道列（称为**线路 line**）上按 **S 形速度曲线**（启动缓慢加速 → 中段匀速 → 到站前平滑减速）升降，停靠由「轿厢内选站 + 厅外带方向的呼叫」共同调度，楼层门与轿厢门由同一份连续进度驱动、逐刻同步滑动。

| 维度 | 事实                                                                      |
| --- |-------------------------------------------------------------------------|
| 模组 ID / 命名空间 | `easyelevator`                                                          |
| 版本 | 2.2.0（见 [gradle.properties](../gradle.properties)）                      |
| 环境 | `*`（客户端与服务端都要安装；服务端权威）                                                  |
| 组件 | 轨道 ×1、楼层电梯门 ×1、轿厢 ×3 型号（普通 / 高速 / 观光）                                   |
| 运动方式 | 直上直下；速度、加速度与加加速度都受限的 S 形曲线（形状由巡航速度与 `CRUISE_RAMP_TICKS` 解出）；不支持转弯、斜轨、分岔 |
| 线路约束 | 同一 X/Z、垂直连续、朝向一致；每条线路最多一台轿厢（三型号合计）                                      |
| 状态保存 | 轿厢实体 NBT + 楼层门方块实体 NBT                                                  |

### 1.2 技术栈与版本矩阵

来源：[build.gradle](../build.gradle)、[gradle.properties](../gradle.properties)、[fabric.mod.json](../src/main/resources/fabric.mod.json)。

| 项 | 值 | 位置 |
| --- | --- | --- |
| Gradle | 8.14.3（wrapper） | `build.gradle` `tasks.named('wrapper')` |
| Fabric Loom | 1.11.8 | `build.gradle` plugins |
| Java 目标 | 21（`options.release` + toolchain） | `build.gradle` |
| Minecraft | 1.21.1 | `gradle.properties` |
| Yarn 映射 | 1.21.1+build.3 | `gradle.properties` |
| Loader | 0.19.2 | `gradle.properties` |
| Fabric API | 0.116.17+1.21.1 | `gradle.properties` |
| 源集拆分 | `splitEnvironmentSourceSets()`（main + client） | `build.gradle` `loom` |
| 数据生成 | `configureDataGeneration { client = true }` | `build.gradle` |
| 源集 | `main`（服务端/公共）+ `client`（客户端），由 `loom.splitEnvironmentSourceSets()` 拆分 | `build.gradle` |

### 1.3 目录结构

```
EasyElevator/
├─ build.gradle                 构建脚本（Loom、源集、打包任务）
├─ gradle.properties            版本与坐标
├─ settings.gradle              Fabric Maven 仓库
├─ gradlew / gradlew.bat        wrapper 入口
├─ README.md                    玩家向说明 + 快速上手
├─ docs/                        文档（见 1.5）
│   ├─ PROJECT_MANUAL.md        ← 本文（开发手册）
│   ├─ PARAMETERS.md            参数手册（数值口径）
│   ├─ TESTING.md               人工验收清单（需要真人进游戏的检查项）
│   ├─ ASSET_INTEGRATION.md     模型/音效/门动画接口
│   └─ BUILD_AND_PACKAGING.md   构建与打包说明书
├─ tools/
│   ├─ build.ps1                本机 Gradle 快捷构建（校验 JDK 21）
│   ├─ generate_art.py          模型与贴图生成 + 几何自检（纯 Python 3）
│   └─ generate_data.py         方块状态/语言/配方/掉落表/音效钩子生成（纯 Python 3）
├─ src/
│   ├─ main/java/org/DJB/easyelevator/     服务端与公共逻辑（权威侧）
│   ├─ main/resources/                     方块状态、模型、贴图、语言、配方、战利品表、音效、mixin 配置
│   ├─ client/java/org/DJB/easyelevator/   客户端源集（渲染、界面、运动、音效）
│   ├─ client/resources/                   客户端 mixin 配置
│   └─ gametest/                           可选的乘客回归测试源集（加 -PriderTests 才构建，永不进发行包）
└─ run/                        开发运行目录（世界、日志）
```

### 1.4 游戏内组件与注册 ID

集中注册在 [Easyelevator.onInitialize()](../src/main/java/org/DJB/easyelevator/Easyelevator.java#L160)。

| 组件 | 注册 ID | 类型 | 备注 |
| --- | --- | --- | --- |
| 电梯轨道 | `easyelevator:elevator_rail` | Block + BlockItem | 朝向 = 轿厢所在方向 = 轿厢门朝向 |
| 楼层电梯门 | `easyelevator:call_button` | Block + BlockItem | **沿用旧 ID 兼容旧存档**；显示名为「电梯门」，实际为 3×3 整门 |
| 楼层门方块实体 | `easyelevator:landing_door` | BlockEntityType | 只有根方块创建 |
| 普通轿厢 | `easyelevator:cabin` | EntityType + Item | 4 格/秒，白色封闭 |
| 高速轿厢 | `easyelevator:high_speed_cabin` | EntityType + Item | 10 格/秒，外观与普通逐面相同 |
| 观光轿厢 | `easyelevator:observation_cabin` | EntityType + Item | 4 格/秒，玻璃墙 |
| 物品栏分组 | `easyelevator:main` | ItemGroup | 图标 = 普通轿厢 |
| 音效 | `easyelevator:elevator_running` / `elevator_arrival` / `elevator_arrival_custom` | SoundEvent | `elevator_arrival` 是每扇门"默认音效"那一项的来源；`elevator_arrival_custom` 只是供上传音频复用的字幕占位。**开关门不再发声**（`door_open`/`door_close` 已移除） |
| 到站音效设置 | 存在门的方块实体 NBT（每扇独立） | DoorArrivalSound | 默认"开 + 默认音效"（等于原有行为）；选项表见 `logic/DoorSounds`。旧存档的门因字段缺失**按出厂默认读回发声**，因此升级后照旧会响（见 8.2） |
| 自定义音频 | `config/easyelevator/arrival_sounds/arrival_<槽>.ogg` | 磁盘文件 | 投影成 `resourcepacks/easyelevator_custom/`；槽位由门坐标推导（最多 64 槽） |

### 1.5 本文与其它文档的分工

| 你想知道 | 看这里 |
| --- | --- |
| 架构、调用关系、类职责、对外接口、扩展入口 | **本文** |
| 某个常量的确切数值、单位、联动关系 | [PARAMETERS.md](PARAMETERS.md) |
| 怎么在游戏里验收 | [TESTING.md](TESTING.md) |
| 换模型/贴图/音效、门动画接口 | [ASSET_INTEGRATION.md](ASSET_INTEGRATION.md) |
| 怎么构建、打包发布版/工程包、IDEA 设置 | [BUILD_AND_PACKAGING.md](BUILD_AND_PACKAGING.md) |
| 玩家怎么用、搭建设置步骤 | [README.md](../README.md) |

---

## 2. 整体设计框架

### 2.1 分层架构

代码按「依赖方向自下而上、Minecraft 依赖逐层变多」分层：

```
        ┌──────────────────────────── client（仅物理客户端）────────────────────────────┐
        │ EasyelevatorClient  CabinRenderer  LandingDoorRenderer  ElevatorScreen        │
        │ LandingDoorScreen   CabinMotion     CabinRunningSound   BoxMesh  StatusArrow   │
        │ mixin/client/ClientPlayerEntityMixin · CabinCrosshairMixin                      │
        └───────────────▲──────────────────────────────────────────────┬────────────────┘
                        │ 只读：DataTracker + MotionFrame 包             │ 只发请求包
        ┌───────────────┴──────────────────────────────────────────────▼────────────────┐
        │ network/ElevatorNetworking     （14 个 payload：7 S2C + 7 C2S + 右键事件注册 + 服务端校验） │
        └───────────────▲──────────────────────────────────────────────┬────────────────┘
                        │                                               │
        ┌───────────────┴──────────────────────────┐   ┌────────────────▼────────────────┐
        │ entity/AbstractCabinEntity（世界适配层）  │   │ block/LandingDoorBlock(+BE/几何) │
        │ + CabinEntity / HighSpeed / Observation  │   │ ElevatorRailBlock               │
        │ item/CabinItem                           │   └────────────────┬────────────────┘
        └───────────────▲──────────────────────────┘                    │
                        │ 注入 Environment 回调、调用 tick()             │
        ┌───────────────┴──────────────────────────────────────────────▼────────────────┐
        │ logic/   ElevatorController（纯 Java 状态机，服务端权威）                       │
        │          ElevatorLine   ElevatorParameters   ElevatorStatus                    │
        │          FloorIndicator PanelLayout        MotionProfile                   │
        └───────────────────────────────────────────────────────────────────────────────┘
                        ▲                                   ▲
        ┌───────────────┴──────────────┐     ┌──────────────┴───────────────┐
        │ api/ElevatorEvents（扩展）    │     │ mixin/EntityViewMixin（碰撞）  │
        └──────────────────────────────┘     └──────────────────────────────┘
```

**分层职责一句话版：**

| 层 | 包/目录 | 职责 | 是否引用 Minecraft |
| --- | --- | --- | --- |
| 注册层 | `org.DJB.easyelevator` | 只做注册与全局单例，零业务逻辑 | 是（注册表） |
| 纯逻辑层 | `logic/` | 状态机、调度、编号、布局、S 形运动曲线与门几何 | **ElevatorParameters / ElevatorController / ElevatorStatus / FloorIndicator / MotionProfile / PanelLayout / FramedLeaf / LeafUv / SlidingDoor / CabinLighting / RiderMotionHistory 完全不引用**（仅 ElevatorLine 需要读世界） |
| 世界适配层 | `entity/`、`block/`、`item/` | 世界查询、方块状态、实体位移、存档、联锁 | 是 |
| 网络层 | `network/` | `ElevatorNetworking` 共 14 个 payload（7 S2C + 7 C2S）做状态同步与反向请求校验；另有 `RiderMove` 的 1 个 C2S（乘客移动包），**全模组合计 15 个 payload** | 是 |
| 扩展 API | `api/` | 对外事件钩子 | 是（Fabric Event） |
| Mixin | `mixin/`、`mixin/client/` | 空心碰撞注入、移动基准平移与地板支撑（`ServerPlayNetworkHandlerMixin`）、玩家 tick HEAD 推进轿厢/乘客、**轿厢内准星裁决**（面板优先 / 穿出轿厢） | 是 |
| 客户端 | `client/` | 渲染、界面、音效、运动样本插值与乘客承托 | 是 |

设计红线：**任何游戏规则（到站、开门、调度、编号）都不得写进客户端**。客户端只消费服务端同步的 `DataTracker` 字段与自定义包。

### 2.2 服务端权威 + 客户端只读

- 只有服务端 tick 会调用 `ElevatorController.tick()`（`AbstractCabinEntity.tick()` 在 `getWorld().isClient` 时提前返回）。
- 客户端能改服务端状态的**唯一**途径是 C2S 请求包：`SelectStop`、`DoorCommand`、`HallCallButton`，以及门设置面板的 `DoorSoundCommand`、`SetBaseFloor`、`DoorSoundUpload`、`RequestDoorSound`；它们都在服务端处理器里重新校验玩家身份、实体归属、方块与站点合法性。**另有 `network/RiderMove`（`easyelevator:rider_move`）**：乘客的移动包本身，携带轿厢样本编号，服务端用 `logic/RiderMotionHistory` 把 Y 换算回当前帧后再交回原版处理器（因此它同样要过原版全部校验）。
- 服务端向客户端同步两组数据：
  1. **实体 DataTracker**（原版机制，随区块追踪自动广播）：`PHASE`、`DOOR`、`FACING`、`TARGET_Y`、`FLOOR`；
  2. **自定义包**：`MotionFrame`（绝对 double 位置）、`OpenPanel`/`PanelState`（选站面板）、`OpenHallPanel`/`HallPanelState`（厅外面板）、`OpenDoorPanel`（单扇门设置面板）、`DoorSoundData`（自定义音频内容的分发与落地）。
- **`MotionFrame.riderOffset` 是"发送但被忽略"的兼容字段**：它的字段没有变，但**自 2.1.2 起客户端已不再使用**（客户端改为自行判定乘客：`containsPassenger`）。它现在不再驱动镜头，读到时按废弃字段对待即可。
- **例外：门音效的"字节"要落在客户端磁盘上**。设置本身完全服务端权威（存在门的方块实体里），但 Minecraft 只从资源包读音频，
  因此自定义 `.ogg` 必须由客户端写成运行时资源包才能播放。分工是：服务端持有权威副本并负责分发（`DoorSoundData`），
  客户端只负责把它投影成资源包并重载（`DoorSoundPack`）。这是表现层落盘，不构成"客户端决定游戏规则"。

### 2.3 三条数据链路

**① 输入链路（玩家操作 → 状态机请求）**

```
右键轿厢（站内）──► UseItem/UseBlockCallback ──► ElevatorNetworking.open ──► OpenPanel ──► ElevatorScreen
        └─ 点数字键 ──► SelectStop(C2S) ──► 服务端校验 ──► cabin.requestStop ──► controller.request
右键楼层门 ──────► LandingDoorBlock.onUse ──► openHallPanel ──► OpenHallPanel ──► LandingDoorScreen
        └─ 点 ▲/▼ ──► HallCallButton(C2S) ──► 服务端校验 ──► cabin.requestHallCall ──► controller.callHall
面板开门/关门 ───► DoorCommand(C2S) ──► cabin.doorCommand ──► controller.forceOpen/forceClose
```

**② 运行链路（服务端每刻）**

```
AbstractCabinEntity.tick()
   ├─ tickPassengers()                       乘客名册维护 / 读档等待归位
   ├─ controller.tick(getY(), Environment)   纯状态机推进，返回本刻 Y
   │      └─ Environment.valid/canMove/doorwayBlocked/arrived  ← 世界查询回调
   ├─ setPosition + 同步搬运乘客
   ├─ 写 DataTracker（PHASE/DOOR/TARGET_Y/FLOOR）
   ├─ syncPanel / syncHallStates / syncMotion
   └─ LandingDoorBlock.refresh(每个站点)      门联锁与方块状态
```

**③ 表现链路（客户端每帧/每刻）**

```
MotionFrame ──► CabinMotion.receive（只记目标高度，绝不外推）
ClientPlayerEntityMixin(tick HEAD) ──► CabinMotion.beginPlayerTick（玩家物理之前：提交轿厢 → 托举乘客）
CabinRenderer.render ──► CabinMotion.renderY（track.previousY → track.physicalY 两刻间插值）──► 视觉 Y
ClientPlayerEntityMixin(sendMovement) ──► CabinMotion.sendMovement ──► RiderMove（携带轿厢样本编号）
DataTracker(FLOOR/PHASE/TARGET_Y) ──► ElevatorStatus.of / FloorIndicator.format ──► 门框与面板文字
LandingDoorBlockEntity.openProgress(t) ──► 楼层门门扇几何
```

### 2.4 六个关键设计决策

| # | 决策 | 为什么这么做（收益） | 代价 / 约束 |
| --- | --- | --- | --- |
| 1 | 状态机 `logic/ElevatorController` 是**纯 Java**，世界查询通过 `Environment` 接口注入 | 可脱离游戏单测；行为完全确定；服务端与客户端共用同一份调度代码 | 每刻多四次接口回调；状态机不认识真实站点，到站校验必须由调用方完成 |
| 2 | 运动用**绝对 double** 自定包同步，绕过原版相对位置包 | 原版 1/4096 格定点量化会让低速运行出现台阶与漂移 | 客户端需要按样本缓冲（`CabinMotion` 的 `Track`）且**绝不外推** |
| 3 | 门用**连续进度**而非离散方块状态；楼层门进度取自轿厢 | 楼层门与轿厢门逐刻同值、同插值，动画自然；门框常驻、门扇收拢 | 需要方块实体承载进度；渲染层约束严格（见 5.14） |
| 4 | 轿厢`isCollidable()=false`，空心外壳由 **EntityViewMixin** 注入 | 3×3×3 必须可走进；实心包围盒会把乘客挡在外面 | Mixin 是必装项，注入失败模组启动失败 |
| 5 | **乘客名册**（UUID + 相对偏移）随实体 NBT 存档，读档先等人 | 区块实体先于玩家实体载入；不等人会出现「开走 → 玩家掉出井道」 | 需要 `RIDER_WAIT_TICKS` 上限与「车上有别人就走」的放行规则 |
| 6 | 三型号轿厢共用父类，只差速度 / 回收物品 / `glassWalls()` | 只有一份运动、乘客、门联锁、存档代码；换型号不改井道 | 型号不写进存档，由实体类型唯一决定 |

### 2.5 关键不变量（改代码前必读）

1. **线路唯一性**：一段垂直连续、朝向一致的轨道列最多一台轿厢（三型号合计）。`line.cabins(world).size() == 1` 才允许移动。
2. **门联锁**：`door > 0`（未完全关闭）不得移动。楼层门只有在「唯一轿厢精确到站且门进度 > 0」时才交出碰撞。
3. **到站精度两条口径**：服务端联锁与请求判定用 `POSITION_EPSILON = 1e-7`；客户端门扇进度与显示判定用 `SYNC_POSITION_EPSILON = 0.01`。二者不可混用。
4. **不主动加载区块**：任一被覆盖区块未加载即判为不可通行/站点无效，状态机转入 `BLOCKED` 等待，而不是加载区块。
5. **不预测**：`CabinMotion` 只认服务端样本、绝不外推，包停止就停在最后一个样本（`Track` 只在两次已提交位置之间插值）。
6. **方块状态属性一旦发布不可改名**：`FACING`、`COLUMN`、`LEVEL`、`OPEN`；注册 ID 同理（`call_button` 的复用是刻意的兼容性 hack）。
7. **只有根方块**（`COLUMN=1, LEVEL=0`）是站点与控制器；其余 8 格只是部件。
8. **单一数据源**：楼层号只在服务端算好写入 `FLOOR`；运行状态由 `Phase + TARGET_Y + Y` 推导；门进度只有一份（轿厢的门进度）。


---

## 3. 文件引用关系

### 3.1 包依赖图

箭头 A ──► B 表示「A 引用/调用 B」。同一包内部引用已合并为自环标注。

```
                          ┌──────────────────────────┐
                          │ org.DJB.easyelevator      │
                          │ Easyelevator（注册）       │
                          └───┬───────┬───────┬───────┘
              ┌───────────────┘       │       └────────────────┐
              ▼                       ▼                        ▼
      ┌──────────────┐        ┌──────────────┐         ┌──────────────┐
      │ block/       │        │ entity/      │         │ item/        │
      │ LandingDoor  │◄──────►│ AbstractCabin│◄────────│ CabinItem    │
      │ Rail / BE /  │        │ (+3 子类)     │         └──────────────┘
      │ Geometry     │        └───┬──────┬───┘
      └───┬──────┬───┘            │      │
          │      │                │      └──────────────► network/
          │      └───────────────►│                        ElevatorNetworking
          │                       │                        （payload 定义 + 服务端校验）
          └───────────┬───────────┘                              ▲
                      ▼                                          │
              ┌──────────────────────────┐                ┌──────┴───────┐
              │ logic/                   │                │ api/         │
              │ ElevatorController ◄─────┼────────────────│ ElevatorEvents│
              │ ElevatorLine  Elevator-  │                └──────────────┘
              │ Parameters  Status       │                       ▲
              │ FloorIndicator  Motion-  │                       │
              │ Timeline  PanelLayout    │                ┌──────┴───────┐
              └─────────────▲────────────┘                │ mixin/       │
                            │                             │ EntityView   │
                            │                             └──────────────┘
        ┌───────────────────┴───────────────────────────────────────────┐
        │ client/  EasyelevatorClient · CabinRenderer · LandingDoorRenderer │
        │ ElevatorScreen · LandingDoorScreen · CabinMotion · BoxMesh        │
        │ StatusArrow · CabinRunningSound · ClientPlayerEntityMixin        │
        │ mixin/client/CabinCrosshairMixin（准星裁决）                       │
        └───────────────────────────────────────────────────────────────────┘
```

**关键事实：**

- `logic/` 中除 `ElevatorLine` 外**不依赖 Minecraft**，因此 `logic` 是整棵树的最底层。
- `entity/AbstractCabinEntity` 与 `block/LandingDoorBlock` 互相引用（实体查门、门查实体），并在 `network` 处交汇（实体发面板，网络校验门）。
- `network/ElevatorNetworking` 同时引用 `entity` 与 `block`，是少数「知道全貌」的类。
- `client/` 只通过 `network` 的 payload 类型和 `entity` 的只读访问器与服务端耦合，**不引用任何 `logic` 状态机的写方法**（读取 `ElevatorStatus`、`FloorIndicator`、`PanelLayout`、`ElevatorParameters` 这些纯函数/常量是允许的）。

### 3.2 逐文件职责与依赖

**main 源集（`src/main/java/org/DJB/easyelevator/`）**

| 文件 | 职责 | 直接依赖（项目内） |
| --- | --- | --- |
| [Easyelevator.java](../src/main/java/org/DJB/easyelevator/Easyelevator.java) | 全部注册：方块、物品、实体、方块实体、音效、物品栏、网络 | block, entity, item, network |
| [logic/ElevatorParameters.java](../src/main/java/org/DJB/easyelevator/logic/ElevatorParameters.java) | 编译期常量（巡航速度、加/减速过渡时间、加速度硬上限、门时序、队列上限、几何内收、插值阈值） | 无 |
| [logic/MotionProfile.java](../src/main/java/org/DJB/easyelevator/logic/MotionProfile.java) | 纯 Java S 形速度曲线：由巡航速度与过渡时间解出加速度/jerk 上限，按峰值速度规划曲线，按时间求值给出每刻位移 | ElevatorParameters |
| [logic/ElevatorController.java](../src/main/java/org/DJB/easyelevator/logic/ElevatorController.java) | 纯 Java 状态机：相位、门进度、目标、队列、厅外呼叫、集选调度（运动形状委托 MotionProfile） | ElevatorParameters, MotionProfile |
| [logic/ElevatorLine.java](../src/main/java/org/DJB/easyelevator/logic/ElevatorLine.java) | 轨道列扫描、站点收集、线路中心、线路轿厢查询 | Easyelevator, LandingDoorBlock, ElevatorRailBlock, AbstractCabinEntity |
| [logic/ElevatorStatus.java](../src/main/java/org/DJB/easyelevator/logic/ElevatorStatus.java) | 显示状态 UP/DOWN/IDLE 推导 | ElevatorController, ElevatorParameters |
| [logic/FloorIndicator.java](../src/main/java/org/DJB/easyelevator/logic/FloorIndicator.java) | 楼层编号（基准层 / 地下层）与文本格式化 | ElevatorParameters |
| [logic/PanelLayout.java](../src/main/java/org/DJB/easyelevator/logic/PanelLayout.java) | 选站面板网格与分页的纯算术 | 无 |
| [logic/CabinLighting.java](../src/main/java/org/DJB/easyelevator/logic/CabinLighting.java) | 舱内补光的纯函数：按面中心到灯位的距离抬方块光分量（只抬不降），灯罩朝下面用 15 级 | 无（纯算术） |
| [logic/DoorSounds.java](../src/main/java/org/DJB/easyelevator/logic/DoorSounds.java) | 每扇门到站音的选项目录，以及"序号 → 音效事件 / 音频文件名"的换算；门槽由门坐标推导 | 无（项目内）；引用 MC 的 `SoundEvent`/`Identifier` |
| [logic/DoorArrivalSound.java](../src/main/java/org/DJB/easyelevator/logic/DoorArrivalSound.java) | record `(enabled, choice)`：单扇门的到站音设置，序号越界在构造器与 `readNbt` 里消毒 | DoorSounds；引用 MC 的 `NbtCompound` |
| [logic/DoorSoundPersistence.java](../src/main/java/org/DJB/easyelevator/logic/DoorSoundPersistence.java) | 上传音频的权威副本存储：`store/read/fileName/listStems`，写入先落 `.tmp` 再原子替换 | 无（只依赖 Fabric 的 `FabricLoader`） |
| [logic/FramedLeaf.java](../src/main/java/org/DJB/easyelevator/logic/FramedLeaf.java) | 铁框玻璃门扇的纯几何：四边框 + 中间玻璃，边框随门扇变窄按比例缩 | 无 |
| [logic/LeafUv.java](../src/main/java/org/DJB/easyelevator/logic/LeafUv.java) | 滑门"随门滑动"的 UV：可见区间、断面取段与映射进图集格（纯算术）。**两套区间函数**：`leafRange` 给楼层门叶、`cabinPanelRange` 给轿厢门扇（轿厢门扇没有朝向镜像，混用会让一侧左右翻转） | 无 |
| [logic/RiderMotionHistory.java](../src/main/java/org/DJB/easyelevator/logic/RiderMotionHistory.java) | 服务端权威的轿厢绝对高度样本历史：`record/height/rebase`，为 `RiderMove` 提供换算基准 | 无 |
| [logic/SlidingDoor.java](../src/main/java/org/DJB/easyelevator/logic/SlidingDoor.java) | 轿厢两扇对开滑门的纯算术布局：外缘固定、先导端随进度外移、门区 Z 与净开度 | 无 |
| [entity/AbstractCabinEntity.java](../src/main/java/org/DJB/easyelevator/entity/AbstractCabinEntity.java) | 三型号共同父类：位移（按状态机给出的位移应用）、乘客、碰撞盒、障碍检测、存档、同步、门命令 | Easyelevator, api.ElevatorEvents, block.LandingDoorBlock, logic.*, network |
| [entity/CabinEntity.java](../src/main/java/org/DJB/easyelevator/entity/CabinEntity.java) | 普通型号：巡航速度 = SPEED，回收 CABIN_ITEM | AbstractCabinEntity, ElevatorParameters |
| [entity/HighSpeedCabinEntity.java](../src/main/java/org/DJB/easyelevator/entity/HighSpeedCabinEntity.java) | 高速型号：巡航速度 = HIGH_SPEED（同样 1.6 秒过渡 ⇒ 加/减速段 8.0 格、比普通型更长） | AbstractCabinEntity, ElevatorParameters |
| [entity/ObservationCabinEntity.java](../src/main/java/org/DJB/easyelevator/entity/ObservationCabinEntity.java) | 观光型号：巡航速度 = SPEED，`glassWalls()=true` | AbstractCabinEntity, ElevatorParameters |
| [block/LandingDoorBlock.java](../src/main/java/org/DJB/easyelevator/block/LandingDoorBlock.java) | 3×3 门方块：放置、自检、联锁、厅外面板、基准层、拆门 | Easyelevator, entity, logic, network |
| [block/LandingDoorBlockEntity.java](../src/main/java/org/DJB/easyelevator/block/LandingDoorBlockEntity.java) | 根方块实体：门扇进度采样、基准层标记、门框显示缓存 | Easyelevator, entity, logic |
| [block/LandingDoorGeometry.java](../src/main/java/org/DJB/easyelevator/block/LandingDoorGeometry.java) | 门框/门扇纯几何 + 体素形状缓存 | 无（纯算术） |
| [block/ElevatorRailBlock.java](../src/main/java/org/DJB/easyelevator/block/ElevatorRailBlock.java) | 轨道方块：朝向继承、轮廓形状 | 无 |
| [item/CabinItem.java](../src/main/java/org/DJB/easyelevator/item/CabinItem.java) | 轿厢生成物品：线路校验、生成、扣物品、提示 | Easyelevator, entity, logic |
| [network/ElevatorNetworking.java](../src/main/java/org/DJB/easyelevator/network/ElevatorNetworking.java) | 14 个 payload（7 S2C + 7 C2S）、编解码、收发、服务端处理器、右键打开面板；另有 `RiderMove` 的 1 个 C2S，全模组合计 15 个 payload | Easyelevator, block, entity, logic |
| [network/RiderMove.java](../src/main/java/org/DJB/easyelevator/network/RiderMove.java) | C2S 乘客移动包（`easyelevator:rider_move`）：把原版移动包换成"携带轿厢 id + 样本编号"的包，服务端换算 Y 后还原成原版包投递；自带 `register()` | Easyelevator, entity, logic(RiderMotionHistory) |
| [network/PlatformMovement.java](../src/main/java/org/DJB/easyelevator/network/PlatformMovement.java) | "随厢移动"适配接口：把原版移动基准（`lastTickY`/`updatedY`）平移而不是发传送包 | 无（实现方是 `mixin/ServerPlayNetworkHandlerMixin`） |
| [api/ElevatorEvents.java](../src/main/java/org/DJB/easyelevator/api/ElevatorEvents.java) | 对外事件：`PHASE_CHANGED`、`ARRIVED` | entity, logic |
| [mixin/EntityViewMixin.java](../src/main/java/org/DJB/easyelevator/mixin/EntityViewMixin.java) | 把空心外壳追加进 `EntityView#getEntityCollisions` | entity |
| [mixin/ServerPlayNetworkHandlerMixin.java](../src/main/java/org/DJB/easyelevator/mixin/ServerPlayNetworkHandlerMixin.java) | 实现 `PlatformMovement`（平移原版移动基准），并注入 `ServerPlayNetworkHandler#onPlayerMove` 的 RETURN：把轿厢地板也算作支撑，清悬浮计数与坠落距离 | network(PlatformMovement), entity |

**client 源集（`src/client/java/org/DJB/easyelevator/`）**（`logic/*.java` 属于 main 源集，见上表）

| 文件 | 职责 | 直接依赖（项目内） |
| --- | --- | --- |
| [client/EasyelevatorClient.java](../src/client/java/org/DJB/easyelevator/client/EasyelevatorClient.java) | 客户端入口：注册渲染器、S2C 处理器、刻回调、断线清理 | Easyelevator, entity, logic, network |
| [client/CabinRenderer.java](../src/client/java/org/DJB/easyelevator/client/CabinRenderer.java) | 轿厢外壳 + 内饰数据表 + 观光分格玻璃 + 材质图集分格、轿内面板文字 | Easyelevator, entity, logic, CabinMotion, BoxMesh, CabinLighting, GlassLayers, StatusArrow |
| [client/LandingDoorRenderer.java](../src/client/java/org/DJB/easyelevator/client/LandingDoorRenderer.java) | 楼层门门扇 + 门框顶部层号/箭头 | Easyelevator, block, logic, BoxMesh, StatusArrow |
| [client/ElevatorScreen.java](../src/client/java/org/DJB/easyelevator/client/ElevatorScreen.java) | 轿厢内选站面板（网格/翻页/开关门） | entity, logic, network |
| [client/LandingDoorScreen.java](../src/client/java/org/DJB/easyelevator/client/LandingDoorScreen.java) | 厅外呼叫面板（▲/▼/×） | network |
| [client/DoorSoundScreen.java](../src/client/java/org/DJB/easyelevator/client/DoorSoundScreen.java) | 单扇门的专属设置面板（潜行右键打开：开关门音效开关/选择/上传/试听 + 设为基准层） | logic(DoorSounds, DoorSoundPersistence), network |
| [client/DoorSoundPack.java](../src/client/java/org/DJB/easyelevator/client/DoorSoundPack.java) | 把权威副本投影成运行时资源包 `resourcepacks/easyelevator_custom/`，扫描/启用/触发资源重载 | logic(DoorSounds, DoorSoundPersistence) |
| [client/CabinMotion.java](../src/client/java/org/DJB/easyelevator/client/CabinMotion.java) | 客户端"平台坐标系"的唯一持有者：收样本（只记目标高度）、在玩家 tick 开头提交轿厢并托举乘客、把移动包换成 `RiderMove`、给渲染提供两刻间插值 | entity, logic(ElevatorParameters), network(RiderMove) |
| [client/CabinRunningSound.java](../src/client/java/org/DJB/easyelevator/client/CabinRunningSound.java) | 跟随轿厢的循环运行音效 | Easyelevator, entity, logic |
| [client/StatusArrow.java](../src/client/java/org/DJB/easyelevator/client/StatusArrow.java) | 闪烁上下箭头字符（两处显示同源） | logic.ElevatorStatus |
| [client/GlassLayers.java](../src/client/java/org/DJB/easyelevator/client/GlassLayers.java) | 玻璃专用渲染层（轿厢与楼层门共用原版无色玻璃贴图；cutout 透明像素丢弃、背面剔除） | 无 |
| [client/FramedGlassDoor.java](../src/client/java/org/DJB/easyelevator/client/FramedGlassDoor.java) | 画铁框加中间玻璃的门扇：铁框在不透明层、玻璃在玻璃层（布局取自 logic/FramedLeaf） | 无 |
| [client/BoxMesh.java](../src/client/java/org/DJB/easyelevator/client/BoxMesh.java) | 共享顶点绘制工具（长方体 / 零厚单面 / 图集 UV 分格 / **逐面** UV 分格） | 无 |
| [client/EasyelevatorDataGenerator.java](../src/client/java/org/DJB/easyelevator/client/EasyelevatorDataGenerator.java) | 数据生成入口（当前为空 pack） | 无 |
| [mixin/client/ClientPlayerEntityMixin.java](../src/client/java/org/DJB/easyelevator/mixin/client/ClientPlayerEntityMixin.java) | 注入 `ClientPlayerEntity#tick` 的 HEAD：在玩家物理之前推进轿厢与乘客；并接管移动包发送改走 `RiderMove` | CabinMotion, network(RiderMove) |
| [mixin/client/CabinCrosshairMixin.java](../src/client/java/org/DJB/easyelevator/mixin/client/CabinCrosshairMixin.java) | **轿厢内准星的唯一裁决者**：乘客在厢内 → 一律把准星改写为"命中本厢"，于是右键必定开面板（**与手里拿什么无关**）；站在外面的玩家不受影响。只改实体命中，不碰碰撞几何与方块射线。无跨帧状态 | `AbstractCabinEntity.containsPassenger` |

**测试源集（可选开启）**

工程内**没有** `src/test`：java 插件自动创建的 `test` / `testClasses` 任务在 `build.gradle` 里被 `enabled = false` 关掉，
因此 `./gradlew.bat build` 只编译与打包，不含任何测试步骤。

另有一套**可选**的乘客回归测试源集 `src/gametest/`，只在加 `-PriderTests` 时才注册与构建：

| 文件 | 内容 |
| --- | --- |
| `java/org/DJB/easyelevator/RiderMovementTests.java` | **12 个** `@GameTest`：乘客移动与历史补偿（延时/速度组合、过期样本）这类可自动化的回归 |
| `java/org/DJB/easyelevator/RiderClientSmoke.java` | 客户端冒烟测试（`loom.runs.riderClient`：vmArg `-Deasyelevator.riderSmoke=true`、`--quickPlaySingleplayer RiderSmoke`） |
| `resources/fabric.mod.json` | 测试模组声明：`id = easyelevator-test`（`fabric-gametest` + `client` 入口点）；**永不进发行包** |

`build.gradle` 侧：`fabricApi { if (project.hasProperty('riderTests')) { configureTests { createSourceSet = true; modId = 'easyelevator-test'; enableGameTests = true; eula = true } } }`，另有 `loom.runs.riderClient`。

运行方式：

- `.\tools\build.ps1 -Jdk <JDK21> -Task runGameTest`（或 `-Task runRiderClient`）——脚本会自动补上 `-PriderTests`；
- 或直接 `.\gradlew.bat -PriderTests runGameTest`（同族任务：`runGameTest`、`runClientGameTest`、`runRiderClient`）。

日常验收仍以 [TESTING.md](TESTING.md) 的人工清单为准；GameTest 只覆盖乘客移动 / 历史补偿这类可自动化的回归。

### 3.3 可脱离 Minecraft 的「叶子」类

以「是否 `import net.minecraft`」为准，`logic/` 下共 **12 个**类不引用任何 Minecraft 类型，可以单独 `javac` 编译：

`CabinLighting` · `DoorSoundPersistence` · `ElevatorController` · `ElevatorParameters` · `ElevatorStatus` · `FloorIndicator` · `FramedLeaf` · `LeafUv` · `MotionProfile` · `PanelLayout` · `RiderMotionHistory` · `SlidingDoor`

其中 **`DoorSoundPersistence` 只依赖 Fabric（`FabricLoader` 取游戏目录）而不依赖 Minecraft**，因此服务端与客户端都能安全使用（详见 5.10）。

反过来，`logic/` 里引用 Minecraft 的只有三个：`DoorSounds`（`SoundEvent`/`Identifier`）、`DoorArrivalSound`（`NbtCompound`）、`ElevatorLine`（`World`/`BlockPos`/`Box`/`Direction`）。
`ElevatorLine` 虽是 record，但 `scan/matches/cabins` 需要 `World`、`BlockPos`，因此**不属于**叶子类。
`LandingDoorGeometry` 只用 `Box`/`VoxelShape`/`Direction`，属于「需要 MC 类路径但不需要世界」的中间类。

### 3.4 资源文件引用关系

| 资源 | 内容 | 引用者 |
| --- | --- | --- |
| [fabric.mod.json](../src/main/resources/fabric.mod.json) | 入口点：`main`=`Easyelevator`、`client`=`EasyelevatorClient`、`fabric-datagen`=`EasyelevatorDataGenerator`；两个 mixin 配置 | Loader |
| [easyelevator.mixins.json](../src/main/resources/easyelevator.mixins.json) | `mixin/EntityViewMixin`、`mixin/ServerPlayNetworkHandlerMixin`（均为必装 required；后者平移原版移动基准 `lastTickY`/`updatedY` 并补上"轿厢地板也算支撑"的判定，是 2.2.0 承托乘客的关键） | Loader |
| [easyelevator.client.mixins.json](../src/client/resources/easyelevator.client.mixins.json) | `mixin.client.ClientPlayerEntityMixin`、`mixin.client.CabinCrosshairMixin`（client，required） | Loader |
| [assets/easyelevator/blockstates/elevator_rail.json](../src/main/resources/assets/easyelevator/blockstates/elevator_rail.json) | 4 朝向 × y 旋转 | 轨道方块模型 |
| [assets/easyelevator/blockstates/call_button.json](../src/main/resources/assets/easyelevator/blockstates/call_button.json) | 楼层门 9 个方块的 `facing × column × level × open` 变体 → `landing_door_frame_*.json`（底行取 `*_bottom` 三件） | 楼层门门框（常驻几何） |
| [assets/easyelevator/models/block/landing_door_frame_*.json](../src/main/resources/assets/easyelevator/models/block/) | 9 个门框模型：底行立柱底座+门槛、中行立柱/门洞、顶行立柱+门楣（门楣中间是凹进去的显示屏 `blank_screen`） | 方块模型系统 |
| [assets/easyelevator/models/block/elevator_rail.json](../src/main/resources/assets/easyelevator/models/block/elevator_rail.json) | 轨道模型：底座法兰 + 四颗螺栓 + 双导轨 + 中间齿条 + 抱箍（X/Z 限制在 3..13，与 `ElevatorRailBlock.getOutlineShape` 一致） | 方块模型系统 |
| [assets/easyelevator/models/item/*.json](../src/main/resources/assets/easyelevator/models/item/) | 5 个物品图标：轨道/门复用方块模型，三个轿厢共用 `cabin_body.json`（1/3 比例迷你轿厢） | 物品模型系统 |
| [assets/easyelevator/textures/block/blank*.png](../src/main/resources/assets/easyelevator/textures/block/) | 方块贴图：`blank`（门框/轨道亮钢）、`blank_door`（门扇深色阳极氧化）、`blank_plate`（机加工深色板）、`blank_screen`（门楣显示屏）、`blank_speed`（高速图标金板）、`blank_glass`（观光图标玻璃板） | 模型与渲染器 |
| [assets/easyelevator/textures/entity/cabin.png](../src/main/resources/assets/easyelevator/textures/entity/cabin.png) | 轿厢 4×4 材质图集（格号 = `CabinRenderer.Mat` 的枚举顺序；玻璃已独立使用原版贴图，旧玻璃格保留） | `CabinRenderer.TEXTURE`、`MATERIAL_UV` |
| [assets/easyelevator/sounds.json](../src/main/resources/assets/easyelevator/sounds.json) | **3 个**音效 ID：`elevator_running`（`sounds: []`，静音占位）、`elevator_arrival`（`sounds: ["easyelevator:man"]`，**有音频**，是每扇门"默认音效"的来源）、`elevator_arrival_custom`（`sounds: []`，供上传音频复用的字幕占位） | `Easyelevator.sound()` |
| [assets/easyelevator/lang/*.json](../src/main/resources/assets/easyelevator/lang/) | 翻译键（en_us / zh_cn） | 所有界面与消息 |
| [data/easyelevator/recipe/*.json](../src/main/resources/data/easyelevator/recipe/) | 5 个配方 | 原版合成 |
| [data/easyelevator/loot_table/blocks/*.json](../src/main/resources/data/easyelevator/loot_table/blocks/) | 轨道与门掉落 | 原版掉落 |
| [data/minecraft/tags/block/mineable/pickaxe.json](../src/main/resources/data/minecraft/tags/block/mineable/pickaxe.json) | 镐可挖 | 原版工具标签 |

**注意**：楼层门的**门扇**不是方块模型，而是由 `LandingDoorRenderer`（方块实体渲染器）绘制；方块模型只负责常驻**门框**。这是理解「换门模型」时最容易踩的坑，详见 [ASSET_INTEGRATION.md](ASSET_INTEGRATION.md)。

---

## 4. 函数调用关系

### 4.1 主链路总览（放置 → 呼叫 → 到站 → 开门）

```
【放置楼层门】玩家右键地面
  ItemPlacementContext
    └─► LandingDoorBlock.getPlacementState
          ├─ ElevatorLine.matches(world, root.opposite(facing)*3, facing)   4 朝向试放
          └─ part(root, facing, col, row) ×9  校验高度/边界/区块/可替换/权限
    └─► LandingDoorBlock.onPlaced
          ├─ world.setBlockState ×8（补齐其余部件）
          ├─ LandingDoorBlock.refresh(world, root)
          │     └─ mayOpen ──► dockedCabin ──► complete / railPos / ElevatorLine.matches
          └─ world.scheduleBlockTick(root, this, 1)
                └─（此后每刻）scheduledTick ──► refresh ──► scheduleBlockTick  ← 自维持轮询

【放置轿厢】手持轿厢物品右键轨道
  CabinItem.useOnBlock
    ├─ ElevatorLine.scan(world, railPos)
    │     ├─ ElevatorLine.matches ×N（向上下扩展）
    │     └─ LandingDoorBlock.isRoot / complete（收集站点）
    ├─ AbstractCabinEntity.initialize(rail, facing)   → setPosition(中心)
    ├─ AbstractCabinEntity.spaceClear(boundingBox)    → 井道 3×3 是否空
    └─ world.spawnEntity(cabin)

【厅外呼叫】右键门 → 面板 → 按 ▲/▼
  LandingDoorBlock.onUse
    └─ openHallPanel
          ├─ complete / ElevatorLine.scan / line.cabins
          ├─ stops.indexOf(origin) → 端站方向过滤（showUp/showDown）
          └─ ServerPlayNetworking.send(OpenHallPanel)
  客户端 LandingDoorScreen 点按钮
    └─ ClientPlayNetworking.send(HallCallButton)
          └─（服务端）registerGlobalReceiver(HallCallButton)
                ├─ isRoot / complete / ElevatorLine.scan / line.cabins
                ├─ cabin.requestHallCall(origin, up)
                │     └─ controller.callHall(HallCall, y)
                └─ syncHallState(...) ──► HallPanelState ──► 同一站点附近客户端按钮变红

【轿厢内选站】站内右键轿厢 → 面板 → 点数字
  UseItemCallback / UseBlockCallback
    └─ containsPassenger(player) → ElevatorNetworking.open(player, cabin)
          └─ ServerPlayNetworking.send(OpenPanel：entityId/stops/planned/baseFloorY)
  客户端 ElevatorScreen 点站点
    └─ ClientPlayNetworking.send(SelectStop)
          └─（服务端）校验 → cabin.requestStop(button)
                └─ controller.request(Stop, y) → insertOrdered
          └─ open(player, cabin)  重新下发最新快照

【运行到站 → 开门】服务端每刻（见 4.2）
  controller.tick
    └─ y == target.y() → env.arrived(stop) + serveStation + phase=OPENING
  OPENING 结束 → phase=OPEN，dwell=DWELL_TICKS
  AbstractCabinEntity.tick 末尾
    └─ LandingDoorBlock.refresh(每个站点)
          └─ mayOpen → OPEN=true（交出碰撞）
    └─ LandingDoorBlockEntity.sample（每刻）→ progress = leafProgress = 在站轿厢门进度
          └─ LandingDoorRenderer 逐帧 openProgress(tickDelta) 画门扇
```

### 4.2 `AbstractCabinEntity.tick()` 内调用顺序（服务端）

顺序**不可调换**，每一步都依赖前一步的结果：

```
tick()
 ├─1  previousDoor = dataTracker.get(DOOR)          渲染插值起点
 ├─2  super.tick()
 ├─3  if isClient → return                          客户端到此为止
 ├─4  before = controller.phase()                   用于末尾判断音效/事件
 ├─5  currentLine = line()                          ElevatorLine.scan
 ├─6  unique = 线路有效 && 朝向一致 && cabins==1      运行许可
 ├─7  waitingForPassengers = tickPassengers()       乘客名册：记录/开门离厢注销/闭门恢复/决定能否移动
 ├─8  nextY = controller.tick(getY(), Environment{...})
 │        valid / canMove / doorwayBlocked / canResume / arrived
 ├─9  dy = nextY - getY()
 ├─10 if dy != 0：收集 riders → setPosition → 逐乘客 carryPassenger(rider, dy)（setPosition + PlatformMovement 平移原版移动基准；2.2.0 起不再发传送包）
 ├─11 dataTracker.set(PHASE / DOOR / TARGET_Y)
 ├─12 plan = plannedStops()；变化则 ElevatorNetworking.syncPanel
 ├─13 syncHallStates()                              变化才发 HallPanelState
 ├─14 updateFloorNumber(currentLine)                写 FLOOR
 ├─15 for stop in currentLine.stops(): LandingDoorBlock.refresh      同刻刷新门联锁
 ├─16 dy!=0 → motionSettleTicks = MOTION_SETTLE_TICKS
 ├─17 dy!=0 || settle>0 → ElevatorNetworking.syncMotion；静止样本递减
 └─18 phase 变化 → ElevatorEvents.PHASE_CHANGED（开关门不再发声；到站音效在 arrived 回调里，见 4.7）
```

### 4.3 `ElevatorController.tick(y, env)` 内部分支与调用

```
tick(y, env)
 ├─ queue.removeIf(s -> !env.valid(s))             剔除失效选站
 ├─ hallCalls.removeIf(c -> !env.valid(...))       剔除失效呼叫
 ├─ target 失效 → target=null; phase=BLOCKED       绝不半空开门
 └─ switch (phase)
     ├─ OPEN
     │    ├─ dwell--
     │    └─ dwell==0                                   停留到点就关门（无请求也一样）
     │         ├─ target = select(y)
     │         └─ target.y == y → serveStation(y); dwell=DWELL_TICKS
     │            否则（含 target==null）phase = CLOSING   ← 关到全闭后停在 MOVING、无目的站
     ├─ CLOSING
     │    ├─ env.doorwayBlocked() → phase=OPENING（防夹，target 保留）
     │    ├─ door -= 1/DOOR_TICKS
     │    └─ door<1e-4 → door=0; phase=MOVING
     ├─ MOVING / BLOCKED（共用运行逻辑）
     │    ├─ door>0 → break（故障脱困时门开着：不派发行程，等门关上再说）
     │    ├─ target==null → select(y)
     │    ├─ retarget(y)                            顺路改道
     │    ├─ 曲线作废或目的站变过 → profile.plan(y, velocity, acceleration, target.y())  规划 S 形曲线
     │    ├─ next = y + profile.advance(1, y, profileTick)                  按时间取样位移
     │    ├─ velocity/acceleration ← 曲线同刻取样（供下次改道接着算）
     │    ├─ !env.canMove(y,next) → phase=BLOCKED; stopMotion()（曲线作废，速度清零）
     │    ├─ y = next; profileTick++
     │    ├─ 曲线走完 → 残余 ≤ POSITION_EPSILON 时吸附到站点高度
     │    └─ y == target.y → y=target.y; env.arrived; serveStation; phase=OPENING; stopMotion()
     └─ OPENING
          ├─ door += 1/DOOR_TICKS
          └─ door>0.9999 → door=1; phase=OPEN; dwell=DWELL_TICKS
               └─ target!=null → queue.addFirst(target)（防夹中断的请求放回队首）

故障脱困与恢复（tick 末尾，紧跟 switch）
 ├─ !env.canResume() → faulted=true（只要"现在走不了"就是故障，刻意不看相位）
 │    · 覆盖：途中被挡 / 目的站被拆 / 停着但线路被拆光 / 读档等乘客还没归位
 ├─ faulted && env.canResume() → 故障解除
 │    ├─ 故障期间开过门（recoveryOpen）→ phase=CLOSING（先把门关回去，再继续行程）
 │    └─ 没开过门 → recoveryOpen=false，行程立刻继续
 └─ faulted && !canResume
      ├─ phase=MOVING && target==null → phase=BLOCKED（客户端据此显示"暂停"并点亮开门键）
      └─ 其余：什么都不做（门开着就让它开着，乘客靠它脱困）

select(y)  ← 派车核心
 ├─ !hasRequests() → null（保留 travel 记忆）
 ├─ travel==NONE → travel = initialTravel(y)
 ├─ ① nearest(y, travel, aheadOnly=false, sameFloorOnly=true) → take()   本层就地开门
 ├─ ② nearest(y, travel, aheadOnly=true,  sameFloorOnly=false) → take()  顺路可服务（选站 + 同向厅外）
 ├─ ③ oldestAheadHallCall(y, travel, includeHere=false) → take()         前方只剩反方向呼叫也先去
 └─ ④ travel = opposite(travel)
      ├─ oldestAheadHallCall(y, travel, includeHere=true) → take()（含本层）
      ├─ nearest(y, null, false, false) → take()（按距离兜底）
      └─ 都没有 → travel 复原; null
      └─ 命中后：travel = directionTowards(y, any.stop().y())   服务方向取实际行驶方向

retarget(y)  ← 运行途中改道
 ├─ travel = directionTowards(y, target.y())
 ├─ ahead = nearest(y, travel, aheadOnly=true, false)
 ├─ ahead==null || ahead.y==target.y → 不动
 ├─ 严格更近才改道（加 POSITION_EPSILON 容差）
 ├─ insertOrdered(target)     原目标放回队列，不丢站
 └─ target = take(ahead)

serveStation(stationY)  ← 到站清扫
 ├─ served = targetHallDirection != NONE ? targetHallDirection : travel
 ├─ served==NONE → hallCalls.removeIf(c.y==stationY)          旧存档兜底全清
 │   否则        → hallCalls.removeIf(c.y==stationY && c.up==(served==UP))  只清本趟方向
 ├─ queue.removeIf(s.y==stationY)                            清同层重复选站
 └─ targetHallDirection = NONE
```

**辅助函数关系：**

| 函数 | 被谁调用 | 作用 |
| --- | --- | --- |
| `insertOrdered` | `request`、`retarget` | 按服务方向插入队列（方向 NONE 时保持插入顺序） |
| `nearest` | `select`、`retarget` | 候选集里取最近（先选站 FIFO 再厅外呼叫登记顺序，严格更近才替换） |
| `oldestAheadHallCall` | `select` | 方向前方**按登记先后**的第一条呼叫（不只是同向） |
| `directionTowards` | `retarget`、`select`、`initialTravel` | 由「当前位置 → 目标」求实际行驶方向 |
| `along` | `nearest`、`oldestAheadHallCall` | 楼层是否位于某方向的前方（含同层，1e-7 容差） |
| `initialTravel` | `select` | 空闲时由最早的请求决定起始方向 |
| `take` | `select`、`retarget` | 消费一次选中：选站出队、记录 `targetHallDirection` |
| `opposite` | `select` | 掉头 |

### 4.4 门联锁刷新链

```
（服务端每刻，来自 AbstractCabinEntity.tick 第 15 步）
LandingDoorBlock.refresh(world, origin)
 ├─ isRoot(state)                          非根直接返回
 ├─ open = mayOpen(world, origin)
 │    └─ dockedCabin(world, origin, POSITION_EPSILON)
 │         ├─ complete(world, origin)       9 格齐全且属性自洽
 │         ├─ ElevatorLine.matches(world, railPos, facing)
 │         ├─ getEntitiesByClass(AbstractCabinEntity, 站台盒, 朝向+XYZ容差)
 │         └─ cars.size()==1 && phase ∉ {MOVING, BLOCKED}
 │    return car != null && car.doorProgress(1) > 0
 └─ 9 格 OPEN 与 open 不同 → world.setBlockState(NOTIFY_LISTENERS)

（每刻，来自任何碰撞/轮廓查询）
LandingDoorBlock.getCollisionShape / getOutlineShape
 └─ progressAt(state, view, pos)
      ├─ 非 World 视图 → OPEN ? 1 : 0
      ├─ 服务端且状态 OPEN 但 mayOpen 已不成立 → 按 0（兜底，不回写状态）
      └─ root 的 BlockEntity.openProgress()

（每刻一次采样，供渲染与碰撞）
LandingDoorBlockEntity.sample()
 ├─ world.getTime() == sampledTick → 直接返回（一刻一样本）
 ├─ previousProgress = progress
 ├─ progress = 门且 OPEN ? LandingDoorBlock.leafProgress(world,pos) : 0
 │     └─ leafProgress → dockedCabin(..., SYNC_POSITION_EPSILON) → car.doorProgress(1)
 ├─ cabinFloor / cabinStatus = cabinOf(world,state) 的对应值（实体 id 最小者）
 └─ sampledTick = now

（客户端每帧）
LandingDoorRenderer.render → door.openProgress(tickDelta) → lerp(previousProgress, progress, t)
```

### 4.5 面板打开与刷新链

```
轿厢内选站面板
  UseItemCallback.EVENT / UseBlockCallback.EVENT（ElevatorNetworking.register 内）
    └─ 主手 && 非旁观 && 非潜行 && 玩家包围盒内有 containsPassenger 的轿厢
         └─ ElevatorNetworking.open(player, cabin)
              ├─ cabin.line()（可能为 null → 空列表）
              ├─ stops.stream().limit(MAX_STOPS)
              └─ ServerPlayNetworking.send(OpenPanel)
  客户端 EasyelevatorClient 收到 OpenPanel → setScreen(new ElevatorScreen(payload))
  计划变化时服务端 syncPanel → PanelState → 只刷新 entityId 匹配的已打开面板
  点站点 → SelectStop → 服务端校验 → requestStop → open() 再下发一次（站点/计划可能已变）
  点开门/关门 → DoorCommand → 服务端校验 → doorCommand → 成功则 open() 刷新

厅外呼叫面板
  LandingDoorBlock.onUse（服务端）→ openHallPanel → OpenHallPanel
  客户端 → setScreen(new LandingDoorScreen(payload))
  登记呼叫 / 到站清扫 / 拆门 → syncHallState(world, station, up, down)
       └─ PlayerLookup.around(server, station, 64) → HallPanelState
  点 ▲/▼ → HallCallButton → 服务端校验 → requestHallCall → syncHallState 回推

门专属设置面板（潜行右键；普通右键仍走上面的厅外呼叫面板）
  LandingDoorBlock.onUse（服务端，player.isSneaking()）→ openDoorSettings → sendDoorPanel → OpenDoorPanel
  客户端 → setScreen(new DoorSoundScreen(payload))；同站点再次下发只 apply() 刷新内容
  点开关 / < > → DoorSoundCommand → 服务端 doorEntity 校验 → 落进方块实体 NBT（choice < 0 = 只改开关）
       └─ 需要试听则 door.arrivalEvent() 当场播放，preview 随快照回传让值格闪一下
  点"设为基准层" → SetBaseFloor → LandingDoorBlock.setFloorBase（原潜行右键的实现）
  点"选择文件…" → AWT FileDialog 选 .ogg → 本机校验 → DoorSoundUpload → 服务端存权威副本
       └─ DoorSoundData（音频内容）→ 发给站点 64 格内所有玩家
             └─ 各客户端存进权威副本 → 标记待重载 → 刻末统一 ensureReady（带内容指纹，内容没变就不重载）
  进服第一刻 → RequestDoorSound(ALL_SLOTS) → 服务端把现有全部音频逐条回 DoorSoundData（进服补齐）
  任何改动后 → broadcastDoorPanel → 站点 64 格内所有者的面板当场刷新（全服同步）

到站音效的播放（服务端，全模组唯一一处）
  ElevatorController.tick() 精确到站 → Environment.arrived(stop)
       └─ AbstractCabinEntity.arrivalChime()
             └─ dockedDoorAtCurrentFloor()（按同高度匹配 line.stops()）
                   └─ LandingDoorBlockEntity.arrivalEvent() → DoorSounds.arrivalEvent(choice, soundSlot)
                         └─ world.playSound(...)，音色完全由"轿厢停靠的那扇门"决定；开关关着则不发声
```

### 4.6 客户端渲染与相机链

```
每帧：
  CabinRenderer.render(cabin, yaw, delta, ...)
    ├─ translate(0, CabinMotion.renderY(cabin,delta) - lerp(delta, cabin.lastRenderY, cabin.getY()), 0)
    │     └─ CabinMotion.renderY
    │          ├─ track==null → vanilla = lerp(delta, lastRenderY, getY())
    │          └─ 否则在 track.previousY → track.physicalY 之间插值（只影响外观）
    │     （`MOTION_STALE_TICKS` 已不参与渲染：它现在在 `beginPlayerTick` 里判定样本是否够新，不够新就不再托举乘客）
    ├─ multiply(绕 Y 旋转到 FACING)
    ├─ 不透明层：drawStandardShell 或 drawObservationShell
    │     + drawParts（内饰表 STANDARD_PARTS / OBSERVATION_PARTS，两张表末尾 6 行是同一个面板）
    │     + drawDoorway（门槛与门楣）+ drawLeaves（两扇滑门，仅普通/高速）
    ├─ 文字层：drawFloorDisplay（StatusArrow.glyph + FloorIndicator.format）
    │     两行按"行心"定位：yOffset = -(行心 + fontHeight*scale/2)/scale
    └─ 玻璃 cutout 层（仅观光）：drawObservationGlass（压条/中梃由 OBSERVATION_PARTS 提供）

  光照：CabinLighting 按面中心与法线区分内外；外表面保留环境光，朝灯的舱内面衰减补光（上限 13）；
        顶灯灯罩（内饰表自发光标志 = 1）仅向下使用 15 级方块光，背面与侧边保持环境光

  LandingDoorRenderer.render(door, tickDelta, ...)
    ├─ drawFloorDisplay（door.cabinFloor / door.cabinStatus / StatusArrow）
    ├─ progress = door.openProgress(tickDelta)
    └─ LandingDoorGeometry.leafBox ×2 → BoxMesh.cuboid（带剔除层）

  ClientPlayerEntityMixin(tick HEAD)
    └─ CabinMotion.beginPlayerTick(player)   玩家物理之前：提交轿厢 → 托举乘客
  ClientPlayerEntityMixin(sendMovement)
    └─ CabinMotion.sendMovement → RiderMove(带轿厢样本编号)

每客户端刻（EasyelevatorClient）：
  清理：实体没了 / Phase != MOVING → stop(CabinRunningSound) 并移出映射
  补充：Phase == MOVING 的轿厢 computeIfAbsent → new CabinRunningSound + play
```

**小结：2.2.0 起不再单独锁定镜头**（`CameraMixin` 已删除）：渲染、碰撞与玩家三者共用同一组服务端样本——
`CabinMotion` 只在 `beginPlayerTick` 里把轿厢与乘客一起搬到样本高度，`renderY` 只做两刻之间的纯视觉插值。


---

## 5. 关键类与关键函数参考

约定：**副作用**一栏只列会改变世界/状态/网络的行为；纯查询不写。参数单位统一为「格 = 方块」「刻 = tick」。

### 5.1 `Easyelevator`（注册层）

| 成员 | 说明 |
| --- | --- |
| `MOD_ID` | `"easyelevator"`，全部注册 ID 的命名空间 |
| `RAIL` / `LANDING_DOOR` | 方块单例（强度 3.0、nonOpaque） |
| `LANDING_DOOR_BE` | 方块实体类型（Fabric builder，因原版 build 需要 datafixer 参数） |
| `CABIN` / `HIGH_SPEED_CABIN` / `OBSERVATION_CABIN` | 实体类型，均 `dimensions(3,3)`、`maxTrackingRange(10)`、`trackingTickInterval(1)` |
| `CABIN_ITEM` / `HIGH_SPEED_CABIN_ITEM` / `OBSERVATION_CABIN_ITEM` | `CabinItem`，`maxCount(1)`，实体类型用 `Supplier` 延迟读取 |
| `RUNNING` / `ARRIVAL` | 音效单例（到站音效由每扇门的面板配置，见 `logic/DoorArrivalSound`） |
| `id(path)` | 构造 `easyelevator:<path>` Identifier |
| `sound(name)` | 注册 SoundEvent |
| `block(name, block)` | 同时注册 BLOCK 与 BlockItem（同一 ID，缺一不可得） |
| `onInitialize()` | 注册顺序：轨道 → 门（沿用 `call_button`）→ 三个轿厢物品 → 物品栏组 → `ElevatorNetworking.register()` |

**为什么物品用 Supplier**：物品与实体类型都是静态字段，Supplier 把「读哪个字段」推迟到放置那一刻，避免静态初始化顺序耦合。

### 5.2 `logic/ElevatorController`（核心状态机）

#### 嵌套类型

| 类型 | 定义 | 说明 |
| --- | --- | --- |
| `enum Phase` | `OPEN, CLOSING, MOVING, OPENING, BLOCKED` | `ordinal()` 进 DataTracker 同步 |
| `enum Travel` | `UP, DOWN, NONE` | 服务方向；NONE = 空闲 |
| `record Stop(long id, int y)` | 站点 | `id = BlockPos.asLong()` 根方块；`y` 站点高度 |
| `record HallCall(long id, int y, boolean up)` | 厅外呼叫 | 同站两方向是两条独立呼叫 |
| `interface Environment` | `valid / canMove / doorwayBlocked / canResume / arrived` | 世界查询回调，见 6.1 |

#### 字段

| 字段 | 类型 | 作用 |
| --- | --- | --- |
| `speed` | `final double` | 实例巡航速度上限（格/刻），构造时注入；非正/非有限值退化为 `SPEED` |
| `profile` | `MotionProfile` | S 形速度曲线实例（按巡航速度与 `CRUISE_RAMP_TICKS` 解出加速度/jerk 上限），回答"本刻走多远" |
| `profileTick` | `double` | 曲线内时间（刻），每次规划后归零；按时间求值避免累加误差 |
| `plannedTarget` | `double` | 当前曲线的计划终点，用于判断目的站是否变过（改道 / 读档） |
| `velocity` / `acceleration` | `double` | 曲线同刻取样的速度与加速度，供改道时给新曲线一个正确初值 |
| `queue` | `ArrayDeque<Stop>` | 选站队列，去重、有序、上限 `MAX_REQUESTS` |
| `hallCalls` | `List<HallCall>` | 厅外呼叫（登记顺序），上限 `MAX_REQUESTS` |
| `target` | `Stop` | 当前目的站，null = 空闲 |
| `phase` | `Phase` | 初值 OPEN（落成即开门便于上人） |
| `door` | `float` | 0 关 / 1 开，每刻 ±1/DOOR_TICKS |
| `dwell` | `int` | 开门剩余停留刻；初值 `DWELL_TICKS`，归零即关门（无请求时关着门停在本层待命） |
| `travel` | `Travel` | 当前服务方向；**停车待命不复位**（方向记忆） |
| `targetHallDirection` | `Travel` | 本次目的站来自哪条方向呼叫，用于到站只清对应方向 |

#### 公开方法

| 方法 | 参数 | 返回 | 副作用 / 说明 |
| --- | --- | --- | --- |
| `ElevatorController()` | — | 实例 | 用 `SPEED` 构造（普通/观光） |
| `ElevatorController(double speed)` | 巡航速度 | 实例 | 高速用 `HIGH_SPEED`；加速度与 jerk 上限由 `MotionProfile.forCruiseSpeed` 按巡航速度与 `CRUISE_RAMP_TICKS` 解出 |
| `ElevatorController(double speed, double rampTicks)` | 巡航速度、加/减速过渡刻数 | 实例 | 显式指定加/减速段长度（调参用）；非法时退化为 `CRUISE_RAMP_TICKS` |
| `speed()` / `phase()` / `door()` / `target()` | — | 对应值 | 只读快照；`phase/door` 会被写入 DataTracker |
| `currentSpeed()` / `profileTime()` / `profileTick()` | — | 格/刻、刻、刻 | 曲线诊断读数，不参与调度 |
| `pending()` / `hallCalls()` | — | 不可变 List | 供同步与存档 |
| `travel()` / `hasRequests()` | — | `Travel` / `boolean` | 只读 |
| `request(Stop, double y)` | 站点、当前 Y | boolean | 已在 target/queue → true；本层且 OPEN/OPENING → 续满 dwell；满 → false；否则 `insertOrdered` |
| `callHall(HallCall, double y)` | 呼叫、当前 Y | boolean | 重复 → true；本层且门开/正开 → 续满 dwell（不入表）；满 → false |
| `forceOpen()` | — | boolean | OPEN→续 dwell；OPENING→true；CLOSING→改 OPENING；door≤0→OPENING；**不动 queue/target** |
| `forceClose()` | — | boolean | OPEN→dwell=0,CLOSING；OPENING→CLOSING；其余 false。只是提前触发自动关门，落点相同 |
| `tick(double y, Environment env)` | 当前 Y、世界回调 | 本刻结束 Y | **主入口**；见 4.3 分支图 |
| `restore(...)` ×2 | phase/door/target/pending[/calls/travel] | — | 覆盖状态；去重截断；**MOVING 降级 BLOCKED 且 door=0** |
| `faulted()` | — | boolean | 是否处于"现在走不了"的故障/受阻期（由 `canResume` 的失败/恢复驱动，不看相位） |
| `canOpenDoor(Phase, int targetY, boolean atStation)` | 相位、目标 Y、是否停在完整站点 | boolean | **static**，开门键受理条件的单一判据：处于故障 → 可用；`MOVING` 且有目标 → 不可用；否则 `atStation`。只依赖同步数据，客户端面板直接复用 |
| `canOpenDoor(boolean atStation)` | 是否停在完整站点 | boolean | 服务端权威版：用 `faulted` 这个准确记忆（乘客开门脱困后相位已变，它仍为真），其余判据同上 |

#### 私有方法（改动调度时的重点）

| 方法 | 要点 |
| --- | --- |
| `insertOrdered(Stop)` | 方向 NONE → 追加（保留先来先服务）；否则按服务方向位置重排 |
| `select(double y)` | 四步派车：本层就地 → 顺路最近 → 前方最早呼叫 → 掉头兜底 |
| `retarget(double y)` | 运行途中「严格更近的同方向请求」改道；原目标放回队列 |
| `serveStation(int stationY)` | 只清本次服务方向的厅外呼叫 + 同层选站 |
| `nearest(y, direction, aheadOnly, sameFloorOnly)` | 先选站（FIFO）后厅外（登记序），严格更近才替换 → 结果确定 |
| `oldestAheadHallCall(y, direction, includeHere)` | 方向前方**登记最早**的呼叫（不是最近） |
| `directionTowards(y, stationY)` | 实际行驶方向；同层沿用当前 travel |
| `initialTravel(y)` | 空闲起始方向：先看队首选站相对位置，再看最早厅外呼叫所在侧（不是按钮方向） |
| `along / opposite / take / Pick` | 纯辅助 |

### 5.3 `entity/AbstractCabinEntity`（世界适配层核心）

#### 同步字段（DataTracker）

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `PHASE` | int | `Phase.ordinal()` |
| `DOOR` | float | 门进度 0..1，与 `controller.door()` 同刻写入 |
| `FACING` | int | 轨道/门朝向 id |
| `TARGET_Y` | int | 目的站 Y；无目标 = `Integer.MIN_VALUE` |
| `FLOOR` | int | 当前楼层号；0 = 尚未经过任何站点 |

#### 内部状态

| 字段 | 作用 |
| --- | --- |
| `controller` / `speed` | 状态机实例与只读巡航速度上限（S 形曲线的形状由状态机内按 `CRUISE_RAMP_TICKS` 解出的加速度/jerk 上限决定） |
| `railX / railZ` | 线路水平坐标（**不进 DataTracker**，客户端恒 0，必须按世界坐标匹配） |
| `previousDoor` | 上一刻门进度，供渲染插值 |
| `motionSettleTicks` | 停车后补发静止运动包的剩余刻数 |
| `lastPlan / lastHallMask` | 「变化才发包」的缓存，不写存档 |
| `floorDirectionY / floorNumber` | 楼层显示方向基准与缓存 |
| `passengers` | 乘客名册 UUID → 相对偏移；`passengerWaitTicks` 读档等待窗口 |

#### 关键方法

| 方法 | 参数 | 返回 | 说明 / 副作用 |
| --- | --- | --- | --- |
| `AbstractCabinEntity(type, world, speed)` | 类型/世界/巡航速度 | — | `setNoGravity(true)`；注入速度构造 controller（加速度/jerk 上限由 `MotionProfile.forCruiseSpeed` 解出） |
| `speed()` | — | double | final，供渲染/面板读取 |
| `cabinItem()` | — | Item | **abstract**，子类给回收物品 |
| `glassWalls()` | — | boolean | 默认 false；观光覆写 true（纯客户端提示） |
| `initDataTracker(b)` | builder | — | 写入 5 个字段默认值 |
| `initialize(rail, facing)` | 轨道、朝向 | — | 设 railX/Z、FACING、`setPosition(中心)`；清名册 |
| `railX() / railZ() / facing() / phase() / doorProgress(t) / targetY() / hasTarget() / floorNumber() / status()` | — | 对应值 | 只读访问器；`doorProgress` 在两刻之间插值 |
| `updateFloorNumber(line)` | 线路 | — | 收集站点 Y → `FloorIndicator.floorNumber` → 写 FLOOR |
| `baseFloorY()` | — | int | 线路里带 `BaseFloor` 标记的门高度；无则 `MIN_VALUE` |
| `requestHallCall(station, up)` | 站点、方向 | boolean | 线路/朝向/唯一性/站点校验后 `controller.callHall` |
| `hasHallCall(station, up)` | 站点、方向 | boolean | 面板按钮是否点亮 |
| `hallCalls()` | — | List | 快照 |
| `syncHallStates()` | — | — | 位掩码比较，变化才 `syncHallState` |
| `line()` | — | `ElevatorLine` | `scan(world, (railX, floor(getY()+.0001), railZ))`；+0.0001 抵消浮点误差 |
| `insideFootprint(e)` | 实体 | boolean | 水平投影在内缘 ±1.31 格内 |
| `containsPassenger(e)` | 实体 | boolean | 非旁观、未骑乘、`insideFootprint`、脚高 ∈ [y+0.14, y+2.7) |
| `tickPassengers()` | — | boolean | 名册 4 步（见 4.2）；返回 true = 本刻必须静止等人 |
| `findLostPassenger(uuid)` | UUID | Player | 井道全高、水平 ±2 格查询 + `insideFootprint` |
| `putPassengerBack(p, offset)` | 玩家、偏移 | — | 偏移夹取后 `requestTeleport`（保留视角）；清下落与纵向速度 |
| `requestStop(button)` | 站点 | boolean | 线路/朝向/唯一性/站点校验后 `controller.request` |
| `plannedStops()` | — | List | target 在前 + 队列随后（面板标红） |
| `doorCommand(open)` | 是否开门 | boolean | 见下 |
| `canHit() / isCollidable() / isPushable()` | — | true / false / false | 可选中；空心碰撞交给 Mixin；不可推动 |
| `interact(player, hand)` | 玩家、手 | ActionResult | 服务端、主手：**潜行 + 主手空 + 厢内没有任何 `containsPassenger` 实体（玩家或生物）** → 回收（非创造掉回本型号物品）并返回 SUCCESS（**不看相位与门进度**：空闲关门后、门开着、甚至运行中都能回收）；**乘客无条件开面板**（不分位置、不看手里拿什么），返回 SUCCESS；非乘客只发一条"请进入轿厢"的提示并返回 **PASS**，把点击让给身后的方块 |
| `tick()` | — | — | 主循环，见 4.2 |
| `sound(event)` | 音效 | — | 以 BLOCKS 分类、`EVENT_VOLUME`/`SOUND_PITCH` 播放 |
| `spaceClear(box)` | 盒 | boolean | 边界/区块/碰撞形状/其它轿厢检查；忽略本线路完整楼层门 |
| `localBox(x1,y1,z1,x2,y2,z2)` | 局部坐标 | `Box` | 绕 Y 旋转到世界；x'=x·fz+z·fx，z'=-x·fx+z·fz |
| `collisionBoxes()` | — | List<Box> | 空心外壳每刻新建：地板/顶板/两侧/背板/两扇门 |
| `writeCustomDataToNbt / readCustomDataFromNbt` | NBT | — | 见第 8 节 |

**`doorCommand(open)` 的受理条件（服务端）**：

- 客户端直接 false。
- 开门：`status() == IDLE`（必须停稳，**不能只看高度**——运行时高度会精确经过整数层）→ 线路有效且朝向一致 → 车体与某站点高度差 ≤ `POSITION_EPSILON` → `controller.forceOpen()`。
- 关门：直接 `controller.forceClose()`（允许队列为空关门停车）。

### 5.4 `block/LandingDoorBlock`

| 成员 | 说明 |
| --- | --- |
| `CODEC` | `createCodec(LandingDoorBlock::new)` |
| `COLUMN` (0..2) / `LEVEL` (0..2) / `OPEN` (boolean) / `FACING` | 4 个状态属性；无 POWERED，红石不驱动 |
| `RAIL_DISTANCE = 3` | 根方块到轨道沿朝向反方向的距离（格） |
| `isRoot(s)` | 是本模组门 && COLUMN==1 && LEVEL==0 |
| `root(s, p)` | 任意部件 → 根坐标：先沿 `facing.rotateYClockwise()` 退 `1-column`，再 `down(level)` |
| `railPos(s, p)` | `root(s,p).offset(facing.getOpposite(), 3)` |
| `part(root, facing, column, level)` | 门内相对坐标 → 绝对坐标 |
| `complete(world, root)` | 9 格齐全、FACING 一致、COLUMN/LEVEL 自洽；区块未加载 = 不完整 |
| `getPlacementState(ctx)` | 4 朝向试放；要求朝向反方向 3 格处是同朝向轨道，且 9 格可替换/在范围内/有权限；全部失败发消息返回 null |
| `onPlaced(...)` | 补齐其余 8 格（NOTIFY_ALL）→ `refresh` → `scheduleBlockTick(1)` |
| `scheduledTick(...)` | 根方块每刻 `refresh` 并重新排程（轮询自维持） |
| `dockedCabin(world, origin, tolerance)` | 私有纯查询：完整门 + 轨道匹配 + 唯一轿厢 + 中心/高度在容差内。**不按相位排除 MOVING/BLOCKED**：故障脱困时轿厢门会打开，若这里排除 BLOCKED，楼层门就拒绝交出碰撞、人走不出去；安全性由调用方的"轿厢门进度 > 0"把关 |
| `mayOpen(world, origin)` | 联锁：`dockedCabin(...,POSITION_EPSILON) != null && doorProgress(1) > 0` |
| `leafProgress(world, origin)` | 楼层门门扇目标进度 = 在站轿厢门进度（用 `SYNC_POSITION_EPSILON`，客户端也要成立） |
| `refresh(world, origin)` | 服务端；把 `mayOpen` 写到 9 格 OPEN（NOTIFY_LISTENERS），写前用 `root(cell,p)==origin` 防误改相邻门 |
| `belongsToCabin(world,p,state,car)` | `spaceClear` 豁免判据：同朝向、同轨道 XZ、整门完整 |
| `onUse(state,world,pos,player,hit)` | 服务端：潜行 → `openDoorSettings`（打开该门专属的设置面板）；否则 `openHallPanel`；恒返回 SUCCESS。`setFloorBase` 已改由面板按钮发出的 `SetBaseFloor` 包触发 |
| `openHallPanel(world, origin, player)` | 扫线路算端站方向 → `OpenHallPanel`（含两方向点亮状态） |
| `openDoorSettings(world, origin, player)` | 潜行右键：发 `OpenDoorPanel`（`sendDoorPanel`）打开该门专属设置面板 |
| `setFloorBase(world, origin, player, on)` | 把 origin 设为/取消基准层，清同线路其它门标记；`syncPanel` + `broadcastDoorPanel`；提示（原潜行右键的实现，改由面板按钮调用） |
| `onBreak(...)` | 非根被拆 → 转 `world.breakBlock(root)`，保证只掉一次 |
| `onStateReplaced(...)` | 真正消失（`!next.isOf(this)`）时清同门其余 8 格为空气 |
| `getOutlineShape / getCollisionShape` | 均返回 `LandingDoorGeometry.shape(facing, column, level, progressAt(...))`（同源，高亮跟随门扇） |
| `progressAt(s,w,p)` | 优先方块实体进度；服务端兜底「状态 OPEN 但 mayOpen 已失效 → 按 0」 |

### 5.5 `block/LandingDoorBlockEntity`

| 成员 | 说明 |
| --- | --- |
| `progress / previousProgress / sampledTick` | 门扇进度样本与刻闸门 |
| `baseFloor` | 基准层标记（随 NBT 存档） |
| `cabinFloor / cabinStatus` | 门框顶部显示缓存，每刻一次 |
| `openProgress()` | 不插值，每次读当前开度，绕过渲染采样缓存，供碰撞与联锁 |
| `openProgress(tickDelta)` | `lerp(previousProgress, progress, clamp(t))`，供渲染 |
| `sample()` | 私有；同刻只推进一次；`progress = OPEN ? leafProgress : 0`；刷新 cabin 显示 |
| `cabinOf(world, state)` | 按线路中心 + 高度区间定位轿厢；多辆时取 **id 最小** |
| `cabinFloor() / cabinStatus() / baseFloor()` | 访问器（`baseFloor` 只在服务端有意义） |
| `setBaseFloor(value)` | 变化则 `markDirty()` |
| `writeNbt / readNbt` | 只写 `BaseFloor=true`（默认门不多写 false） |

### 5.6 `block/LandingDoorGeometry`（门几何单一数据源）

常量：`DOOR_WIDTH=DOOR_HEIGHT=48`（1/16 格单位，= 3 格）、`FRAME=3`、`LEAF_TOP=45`、`SEAM=1`、`LEAF_TRAVEL=(48-6-1)/2=20.5`、`SHAPE_STEPS=16`。

| 方法 | 说明 |
| --- | --- |
| `leafEdge(progress, right)` | 门扇内缘横坐标；progress=0 两扇各半、中缝 SEAM；progress=1 宽度归零 |
| `leafBox(facing, progress, right)` | 渲染用门扇盒（相对根方块）；宽度归零时返回 null |
| `doorBox(facing, u1, v1, u2, v2)` | 整门坐标（1/16 格）→ 根方块局部盒；SOUTH/WEST 镜像（`2-u/16`） |
| `shape(facing, column, level, progress)` | 某格体素形状 = 左立柱 + 右立柱 + 门楣 + 左扇 + 右扇；按（朝向,列,层,进度档）缓存 |
| `add / partBox` | 私有：并盒 / 平移并夹到本格 0..1 |

缓存下标：`((facing.ordinal()*3+column)*3+level)*(SHAPE_STEPS+1)+step`。用普通数组而非同步容器：最坏重复计算是幂等的。

### 5.7 `block/ElevatorRailBlock`

| 成员 | 说明 |
| --- | --- |
| `FACING` | 唯一状态属性（非原版 AbstractRailBlock） |
| `getPlacementState` | 先看正下方、再看正上方，有同方块则继承 FACING；否则取 `ctx.getHorizontalPlayerFacing().getOpposite()`（朝向放置者） |
| `getOutlineShape` | `Block.createCuboidShape(3, 0, 3, 13, 16, 13)`；**碰撞仍是整格** |

### 5.8 `logic/ElevatorLine`（record）

字段：`x, z, bottom, top, facing, stops`。

| 方法 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `scan(world, seed)` | 世界、轨道位置 | 线路或 null | 区块未加载/非轨道 → null；向上下扩展同朝向轨道；逐层找「朝向前方 3 格的完整正面门」为站点；按 Y→X→Z 固定排序 |
| `matches(w, p, d)` | 世界、位置、方向 | boolean | 区块已加载且为同朝向轨道 |
| `containsRail(p)` | 位置 | boolean | 同 X/Z 且 Y ∈ [bottom, top] |
| `centerX() / centerZ()` | — | double | 轨道中心 + 朝向前方 2 格 |
| `cabins(world)` | 世界 | List | 粗筛盒（±2 格、Y bottom-1..top+4）+ 精筛 `railX/railZ` 与 Y（±0.01） |

### 5.9 `item/CabinItem`

`useOnBlock(ctx)` 流程：非轨道 → PASS；客户端 → SUCCESS（仅预测）；服务端 → `ElevatorLine.scan` → 线路 null / 已有轿厢 / 空间受阻分别给消息键 → `type.get().create` → `cabin.initialize` → `cabin.spaceClear(cabin.getBoundingBox())` → `spawnEntity` → 非创造扣 1 → CONSUME；失败 → actionbar 消息 + FAIL（物品保留）。

### 5.10 其余 `logic` 类

| 类 | 公开 API 要点 |
| --- | --- |
| `ElevatorParameters` | 全部 `public static final`；详见 7.1 |
| `ElevatorStatus` | `IDLE/UP/DOWN`；`key()`；`of(phase, targetY, y)`：只有 MOVING 且有目标才可能上下行，差值 < `SYNC_POSITION_EPSILON` 视为停靠；`faulted(phase)`：处于故障/受阻暂停时为 true（`canOpenDoor` 的静态判据用它） |
| `FloorIndicator` | `floorNumber(stationYs, y, previousY[, baseY])`、`baseIndex`、`value(index, baseIndex)`、`format(floor)`、`label(index, baseIndex)`；上行取已过最高层、下行取已过最低层 |
| `PanelLayout` | `grid(count, maxColumns, maxRows)` → `Grid(columns, rowsPerPage, totalRows, pageCount)`；`chooseColumns` 评分 = 空格数×2 + 列数与行数之差的绝对值，同分取更宽；`capacity / pageStart / columnFromRight / rowFromBottom` |
| `MotionProfile` | S 形速度曲线（纯 Java、确定性）：`forCruiseSpeed(cruiseSpeed[, rampTicks])` 按巡航速度与 `CRUISE_RAMP_TICKS` 解出加速度 / jerk 上限；`plan(position, velocity, acceleration, target)` 以"对峰值速度二分 + 匀速段填充"一次算完整条曲线（可重规划）；`advance(dt, position, tick)` 按时间求值给出本刻位置，终点精确落在目标上；`velocityAt / accelerationAt / distanceAt / totalTime / totalDistance / peakSpeed / maxAcceleration / maxJerk / cruiseSpeed / idle` 为诊断读数 |
| `FramedLeaf` | 铁框玻璃门扇的纯几何：`FRAME = 2/16`、`FRAME_MAX_RATIO = .34`、`RAIL_MAX_RATIO = .2`；`frameWidth(width)` 让边框随门扇变窄按比例缩（玻璃宽度不会变成负数）；`layout(w0,w1,y0,y1)` → 四条边框 + 中间玻璃矩形；`glassVisible(layout)`；`centredSlice / stileUv / railUv / paneUv` 为 UV 助手 |
| `LeafUv` | 滑门"随门滑动"的 UV（纯算术），**两套区间函数不能混用**：`leafRange(progress, right)` 给**楼层门叶**（左扇 `{1, p}`、右扇 `{1-p, 0}`；依赖 `LandingDoorGeometry.doorBox` 的朝向镜像）；`cabinPanelRange(progress, right)` 给**轿厢门扇**（`+X` 扇 `{p, 1}`、`-X` 扇 `{1, p}`；轿厢门扇无朝向镜像，两扇 maxX 一头一尾）。另有 `EDGE_WIDTH = .06f`、`sanitize`、`edgeRange(right, width)`、`centredThinSlice`、`toUv(range, cell)` 映射进整张图或图集一格、`slabUv(panelUv, edgeUv, normalAlongZ)` 供长方体分面使用（大面随门滑动、断面固定一小段）。两者都让中缝端落在纹理 `t=1`、门框端落在 `t=进度`（见 2.2.0 修的那条"厢内往外看左侧反了"） |
| `SlidingDoor` | 轿厢两扇对开滑门的纯算术布局：`DOORWAY_HALF = 1.3`、`OUTER_INSET = .001`、`OUTER_EDGE`、`SEAM = .005`、`DOOR_Z_BACK = 1.1`、`DOOR_Z_FRONT = 1.3`（与楼层门后缘 1.3125 留 0.0125 格）；`panelX(right, progress)` 外缘固定、内缘（先导端）随进度外移、全开时宽度归零；`doorZ()`、`clearHalfWidth(progress)`、`visible(progress)`、`sanitize` |
| `CabinLighting` | 舱内补光的纯函数：`LAMP_LEVEL = 15`；`surface(worldLight, x,y,z, nx,ny,nz)` 按面中心到灯位（本地 `0, 2.78, -.25`）的距离衰减抬高方块光分量，只抬不降、超出舱内范围原样返回世界光照；`lamp(...)` 供顶灯灯罩朝下面使用 15 级。入参是**轿厢本地坐标**（施加旋转之前采样），无状态、可并发调用 |
| `RiderMotionHistory` | 服务端权威的"轿厢绝对高度样本历史"（纯 Java）：`MAX_AGE = 60` 刻；`record(tick, y)` 只接受**严格递增**且有限的世界时间，超龄样本从队首淘汰；`height(tick, now)` 按客户端移动包携带的样本编号取回当时高度（过期 / 未知返回 `NaN`，调用方必须据此退回原版行为）；`rebase(playerY, seenCabinY, currentCabinY)` 把玩家 Y 从"客户端所见帧"换算到"服务端当前帧"（纯函数，跳跃高度原样保留） |
| `DoorSounds` | 每扇门"到站提示音"的选项目录与换算：`DEFAULT = 0`、`CUSTOM = 1`、`PRESET_BASE = 2`、`CHOICE_COUNT`、`MAX_SLOTS = 64`；`slotFor(x, y, z)` 由门坐标混合推导门槽（纯函数，拆了再放回仍是同一槽）；`soundId / slotEvent / fileStem / isStem / slotOfStem` 打通"槽位 ↔ 音频文件名 ↔ 音效事件 ID"；`arrivalEvent(choice, slot)` 把序号翻成 `SoundEvent`（越界退回默认）；`clamp / isDefault / isCustom / isPreset / presetId`。**序号本身就是协议**，服务端与客户端不必交换音效 ID |
| `DoorArrivalSound` | record `(enabled, choice)`：单扇门的到站音设置（不可变值对象，随方块实体 NBT 存）。`DEFAULT = (true, DoorSounds.DEFAULT)`（默认开 + 默认音效 = 模组原有行为）；紧凑构造器与 `readNbt` 都用 `DoorSounds.clamp` 消毒；`withEnabled / withChoice` 返回新值；`writeNbt / readNbt`（键 `ArrivalSound` / `ArrivalSoundChoice`，读不到即默认） |
| `DoorSoundPersistence` | 上传音频的**权威副本**存储（只依赖 Fabric，不依赖 Minecraft）：`STORE_DIR_NAME = "arrival_sounds"`（`<gameDir>/config/easyelevator/` 下）、`MAX_AUDIO_BYTES = 512 KiB`；`storeDir()`、`store(slot, bytes)`（重新校验 `OggS` 魔数与长度）、`read(slot)`、`fileName(slot)`、`listStems()`、`isOgg(bytes)`；所有写入先落同目录 `.tmp` 再原子替换（资源重载可能在任何时刻发生） |

### 5.11 `network/ElevatorNetworking`

见第 6.3 节的协议表。核心静态方法：

| 方法 | 说明 |
| --- | --- |
| `syncMotion(cabin)` | 对每个追踪者发 `MotionFrame`；`riderOffset` 按接收者单独算（非乘客 NaN） |
| `syncPanel(cabin, planned)` | 计划变化时发 `PanelState`（含 `baseFloorY`） |
| `syncHallState(world, station, up, down)` | 只发站点 64 格内玩家 `HallPanelState` |
| `open(player, cabin)` | 发 `OpenPanel`；`line == null` 时给空列表 |
| `register()` | `ElevatorNetworking.register()` 注册 7 S2C + 7 C2S + 7 个全局接收器 + 2 个右键事件，并调用 `RiderMove.register()`（后者另注册 1 个 C2S + 1 个接收器） |
| `readPositions / writePositions` | 私有；数量先校验（<0 或 > `MAX_STOPS` 抛异常），再逐个读写，返回不可变列表 |

### 5.12 `api/ElevatorEvents`

| 成员 | 类型 | 触发点与参数 |
| --- | --- | --- |
| `PhaseChanged` | 函数式接口 | `onChange(cabin, before, after)`；`AbstractCabinEntity.tick` 末尾 phase 变化时，**在状态全部写回后** |
| `Arrived` | 函数式接口 | `onArrival(cabin, floorY)`；状态机精确到站并回调 `Environment.arrived` 时，与 `ARRIVAL` 音效同刻 |
| `PHASE_CHANGED` / `ARRIVED` | `Event<T>` | `EventFactory.createArrayBacked`；按注册顺序同步调用，**回调异常会跳过其后回调并冒泡** |

### 5.13 Mixin

| 类 | 目标 | 注入 | 作用 / 约束 |
| --- | --- | --- | --- |
| `mixin/EntityViewMixin` | `EntityView` | `getEntityCollisions` @RETURN, cancellable | 是轿厢实体则跳过（防自卡）；查询盒外扩 0.001 格；复制原版列表后追加相交的外壳盒 |
| `mixin/client/ClientPlayerEntityMixin` | `ClientPlayerEntity` | `tick` 的 HEAD（Inject）+ `sendMovementPackets` 内 `ClientPlayNetworkHandler#sendPacket` 的 Redirect | HEAD 调 `CabinMotion.beginPlayerTick`：在玩家本刻物理与移动包生成之前提交轿厢位置并托举乘客（放在 HEAD 是硬要求，否则会穿模）；Redirect 把原版此刻选中的移动包换成 `RiderMove`（携带轿厢样本编号），非随厢或不是移动包时原样转发，绝不重复发送 |
| `mixin/client/CabinCrosshairMixin` | `GameRenderer` | `findCrosshairTarget` @RETURN, cancellable | **轿厢内准星的唯一裁决者**：乘客在厢内 → 一律把命中改写成"命中本厢"，右键必定开面板（与手里拿什么无关）；门全开且方块命中点在**静态舱体**之外时让给那个方块，射线从门洞穿出去（左键拆/右键放）。只改实体命中，不碰碰撞几何与方块射线；判据是沿射线比距离，**不能**用"方块坐标是否在轿厢包围盒内"（楼层门就在那个盒子里，会恒为假） |

### 5.14 客户端类

| 类 | 关键成员 | 要点 |
| --- | --- | --- |
| `EasyelevatorClient` | `sounds: Map<int, CabinRunningSound>` | 注册 3 个轿厢渲染器（同一 `CabinRenderer::new`）与 1 个方块实体渲染器；7 个 S2C 处理器都走 `context.client().execute`；END_CLIENT_TICK 清理+补充运行声；DISCONNECT 停止音效并 `CabinMotion.clear()` |
| `CabinRenderer<T>` | `TEXTURE`、`GlassLayers`、`CabinLighting`、`Mat/MATERIAL_UV`、`STANDARD_PARTS/OBSERVATION_PARTS`、门底偏移与文字排版 | 普通与高速共用模型；按“不透明件 → 文字 → 玻璃”分组；外壳保留环境光，舱内面补光，灯罩仅向下发亮；玻璃为原版无色 cutout |
| `BoxMesh` | `FULL_UV = {0,0,1,1}`、`float[] uv`（六面同一分格）与 `float[][] faceUv`（**逐面**分格，顺序 -Z,+Z,-X,+X,+Y,-Y） | `cuboid`×**9**（裸坐标 / `Box` × 有无 UV 矩形 × 有无 `FaceLighting`）、`planeX/Y/Z` 各两个重载、私有 `quad`；长方体网格 / 零厚度单面；UV 矩形默认铺满整张图，传分格即取图集一格，按"第 0 顶点 (u0,v1)、第 2 顶点 (u1,v0)"落到四个角，因此默认值时就是原版 (0,1)(1,1)(1,0)(0,0)；**逐面分格用于滑门与呼梯按钮**（滑门：大面随门滑动、断面固定一小段；按钮：圆点只贴朝乘客那一面，其余五面取无图案窄条，见 `CabinRenderer.buttonFaceUv`）；平面单面的 UV 方向与顶点顺序绑定（`planeZ` 从 +X 侧起步，u 才沿 X 增长） |
| `LandingDoorRenderer` | `TEXTURE=blank_door`、`FLOOR_SCALE=.016f`、`SCREEN_CENTRE_Y=2.90625`、`SCREEN_INSET=.75/16`、`TEXT_STANDOFF=.008`、`STATUS_GAP=6f` | 先画层号再画门扇（全开无门扇直接返回）；文字按行心定位（`draw` 的 y 是顶边）；门扇 UV 只取"还露在外面"的一段（贴图随门板滑而不是被压扁）；`rendersOutsideBoundingBox=true`（门扇会滑出根方块那格，默认剔除会让它提前消失） |
| `ElevatorScreen` | `BUTTON=20,GAP=4`、`MAX_COLUMNS/ROWS=8`、`PADDING=16,HEADER=64,FOOTER=40`、配色常量 | 站点自行按 Y→X→Z 排序；`init()` 算网格与面板矩形并铺控件；`tick()` 用宽松 `staysInside` 自动关闭；`render` 每帧刷新区按钮可用性；`renderBackground` 只做淡黑叠加（不用模糊） |
| `LandingDoorScreen` | `BUTTON=20,GAP=6,PADDING=10` | 按钮数 = 显示的方向数 + 1（关闭）；`pending` 决定是否红色 |
| `CabinMotion` | `TRACKS: WeakHashMap<AbstractCabinEntity, Track>`、`tickCabin / tickFrame` | `receive` 只记目标高度与接收时刻、**绝不外推**（乱序/重复样本丢弃）；`beginPlayerTick` 在玩家物理之前提交轿厢并托举乘客（`containsPassenger` + `MOTION_SNAP_DISTANCE` 把关，一刻只认第一辆车，样本超过 `MOTION_STALE_TICKS` 未更新就不再托举）；`sendMovement` 把原版移动包换成 `RiderMove`；`renderY` 在 `Track.previousY → physicalY` 之间插值；`clear` 供换世界/断线清理 |
| `CabinRunningSound` | `cabin` | `super(Easyelevator.RUNNING, BLOCKS, createRandom())`；`repeat=true, repeatDelay=0`；`tick()` 中实体移除或非 MOVING → `setDone()` |
| `StatusArrow` | `BLINK_MS=500`、`UP="▲"`、`DOWN="▼"` | `moving / lit / glyph / width`；用墙钟毫秒而非游戏刻（暂停菜单里仍闪） |
| `DoorSoundScreen` | `ROW=20, GAP=4, PAD=12`；`TOGGLE_W=86, VALUE_W=140, ARROW_W=20, PICK_W=58, TRY_W=40`；`CLOSE_W=56`；`TITLE_H=12, HEADER_H=18` | 单扇门的专属设置面板（潜行右键打开）：一行「到站音效」= 开关 + 值格 + `<` `>` + 选择文件 + 试听，底部「设为基准层」与「关闭」。打开**不暂停游戏**；`open=false` 的广播只刷新同站点已打开的面板，不抢其它界面；试听靠 `preview` 让值格闪 `▶` |
| `DoorSoundPack` | `PACK_DIR_NAME="easyelevator_custom"`、`FINGERPRINT_FILE="sound_pack.sha1"`、`SLOT_DIR="arrival"`、`PACK_FORMAT=34` | 把权威副本投影成运行时资源包 `resourcepacks/easyelevator_custom/`（`pack.mcmeta` + `assets/easyelevator/sounds.json` + `assets/easyelevator/sounds/arrival/*.ogg`）。**先构建到临时目录再算内容指纹**，与上次一致就什么都不做（根治"每次进存档闪红屏"）；`enable` 负责写进已启用包列表 |
| `GlassLayers` | `CABIN_TEXTURE = Identifier.ofVanilla("textures/block/glass.png")`、`CABIN = getEntityCutout(...)`、`DOOR = CABIN` | 轿厢与楼层门共用**原版无色玻璃**贴图（资源包可直接替换）。透明像素丢弃、其余正常写深度并用环境光；正反两面法线相反、按朝向剔除，因此不会重复混合也不会挡住后绘制的楼层门；顶点色恒为白色 |
| `FramedGlassDoor` | `frame(...)` 两个重载（含 `FaceLighting`）、`glass(...)` | 画"周围铁框 + 中间玻璃"的门扇：铁框在不透明层、玻璃在 `GlassLayers.DOOR` 层；布局与各构件 UV 取自纯算术类 `logic/FramedLeaf`（`stileUv/railUv/paneUv`），避免整张贴图铺到 2/16 格竖框上被压成"条形码" |
| `EasyelevatorDataGenerator` | 实现 `DataGeneratorEntrypoint` | 数据生成入口（`fabric-datagen`）。`onInitializeDataGenerator` 只 `createPack()`，**当前不注册任何 provider，因此跑数据生成不产出任何文件**；入口保留以便后续新增 provider。不参与游戏运行时，打包后的模组也不会执行它 |


---

## 6. 接口介绍

### 6.1 `ElevatorController.Environment`（世界查询回调接口）

这是**纯逻辑状态机与 Minecraft 世界之间唯一的契约**。实现方是 `AbstractCabinEntity.tick()` 内的匿名类，每刻同步调用，返回值只对本次调用有效。

| 方法 | 签名 | 何时调用 | 实现要点（AbstractCabinEntity） |
| --- | --- | --- | --- |
| `valid` | `boolean valid(Stop stop)` | 每刻开头剔除失效请求；目标失效则转 BLOCKED | 区块未加载 → **true**（暂停而非丢目标）；否则要求 `isRoot && complete && railPos == (railX, stop.y, railZ)` |
| `canMove` | `boolean canMove(double from, double to)` | MOVING/BLOCKED 分支每刻一次 | 等人中/线路不唯一/目标不在线路 → false；逐格 `ElevatorLine.matches`；扫掠盒 `spaceClear`；对非乘客实体用前后两段外壳求交防穿越 |
| `doorwayBlocked` | `boolean doorwayBlocked()` | CLOSING 分支每刻 | 区域 `localBox(-1.3, .2, CABIN_DOOR_BACK_Z-0.15, 1.3, 2.8, 1.6)`，只算 `LivingEntity` 且非旁观 |
| `canResume` | `boolean canResume()` | tick 开头的故障判定（仅故障期） | 回答"让 `canMove` 失败的原因是否消失"，与 `canMove` 共用 `pathClear`；**有目的站时按 `pathClear(getY(), target.y())` 判整段**（不能按"原地一步"判，那样扫掠体积为 0、井道障碍不参与判定，会把故障误判成已解除）。**无副作用**，且允许 `target == null`（目的站被拆后清空）时调用。故障期由它复位，而不是看相位——乘客开门脱困会改相位。清故障时还必须把相位从 `BLOCKED` 复位回 `MOVING`，否则客户端按钮与服务端判定不同步（见 `PARAMETERS.md` 4.1） |
| `arrived` | `void arrived(Stop stop)` | 精确到站时一次 | 播放 `ARRIVAL` 音效 + `ElevatorEvents.ARRIVED` |

**实现自定义状态机的步骤**：实现这 5 个方法 → 每刻 `controller.tick(y, env)` → 把返回值作为新 Y。状态机保证：`door==0` 才移动；到站时先把 Y 吸附到 `target.y()` 再回调 `arrived`（所以 `arrived` 里做 1e-7 判定一定成立）。

### 6.2 事件 API（面向整合方）

`ElevatorEvents.PHASE_CHANGED` 与 `ElevatorEvents.ARRIVED`，用法：

```java
ElevatorEvents.ARRIVED.register((cabin, floorY) -> {
    // 服务端主线程；cabin 为服务端实体实例
});
ElevatorEvents.PHASE_CHANGED.register((cabin, before, after) -> {
    // before != after；此时 DataTracker 已写回，读到的状态自洽
});
```

约束：**服务端事件**，客户端不要订阅（客户端应读 `DataTracker` 的 `phase()`/`doorProgress()` 与 `MotionFrame`）；回调抛异常会中断后续回调并冒泡打断 `tick()`，因此必须自建 try/catch 且不做耗时世界查询。

### 6.3 网络协议（服务端权威）

所有 payload 都是 `CustomPayload` + `PacketCodec<RegistryByteBuf, ...>`。**字段顺序即协议**，编解码必须严格对称。

**S2C（服务端 → 客户端，7 个）**

| Payload | ID | 字段 | 触发时机 | 客户端处理 |
| --- | --- | --- | --- | --- |
| `MotionFrame` | `easyelevator:motion_frame` | `int entityId, long tick, double y, double riderOffset` | `syncMotion`：移动中每刻 + 停车后 `MOTION_SETTLE_TICKS` | `CabinMotion.receive` 只取 `entityId`/`tick`/`y`；`riderOffset` 是**已废弃的兼容字段**（自 2.1.2 起客户端不再使用，改由 `containsPassenger` 自行判定乘客，见 2.2） |
| `OpenPanel` | `easyelevator:open_panel` | `int entityId, List<BlockPos> stops, List<BlockPos> planned, int baseFloorY` | `open()`：进入轿厢右键、选站/开关门成功后刷新 | `setScreen(new ElevatorScreen)` |
| `PanelState` | `easyelevator:panel_state` | `int entityId, List<BlockPos> planned, int baseFloorY` | 计划或基准层变化 | 只刷新 `entityId` 匹配的已打开面板 |
| `OpenHallPanel` | `easyelevator:open_hall_panel` | `BlockPos station, boolean up, down, showUp, showDown` | 右键楼层门 | `setScreen(new LandingDoorScreen)` |
| `HallPanelState` | `easyelevator:hall_panel_state` | `BlockPos station, boolean up, boolean down` | 登记/到站清扫/拆门 `syncHallState` | 只刷新 `station` 匹配的已打开面板 |
| `OpenDoorPanel` | `easyelevator:open_door_panel` | `BlockPos station, String floorLabel, boolean enabled, int choice, boolean baseFloor, int soundSlot, int preview, boolean open` | 潜行右键楼层门（`openDoorSettings`）、以及任何人改动设置后 `broadcastDoorPanel` | `open=true` 允许打开；广播 `open=false` 仅刷新同站点已打开的 `DoorSoundScreen`，其他界面保持原样；`preview` 让值格闪一下 |
| `DoorSoundData` | `easyelevator:door_sound_data` | `int slot, byte[] bytes` | 上传成功后广播给站点 64 格内的玩家；或回应 `RequestDoorSound` | 存进权威副本 → 标记待重载 → 刻末统一 `DoorSoundPack.ensureReady`（指纹没变就不重载）；空数组表示"服务端也没有"，保持静音 |

**C2S（客户端 → 服务端，8 个：`ElevatorNetworking` 7 个 + `RiderMove` 1 个）**

| Payload | ID | 字段 | 服务端校验（全部重新校验，客户端数据不可信） |
| --- | --- | --- | --- |
| `SelectStop` | `easyelevator:select_stop` | `int entityId, BlockPos button` | 非旁观且存活 → `getEntityById` 是轿厢 → `containsPassenger(player)` → `cabin.requestStop(button)`（内部再校验线路/朝向/唯一性/站点） → 回消息 + `open()` 刷新 |
| `DoorCommand` | `easyelevator:door_command` | `int entityId, boolean open` | 同上身份校验 → `cabin.doorCommand(open)` → 成功 `open()`，失败回 `door_no_station` / `door_close_locked` |
| `HallCallButton` | `easyelevator:hall_call_button` | `BlockPos station, boolean up` | 区块已加载 → `isRoot && complete` → `scan` 且朝向一致且站点命中 → 恰好一辆轿厢 → `cabin.requestHallCall` → 回消息 + `syncHallState` |
| `DoorSoundCommand` | `easyelevator:door_sound_command` | `BlockPos station, boolean enabled, int choice, boolean preview` | `doorEntity`（区块已加载 + `isRoot` + `complete`）→ `withEnabled/withChoice` 落进方块实体 NBT（`choice < 0` = 只改开关）→ 可选试听 `door.arrivalEvent()` → `broadcastDoorPanel` |
| `SetBaseFloor` | `easyelevator:set_base_floor` | `BlockPos station, boolean on` | 非旁观且存活 → `LandingDoorBlock.setFloorBase(world, station, player, on)`（内部再校验整扇门与线路）；原"潜行右键门"的实现，只是入口换了 |
| `DoorSoundUpload` | `easyelevator:door_sound_upload` | `BlockPos station, byte[] bytes` | `doorEntity` → 长度 ≤ `MAX_AUDIO_BYTES` 且 `OggS` 魔数 → `DoorSoundPersistence.store(slot, bytes)` → 切到"自定义文件"并开启 → 广播 `DoorSoundData` → 试听 → `broadcastDoorPanel` |
| `RequestDoorSound` | `easyelevator:request_door_sound` | `int slot` | 非旁观且存活 → 具体槽位回一条 `DoorSoundData`；`slot == ALL_SLOTS(-1)` 表示"进服补齐"，遍历 `DoorSoundPersistence.listStems()` 把服务端现有的全部音频逐条回发（跳过读不到的） |
| `RiderMove`（`network/RiderMove.java`，**不在** `ElevatorNetworking` 里） | `easyelevator:rider_move` | `int cabinId, long tick, double x, double y, double z, float yaw, float pitch, boolean ground, boolean position, boolean look`（原版移动包的字段原样照抄，另加轿厢 id 与样本编号） | 本包携带位置变化、玩家存活非旁观未骑乘、`cabinId` 确实是本世界轿厢、玩家确是 `containsPassenger` 乘客、样本编号能查到（`cabin.motionHeight(tick)`）、相对高度 ∈ [-0.1, 2.8] 且水平距轿厢中心 ≤ 1.9 → `RiderMotionHistory.rebase` 换算 Y 后交回原版处理器；任一不满足即**原样投递**（等价纯原版行为，不凭空多给位移） |

**容量上限**：单个 `OpenPanel` 最多 `MAX_STOPS = 16384` 个站点；解码时先读数量并校验（负数或超限抛异常），防止无界内存分配。发送侧也 `limit(MAX_STOPS)`。全模组共 **15 个** payload：`ElevatorNetworking` 的 14 个（7 S2C + 7 C2S）+ `RiderMove` 的 1 个 C2S。
自定义音频相关上限：`MAX_AUDIO_BYTES = 512 KiB`（单文件），`MAX_PAYLOAD_BYTES = MAX_AUDIO_BYTES + 1 KiB`（`DoorSoundUpload` / `DoorSoundData` 的解码上限），`DoorSounds.MAX_SLOTS = 64`（门槽数），`MAX_FLOOR_LABEL = 32`（层号文本）。

**右键事件**（`register()` 内）：

| 事件 | 条件 | 行为 |
| --- | --- | --- |
| `UseItemCallback` | 主手 && 非旁观 && 非潜行 && 玩家包围盒内有含自己的轿厢 | 服务端 `open`；客户端返回 success（避免重复面板） |
| `UseBlockCallback` | 同上 | 命中方块时走这里；未命中时走上一个；都不满足 → PASS（让位给门/轨道逻辑） |

### 6.4 客户端 ↔ 服务端交互时序（一次完整乘梯）

```
玩家走进轿厢
  右键（空气或方块）──► UseItem/UseBlockCallback ──► open ──► OpenPanel ──► ElevatorScreen
点 5 层
  SelectStop ──► 服务端校验 ──► requestStop ──► controller.request
  服务端 ──► open（刷新）+ 每刻 DataTracker/MotionFrame
门关、轿厢运行
  DataTracker(PHASE=CLOSING→MOVING) ──► 门动画；MotionFrame ──► CabinMotion 提交轿厢并托举乘客
  服务端每刻 refresh 楼层门（关闭）
到站
  controller: y==target → arrived + serveStation + OPENING
  DataTracker(PHASE=OPENING/OPEN, FLOOR=n, TARGET_Y=MIN) ──► 面板与门框文字
  refresh ──► 该站 OPEN=true ──► 门扇跟随门进度打开、交出碰撞
```

### 6.5 资源与替换接口

| 想替换 | 改哪里 | 详见 |
| --- | --- | --- |
| 轨道模型 | `assets/easyelevator/models/block/elevator_rail.json` + `ElevatorRailBlock.getOutlineShape`（轮廓必须覆盖模型可见范围，右键才点得到） | [ASSET_INTEGRATION.md](ASSET_INTEGRATION.md) |
| 楼层门**门框**（常驻） | `assets/easyelevator/models/block/landing_door_frame_*.json`（底行 `*_bottom`）+ `blockstates/call_button.json` + `tools/generate_data.py` 的 `FRAME_MODELS` | 同上 |
| 楼层门**门扇**（可动） | `LandingDoorRenderer` + `LandingDoorGeometry`（几何与碰撞同源） | 同上 |
| 轿厢模型（三种共用） | `CabinRenderer`：`drawStandardShell` / `drawObservationShell` / `drawDoorway` / `drawLeaves` / `drawObservationGlass` + 内饰表 `STANDARD_PARTS` / `OBSERVATION_PARTS` | 同上 |
| 轿厢材质 | `textures/entity/cabin.png`（4×4 图集）+ `CabinRenderer.Mat` / `MATERIAL_UV`；方块贴图见 `textures/block/` | 同上 |
| 观光玻璃颜色/通透度 | `GlassLayers` 绑定原版玻璃贴图，顶点色白色；透明像素丢弃，无整面染色 | 同上 |
| 厢内照明 | `CabinLighting.surface/lamp` 逐面计算：舱内补光上限 13、灯罩仅向下 15，外壳与玻璃保留环境光 | 同上 |
| 音效音频 | `assets/easyelevator/sounds.json` 的 `sounds` 数组 + ogg 文件 | 同上 |
| 门动画取值 | 轿厢 `doorProgress(tickDelta)`；楼层门 `openProgress(tickDelta)`，0 关 1 开 | 同上 |

### 6.6 数据接口（方块状态 / 同步字段 / NBT）

**楼层门方块状态**（`LandingDoorBlock`）：`facing`(north/east/south/west)、`column`(0..2)、`level`(0..2)、`open`(true/false)。`open` 只表示**联锁是否解除**，外观与碰撞由连续进度决定。

**轨道方块状态**：`facing`。

**实体同步字段**：见 5.3 表格（`PHASE/DOOR/FACING/TARGET_Y/FLOOR`）。这些是客户端唯一可依赖的服务端状态；`railX`/`railZ` **不在其中**（客户端恒 0，所以服务端与客户端都改按世界坐标匹配轿厢）。

---

## 7. 参数介绍

> 完整逐项数值、单位与联动说明以 [PARAMETERS.md](PARAMETERS.md) 为准；本节只列**改代码时必须知道的核心参数与耦合关系**。

### 7.1 核心常量（`logic/ElevatorParameters`）

| 常量 | 值 | 单位 | 作用与耦合 |
| --- | --- | --- | --- |
| `TICKS_PER_SECOND` | 20 | 刻/秒 | 仅换算用 |
| `SPEED` | 0.20 | 格/刻 | 4 格/秒**巡航上限**；普通与观光 |
| `HIGH_SPEED` | `SPEED*2.5` = 0.50 | 格/刻 | 10 格/秒巡航上限；高速。约束：`speed + POSITION_EPSILON ≤ 1` |
| `CRUISE_RAMP_TICKS` | 32 | 刻 | 从静止加到本型号巡航速度的时间；加速度上限 = `速度/它`、jerk 上限 = `2·加速度/它`（`MotionProfile.forCruiseSpeed`） |
| `MAX_ACCELERATION` | 0.15 | 格/刻² | 加速度**硬上限**（60 格/秒²），只在 `CRUISE_RAMP_TICKS` 被调得极小时起作用 |
| `POSITION_EPSILON` | 1e-7 | 格 | 服务端到站/请求判定容限 |
| `SYNC_POSITION_EPSILON` | 0.01 | 格 | **仅**客户端门扇进度与显示判定 |
| `DOOR_TICKS` | 20 | 刻 | 开关门各 1 秒；不随速度变 |
| `DWELL_TICKS` | 40 | 刻 | 最短开门停留 2 秒；到点自动关门（无请求也关）；不随速度变 |
| `MAX_REQUESTS` | 128 | 条 | **选站队列与厅外呼叫表各自独立**的上限 |
| `RIDER_WAIT_TICKS` | `30*20` = 600 | 刻 | 读档等待名册乘客归位上限 |
| `CABIN_FRONT_Z` | 1.3 | 格 | 轿厢本地正面；与楼层门后缘 1.3125 留 0.0125 间隙防闪烁 |
| `CABIN_DOOR_BACK_Z` | `1.3-0.2` = 1.1 | 格 | 门扇背面 |
| `MOTION_STALE_TICKS` | 10 | 刻 | 连续超过 10 刻收不到新样本就不再托举乘客（轿厢保持最后收到的目标高度，绝不外推） |
| `MOTION_SNAP_DISTANCE` | 4.0 | 格 | 单包位移超过 4.0 格视为传送/归位：轿厢照常对齐，但不把乘客随这个跳变一起搬 |
| `MOTION_SETTLE_TICKS` | 4 | 刻 | 停车后补发静止样本数（字面量 4，不再由插值延迟派生） |
| `EVENT_VOLUME` / `RUNNING_VOLUME` / `SOUND_PITCH` | 0.8 / 0.6 / 1.0 | — | 音效参数 |

### 7.2 派生公式

| 量 | 公式 |
| --- | --- |
| 速度上限（格/秒） | `SPEED × TICKS_PER_SECOND`，例如 0.20 × 20 = 4 |
| 加速度上限（格/秒²） | 工作点 `(速度 / CRUISE_RAMP_TICKS) × TICKS_PER_SECOND²`：普通/观光 0.00625 × 400 = 2.5、高速 0.015625 × 400 = 6.25；`MAX_ACCELERATION × TICKS_PER_SECOND²` = 0.15 × 400 = 60 只是**硬上限** |
| 加/减速段时长（刻） | `CRUISE_RAMP_TICKS`（本型号从静止加到巡航速度的时间；实际行程到不了巡航速度时按比例缩短） |
| 加/减速段距离（格） | 满速段为 `速度 × CRUISE_RAMP_TICKS / 2`：普通/观光 3.2 格、高速 8.0 格 |
| 门单程 | `DOOR_TICKS / 20` 秒 |
| 最短停站总时长 | S 形运行段 + 关门 + `DWELL_TICKS/20` + 开门 |
| 轿厢中心 | 轨道中心 + 朝向前方 **2 格** |
| 楼层门根方块 | 轨道 + 朝向前方 `RAIL_DISTANCE = 3` 格，同 Y |
| 单扇门行程 | `(DOOR_WIDTH - 2*FRAME - SEAM)/2 = 20.5`（1/16 格）= 1.28125 格 |
| 观光隔两面玻璃透过率 | 透明像素不叠色，仅原版玻璃边框与亮纹局部遮挡 |

### 7.3 参数联动「改一处、跟着改」清单

| 改动 | 必须一起改 |
| --- | --- |
| 提高 `HIGH_SPEED` | 确认 `speed + POSITION_EPSILON ≤ 1`（否则单刻可能跨过整格站点），并进游戏复核加/减速手感与到站对齐 |
| 改 `CRUISE_RAMP_TICKS` / `MAX_ACCELERATION` | 加/减速段的时长、距离与加速度峰值都由 `CRUISE_RAMP_TICKS` 与巡航速度推出（`MAX_ACCELERATION` 只是硬上限）；注意"舒服"与"快"是此消彼长：过渡时间越长，段越长、加速度峰值越低、每趟越慢 |
| 改门尺寸 | `LandingDoorGeometry` 常量、`blockstates/call_button.json`、门框模型、`collisionBoxes`、`doorwayBlocked` 区域、`spaceClear` 几何、`RAIL_DISTANCE` 与井道预留，以及渲染同步 |
| 改轿厢尺寸 | 实体 `dimensions`、`localBox`/`collisionBoxes`、`containsPassenger` 边界、`doorwayBlocked`、渲染几何、`ElevatorLine.cabins` 包围盒 |
| 改选站面板布局 | `PanelLayout` + `ElevatorScreen` 常量 + `MAX_COLUMNS/MAX_ROWS` |
| 新增网络包 | `PayloadTypeRegistry` 注册（S2C/C2S 不能混）+ 客户端/服务端处理器 + 编解码对称 |
| 改注册 ID | **不要改**（旧存档、旧物品、旧配方全部失效） |

---

## 8. 存档格式与版本兼容

### 8.1 轿厢实体 NBT（`AbstractCabinEntity`）

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `RailX` / `RailZ` | int | 线路水平坐标 |
| `Facing` | int | 朝向 id；非法/竖直朝向读档降级 NORTH |
| `Phase` | String | 枚举名；不存在（旧存档/损坏）→ `BLOCKED` |
| `Door` | float | 门进度，restore 时夹到 0..1 |
| `Target` | long | 站点打包坐标；**无目标时不写**，读档用 `contains` 判定 |
| `Queue` | List<Compound> | 每项 `Button`(long)；读档去重 + 上限 `MAX_REQUESTS` |
| `HallCalls` | List<Compound> | 每项 `Button`(long) + `Up`(boolean)；旧存档无此键 → 空表 |
| `Travel` | String | 服务方向；旧存档/非法 → `NONE` |
| `Riders` | List<Compound> | 每项 `Most`/`Least`(long UUID) + `X`/`Y`/`Z`(double 相对偏移)；旧存档无此键 → 不等待 |

实体世界坐标由原版实体保存流程负责。

### 8.2 楼层门方块实体 NBT（`LandingDoorBlockEntity`）

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `BaseFloor` | boolean | 只写 true；标记本门为线路的「1 层」。拆门即失效，默认回到「最低站点 = 1 层」 |
| `ArrivalSound` | boolean | 本门到站时是否播放提示音；**只在玩家关掉（`false`）时才写**——出厂默认是发声，所以默认设置的门不写这个键，存档体积与加此功能之前一致 |
| `ArrivalSoundChoice` | int | 到站提示音的选项序号（见 `logic/DoorSounds`）；**只在偏离 `DoorSounds.DEFAULT` 时写** |

**旧存档读回口径**：`writeNbt` 只写偏离出厂默认的那一侧，整份默认设置的门存档体积与加此功能之前逐字节一致；
`readNbt` 对**缺失的开关回退到 `DoorArrivalSound.DEFAULT.enabled()`（发声）**、缺失的序号按 `0`（默认音效）。
因此**本功能之前放置的门升级后照旧会响**，不需要逐扇重设；只有玩家在面板里主动关掉才会写入 `ArrivalSound=false`。
（`nbt.getBoolean` 的缺省值是 `false`，若直接用它就会把"键不存在"误判成"玩家关掉了"——那正是行为回退。）

### 8.3 兼容性规则

1. **`call_button` ID 复用是刻意的**：楼层电梯门沿用旧按钮 ID，旧存档方块状态、旧物品堆、旧配方/掉落表继续有效；但旧位置不再是有效站点，需按新规则（距轨道 3 格）重放。
2. `cabin` 就是普通型号，行为不变；高速/观光是新实体 ID 与新物品，不会与旧存档冲突。
3. **型号不写进存档**：由实体类型唯一决定，避免「存档里的速度被写坏」。
4. 读档时 `restore` 把 `MOVING` 降级为 `BLOCKED` 且 `door=0`，由下一 tick 先校验线路再恢复运行。
5. 带着 `Riders` 的存档会先静止等人，上限 `RIDER_WAIT_TICKS`。
6. **到站音效字段缺失按出厂默认处理**：本功能之前放置的门存档里没有 `ArrivalSound` / `ArrivalSoundChoice`，`readNbt` 对缺失的开关回退到"发声"，因此**旧门升级后照旧会响**，不需要逐扇重设；只有玩家在面板里主动关掉才写入 `ArrivalSound=false`（见 8.2）。
7. **唯一的单向迁移**：本功能早期版本的 `writeNbt` 写反了（发声时写 `true`、关掉时什么都不写），那种存档里"关掉"没有留下痕迹，读回来会恢复成发声一次，需要在面板里再关一次。

---

## 9. 构建与运行

完整说明见 [BUILD_AND_PACKAGING.md](BUILD_AND_PACKAGING.md) 与 [TESTING.md](TESTING.md)；速查：

| 目的 | 命令 |
| --- | --- |
| 构建 | `./gradlew.bat build`（需 JDK 21；成品 `build/libs/easyelevator-2.2.0.jar`） |
| 本机快捷构建 | `./tools/build.ps1 -Jdk <JDK21> -Task build` |
| 发布包 / 工程包 | `packageRelease` / `packageProject`（`build/distributions/`） |
| 开发启动 | `./gradlew.bat runClient` |
| 可选回归测试 | `./tools/build.ps1 -Jdk <JDK21> -Task runGameTest`（脚本自动补 `-PriderTests`；等价于 `./gradlew.bat -PriderTests runGameTest`） |

**验收方式：** 工程内没有 `src/test`；`test` / `testClasses` 任务在 `build.gradle` 里被 `enabled = false` 关闭，
因此 `./gradlew.bat build` 只编译与打包。改完代码后按 [TESTING.md](TESTING.md) 的人工清单进游戏逐项确认——
重点是乘坐手感、到站对齐、门联锁与防夹、以及多人同时乘坐。另有一套用 `-PriderTests` 可选开启的 GameTest 源集
`src/gametest/`（12 个 `@GameTest` + 1 个客户端冒烟测试，测试模组永不进发行包），只覆盖乘客移动 / 历史补偿这类
可自动化的回归，详见 3.2。

---

## 10. 扩展开发指南

### 10.1 新增第四种轿厢型号

1. 新建 `entity/XxxCabinEntity extends AbstractCabinEntity`，构造传速度，覆写 `cabinItem()`（需要则覆写 `glassWalls()`）。
2. 在 `Easyelevator` 注册 `EntityType`（照抄现有维度/追踪参数）与 `CabinItem`，并加入物品栏 `entries`。
3. 需要新外观时在 `CabinRenderer` 分支里加绘制方法；碰撞与井道**不要动**（沿用 `collisionBoxes()`）。
4. `EasyelevatorClient` 加一行 `EntityRendererRegistry.register(...)`。
5. 加模型/物品图标/语言键/配方，并按人工清单进游戏验收。

### 10.2 调速度

只改 `ElevatorParameters`（或新子类的构造参数）。**门时序不受影响**。确认 `speed + POSITION_EPSILON ≤ 1`，并进游戏复核到站是否精确对齐楼层。

### 10.3 改门尺寸 / 门面结构

统一改 `LandingDoorGeometry` 的常量与 `leafBox/doorBox/shape`，然后：方块状态模型（`blockstates/call_button.json` + 门框模型）、`RAIL_DISTANCE`、`collisionBoxes`、`doorwayBlocked`、`spaceClear` 全部同步。**渲染与碰撞必须继续同源**。

### 10.4 换模型 / 贴图 / 音效

见 [ASSET_INTEGRATION.md](ASSET_INTEGRATION.md)。要点：门框走方块模型，门扇走方块实体渲染器；轿厢是硬编码的几何数据表（`BoxMesh` + `CabinRenderer` 的两张 PARTS 表），换模型即改那两张表或换 `CabinRenderer`。

**模型与贴图都由脚本生成，别手改单个文件**：`python tools/generate_art.py` 写全部模型与贴图（并做几何自检），`python tools/generate_data.py` 写方块状态/语言/配方/掉落表/音效钩子。只换外观时改脚本再跑，两个脚本都可重复运行。

### 10.5 改调度策略

只在 `logic/ElevatorController` 内改，保持「纯 Java」与确定性，并在 `docs/TESTING.md` 增补对应的人工验收场景（没有常驻测试套件；另有 `-PriderTests` 可选开启的 `src/gametest` 乘客回归 GameTest，见 3.2 与第 9 节）。重点函数：`select / retarget / insertOrdered / nearest / oldestAheadHallCall / serveStation`。

### 10.6 新增网络包

1. 定义 `record ... implements CustomPayload`（`ID` + `CODEC` + `getId`）。
2. 在 `register()` 里按方向注册（`playS2C` / `playC2S`）。
3. 服务端处理器必须`context.server().execute(...)`回到主线程，并**重新校验一切客户端输入**。
4. 客户端处理器必须`context.client().execute(...)`回到客户端线程。
5. 编解码顺序与上限校验（参考 `readPositions`）。

### 10.7 新增扩展事件

在 `api/ElevatorEvents` 加接口与 `Event<T>` 字段，触发点放在 `AbstractCabinEntity.tick()` 的状态写回之后，保证订阅者看到自洽状态。

---

## 11. 常见故障定位

| 现象 | 最可能的原因 | 去哪里看 |
| --- | --- | --- |
| 客户端崩溃 `IllegalStateException: Not building!` | 在实体渲染器里先取 A 层缓冲、切到 B 层后又往 A 写顶点 | `CabinRenderer` 类注释的层顺序约束；`LandingDoorRenderer` 同规则 |
| 轿厢门开了、楼层门不动 | 用 `railX()/railZ()` 匹配轿厢（客户端恒 0） | `dockedCabin` / `cabinOf`：必须按车体世界坐标匹配 |
| 坐在观光轿厢里看不见楼层门 | 检查是否误用整面写深度的半透明层 | `GlassLayers` 采用原版 cutout，透明像素必须丢弃 |
| 门与门框/玻璃与框架闪烁 | 两个面共面 | `CABIN_FRONT_Z` 内收 0.0125 格；观光玻璃四周留 0.01 格缝 |
| 楼层门在还看得见时突然消失 | 方块实体渲染器默认按根方块那格轮廓剔除，门扇会滑出该格 | `LandingDoorRenderer.rendersOutsideBoundingBox = true` |
| 面板刚打开就自动关闭（尤其下行） | 用了服务端的严格 `containsPassenger` 判定 | `ElevatorScreen.staysInside`（宽松 UI 判定） |
| 面板上「当前层」绿框逐层闪 | 只看高度判断停稳 | `ElevatorScreen.stopped/parkedAt`：必须结合 Phase |
| 运行途中保存退出后乘客掉出电梯 | 乘客名册丢失或等待窗口被绕过 | `tickPassengers`、`Riders` NBT、`RIDER_WAIT_TICKS` |
| 高速车跳过整格站点 | 单刻位移 ≥ 1 格 | 约束 `speed + POSITION_EPSILON ≤ 1` |
| 断轨/障碍后停在半空 | `BLOCKED` 是暂停不是失败。这时**开门键可用**，乘客可开门脱困；恢复后先关门再继续原行程（见 `PARAMETERS.md` 4.1） | `controller.tick` 末尾的故障脱困段、`canResume` |
| 按钮一直红着 | 到站只在对应服务方向清扫；反方向呼叫会保留 | `serveStation` 与 `targetHallDirection` |
| 按了楼层却被径直开过 | 缺 `retarget` 的顺路改道 | `controller.retarget` |
| 后按的楼层抢走目的地 | 派车取「最近」而非「最早」 | `select` 第 ③ 步用 `oldestAheadHallCall`，不是距离 |
| 站点扫不到 / 线路被截断 | 门不完整、朝向不一致、区块未加载 | `LandingDoorBlock.complete`、`ElevatorLine.matches`（未加载一律 false） |
| 面板/门框显示 `--` | `FLOOR==0`：尚未经过任何站点或线路无效 | `FloorIndicator.floorNumber` 返回 0 |

---

## 12. 附录

### 12.1 注册 ID 一览

```
方块/物品   easyelevator:elevator_rail         电梯轨道
           easyelevator:call_button           楼层电梯门（沿用旧 ID）
方块实体   easyelevator:landing_door
实体/物品  easyelevator:cabin                 普通
           easyelevator:high_speed_cabin      高速
           easyelevator:observation_cabin     观光
物品栏     easyelevator:main
音效       easyelevator:elevator_running / elevator_arrival / elevator_arrival_custom
自定义包   S2C（network/ElevatorNetworking.java，7 个）
           easyelevator:motion_frame / open_panel / panel_state / open_hall_panel /
           hall_panel_state / open_door_panel / door_sound_data
           C2S（network/ElevatorNetworking.java，7 个）
           easyelevator:select_stop / door_command / hall_call_button /
           door_sound_command / set_base_floor / door_sound_upload / request_door_sound
           C2S（独立文件 network/RiderMove.java）
           easyelevator:rider_move            乘客移动包（携带轿厢样本编号，不在 ElevatorNetworking 里）
```

ID 与 §6.3 协议表逐一对应：`ElevatorNetworking` 共 **14 个** payload（7 S2C + 7 C2S），另有独立的 `rider_move`。

### 12.2 翻译键一览（完整列表见 [lang/en_us.json](../src/main/resources/assets/easyelevator/lang/en_us.json)）

| 前缀 | 用途 |
| --- | --- |
| `itemGroup.easyelevator` | 物品栏组名 |
| `block.easyelevator.*` / `item.easyelevator.*` / `entity.easyelevator.*` | 方块/物品/实体名 |
| `message.easyelevator.*` | actionbar 与提示（无轿厢/多轿厢/无效站点/已排队/受阻/进入提示/门按钮失败/基准层已设/门放置说明） |
| `screen.easyelevator.*` | 标题、站点、状态、页数、空列表、开门/关门、翻页、厅外面板与悬停 |
| `phase.easyelevator.*` | `open/closing/moving/opening/blocked`（面板状态行） |
| `status.easyelevator.*` | `up/down/idle`（箭头/状态语义） |
| `subtitles.easyelevator.*` | 3 个音效字幕 |

### 12.3 关键常量与几何速查

| 量 | 值 |
| --- | --- |
| 轿厢占地 / 净高 | 3×3 格，地板 0.2、顶板 2.8..3.0；净高 2.6 格 |
| 轿厢中心 | 轨道朝向前方 2 格 |
| 楼层门 | 3 宽 × 3 高 × 3/16 厚；根方块 = 底部中心 |
| 楼层门位置 | 轨道朝向前方 3 格，同 Y |
| 门框厚度 / 门楣高 | 3/16 格 |
| 门缝 SEAM | 1/16 格 |
| 单扇行程 | 20.5/16 ≈ 1.28125 格 |
| 轿厢正面前缘 | 局部 Z = 1.3；门背面 1.1 |
| 楼层门后缘 | 局部 Z = 1.3125（与轿厢正面留 0.0125 格） |
| 防夹区域 | X ±1.3，Y 0.2..2.8，Z 0.95..1.6 |
| 面板按键 | 20×20 像素，间距 4，单页 ≤ 8×8=64 |
| 面板分区 | 表头 64 + 1 分隔线 + 凹底 ±6 + 按键区 + 1 分隔线 + 页脚 40 |
| 方向箭头闪烁 | 亮 0.5 s / 灭 0.5 s（墙钟毫秒） |

### 12.4 接入新设备的检查清单

- [ ] 新增/修改的 `logic` 类保持无 Minecraft 依赖（便于单独 `javac` 验证）
- [ ] 服务端与客户端都改了（网络字段、渲染、语言键）
- [ ] 所有客户端输入在服务端重新校验
- [ ] 不新增区块加载
- [ ] `./gradlew.bat build` 成功
- [ ] 按 `docs/TESTING.md` 人工清单验收（上下各一次、运行中存档重进、防夹、观光玻璃视角）
- [ ] 文档同步：`PARAMETERS.md`（改常量）、`ASSET_INTEGRATION.md`（改素材）、`README.md`（改玩法）

---

*本手册由代码通读整理，覆盖版本 2.2.0。改动架构或接口后请同步更新本文对应章节。*
