# EasyElevator 电梯参数手册

适用模组 1.5.0。当前参数采用**源码常量**，不是游戏内设置，也不会自动生成 JSON 配置文件。修改后必须重新构建并同时更新服务端和客户端。

## 1. 运行、精度和时序

主要入口：`src/main/java/org/DJB/easyelevator/logic/ElevatorParameters.java`。

| 参数 | 默认值 | 单位与作用 |
| --- | --- | --- |
| `TICKS_PER_SECOND` | 20 | 换算用的标准游戏 tick/s；不能通过改这个数改变 Minecraft 时钟 |
| `SPEED` | 0.20 | 格/tick，正常 20 TPS 时为 **4 格/秒**，是 1.0 的两倍；普通轿厢与观光轿厢用它 |
| `HIGH_SPEED` | 0.50 | 格/tick，`SPEED × 2.5`，正常 20 TPS 时为 **10 格/秒**；只给高速轿厢用 |
| `POSITION_EPSILON` | 0.0000001 | 格，同层判断和最后一步的浮点误差容限 |
| `DOOR_TICKS` | 20 | 单次开门或关门各 1 秒（三种轿厢共用，不随速度变化） |
| `DWELL_TICKS` | 40 | 门完全打开后的最短停留 2 秒，无请求时持续开门（三种轿厢共用） |
| `MAX_REQUESTS` | 128 | 等待队列上限，不包含正在执行的目标 |
| `RIDER_WAIT_TICKS` | 600 | 刻，读档后等"存档时在车上的乘客"回到世界的上限（30 秒）；超时后行程照原计划继续 |

速度公式：`格/秒 = SPEED × 20`。例如 3 格/秒用 0.15，6 格/秒用 0.30。服务端低于 20 TPS 时实际运行时间会变长。不要为了“更细”而减小 SPEED，否则会直接降低速度。

速度不再是全局唯一值：`ElevatorController` 把步长作为**实例字段**在构造时注入（`new ElevatorController(ElevatorParameters.HIGH_SPEED)`），因此普通 / 高速 / 观光三种轿厢共用同一个状态机实现、同一个队列与同一套门联锁，只有"每刻走多远"不同。约束有两条：① 步长必须满足 `步长 + POSITION_EPSILON ≤ 1 格`，否则单刻可能跨过整格站点、到站吸附失效；② 改速度不会改变 `DOOR_TICKS` / `DWELL_TICKS`，所以"更快"只体现在运行段。非正数或非有限值会退化为 `SPEED`（坏存档不会把轿厢钉死）。

控制器最后一步直接使用站点 Y；没有“必须移动满 0.2 格才能动”的门槛。小于一步的剩余距离仍会移动，不会来回越过楼层。到站误差容限从旧版 1e-5 收紧为 1e-7；这不是显示帧率，也不是一个强制的最小位移。位置与运动同步包使用 double，不把高度取整到格、0.1 格或小数两位。

以相差 8 格的楼层为例：纯运行约 2 秒；再加等候、关门、开门时间。提高速度不会同比缩短固定的门动画与等候时间。

建议保持 SPEED>0、DOOR_TICKS≥1、DWELL_TICKS≥0、MAX_REQUESTS≥1。过高速度需要重新验证乘客、障碍及客户端同步。

## 2. 平滑显示与网络

| 参数 | 默认值 | 意义 |
| --- | --- | --- |
| `INTERPOLATION_DELAY_TICKS` | 2 | 显示落后服务端样本约 100 ms，用于连续插值 |
| `MOTION_HISTORY_SIZE` | 32 | 每轿厢保留的运动样本数量 |
| `MOTION_RESET_GAP_TICKS` | 20 | 长时间没有采样，再收到时重建显示时间基准 |
| `MOTION_STALE_TICKS` | 10 | 客户端 10 tick 未收到样本后停止使用旧显示轨迹 |
| `MOTION_SNAP_DISTANCE` | 4.0 | 单次异常位置跳变大于 4 格时直接重置，避免动画穿过世界 |
| `MOTION_SETTLE_TICKS` | 延迟+2，即 4 | 停车后额外发出的静止样本数，让显示平稳收敛 |

