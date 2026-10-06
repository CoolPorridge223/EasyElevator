# 模型、音效与门动画接口

适用模组版本 **2.1.0**；运行环境与安装步骤见 [构建与打包](BUILD_AND_PACKAGING.md)。

> **模型与贴图由脚本生成，不要手改单个数不出来源的文件。**
> `python tools/generate_art.py` 写全部方块/物品模型与贴图，`python tools/generate_data.py`
> 写方块状态、本地化、配方、掉落表与音效钩子。两个脚本都是纯 Python 3、无第三方依赖、
> 可重复运行且输出逐字节一致，并且在写文件前会做几何自检（见各自文件头与
> `check_door_models` / `check_item_models` / `check_cabin_parts` / `check_blockstates`）。
> 只想换外观（贴图或模型数值）时改脚本再跑；只做资源包覆盖时可以照下表直接替换成品文件。

## 模型

方块模型可直接替换资源 JSON，注册 ID 不变：

| 资源 | 位置（相对 `src/main/resources/assets/easyelevator/`） |
| --- | --- |
| 轨道 | `models/block/elevator_rail.json` |
| 楼层门**门框**（常驻的左右立柱与门楣） | 底行 `models/block/landing_door_frame_{left,middle,right}_bottom.json`（立柱底座 + 整条门槛）、中行 `landing_door_frame_{left,middle,right}.json`（立柱 / 门洞空模型）、顶行 `landing_door_frame_top.json`（中列门楣）与 `landing_door_frame_top_left.json` / `landing_door_frame_top_right.json`（顶行立柱 + 门楣） |
| 楼层门物品图标（关门状态的整扇门切片；方块本身已不再使用它） | `models/block/call_button.json` |
| 物品显示 | `models/item/elevator_rail.json`、`call_button.json` 直接以方块模型为父；三个轿厢图标共用 `models/item/cabin_body.json`（1/3 比例的迷你轿厢），各自只覆盖 `shell` / `wall` / `door` / `base` / `lamp` 五个贴图变量 |
| 门框 / 轨道亮钢贴图 | `textures/block/blank.png` |
| 亮钢门扇贴图（楼层门叶） | `textures/block/blank_door.png`（1.5.6 由 `blank_dark.png` 更名并改亮） |
| 机加工深色板（立柱底座、门槛、轨道法兰与抱箍） | `textures/block/blank_plate.png` |
| 门楣**显示屏**（近黑玻璃 + 掠光；横向均匀，因此三列门楣拼起来是同一块连续屏幕。屏幕本身在模型里是**凹进去**的一件，四周由压边与立柱收头当边框） | `textures/block/blank_screen.png` |
| 高速轿厢图标金板（拉丝金 + 三个速度箭头） | `textures/block/blank_speed.png` |
| 观光轿厢图标玻璃板（冷灰蓝玻璃 + 钢框，仅用于物品图标，不用于世界玻璃） | `textures/block/blank_glass.png` |
| 厅外呼叫面板（右键楼层门弹出的 ▲ / ▼ / 关闭 三个按钮） | 纯 Java 绘制，见 `client/LandingDoorScreen`；按钮文字用的是 ▲(U+25B2) / ▼(U+25BC) 字符，由原版字体的 Unicode 回退提供，文案键为 `screen.easyelevator.hall_title / hall_up / hall_down / hall_station / close` |
| 轿厢**材质图集**（三种型号共用一张 4×4 共 16 格） | `textures/entity/cabin.png` |
| 模组列表图标 | `icon.png` |

图集格号由 `CabinRenderer.Mat` 的枚举顺序给出：`WALL`（亮钢舱壁）、`TRIM`（中性饰条：扶手压条、灯槽、门楣）、
`DARK`（深色阳极氧化：踢脚线、显示窗）、`FLOOR`（拉丝地板）、`CEIL`（顶板）、`LAMP`（灯罩，自发光）、
`RAIL`（不锈钢扶手）、`SILL`（防滑门槛）、`PANEL`（操纵面板）、`BEZEL`（面板边框）、`BUTTON`（按钮）、
`GLASS`（旧玻璃格，世界玻璃已不再使用）、`ACCENT`（后壁暖色饰板）、`DOOR`（轿厢门扇，右侧带一组会随门滑动的折边竖线）与两个备用格。改配色只要重画对应格子；
换格数或换顺序必须同时改 `tools/generate_art.py` 的 `ATLAS_TILES`，否则脚本会报错。

