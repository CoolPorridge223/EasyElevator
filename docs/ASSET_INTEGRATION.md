# 模型、音效与门动画接口

## 模型

方块模型可直接替换资源 JSON，注册 ID 不变：

| 资源 | 位置（相对 `src/main/resources/assets/easyelevator/`） |
| --- | --- |
| 轨道 | `models/block/elevator_rail.json` |
| 楼层门**门框**（常驻的左右立柱与门楣） | `models/block/landing_door_frame_left.json`、`landing_door_frame_right.json`、`landing_door_frame_top.json`（中列门楣）、`landing_door_frame_top_left.json` / `landing_door_frame_top_right.json`（顶行的立柱+门楣）、`landing_door_frame_middle.json`（门洞，空模型） |
| 楼层门物品图标（关门状态的整扇门；方块本身已不再使用它） | `models/block/call_button.json` |
| 物品显示 | `models/item/elevator_rail.json`、`call_button.json`、`cabin.json`、`high_speed_cabin.json`、`observation_cabin.json` |
| 门框白色贴图（亮白） | `textures/block/blank.png` |
| 门扇白色贴图（略暗，用于区分框与扇） | `textures/block/blank_dark.png` |
| 高速轿厢物品图标占位贴图（淡金） | `textures/block/blank_speed.png` |
| 观光轿厢物品图标占位贴图（淡蓝，与玻璃顶点色同色系） | `textures/block/blank_glass.png` |
| 厅外呼叫面板（右键楼层门弹出的 ▲ / ▼ / 关闭 三个按钮） | 纯 Java 绘制，见 `client/LandingDoorScreen`；按钮文字用的是 ▲(U+25B2) / ▼(U+25BC) 字符，由原版字体的 Unicode 回退提供，文案键为 `screen.easyelevator.hall_title / hall_up / hall_down / hall_station / close` |
| 轿厢白色贴图（三种型号共用） | `textures/entity/cabin.png` |

轿厢是移动实体，**不是可直接替换 block JSON 的方块**。当前 `CabinRenderer` 用白色立方体绘制地板、顶板、侧壁、两扇门及内部面板；替换为你的 Java/Blockbench 实体模型时，保留注册的 `CabinRenderer`，在其 `render` 内调用你的模型即可。如果将来采用 GeckoLib，需要自行增加适用于 1.21.1 的依赖及动画控制器；当前实现不依赖动画库。

`CabinRenderer<T extends AbstractCabinEntity>` 同时服务三种轿厢，外观只分两个分支：**普通与高速**走 `drawStandard`（完全不透明，两者逐面相同，因此高速型号没有独立模型）；**观光**走 `drawObservationShell` + `drawObservationGlass`（地板、顶板、四根角柱不透明，左右侧墙 / 后墙 / 两扇门各画一张 `BoxMesh.planeX/planeZ` 的**零厚度单面玻璃**，正反都可见，颜色常量是 `GLASS_COLOR` / `GLASS_DOOR_COLOR`）。单面而不是薄板，是因为半透明层不剔除背面、薄板会正反各叠一次而发灰；单面必须画在禁止剔除的层上。玻璃通透度全部来自顶点色的 alpha，贴图仍是同一张全白的 `textures/entity/cabin.png`，所以换玻璃观感只需要改这两个常量或换贴图，不必新增资源。玻璃面四周都与不透明结构留 0.01 格缝，避免共面时浮点深度差造成接缝闪烁；**碰撞不跟着变**——外壳仍由 `AbstractCabinEntity.collisionBoxes()` 按 0.2 格厚的实心墙生成。

玻璃用的是本项目自建的渲染层 `CabinRenderer.GLASS_LAYER`（`easyelevator_cabin_glass`），它照抄原版 `entity_translucent`，只把写掩码改成"只写颜色、不写深度"。**不要换回 `RenderLayer.getEntityTranslucent`**：世界渲染顺序是实体在前、方块实体在后，玻璃一旦写深度，之后绘制的楼层门（方块实体渲染器）会被深度测试整片剔除，表现为"坐在观光轿厢里看不见每层的电梯门"。

轿厢局部坐标：原点在底部中心，X 左右，Y 向上，+Z 为门口。井道预留包围范围仍为 3×3×3 格；实体模型及碰撞范围 X=-1.5..1.5、Z=-1.5..1.3、Y=0..3。内部地板面 Y=0.2，侧壁内缘 ±1.3，天花板内侧 Y=2.8。

楼层门及门框占轿厢局部 Z=1.3125..1.5。`ElevatorParameters.CABIN_FRONT_Z=1.3` 将地板、顶板、侧壁和轿厢门的前缘统一内收，与楼层门留出 0.0125 格间隙，避免重叠面闪烁。轿厢门厚度仍为 0.2 格，后缘 `CABIN_DOOR_BACK_Z=1.1`。渲染与碰撞共用这些参数；替换模型时也必须保持两层门和外壳之间的间隙。

楼层门采用3×3多方块白模。方块状态 `facing` 表示朝向、`column=0/1/2` 表示横向位置、`level=0/1/2` 表示高度、`open` 是服务端联锁状态（客户端只读，两个取值现在指向同一套门框模型，不再切换外观）。只有 `column=1,level=0` 的底部中心定义站点；资源组合见 `blockstates/call_button.json`。门物品/方块 ID 保留 `easyelevator:call_button`。