运动时每个服务端 tick 向正在观察该轿厢的玩家发送一次 `motion_frame`：实体 ID、服务端 tick、double 高度、乘客相对地板高度。轿厢模型与本地乘客镜头读取同一插值轨迹；不会根据旧速度猜测未来位置，因此断网或急停时不继续向前预测。

正常移动同步仍保留服务端乘客位置校正、碰撞检查和传送确认；显示层做平滑处理，并用相对旋转标记避免每次乘客同步重置观看方向。它不是改变服务端 tick 频率，也不能消除低帧率、长时间丢包或服务器卡顿。第三人称其他玩家模型仍使用原版实体同步。

插值只影响模型和本地镜头，不延迟实际碰撞、到站判定、门联锁。更低延迟会更直接，也更容易看见网络抖动；更高延迟会增加视觉与实际碰撞的位置差。调整时保证历史容量大于插值延迟，且停车补发时长足够。

## 3. 线路、站点与尺寸

| 参数/规则 | 默认值 | 修改位置及联动 |
| --- | --- | --- |
| 轨道走向 | 同 X/Z、连续、朝向一致的竖直列 | `ElevatorLine.scan/matches` |
| 轿厢型号 | 三种：普通 `easyelevator:cabin`（4 格/秒、白模）、高速 `easyelevator:high_speed_cabin`（10 格/秒、外观与普通逐面相同）、观光 `easyelevator:observation_cabin`（4 格/秒、四面玻璃保留四个支撑边） | `entity/AbstractCabinEntity` 为共同父类；三个子类只声明速度（构造注入）、回收物品与 `glassWalls()` |
| 每线路轿厢数量 | 1（三种型号一起计数） | `CabinItem`、`AbstractCabinEntity.requestStop`、运行检查 |
| 轿厢外观开关 | `glassWalls()` = false/true（仅观光为 true）；**纯客户端渲染提示**，不参与任何判定，也不进存档与网络包 | `CabinRenderer.drawStandard` / `drawObservationShell` + `drawObservationGlass` |
| 观光玻璃几何 | 每个面都是**零厚度单面**（正反都可见）：左右侧墙贴在墙心 X=∓1.4、后墙 Z=-1.4、两扇门 Z=轿厢正面-0.01；四周与角柱/地板/顶板各留 0.01 格缝（Y=0.21..2.79、Z=-1.29..门背面-0.01、后墙 X=∓1.29）；四根角柱 0.2×0.2 保持不透明 | 单面每层只叠一次透明度；换成 0.12 格厚薄板会正反各叠一次而发灰。0.01 格缝是为了不与不透明面共面——共面时浮点深度差会造成"玻璃与框架衔接处闪烁"。只影响绘制；碰撞仍取 `collisionBoxes()` 的 0.2 格实心墙 |
| 观光玻璃渲染层 | 专用层 `easyelevator_cabin_glass`：照抄原版 `entity_translucent`（同着色器、贴图、混合、禁止剔除），只把写掩码换成 `COLOR_MASK`（**只写颜色、不写深度**） | `CabinRenderer.GLASS_LAYER`。世界渲染顺序是**实体 → 方块实体**（`WorldRenderer.render` 里 "entities" 早于 "blockentities"），而玻璃比楼层门更靠近观察者；玻璃若写深度，之后才绘制的楼层门会被深度测试整片剔除（"坐观光轿厢看不见每层电梯门"）。只写颜色后玻璃不遮挡任何后画几何，自身仍受深度测试约束 |
| 观光玻璃通透度 | 墙面 `GLASS_COLOR` = 0x40BFE4F5（每面 25%）、门扇 `GLASS_DOOR_COLOR` = 0x59A8D2EC（每面 35%）；单面各叠一次，隔着轿厢看穿两面约 44%；alpha 全在顶点色里，贴图仍是一张全白图 | `CabinRenderer`；调通透度只改这两个常量 |
| 站点数量来源 | 每扇完整的3×3楼层门产生一个站点 | `ElevatorLine.scan`、`LandingDoorBlock.complete` |
| 厅外呼叫 | 每站两个方向各一条（▲ 上行 / ▼ 下行），带方向入状态机，最多 128 条；到站开门时清除该站两条，门被拆也清除 | `ElevatorController.HallCall/callHall`、`AbstractCabinEntity.requestHallCall` |
| 呼叫方向调度 | 集选控制：先顺路（本侧前方、方向一致的厅外呼叫 + 任意选站，取最近），没有就掉头再找，两侧都没有则按距离兜底并让服务方向跟随该呼叫（保证不饥饿、不空转）；请求全部完成后方向复位为空闲 | `ElevatorController.select/nearest/initialTravel` |
| 插入即重排 | 选站插入队列时按服务方向的位置顺序重排（`insertOrdered`；方向未定时保持插入顺序，让最早的请求决定起始方向）；运行途中每刻 `retarget` 检查"同方向前方且严格更近"的请求并改道先去它，原目标放回队列不丢站；已驶过的楼层不会回头补停，反方向呼叫也不会让轿厢半路掉头 | `ElevatorController.insertOrdered/retarget` |
| 基准层（1 层） | 每条线路最多一扇门带 `BaseFloor` 标记（潜行右键设置，写在门的方块实体 NBT 里）；未设置时按最低站点编号 | `LandingDoorBlockEntity.baseFloor/setBaseFloor`、`FloorIndicator.baseIndex` |
| 同高度楼层门 | 每线路同一高度一扇正面门；9个部分只计1站 | 底部中心 column=1、level=0 |
| 门中心与轨道距离 | 3 格、同一Y、沿轨道朝向 | `LandingDoorBlock.RAIL_DISTANCE` |
| 楼层门尺寸 | 宽3、高3、厚3/16格；门框立柱与门楣各3/16格（立柱顶到门楣，四角相连），门扇各1/2门洞宽、中缝1/16格 | `LandingDoorGeometry`（碰撞、轮廓与渲染同源） |
| 楼层门门扇动画 | 逐刻等于在站轿厢门进度；全开时宽度归零、收进门框 | `LandingDoorBlockEntity.openProgress`、`LandingDoorRenderer` |
| 门状态刷新 | 每1 tick，并在轿厢状态变化当 tick 更新（`open` 只是联锁标志） | `scheduledTick`、`AbstractCabinEntity.tick` |
| 水平轨道到轿厢中心偏移 | 2 格 | `ElevatorLine.centerX/centerZ`、`AbstractCabinEntity.initialize` |
| 轿厢预留范围 / 模型尺寸 | 预留3×3×3；模型宽3 × 深2.8 × 高3 格（三种型号完全相同） | `Easyelevator.CABIN/HIGH_SPEED_CABIN/OBSERVATION_CABIN` dimensions 保留预留范围；正面内收避免与楼层门重叠 |
| 局部坐标 | 原点底部中心，+Z 门口 | `AbstractCabinEntity.localBox`、`CabinRenderer` |
| 地板厚度/表面 | 0.2 格；相对 Y=0.2 | `collisionBoxes`、白模 renderer |
| 侧壁厚度 | 0.2 格 | 外缘 ±1.5，内缘 ±1.3 |
| 顶板 | 相对 Y=2.8..3.0 | 轿厢净高 2.6 格 |
| 轿厢正面前缘 | 局部 Z=1.3 | `ElevatorParameters.CABIN_FRONT_Z`，渲染与碰撞共用；楼层门后缘 Z=1.3125，间隙0.0125格 |
| 门口 | X=-1.3..1.3，Y=0.2..2.8，Z=1.1..1.3 | 双扇门各占一半；后缘 `CABIN_DOOR_BACK_Z`，厚度仍0.2格 |
| 乘客横向包围盒边界 | 中心 ±1.31 格 | `containsPassenger`、`insideFootprint`；非旁观、未骑乘 |
| 乘客脚部高度范围 | 相对 Y≥0.14 且 <2.7 | `containsPassenger` |
| 乘客名册 | UUID + 相对轿厢底部中心的偏移（格），随实体 NBT 的 `Riders` 一起存档 | `writeCustomDataToNbt` / `readCustomDataFromNbt`；读档后据此等乘客归位 |
| 掉队乘客找回范围 | 同一条井道：世界全高、水平 ±2 格查询，再要求水平落在内缘 ±1.31 格内 | `findLostPassenger`、`insideFootprint`（与"算不算乘客"共用同一水平判据） |
| 乘客归位位置 | 名册偏移夹到 X/Z ±1.3 格、Y 0.2..2.6 格 | `putPassengerBack`；夹取后下一刻必然满足 `containsPassenger` |
| 门口防夹检测区域 | X ±1.3，Y 0.2..2.8，Z 0.95..1.6 | `doorwayBlocked`，内缘为 `CABIN_DOOR_BACK_Z-0.15`，检测 LivingEntity |
| 障碍扫描边界内缩 | 0.001 格 | `canMove` 的 swept box，避免面接触误判 |
| 实体追踪范围 | 10 个区块，即约160格 | `Easyelevator.CABIN.maxTrackingRange(10)` |
| 原版实体追踪间隔 | 1 tick | `trackingTickInterval(1)` |