轿厢是移动实体，**不是可直接替换 block JSON 的方块**。`CabinRenderer` 用 `BoxMesh` 直接画几何：
外壳五个长方体与 `AbstractCabinEntity.collisionBoxes()` 逐项对应，内饰件写在两张数据表里
（`STANDARD_PARTS` / `OBSERVATION_PARTS`，每行 = `{x,y,z,X,Y,Z,材质格号,自发光}`，单位格、轿厢局部坐标）。
替换为你的 Java/Blockbench 实体模型时，保留注册的 `CabinRenderer`，在其 `render` 内调用你的模型即可。
如果将来采用 GeckoLib，需要自行增加适用于 1.21.1 的依赖及动画控制器；当前实现不依赖动画库。

内饰表有三条硬不变量，改表后必须让 `python tools/generate_art.py` 通过（它会解析 Java 源文件并逐条校验）：
① 所有件待在净空 `X ±1.3、Y 0.2..2.8、Z -1.3..门背面` 之内（允许向外壳嵌 0.002 格）；
② 与外壳或彼此相接时，相接面要错开 0.002 格以上，**不允许两个面共面**——即使采用背面剔除，同向共面片仍会互相抢深度；③ 材质格号 0..15、自发光只能 0/1。
另外两张表的**最后 6 行必须逐字相同**（操纵面板）：观光舱的侧壁是玻璃，但层号与呼梯键同样要有，
漏掉就会出现"红字浮在空中"——脚本会断言 `STANDARD_PARTS[-6:] == OBSERVATION_PARTS[-6:]`。

**厢内照明（2.1.0）**：`logic/CabinLighting.surface` 根据轿厢局部面中心、法线与灯位
`(0, 2.78, -0.25)` 计算距离和朝向衰减。外壳、顶板外侧、底面、门外侧与导靴保持环境光；
舱内朝向灯具的面才获得补光，补光上限 13 级。`CabinLighting.lamp` 仅将灯罩朝下的面提高到
15 级方块光，灯罩背面和侧边保持环境光。全部处理保留天光，不修改世界数据。
这属于模型表面的局部照明，不会给玩家、附近方块提供真实动态光源。
普通与高速共用模型和照明，观光型号使用同一套规则；本版保留之前的模型尺寸与门底防闪烁偏移。

`CabinRenderer<T extends AbstractCabinEntity>` 同时服务三种轿厢，外观只分两个分支：**普通与高速**走
`drawStandardShell` + `STANDARD_PARTS` + `drawDoorway` + `drawDoors`（完全不透明，两者逐面相同，因此高速型号没有独立模型）；
**观光**走 `drawObservationShell` + `OBSERVATION_PARTS` + `drawObservationGlass`（地板、顶板、四根角柱、上下压条、
中梃与竖向分格、扶手、灯槽顶灯与**操纵面板**都不透明，左右侧墙 / 后墙 / 两扇门各画一张
`BoxMesh.glassX/glassZ` 的**零厚度双面玻璃**，正反都可见，颜色常量是 `GLASS_COLOR` / `GLASS_DOOR_COLOR`）。
门扇是**铁框玻璃**（周围钢框、中间玻璃）：铁框由四块长方体组成、玻璃是门扇厚度中线上的零厚度双面，
两者分开画（铁框在不透明层、玻璃在原版 cutout 的 `GlassLayers` 层），布局由纯算术类 `logic/FramedLeaf` 给出。
**每块构件的 UV 都按它自己的尺寸取**（竖框只取横向那一段、横框只取纵向那一段、玻璃按宽高比取，
断面取中心一小块）——这与普通电梯门门扇"大面用随门滑动的窗口、断面用 `edgeRange` 小片"是同一套处理；
照旧把整张贴图铺到 2/16 格宽的竖框上，整块门板贴图会被压成一条"条形码"（1.5.6 实机反馈的"贴图拉伸"）。
观光舱的侧壁是玻璃，面板悬在玻璃内侧 0.1 格，因此 `OBSERVATION_PARTS` 里多一块"面板安装座"把面板接到玻璃上；
实心门扇只画在普通/高速分支——观光舱的两扇门就是玻璃面，若再画一层实心门扇，门会重新变成不透明的。
每片玻璃正反两面各有正确法线，使用背面剔除后每次只画朝向观察者的一面。
压条与中梃**横跨**玻璃平面（玻璃在 X=±1.4 / Z=-1.4），因此玻璃片段被它们正确遮挡，看上去就是 2×3 格的分格窗。
玻璃通透度见下一节。玻璃面四周都与不透明结构留 0.01 格缝，避免共面时浮点深度差造成接缝闪烁；
**碰撞不跟着变**——外壳仍由 `AbstractCabinEntity.collisionBoxes()` 按 0.2 格厚的实心墙生成。

