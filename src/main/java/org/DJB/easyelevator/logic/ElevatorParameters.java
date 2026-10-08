package org.DJB.easyelevator.logic;

/**
 * Compile-time tuning values. Units and coupled geometry are documented in docs/PARAMETERS.md.
 *
 * <p>职责：整个模组的编译期调参常量集中处——运行速度、门时序、请求队列上限、轿厢正面几何内收量，
 * 以及客户端样本的时效与瞬移阈值都在这里定义，便于在脱离游戏的前提下单测与统一调参。</p>
 *
 * <p>在整体架构中的位置：logic 包的最底层，不引用任何 Minecraft 类；ElevatorController、
 * ElevatorLine/AbstractCabinEntity、client/CabinMotion 都从这里取值，保证服务端状态机与客户端运动口径一致。</p>
 *
 * <p>单位约定：格 = 方块（block），刻 = tick，1 秒 = {@link #TICKS_PER_SECOND} 刻；
 * 速度单位为格/刻。几何常量以轿厢本地坐标系（正面朝 +Z）给出，长度单位均为格。</p>
 */
public final class ElevatorParameters {
    /** 工具类，禁止实例化：所有成员都是 static final 常量。 */
    private ElevatorParameters() { }

    /**
     * 普通 / 高速 / 观光三型轿厢的限载人数：<b>8 人</b>。
     *
     * <p>这三型没有货舱，载客量就是一个固定值：超过它时状态机进入
     * {@link ElevatorController.Phase#OVERLOAD}（门保持全开、不派发行程），人数降回来即恢复。
     * 重载型号不吃这个值——它在构造时拿 {@link #HIGH_PASSENGER_NUM_LIMIT} 作为<b>空舱基准</b>，
     * 之后按货量覆写 {@code passengerNumLimit()}（见 {@code CargoLoad.passengers}）。
     *
     * <p><b>0 或负数 = 不限载</b>，这是 {@code AbstractCabinEntity.overloaded()} 的判据
     * （{@code limit > 0 && 人数 > limit}）：把它改成 0 就回到"能站多少人就装多少人"的旧行为。
     * 为什么判据必须先看"限载为正"：否则 {@code 人数 > 0} 会在厢内站进第一个人时立刻成立，
     * 于是轿厢一到站就显示"超载"、门再也关不上。
     */
    public static final int PASSENGER_NUM_LIMIT = 8;

    /**
     * 重载电梯的<b>空舱</b>限载人数：20 人。
     *
     * <p>它是重载型号的基准值，而不是恒定上限：实体构造时把它交给父类，
     * 之后 {@code PowerfulCabinEntity.passengerNumLimit()} 按货量覆写为
     * {@code CargoLoad.passengers(件数)}——每开始一档 {@code CargoLoad.ITEMS_PER_PASSENGER}（128）件少载 1 人，
     * 满载 1728 件仍可载 6 人，并且<b>保底 1 人</b>（0 会被当成"不限载"，绝不能让货量把它压到 0）。
     * 厢内玩家数超过当前上限时状态机进入 {@link ElevatorController.Phase#OVERLOAD}——
     * 门保持全开、不派发行程，减人或卸货后自动恢复。
     *
     * <p>这个数字同时是界面上那两处显示的唯一来源：后壁载重铭牌（{@code CabinRenderer.drawCapacityPlate}）
     * 与货舱面板的"当前限载"都由 {@code passengerNumLimit()} 算出，因此改这里（或改货量）不需要动任何贴图或几何。
     * 3x3x3 的净空（内缘 ±1.3 格）实际能站下 20 人上下：调大之后玩家会挤在一起但不会掉出轿厢；调小则更容易触发超载。
     */
    public static final int HIGH_PASSENGER_NUM_LIMIT = 20;