尺寸目前不是集中配置项。换成不同尺寸的模型时，必须同时调整**实体尺寸、井道偏移、碰撞、乘客范围、门口检测、渲染几何和线路轿厢查找包围盒**。只改画面会造成穿模或无法进入。

线路无固定楼层间距，站点停靠 Y 就是电梯门底部中心的 Y（与连接轨道同高）。轿厢底部对齐该 Y，乘客站立表面比它高 0.2 格。若相邻站点太近，建筑楼板仍可能挡住 3 格高的轿厢，需要按实际井道留空。

楼层门只有在同一轨道的轿厢准确停在该高度、且轿厢门正在开放时才开。离站前关闭，未到站层始终关闭。红石或普通右键都不会强制把空井道门打开；右键只发送呼叫请求。轿厢仅忽略本线路完整楼层门与正面外壳的重叠，不忽略其他方块障碍。

## 4. 门、队列和异常行为

| 状态 | 行为 |
| --- | --- |
| OPEN | 完全开门，等待停留计时和新请求 |
| CLOSING | 门进度从1降至0；门口有人则重新开门并保留请求 |
| MOVING | 门必须为0；每 tick 验证轨道与扫过的空间 |
| OPENING | 到站后门进度从0增至1 |
| BLOCKED | 停止移动；障碍恢复且目标有效时继续。读档后"名册里的乘客还没回到世界"也走这一相位（上限 `RIDER_WAIT_TICKS`） |