`BoxMesh` 新增了 UV 矩形重载（`cuboid(..., float[] uv)` 与 `planeX/Y/Z` 的同名重载，`{u0,v0,u1,v1}` 归一化 0..1，
传 `FULL_UV` 即旧行为）。这就是"一张图集画完不透明轿厢构件"的基础：实体渲染每层只有一个正在构建的缓冲，
换一次贴图就要切一次缓冲、旧引用立刻失效，用 UV 分格可以在同一个缓冲里画完全部材质。

### 玻璃在光影下的表现

轿厢侧墙、后墙、轿厢玻璃门和观光线路的楼层玻璃门统一使用
`minecraft:textures/block/glass.png`，顶点色为 `0xFFFFFFFF`，不附加整面颜色或 alpha。
玻璃走 `GlassLayers` 的原版 `getEntityCutout` 管线：透明像素直接丢弃，其余纹理像素
正常写深度、使用环境光。原版玻璃的 alpha 为 0/255，因此透过透明区域不会叠出灰白或蓝色底色。
正反两面绕序与法线相反，按朝向剔除，不会把同一面玻璃重复混合，也不会整片挡住后绘制的楼层门。

玻璃不再取轿厢图集的 `Mat.GLASS` 格；`blank_glass.png` 保留用于物品图标。
原版玻璃贴图可由资源包替换，但半透明染色玻璃不属于这个 cutout 材质的目标。
移动实体不自动获得光影包分配给地形玻璃的特殊反射/折射材质 ID；这里实现的是无色通透的原版玻璃外观。

不透明轿厢由有厚度的封闭长方体组成，同样使用带背面剔除的实体 cutout 层，
防止从外侧看到内侧补光面的背面。始终保持“不透明件 → 文字 → 玻璃”的缓冲分组，
切层之后不能再写旧 `VertexConsumer`。

轿厢局部坐标：原点在底部中心，X 左右，Y 向上，+Z 为门口。井道预留包围范围仍为 3×3×3 格；实体模型及碰撞范围 X=-1.5..1.5、Z=-1.5..1.3、Y=0..3。内部地板面 Y=0.2，侧壁内缘 ±1.3，天花板内侧 Y=2.8。

楼层门及门框占轿厢局部 Z=1.3125..1.5。`ElevatorParameters.CABIN_FRONT_Z=1.3` 将地板、顶板、侧壁和轿厢门的前缘统一内收，与楼层门留出 0.0125 格间隙，避免重叠面闪烁。轿厢门厚度仍为 0.2 格，后缘 `CABIN_DOOR_BACK_Z=1.1`。渲染与碰撞共用这些参数；替换模型时也必须保持两层门和外壳之间的间隙。

楼层门采用3×3多方块结构。方块状态 `facing` 表示朝向、`column=0/1/2` 表示横向位置、`level=0/1/2` 表示高度、`open` 是服务端联锁状态（客户端只读，两个取值指向同一套门框模型，不再切换外观）。只有 `column=1,level=0` 的底部中心定义站点；资源组合见 `blockstates/call_button.json`（由 `tools/generate_data.py` 生成，并会自检每个被引用的模型文件确实存在）。门物品/方块 ID 保留 `easyelevator:call_button`。