门面拆成"门框 + 两扇可动门扇"：门框是常驻方块模型（亮白 `blank` 贴图），顶行的左右两列是"门楣 + 立柱"的组合模型（`landing_door_frame_top_left` / `top_right`），立柱一直顶到门楣，因此整圈门框是连着的、不会看起来像三段；门扇由 `LandingDoorRenderer` 按连续进度绘制（暗白 `blank_dark` 贴图），几何集中定义在 `block/LandingDoorGeometry.java`，碰撞形状、轮廓与渲染共用同一份数据，因此不会出现画面与碰撞各一套。门扇进度来自根方块的方块实体 `LandingDoorBlockEntity`，逐刻等于在站轿厢的门进度，开关门与轿厢门完全同步；两扇门扇向两侧收拢滑入门框，关门时各占门洞一半、正中只留 1/16 格细门缝（`LandingDoorGeometry.SEAM`，改成 0 即完全贴合）。

替换门扇外观时改渲染器里的长方体或换成骨骼模型即可，但必须保留 `LandingDoorBlock` 的服务端联锁与完整性检查，并让碰撞取自 `LandingDoorGeometry`——不要让动画自己决定能否打开，也不要让碰撞与渲染分叉。

模型尺寸变化时同步调整 `AbstractCabinEntity.collisionBoxes`、`containsPassenger`、门口检测体积、实体注册 dimensions 和线路中心偏移。仅改贴图/外观且不改尺寸时，无需改运行逻辑；观光型号的玻璃只是绘制分支，改它同样不需要动任何服务端逻辑。

## 开关门动画

```java
float progress = cabin.doorProgress(tickDelta); // 0=全关, 1=全开
var state = cabin.phase();
// 示例：将 progress 映射到左右门骨骼的位移或旋转。
// leftDoor.x = closedLeftX - progress * travel;
// rightDoor.x = closedRightX + progress * travel;
```

状态包括 OPEN、CLOSING、MOVING、OPENING、BLOCKED。状态及门进度由服务端计算并同步，进入观察范围的玩家也能看到当前进度。白模门使用横向收回占位动画；换模型后可使用骨骼平移或动画时间轴。渲染动画不能直接触发移动；安全联锁以服务端进度为准。

楼层门的门扇现在也走同一套连续进度，替换外观时读同一个值即可：

```java
// 渲染楼层门门扇（在方块实体渲染器里调用）：进度已经在按刻采样，这里只做插值
float progress = doorEntity.openProgress(tickDelta); // 0=全关, 1=全开，等于在站轿厢的门进度
Box left  = LandingDoorGeometry.leafBox(facing, progress, false); // 相对根方块原点，单位格
Box right = LandingDoorGeometry.leafBox(facing, progress, true);  // 全开时返回 null（已收进门框）
```

进度是"已同步的轿厢门进度 + 已同步的 `open` 方块状态"的纯函数，客户端与服务端各自就地算出，因此不需要额外的同步包。方块状态 `open` 只表示联锁是否解除（能否交出真实碰撞），不表示门扇位置；门扇位置一律取 `LandingDoorBlockEntity.openProgress`。

改动画时长、运行速度、开门停留时间：修改 `ElevatorParameters.DOOR_TICKS / SPEED / HIGH_SPEED / DWELL_TICKS`；完整参数见 [PARAMETERS.md](PARAMETERS.md)。其中 `SPEED` 是普通与观光轿厢的步长、`HIGH_SPEED` 是高速轿厢的步长，两者都在实体构造时注入各自的 `ElevatorController` 实例。

1.1.0 的 `CabinRenderer.render` 在模型矩阵上应用 `CabinMotion.renderY` 的平滑高度偏移。替换模型时保留这一步，保证轿厢与本地乘客镜头沿同一轨迹显示。

服务端事件可注册其他行为：

```java
ElevatorEvents.PHASE_CHANGED.register((cabin, before, after) -> {
    // 服务端逻辑；不能在这里直接调用客户端渲染类。
});
ElevatorEvents.ARRIVED.register((cabin, floorY) -> {
    // 到达站点；随后开始开门。
});
```

## 音效

已注册四个稳定 ID，默认 `sounds: []`，因此运行时保持静音：

- `easyelevator:elevator_running`：客户端随轿厢位置循环，离开 MOVING 状态立即停止。
- `easyelevator:elevator_arrival`：服务端到站时播放一次。
- `easyelevator:door_open`：开始开门时播放一次。
- `easyelevator:door_close`：开始关门时播放一次。

将四个 `.ogg`（推荐单声道，以保留 3D 方位）放进 `assets/easyelevator/sounds/`，例如：

```json
{
  "elevator_running": {
    "subtitle": "subtitles.easyelevator.elevator_running",
    "sounds": [{"name": "easyelevator:elevator_running", "stream": false}]
  },
  "elevator_arrival": {
    "subtitle": "subtitles.easyelevator.elevator_arrival",
    "sounds": ["easyelevator:elevator_arrival"]
  },
  "door_open": {
    "subtitle": "subtitles.easyelevator.door_open",
    "sounds": ["easyelevator:door_open"]
  },
  "door_close": {
    "subtitle": "subtitles.easyelevator.door_close",
    "sounds": ["easyelevator:door_close"]
  }
}
```

运行音频制作成首尾无缝短循环。也可通过资源包覆盖上述资源；替换后重载资源或重启游戏。资源生成脚本 `tools/generate_placeholders.py` 会覆盖占位资源，导入正式素材后不要再次运行。