读档时轿厢保存的 `MOVING` 一律降级为 `BLOCKED`，先重验线路再继续；如果存档里带着乘客名册（`Riders`），会先保持静止等这些人回到世界：轿厢是区块实体、随区块载入，而玩家实体由登录流程单独载入、必然更晚，抢跑会让乘客被留在已经空掉的井道里掉出电梯。等待期间名册里的乘客一在井道里出现就被按存档偏移放回厢内；名册齐了、等待窗口用尽、或车上已经有别的乘客要走，就立刻放行继续原行程。

按**运行方向上的位置顺序**处理请求（不是按键先后）：同一方向上更近的楼层先停，因此上行途中不会越过同方向的楼层去更远的那层；重复的同一门请求合并。厅外呼叫带方向，只有正在按该方向运行的轿厢才顺路接走它；两侧都没有顺路请求时按距离兜底（空车去接反方向的孤立呼叫），保证任何请求最终都会被服务。请求删除或不完整的门会被拒绝；已排队但被拆除的门被移除，被拆门所在站的厅外呼叫也一并取消。运行中目的门拆除会暂停，避免半空开门；新的有效请求可以恢复运行。断轨、朝向改变、多个轿厢、实体/方块障碍、世界边界和区块加载状态也会影响移动。

不会主动加载区块，没有能耗、载重量、加速度、减速度、横向转弯和站点自定义名称参数。速度当前为匀速，插值用于平滑显示。

## 5. 面板与安全边界

