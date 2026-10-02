# 模型、音效与门动画接口

## 模型

方块模型可直接替换资源 JSON，注册 ID 不变：

| 资源 | 位置（相对 `src/main/resources/assets/easyelevator/`） |
| --- | --- |
| 轨道 | `models/block/elevator_rail.json` |
| 楼层门**门框**（常驻的左右立柱与门楣） | `models/block/landing_door_frame_left.json`、`landing_door_frame_right.json`、`landing_door_frame_top.json`（中列门楣）、`landing_door_frame_top_left.json` / `landing_door_frame_top_right.json`（顶行的立柱+门楣）、`landing_door_frame_middle.json`（门洞，空模型） |
| 楼层门物品图标（关门状态的整扇门；方块本身已不再使用它） | `models/block/call_button.json` |
| 物品显示 | `models/item/elevator_rail.json`、`call_button.json`、`cabin.json` |
| 门框白色贴图（亮白） | `textures/block/blank.png` |
| 门扇白色贴图（略暗，用于区分框与扇） | `textures/block/blank_dark.png` |
| 轿厢白色贴图 | `textures/entity/cabin.png` |

轿厢是移动实体，**不是可直接替换 block JSON 的方块**。当前 `CabinRenderer` 用白色立方体绘制地板、顶板、侧壁、两扇门及内部面板；替换为你的 Java/Blockbench 实体模型时，保留注册的 `CabinRenderer`，在其 `render` 内调用你的模型即可。如果将来采用 GeckoLib，需要自行增加适用于 1.21.1 的依赖及动画控制器；当前实现不依赖动画库。

轿厢局部坐标：原点在底部中心，X 左右，Y 向上，+Z 为门口。井道预留包围范围仍为 3×3×3 格；实体模型及碰撞范围 X=-1.5..1.5、Z=-1.5..1.3、Y=0..3。内部地板面 Y=0.2，侧壁内缘 ±1.3，天花板内侧 Y=2.8。

楼层门及门框占轿厢局部 Z=1.3125..1.5。`ElevatorParameters.CABIN_FRONT_Z=1.3` 将地板、顶板、侧壁和轿厢门的前缘统一内收，与楼层门留出 0.0125 格间隙，避免重叠面闪烁。轿厢门厚度仍为 0.2 格，后缘 `CABIN_DOOR_BACK_Z=1.1`。渲染与碰撞共用这些参数；替换模型时也必须保持两层门和外壳之间的间隙。

楼层门采用3×3多方块白模。方块状态 `facing` 表示朝向、`column=0/1/2` 表示横向位置、`level=0/1/2` 表示高度、`open` 是服务端联锁状态（客户端只读，两个取值现在指向同一套门框模型，不再切换外观）。只有 `column=1,level=0` 的底部中心定义站点；资源组合见 `blockstates/call_button.json`。门物品/方块 ID 保留 `easyelevator:call_button`。

门面拆成"门框 + 两扇可动门扇"：门框是常驻方块模型（亮白 `blank` 贴图），顶行的左右两列是"门楣 + 立柱"的组合模型（`landing_door_frame_top_left` / `top_right`），立柱一直顶到门楣，因此整圈门框是连着的、不会看起来像三段；门扇由 `LandingDoorRenderer` 按连续进度绘制（暗白 `blank_dark` 贴图），几何集中定义在 `block/LandingDoorGeometry.java`，碰撞形状、轮廓与渲染共用同一份数据，因此不会出现画面与碰撞各一套。门扇进度来自根方块的方块实体 `LandingDoorBlockEntity`，逐刻等于在站轿厢的门进度，开关门与轿厢门完全同步；两扇门扇向两侧收拢滑入门框，关门时各占门洞一半、正中只留 1/16 格细门缝（`LandingDoorGeometry.SEAM`，改成 0 即完全贴合）。

替换门扇外观时改渲染器里的长方体或换成骨骼模型即可，但必须保留 `LandingDoorBlock` 的服务端联锁与完整性检查，并让碰撞取自 `LandingDoorGeometry`——不要让动画自己决定能否打开，也不要让碰撞与渲染分叉。

模型尺寸变化时同步调整 `CabinEntity.collisionBoxes`、`containsPassenger`、门口检测体积、实体注册 dimensions 和线路中心偏移。仅改贴图/外观且不改尺寸时，无需改运行逻辑。

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

改动画时长、运行速度、开门停留时间：修改 `ElevatorParameters.DOOR_TICKS / SPEED / DWELL_TICKS`；完整参数见 [PARAMETERS.md](PARAMETERS.md)。

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