门面拆成"门框 + 两扇可动门扇"：门框是常驻方块模型（亮钢 `blank` + 底座/门槛用 `blank_plate`，门楣正面是凹进去的显示屏 `blank_screen`），底行三列是"立柱底座 + 门槛"的组合模型（`*_bottom`），顶行的左右两列是"门楣 + 立柱"的组合模型（`landing_door_frame_top_left` / `top_right`），立柱一直顶到门楣，因此整圈门框是连着的、不会看起来像三段；门扇由 `LandingDoorRenderer` 按连续进度绘制（深色阳极氧化 `blank_door` 贴图，贴图里自带面板压边、中缝与踢脚板），几何集中定义在 `block/LandingDoorGeometry.java`，碰撞形状、轮廓与渲染共用同一份数据，因此不会出现画面与碰撞各一套。门扇进度来自根方块的方块实体 `LandingDoorBlockEntity`，逐刻等于在站轿厢的门进度，开关门与轿厢门完全同步；两扇门扇向两侧收拢滑入门框，关门时各占门洞一半、正中只留 1/16 格细门缝（`LandingDoorGeometry.SEAM`，改成 0 即完全贴合）。

**屏幕必须留够高度**：门楣显示屏凹进 0.75/16 格、净高 2.5/16 格，字号 0.016（字模约 7 像素 = 0.112 格）。
屏幕一旦做矮（或字号调大），红色的楼层号就会溢出屏幕压在钢框上——那正是 1.5.6 实机反馈的"数字有点突兀"。
`tools/generate_art.py` 的 `check_door_models` 会断言屏幕的凹进深度与最小高度（`MIN_SCREEN_HEIGHT`），
改模型时直接报错而不是默默变丑。

**门扇贴图"随门滑动"而不是被压扁**（楼层门）：门叶是"盒子越开越窄"画出来的，所以 `LandingDoorRenderer`
给每扇门叶传自己的 UV 矩形，只取此刻还露在外面的那一段（靠门框那一端固定不动，另一端被门框挡住）。
区间与映射都由 `logic/LeafUv`（纯算术）给出。轿厢门不是这样：它是**两扇对开滑门**，门板整块
同一套做法：门洞就是整个正面，外缘固定在侧壁内侧、先导端向两侧移开，全开时门洞全通（`SlidingDoor` +
`CabinRenderer.drawDoors`，贴图同样按"还露在外面"的那一段取）。
`LeafUv` 有两个必须记住的约束：
**① 必须映射进图集格子**——轿厢门使用 4×4 图集里的 `DOOR` 格，先用 `leafRange` 取得可见区间，
再用 `toUv` 映射到该格；不能直接把局部 0..1 当成整张图集坐标。
**② 不按朝向额外翻转 UV**——`BoxMesh` 的顶点绕序与 `LandingDoorGeometry.doorBox` 的朝向映射已经配合，
贴图方向仅由左右门扇决定。旧版按 SOUTH/WEST 额外翻端曾造成贴图反向；修改后需检查四个朝向。
可见宽度恒为 `1-进度`，贴图只取仍露出的部分，因此门缩进门框时花纹不会被横向压扁。

**还有两件事让"滑动"看得见**（否则再正确也像是门被削窄了）：① 门板贴图里那条**折边**
（`DOOR` 格右侧的明暗竖线、以及 `blank_door` 已有的边框亮线）会随门板一起移动，开门时就是一条亮线扫过门洞、
最后收进立柱；② 门板**四周断面**另用一小段贴图（`LeafUv.edgeRange`），不然 0.2 格厚的断面会把半张贴图挤进去，
看起来正好像"贴图被掐断"——这也是 1.5.6 第四轮反馈里"像贴图被截断"的来源。

门槛与**碰撞无关**，它只贴地 0.03 格高、并且前后各内缩 0.01 格，门扇关着时完全被门扇挡住，因此既不会和门扇抢深度、也不需要改 `LandingDoorGeometry`；`LandingDoorBlock.getOutlineShape` / `getCollisionShape` 仍只取那份几何。轨道模型同理：新增的底座法兰、四颗地脚螺栓与抱箍都限制在 `3..13` 的平面范围内，`ElevatorRailBlock.getOutlineShape` 已同步改成 `createCuboidShape(3,0,3,13,16,13)`，保证右键放置轿厢的判定范围覆盖整个看得见的模型（轨道碰撞仍是整格，逻辑不变）。

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

