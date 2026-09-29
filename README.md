# EasyElevator

Minecraft **1.21.1** · Fabric Loader **0.19.2** · Fabric API **0.116.17+1.21.1** · Java **21**。

当前模组版本 **1.1.0**。三个组件：电梯轨道、呼叫按钮、电梯轿厢。当前采用无图案白模，声音默认静音，便于后续替换。

## 搭建和使用

1. 从创造模式「简易电梯」物品栏取出三个组件，或使用配方合成。
2. 垂直放置一列连续轨道。第一块轨道朝向放置者，后续上下延伸会继承方向。**轨道朝向是轿厢所在方向，也是轿厢门朝向**。
3. 在每个停靠高度的轨道侧面放置呼叫按钮。按钮必须直接水平邻接轨道。一个按钮对应一个站点，按高度排序；同高度的多个按钮仍显示为多个站点，停靠高度相同。
4. 手持「电梯轿厢」，右键任意一块轨道。轿厢中心位于该轨道朝向的前方 2 格；底部 Y 与被点击轨道相同。预留 **3×3 的井道、3 格轿厢高度**，整个运行区间清空。每段连续线路只能放置一个轿厢。
5. 右键外部呼叫按钮，轿厢会排队前往该按钮高度。穿过轿厢正面的门进入后，右键打开选站面板。按钮较多时可翻页；选择后由服务端重新检查按钮及线路，随后入队。
6. 无人乘坐且门完全打开时，在轿厢外**空手潜行右键**回收轿厢。生存模式返还物品。

### 一个具体示例

轨道放在 `(0, 64..80, 0)`，全部朝 `south`。在轨道西侧 `(-1,64,0)`、`(-1,72,0)`、`(-1,80,0)` 放置朝 `west` 的呼叫按钮。

右键 Y=64 的轨道生成轿厢后，轿厢中心为 `(0.5,64,2.5)`，占地为 X=-1..1、Z=1..3，门朝南，门外走廊在 Z≥4。地板表面是 Y=64.2；楼层走廊表面可用 Y=64，0.2 格的门槛可正常走上。其他楼层同理。

按钮可以安在轨道的任何一侧，但安装在轿厢背面的按钮通常不方便乘客接近；建议放在左右侧并留通道。当前是**直上直下轨道**，横向相邻轨道是独立线路，不支持转弯、斜轨或分岔。

### 行为

- 默认速度 **4 格/秒**，是旧版两倍；开关门各 1 秒，开门停留至少 2 秒；无新请求时保持开门。
- 到站误差容限 1e-7 格，最后一步精确对齐站点；double 运动样本和约100 ms显示缓冲，让轿厢和本地乘客镜头逐帧插值。
- 按调用顺序处理请求，相同按钮重复请求合并；最多等待 128 个请求。
- 门未完全关闭不能移动；门口有生物会重新开门并保留原请求。
- 乘客站在空心轿厢内随轿厢移动，不要求骑乘；服务端同步位置并清除下落伤害。
- 断轨、轨道朝向不一致、运行区域有实体/方块障碍、区块未加载时停止；恢复后继续原行程。
- 运行中拆除目的站按钮会暂停并取消该目的站；其他有效请求可以继续，或从轿厢重新选站。避免在半空开门。
- 轿厢、目标、队列和门进度保存进世界。重载后先检查线路再恢复运行。
- 不主动加载区块；线路及井道需要在已加载范围内。多人服务器和客户端都要安装模组与 Fabric API。

## 替换模型与音效

详细接口见 [docs/ASSET_INTEGRATION.md](docs/ASSET_INTEGRATION.md)。

- 轨道模型：`assets/easyelevator/models/block/elevator_rail.json`
- 外部按钮：`assets/easyelevator/models/block/call_button.json`
- 轿厢及双扇门：`src/client/java/org/DJB/easyelevator/client/CabinRenderer.java`
- 运行/到站/开门/关门音效：`assets/easyelevator/sounds.json`
- 门动画：`CabinEntity.doorProgress(tickDelta)`，0 关闭、1 打开。
- 服务端扩展事件：`ElevatorEvents.PHASE_CHANGED`、`ElevatorEvents.ARRIVED`。

## 构建

先将 `JAVA_HOME` 指向 **JDK 21**，然后在项目目录执行：

```powershell
.\gradlew.bat build
```

成品在 `build/libs/easyelevator-1.1.0.jar`；带 `-sources` 后缀的是源码包，不放入 mods 文件夹。

完整说明见 [工程构建与打包说明书](docs/BUILD_AND_PACKAGING.md)；所有运行、精度、动画、尺寸、面板和音效参数见 [电梯参数手册](docs/PARAMETERS.md)。

本机快捷构建和两种打包：

```powershell
.\tools\build.ps1 -Jdk 'E:\workspace\JAVA\JAVA21' -Task build
.\tools\build.ps1 -Jdk 'E:\workspace\JAVA\JAVA21' -Task packageRelease
.\tools\build.ps1 -Jdk 'E:\workspace\JAVA\JAVA21' -Task packageProject
```

开发启动：`./gradlew.bat runClient`。核心逻辑测试和服务端 GameTest 已纳入 `build`，也可以脱离游戏单独运行状态机测试：

```powershell
.\tools\test-logic.ps1 -Jdk '你的 JDK 21 目录'
```

## 文件组织

| 目录/类 | 职责 |
| --- | --- |
| `block/`、`item/` | 轨道、呼叫按钮、轿厢放置和回收 |
| `logic/ElevatorLine` | 轨道连续性、站点扫描、单线路单轿厢 |
| `logic/ElevatorController` | 独立状态机、门联锁、请求队列 |
| `entity/CabinEntity` | 实际位移、乘客、障碍检测、存档、同步 |
| `mixin/EntityViewMixin` | 空心轿厢地板、侧壁、门的实体碰撞 |
| `network/` | 面板数据和选站请求；服务端验证 |
| `client/` | 面板、白模、位置音效循环 |
| `api/` | 到站和状态变化扩展事件 |

游戏内验收步骤见 [docs/TESTING.md](docs/TESTING.md)。
