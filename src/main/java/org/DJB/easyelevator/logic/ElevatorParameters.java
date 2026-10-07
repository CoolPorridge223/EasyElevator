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
     * 普通 / 高速 / 观光三型轿厢的限载人数：<b>0 = 不限载</b>（加进来多少人就装多少人，即本功能之前的旧行为）。
     *
     * <p>为什么用 0 表示"不限"而不是随便填一个大数：这三型的轿厢本来就没有载客上限，
     * 判超载的条件必须是"限载人数为正"，否则 {@code 人数 > 0} 会在厢内站进第一个人时立刻成立，
     * 于是普通轿厢一到站就显示"超载"、门再也关不上（见 {@code AbstractCabinEntity.overloaded()}）。
     * 想给普通轿厢也加个上限，把这里改成正数即可，渲染与状态机读的是同一个值。
     */
    public static final int PASSENGER_NUM_LIMIT = 8;

    /**
     * 强力电梯的限载人数：20 人。厢内玩家数<b>超过</b>它时状态机进入
     * {@link ElevatorController.Phase#OVERLOAD}——门保持全开、不派发行程，直到有人走出厢门。
     *
     * <p>这一型与其它型号的外壳、门、速度完全相同，因此"能拉更多人"是靠更大的轿厢<b>用途</b>定义的：
     * 载客量写在这里、由内饰里的载重铭牌显示（`CabinRenderer` 把它画成红字），
     * 因此改这个数字不需要动任何贴图或几何。3x3x3 的净空（内缘 ±1.3 格）实际能站下 20 人上下，
     * 调大之后玩家会挤在一起但不会掉出轿厢；调小则更容易触发超载。
     */
    public static final int HIGH_PASSENGER_NUM_LIMIT = 20;

    /** 每游戏秒的刻数：Minecraft 固定 20 刻/秒，用于在“秒”与“刻”之间换算。 */
    public static final int TICKS_PER_SECOND = 20;
    /** 轿厢运行速度：0.20 格/刻 = 4 格/秒，为旧版 0.10 格/刻的两倍；普通轿厢与观光轿厢都用它。 */
    public static final double SPEED = 0.20; // 4 blocks/second, twice the original 0.10
    /**
     * 高速轿厢运行速度：{@link #SPEED} 的 2.5 倍 = 0.50 格/刻 = 10 格/秒。
     *
     * <p>只改速度，不改门时序、到站容限与其它任何参数：外观与普通轿厢完全一致，
     * 三型轿厢共用同一套状态机与几何，唯一差别是喂给 {@link ElevatorController} 的步长。
     * 仍满足 {@code 步长 + POSITION_EPSILON <= 1 格}，因此单刻位移不会跨过整格站点，
     * 到站吸附与井道扫描的精度语义保持不变。
     */
    public static final double HIGH_SPEED = SPEED * 2.5;
    /**
     * 轿厢从静止加速到<b>本型号巡航速度</b>所需的刻数：32 刻 = 1.6 秒。
     *
     * <p>这是 S 形曲线（{@link MotionProfile}）的<b>唯一舒适度旋钮</b>：加/减速段的时长、距离与
     * 加速度峰值全部由它和巡航速度推出（{@code forCruiseSpeed}），三种型号共用同一个"过渡时间"，
     * 因此高速梯的加/减速<b>距离</b>自然更长（速度越高、同样时间走得越远），不需要另一套参数。
     *
     * <p>它决定了三件互相绑定的量（给定巡航速度 v、本时间 T）：
     * <ul>
     *   <li><b>加/减速段时长</b> = T 刻（2 × T/2：加速度从 0 升到峰值再回落到 0）；</li>
     *   <li><b>加速度峰值</b> = v / T（该几何下三角形加速度波形的高度）；</li>
     *   <li><b>加/减速距离</b> = v · T / 2 格（T 刻内速度从 0 线性升到 v 的位移）。</li>
     * </ul>
     * 实测（1 格/刻² = 400 格/秒²）：
     * <table border="1">
     *   <tr><th>型号</th><th>巡航</th><th>加速度峰值</th><th>单段距离</th></tr>
     *   <tr><td>普通 / 观光</td><td>4 格/秒</td><td>0.00625 格/刻² ≈ 1.22 m/s²</td><td>3.2 格</td></tr>
     *   <tr><td>高速</td><td>10 格/秒</td><td>0.015625 格/刻² ≈ 3.05 m/s²</td><td>8.0 格</td></tr>
     * </table>
     *
     * <p>为什么不用固定的 jerk 上限：一旦把 jerk 写成常量，加速段形状就被它单独锁死——
     * 加速度峰值变成 {@code sqrt(jerk·Δv)}，无论把 {@link #MAX_ACCELERATION} 调到多大都够不着
     * （普通梯实际峰值只有 0.095 格/刻²），"加速距离"因此改不动。改成"过渡时间"之后，
     * 距离与峰值都能由同一个旋钮连续调节，而且"每段多长"在三种型号之间口径一致。
     *
     * <p>调大的代价是每趟多花时间（每个加/减速段各多 T/2 刻）与推背感变淡；
     * 调小的代价是启停变突兀。调完务必按 docs/TESTING.md 的乘坐类条目实机确认。
     */
    public static final int CRUISE_RAMP_TICKS = 32;
    /**
     * 轿厢加速度的<b>硬上限</b>：0.15 格/刻² = 60 格/秒²，防止调参把加速度放到乘客无法承受。
     *
     * <p>注意这是兜底，不是工作点：三种型号实际使用的加速度上限由
     * {@code MotionProfile.forCruiseSpeed} 按 {@link #CRUISE_RAMP_TICKS} 与巡航速度推出
     * （普通 0.00625、高速 0.015625），只有在 {@link #CRUISE_RAMP_TICKS} 被调到极小值时
     * 才会撞上这条线。三种型号共用同一个上限，因此换型号只改变巡航速度与加/减速段长度。
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