状态包括 OPEN、CLOSING、MOVING、OPENING、BLOCKED。状态及门进度由服务端计算并同步，进入观察范围的玩家也能看到当前进度。两扇滑门使用横向收回动画；换模型后可使用骨骼平移或动画时间轴。渲染动画不能直接触发移动；安全联锁以服务端进度为准。

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

只注册三个 ID，全部与"到站"有关——**开关门不再发声**：

- `easyelevator:elevator_running`：客户端随轿厢位置循环，离开 MOVING 状态立即停止。默认 `sounds: []`（静音占位）。
- `easyelevator:elevator_arrival`：服务端到站时播放一次；也是每扇门设置面板里"默认音效"那一项的来源。
  出厂指向 `assets/easyelevator/sounds/man.ogg`。
- `easyelevator:elevator_arrival_custom`：只提供字幕（供上传的自定义音效复用），
  **不**在这里绑定音频——每扇门上传的音频由 `client/DoorSoundPack` 生成的运行时资源包按门槽
  声明成 `elevator_arrival_custom_<槽>`。

把音频放进 `assets/easyelevator/sounds/` 后按下面的形状写 `sounds.json`（例如给运行声与到站声各换一个 ogg）：

```json
{
  "elevator_running": {
    "subtitle": "subtitles.easyelevator.elevator_running",
    "sounds": [{"name": "easyelevator:elevator_running", "stream": false}]
  },
  "elevator_arrival": {
    "subtitle": "subtitles.easyelevator.elevator_arrival",
    "sounds": ["easyelevator:elevator_arrival"]
  }
}
```

运行音频制作成首尾无缝短循环。也可通过资源包覆盖上述资源；替换后重载资源或重启游戏。

### 每扇门自己的到站音效（玩家在游戏内上传）

从本版本起，**每扇楼层门可以各配一条到站提示音**（潜行右键门打开设置面板）。资源侧的分工：

| 关注点 | 位置 |
| --- | --- |
| 玩家上传的音频（权威副本） | `config/easyelevator/arrival_sounds/arrival_<槽>.ogg` |
| 由它生成的运行时资源包 | `resourcepacks/easyelevator_custom/`（`pack.mcmeta` + `sounds.json` + `sounds/arrival/arrival_<槽>.ogg`） |
| 槽位与"选项 → 音效 ID"的换算 | `logic/DoorSounds.java`（`soundId` / `fileStem` / `isStem` / `slotOfStem`） |
| 写盘与触发重载 | `logic/DoorSoundPersistence.java`（存读）、`client/DoorSoundPack.java`（生成包 + 内容指纹 + `reloadResources`） |
| 预设原版音效表 | `logic/DoorSounds.java` 的 `ARRIVAL_PRESETS`（对原版 ID 的引用，不需要任何资源文件） |

想给某个服务器**统一预置**一套到站音、而不是让玩家一扇扇上传时，最省事的做法是直接往
`config/easyelevator/arrival_sounds/` 里放好 `arrival_<槽>.ogg`：客户端进服时会自动把它投影成
运行时资源包并加载。想要原版式的全局替换（所有"默认音效"的门一起变），仍然按上面那节换
`assets/easyelevator/sounds.json` 里 `elevator_arrival` 的音频即可。

**资源包只在内容变化时重载**：`DoorSoundPack` 先把整包构建到临时目录、算出内容指纹，与上次装进去的
指纹一致就什么都不做。这是为了避免"每次进存档都弹一次红色 Mojang 加载画面"——`reloadResources()`
的代价与视觉干扰都很明显，只有玩家真的上传/换掉音频时才值得付。

`tools/generate_data.py` 会重写 `sounds.json` 为"三个 ID"的声明——
**导入正式音频后不要再次运行它**，否则音频条目会被清掉（模型与贴图不受影响，那部分在 `tools/generate_art.py` 里）。
`tools/generate_art.py` 只写模型与贴图，会重复运行也不会破坏别的东西。