    /** 每游戏秒的刻数：Minecraft 固定 20 刻/秒，用于在“秒”与“刻”之间换算。 */
    public static final int TICKS_PER_SECOND = 20;
    /**
     * 普通轿厢与观光轿厢的运行速度：0.20 格/刻 = 4 格/秒（旧版 0.10 的两倍）。
     *
     * <p>另两型各有自己的速度常量：重载用 {@link #LOW_SPEED}（2/3）、高速用 {@link #HIGH_SPEED}（2.5 倍）。
     * 三者的门时序、到站容限、碰撞与几何完全相同，只有"每刻走多远"不同。
     */
    public static final double SPEED = 0.20; // 4 blocks/second, twice the original 0.10
    /**
     * 重载轿厢的运行速度：{@link #SPEED} 的 2/3 ≈ 0.1333 格/刻 = <b>2.67 格/秒</b>。
     *
     * <p>为什么重载反而更慢：这一型的卖点是"能装货"，而货舱满装 1728 件（相当于 13.5 个人的载重）
     * 还要留 6 个客位。让它跑得和普通梯一样快，等于"既有更大的载重、又没有代价"；
     * 2/3 的速度把代价放在"每趟多花约 50% 的运行时间"上，同时加减速距离更短（3.2 格 vs 4.8 格），
     * 观感上就是一部"装得多、走得稳"的货梯。
     *
     * <p>注入方式与其它型号一致：{@code PowerfulCabinEntity} 构造时把它交给父类，
     * 由 {@code MotionProfile.forCruiseSpeed} 解出该速度下的加速度/jerk 上限。
     * 仍是{@link #SPEED} 的常数倍，因此 {@code 速度 + POSITION_EPSILON ≤ 1 格} 继续成立，
     * 单刻位移不会跨过整格站点，到站吸附与井道扫描的精度语义不变。
     */
    public static final double LOW_SPEED = SPEED / 3 * 2;
    /**
     * 高速轿厢运行速度：{@link #SPEED} 的 2.5 倍 = 0.50 格/刻 = 10 格/秒。
     *
     * <p>只改速度，不改门时序、到站容限与其它任何参数：外观与普通轿厢完全一致，
     * 四种轿厢共用同一套状态机与几何，唯一差别是喂给 {@link ElevatorController} 的步长。
     * 仍满足 {@code 步长 + POSITION_EPSILON <= 1 格}，因此单刻位移不会跨过整格站点，
     * 到站吸附与井道扫描的精度语义保持不变。
     */
    public static final double HIGH_SPEED = SPEED * 2.5;
    /**
     * 轿厢从静止加到<b>本型号巡航速度</b>所需的刻数 T：32 刻 = 1.6 秒。
     *
     * <p>这是 S 形曲线（{@link MotionProfile}）的<b>唯一舒适度旋钮</b>：加/减速段的形状、时长、距离与
     * 加速度峰值全部由它和巡航速度推出（{@code MotionProfile.forCruiseSpeed}），四种型号共用同一个 T，
     * 因此"高速梯的加/减速距离更长""重载梯的加速度更温柔"都是同一个公式的自然结果，不需要第二套参数。
     *
     * <p>它决定了四个互相绑定的量（给定巡航速度 v、本时间 T）：
     * <ul>
     *   <li><b>加速度峰值</b> = {@code v / T}（这正是 {@code forCruiseSpeed} 交给曲线的加速度上限）；</li>
     *   <li><b>jerk 上限</b> = {@code 2·(v/T) / T}；</li>
     *   <li><b>加/减速段时长</b> = <b>1.5·T</b> 刻：加速度沿 jerk 斜坡升 T/2 → 在峰值上走平台 T/2 →
     *       沿反向 jerk 回落 T/2。平台段不是可选装饰，而是"把加速度压在 v/T 以内"的必然结果
     *       （若只用升+降两段斜坡，速度只能涨到 v/2，见 {@code MotionProfile.planRamp} 的触顶分支）；</li>
     *   <li><b>加/减速段距离</b> = <b>0.75·v·T</b> 格（段内平均速度恰为 v/2，故 = 1.5 × v·T/2）。</li>
     * </ul>
     * 实测（从代码直接读出；1 格 = 1 米、1 刻 = 0.05 秒，因此 1 格/刻² = 400 m/s²）：
     * <table border="1">
     *   <tr><th>型号</th><th>巡航</th><th>加速度峰值</th><th>单段时长 / 距离</th></tr>
     *   <tr><td>重载（{@link #LOW_SPEED}）</td><td>2.67 格/秒</td><td>0.0041667 格/刻² ≈ 1.67 m/s²</td><td>48 刻 / 3.2 格</td></tr>
     *   <tr><td>普通 / 观光（{@link #SPEED}）</td><td>4 格/秒</td><td>0.00625 格/刻² ≈ 2.5 m/s²</td><td>48 刻 / 4.8 格</td></tr>
     *   <tr><td>高速（{@link #HIGH_SPEED}）</td><td>10 格/秒</td><td>0.015625 格/刻² ≈ 6.25 m/s²</td><td>48 刻 / 12.0 格</td></tr>
     * </table>
     * 三型共用同一个 T，因此加/减速段的<b>时长</b>都是 48 刻（2.4 秒），而<b>距离</b>与<b>加速度峰值</b>
     * 都与巡航速度成正比。站距太短（到不了巡航速度）时曲线改用更短的"收尾斜坡"，两段斜坡直接接上，
     * 仍精确到站——只有长行程才会跑满上表这组数字。
     *
     * <p>为什么不用固定的 jerk 上限：一旦把 jerk 写成常量，斜坡时长就被它单独锁死
     * （{@code t = sqrt(Δv/jerk)}），而加速度上限只影响"能不能到峰"，两个旋钮互相打架；
     * 改成"过渡时间"之后，时长、距离与峰值都能由同一个旋钮连续调节，而且"每段多长"在四种型号之间口径一致。
     *
     * <p>调大的代价是每趟多花时间（T 每加 1 刻，每个加/减速段各多约 1.5 刻、多走 {@code 0.75·v} 格）
     * 与推背感变淡；调小的代价是启停变突兀。调完务必按 docs/TESTING.md 的乘坐类条目实机确认。
     */
    public static final int CRUISE_RAMP_TICKS = 32;
    /**
     * 轿厢加速度的<b>硬上限</b>：0.15 格/刻² = 60 格/秒²，防止调参把加速度放到乘客无法承受。
     *
     * <p>注意这是兜底，不是工作点：四种型号实际使用的加速度上限由
     * {@code MotionProfile.forCruiseSpeed} 按 {@link #CRUISE_RAMP_TICKS} 与巡航速度算出
     * （{@code v / T}：重载 0.0041667、普通/观光 0.00625、高速 0.015625），只有把
     * {@link #CRUISE_RAMP_TICKS} 调到极小值（小于 {@code v / 0.15}）时才会先撞上这条线，
     * 此时加/减速段会比 1.5·T 更短。四种型号共用同一个上限，因此换型号只改变巡航速度。
     */
    public static final double MAX_ACCELERATION = 0.15;
    /**
     * 到站误差容限：1e-7 格。
     * 双精度运动刻意不引入“最小位移量子”，最后一步不足 {@link #SPEED} 时直接吸附到目标值，
     * 但仍保留该容限用于请求判定与门联锁的“精确到站”语义（避免浮点残差被当成未到站）。
     */
    public static final double POSITION_EPSILON = 1.0e-7;
    /**
     * 客户端位置匹配容差：0.01 格（1 厘米），只用于"门扇进度跟随轿厢门"这类显示与碰撞取值。
     *
     * <p>为什么不能沿用 {@link #POSITION_EPSILON}：客户端实体坐标来自原版位置包，会被量化到
     * 1/4096 格（约 2.4e-4 格），也不保证与站点高度逐位相等。服务端联锁的"精确到站"语义
     * 仍然只用 {@link #POSITION_EPSILON}，本常量只放宽客户端侧的进度采样，不参与联锁判定。
     */
    public static final double SYNC_POSITION_EPSILON = 0.01;
    /** 开关门单程耗时：20 刻 = 1 秒；门联锁进度 door 每刻推进 1/DOOR_TICKS。 */
    public static final int DOOR_TICKS = 20;
    /**
     * 开门后的最短停留 40 刻 = 2 秒：期间没有新请求到点就自动关门。
     *
     * <p>注意"无请求"也会关门：停留时间一到，轿厢一律把门关上并停在本层待命（相位停在 MOVING、
     * 无目的站），不再敞着门无限等待——与面板上手动按关门键的落点完全一致。有任何新请求
     * （轿厢内选站或厅外呼叫）都会把停留时间续满，因此有人在门口按按钮时门不会关。
     */
    public static final int DWELL_TICKS = 40;
    /** 请求队列上限 128：避免失控或恶意请求让队列无界增长，溢出时 ElevatorController.request 返回 false。 */
    public static final int MAX_REQUESTS = 128;
    /**
     * 读档后等待"存档时在车上的乘客"回到世界的上限刻数：600 刻 = 30 秒。
     *
     * <p>为什么必须等：轿厢是区块实体，随区块一起载入；玩家实体由登录流程单独载入，一定晚于
     * 区块实体。若读档后立刻继续行程，轿厢会在乘客还没回到世界之前先开走，乘客随后被放回自己的
     * 存档坐标——也就是已经空掉的井道，于是掉出电梯。等待期间轿厢保持静止，乘客一出现就放回厢内。
     *
     * <p>为什么必须有上限：乘客可能永远不再回来（掉线、退服）。超时后行程照原计划继续，
     * 不会把电梯永久钉死。正常重进游戏时"轿厢所在区块载入 → 玩家实体载入"只隔几秒，
     * 30 秒是留足余量的兜底值。
     */
    public static final int RIDER_WAIT_TICKS = 30 * TICKS_PER_SECOND;
    // Landing doors occupy local Z=1.3125..1.5. Recess the entire front, not just
    // the leaves, so the floor, roof and side walls also clear the landing frame.
    /**
     * 轿厢正面内收后的 Z 坐标：1.3 格（轿厢本地坐标，正面朝 +Z）。
     * 楼层门占据本地 Z=1.3125..1.5，所以不只是门扇，正面整体（地板、顶板、侧墙）都内收到 1.3，
     * 与楼层门框留约 0.0125 格间隙，避免门与门框面重叠导致的渲染闪烁（z-fighting）。
     */
    public static final double CABIN_FRONT_Z = 1.3;
    /** 轿厢门区的背面 Z 坐标 = 正面内收 0.2 格；门区占据 [CABIN_DOOR_BACK_Z, CABIN_FRONT_Z]，
     * 两扇对开滑门整层占满这 0.2 格厚，见 {@link SlidingDoor}。 */
    public static final double CABIN_DOOR_BACK_Z = CABIN_FRONT_Z - .2;
    /** 连续超过 10 刻收不到新样本时停止本地乘客绑定；平台保持最后收到的位置，不外推。 */
    public static final int MOTION_STALE_TICKS = 10;
    /**
     * 单包位移超过 4.0 格即视为传送/瞬移（例如 restore() 后的强制归位、管理员传送），
     * 客户端不把附近玩家随此类异常跳变一起移动。
     */
    public static final double MOTION_SNAP_DISTANCE = 4.0;
    /**
     * 到站后补发 4 刻静止位置，确保客户端采用精确到站值；不延迟服务端开门。
     */
    public static final int MOTION_SETTLE_TICKS = 4;
    /** 一次性事件音效（开关门、到站等）的音量。 */
    public static final float EVENT_VOLUME = .8f;
    /** 运行中持续音效（轿厢移动）的音量：低于事件音量，以免盖过开关门等关键提示音。 */
    public static final float RUNNING_VOLUME = .6f;
    /** 音效基准音高 1f：不升调也不降调，保持一致听感。 */
    public static final float SOUND_PITCH = 1f;
}