| 项目 | 默认值/位置 |
| --- | --- |
| 按键外观 | 方形 20×20 像素按钮，只印楼层编号；悬停提示显示站点与高度，轿厢当前停靠层用绿色描边点亮（`ElevatorScreen.StationButton`） |
| 按键编号 | 站点按高度升序，**最底层 = 1 层**；编号即按钮上的数字 |
| 按键铺排顺序 | 从右下角起步，先右→左排满一行、再换上一行（下→上），因此每列数字自下而上递增（`PanelLayout`） |
| 按键网格 | 列数自动选成尽量长方形：优先不留空行，其次接近正方形，同分取更宽（例 9→3×3、12→4×3、10→5×2、7→4×2；`PanelLayout.chooseColumns`） |
| 单页容量 / 翻页 | 列数≤8、行数≤8（单页最多 64 个站点），超出用底部 `<` `>` 翻页（`ElevatorScreen.init`） |
| 按钮间距 / 面板内边距 | 间距 4 像素，内边距 16 像素，头部 52 像素、底部 40 像素 |
| 按键颜色 | 普通灰色描边；轿厢当前停靠层绿色描边；已加入停靠计划的站点红色描边；悬停/键盘聚焦点亮为白色（`ElevatorScreen.StationButton`） |
| "当前层/开门键"的前置条件 | 必须**停稳**：相位不是 MOVING，或处于 MOVING 且没有目的站（= 关着门停在本层）。轿厢以 0.20 或 0.50 格/刻运行（普通/观光 与 高速），运行时高度会精确经过整数楼层，只看高度会让绿色与开门键逐层闪一下（`ElevatorScreen.stopped/parkedAt`） |
| 开门键语义 | 纯门操作、**不进队列**：门已全开 → 续满停留；正在关门 → 反向重新打开（中断关门）；门已全关但停在本层 → 直接开门。服务端受理条件＝`status()==IDLE` 且车体精确停在某站点（`AbstractCabinEntity.doorCommand`、`ElevatorController.forceOpen`），因此不会把本层排进呼叫队列、也不会开走再回来 |
| 关门键语义 | 立刻结束停留并关门；门已全开或正在开门时受理（正在开门则反向关闭），门已关着时拒绝。允许队列为空时关门停在本层等待下一次呼叫，关门途中防夹仍然生效 |
| 楼层显示 | 面板顶部仿数码管的红字层号；**楼层门框顶部**与**轿厢内模拟面板**也用红字显示。轿厢面板两行：第一行到达层数（字号 0.018，行锚点 -10 像素）、第二行运行状态（字号 0.011，行锚点 +5 像素），各自水平居中。世界内文字统一走"局部坐标 + 最高亮度 + 负 Y 缩放（字体内部 Y 向下，同原版告示牌 `setTextAngles`）+ `POLYGON_OFFSET`"（`LandingDoorRenderer`、`CabinRenderer`） |
| 门框顶部排版 | 横向一行"[运行状态] [楼层号]"（中间留白 6 像素），起点取负的半个总宽，因此状态文本在左、层数在右、整组居中；状态文本宽度每帧重算。位置在门楣正中面外 0.02 格，只从走廊一侧可见 |
| 运行状态 | 由同步数据推导，无额外同步字段：只有 MOVING 且有目的站才判上行/下行（比当前高度高＝上行、低＝下行，差值小于 `SYNC_POSITION_EPSILON` 视为已到站），其余相位（开门/开门中/关门中/暂停/关着门停靠）一律"停靠"（`logic/ElevatorStatus`） |
| 翻译键 | `status.easyelevator.up` = 电梯上行、`status.easyelevator.down` = 电梯下行、`status.easyelevator.idle` = 停靠（`en_us` 为 Going up / Going down / Parked） |
| 楼层号规则 | 站点按高度升序；**基准层 = 1 层**、其上 2、3…、其下 B1、B2…（内部负数，未设基准层时最低站点 = 1 层）。上行取"已到过/经过的最高一层"、下行取"已经过的最低一层"，因此只在经过或到达一层时变化一次；尚未经过任何站点时显示 `--`。三处显示同源：选站面板按钮、轿厢内面板、楼层门框顶部（`logic/FloorIndicator`） |
| 基准层设置 | 潜行右键任意一扇完整的楼层门 = 把它设为 1 层；同一线路其它门的标记会被清掉，拆掉基准门即回到默认编号。轿厢每刻扫描线路上的门方块实体取得它，因此改完下一 tick 三处显示全部跟上 | `LandingDoorBlock.onUse/setFloorBase`、`AbstractCabinEntity.baseFloorY/updateFloorNumber` |
| 厅外呼叫面板 | 右键楼层门弹出（不再直接呼叫）：三个按钮竖排——▲ 上行、▼ 下行、关闭；按钮为 20×20 方形，配色与选站面板的楼层键同源，**呼叫未完成时（服务端仍挂着这条呼叫）画成红色**，到站开门那一刻服务端推送新状态、按钮恢复 | `client/LandingDoorScreen`、`ElevatorNetworking.OpenHallPanel/HallPanelState/HallCallButton` |
| 停靠计划高亮 | 目的站 + 队列中的站点，服务端在计划变化时用 `PanelState` 推送（到达、取消、新请求），只影响已打开的面板 |
| 底部按键 | `<` `>` 翻页（首/末页禁用）、开门、关门、完成；开门/关门按轿厢实时状态自动禁用（无轿厢、不在站点、门已关好等） |
| 开门键语义 | 只在车体精确停在某个完整站点时受理（等于"请求当前这一层"）；门已全开时按下只续满停留时间；不在楼层之间开门（`AbstractCabinEntity.doorCommand`） |
| 关门键语义 | 立刻结束停留并关门，队列为空也允许（关门后停在本层等待）；关门中仍逐刻检查门口是否有人，防夹照旧（`ElevatorController.forceClose`） |
| 面板有效性判定 | UI 用宽松判定（水平 ±1.5 格、相对轿厢底 -0.6..+2.9 格）以免下行时被误判为已离开轿厢；权限校验始终在服务端（`ElevatorScreen.staysInside`） |
| 面板最大解码站点数 | 16384，`ElevatorNetworking.MAX_STOPS` |
| 服务端选站验证 | 活着、非旁观、身处指定轿厢、完整电梯门仍存在且同线路 |
| 面板背景 | 轻微暗色遮罩，不调用原版模糊着色器 |
| 显示高度 | 面板保留1位小数；仅显示，不降低运行精度 |
| 内部交互 | 主手右键选站，潜行时不抢占普通方块/物品交互 |

