# 模型、音效与门动画接口

## 模型

方块模型可直接替换资源 JSON，注册 ID 不变：

| 资源 | 位置（相对 `src/main/resources/assets/easyelevator/`） |
| --- | --- |
| 轨道 | `models/block/elevator_rail.json` |
| 楼层门关闭部分（保留旧 ID） | `models/block/call_button.json` |
| 楼层门打开后的左/右/上框及空心部分 | `models/block/landing_door_open_left.json`、`landing_door_open_right.json`、`landing_door_open_top.json`、`landing_door_open_middle.json` |
| 物品显示 | `models/item/elevator_rail.json`、`call_button.json`、`cabin.json` |
| 方块白色贴图 | `textures/block/blank.png` |
| 轿厢白色贴图 | `textures/entity/cabin.png` |

轿厢是移动实体，**不是可直接替换 block JSON 的方块**。当前 `CabinRenderer` 用白色立方体绘制地板、顶板、侧壁、两扇门及内部面板；替换为你的 Java/Blockbench 实体模型时，保留注册的 `CabinRenderer`，在其 `render` 内调用你的模型即可。如果将来采用 GeckoLib，需要自行增加适用于 1.21.1 的依赖及动画控制器；当前实现不依赖动画库。

轿厢局部坐标：原点在底部中心，X 左右，Y 向上，+Z 为门口。尺寸 3×3×3 格，范围 X/Z=-1.5..1.5，Y=0..3。内部地板面 Y=0.2，侧壁内缘 ±1.3，天花板内侧 Y=2.8。

楼层门采用3×3多方块白模。方块状态 `facing` 表示朝向、`column=0/1/2` 表示横向位置、`level=0/1/2` 表示高度、`open` 控制门面及碰撞。只有 `column=1,level=0` 的底部中心定义站点；资源组合见 `blockstates/call_button.json`。门物品/方块 ID 保留 `easyelevator:call_button`。

楼层门白模目前通过 open 状态切换门面，轿厢双扇门仍使用原有连续门动画接口。将来给楼层门添加骨骼动画时，应保留 `LandingDoorBlock` 的服务端联锁和完整性检查，不要由动画自行决定能否打开。

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