## 6. 音效、动画和存档

| 参数 | 默认值/说明 |
| --- | --- |
| `EVENT_VOLUME` | 0.8，到站及开关门音量 |
| `RUNNING_VOLUME` | 0.6，随轿厢循环音量 |
| `SOUND_PITCH` | 1.0，原音高 |
| 声音分类 | BLOCKS，受游戏“方块”音量控制 |
| 运行音效 | `easyelevator:elevator_running` |
| 到站音效 | `easyelevator:elevator_arrival` |
| 开/关门音效 | `easyelevator:door_open` / `door_close` |
| 门动画接口 | 轿厢门 `cabin.doorProgress(tickDelta)`；楼层门门扇 `doorEntity.openProgress(tickDelta)`，0全关、1全开，两者逐刻同值 |
| 扩展事件 | `ElevatorEvents.PHASE_CHANGED`、`ARRIVED` |

音效资源默认静音；具体 OGG 和模型替换步骤见 [ASSET_INTEGRATION.md](ASSET_INTEGRATION.md)。

存档保存 RailX、RailZ、Facing、Phase、Door、Target、Queue、HallCalls（厅外呼叫：站点坐标 + 方向）与 Travel（当前服务方向），以及原版实体坐标。基准层标记写在**基准门自己的方块实体 NBT** 里（随区块存档），不占轿厢字段。**轿厢型号（普通/高速/观光）不写进存档**：它由实体类型唯一决定，读档时按注册类型重建，因此速度与玻璃外观永远与物品一致，也不会出现"存档里写坏了速度"的情况。客户端插值样本属于临时显示数据，不写进存档。沿用旧存档字段，1.5.0 可读取之前的轿厢（旧的 `easyelevator:cabin` 就是现在的普通型号，行为不变）；旧按钮需要按距轨道3格重新放置为完整电梯门，旧队列中的无效位置会被过滤；改动存档字段或几何尺寸前先备份世界。

## 7. 修改后的验证

改参数 → `build` → 阅读逻辑测试及 GameTest 结果 → 客户端上下各乘一次 → 多人同时乘坐测试。重点确认相机与地板没有相对抖动、最终高度准确、障碍不会穿越、门口防夹正常。当前自动测试不等同于真人多人网络验收。

改 `HIGH_SPEED`（或新增第四种型号）后至少复跑：`tools/test-logic.ps1`（状态机按实例速度、单刻位移不超步长、高速车最后一步精确到站、多实例速度互不串台）与服务端 GameTest 的 `highSpeedCabinRunsTwoAndAHalfTimesFaster`、`observationCabinIsGlassButOtherwiseIdentical`（高速车外观外壳与普通车逐盒相同、80 刻内到站；观光车速度与碰撞与普通车一致、仅 `glassWalls()` 为真）。再确认 `步长 + POSITION_EPSILON ≤ 1 格` 仍然成立。
