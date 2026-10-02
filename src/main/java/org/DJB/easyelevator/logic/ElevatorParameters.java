package org.DJB.easyelevator.logic;

/**
 * Compile-time tuning values. Units and coupled geometry are documented in docs/PARAMETERS.md.
 *
 * <p>职责：整个模组的编译期调参常量集中处——运行速度、门时序、请求队列上限、轿厢正面几何内收量，
 * 以及客户端运动时间线的插值与重置阈值都在这里定义，便于在脱离游戏的前提下单测与统一调参。</p>
 *
 * <p>在整体架构中的位置：logic 包的最底层，不引用任何 Minecraft 类；ElevatorController、
 * ElevatorLine/CabinEntity、client/MotionTimeline 都从这里取值，保证服务端状态机与客户端插值口径一致。</p>
 *
 * <p>单位约定：格 = 方块（block），刻 = tick，1 秒 = {@link #TICKS_PER_SECOND} 刻；
 * 速度单位为格/刻。几何常量以轿厢本地坐标系（正面朝 +Z）给出，长度单位均为格。</p>
 */
public final class ElevatorParameters {
    /** 工具类，禁止实例化：所有成员都是 static final 常量。 */
    private ElevatorParameters() { }
    /** 每游戏秒的刻数：Minecraft 固定 20 刻/秒，用于在“秒”与“刻”之间换算。 */
    public static final int TICKS_PER_SECOND = 20;
    /** 轿厢运行速度：0.20 格/刻 = 4 格/秒，为旧版 0.10 格/刻的两倍。 */
    public static final double SPEED = 0.20; // 4 blocks/second, twice the original 0.10
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
    /** 开门后至少停留 40 刻 = 2 秒；期间无新请求则保持开门。 */
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
    /** 轿厢门背面 Z 坐标 = 正面内收 0.2 格；门扇厚度方向占据 [CABIN_DOOR_BACK_Z, CABIN_FRONT_Z]。 */
    public static final double CABIN_DOOR_BACK_Z = CABIN_FRONT_Z - .2;
    /**
     * 客户端插值延迟 2 刻（约 100 毫秒）：采样时回退这么久再做插值，用来吸收网络抖动。
     * 代价是渲染与乘客镜头比服务端慢约 100 毫秒，换取运动平滑。
     */
    public static final int INTERPOLATION_DELAY_TICKS = 2;
    /** 运动时间线保留的样本上限：只保留最近 32 个服务端位置样本，使内存占用有界。 */
    public static final int MOTION_HISTORY_SIZE = 32;
    /**
     * 样本间隔超过 20 刻即判定时间线不连续（换次行程、区块重载、长时间丢包），
     * 整体清空并从当前值重新起算，避免把两段彼此无关的运动插值连成一条假轨迹。
     */
    public static final int MOTION_RESET_GAP_TICKS = 20;
    /** 连续超过 10 刻收不到新样本即判定时间线陈旧，渲染退回服务端同步值而不继续插值。 */
    public static final int MOTION_STALE_TICKS = 10;
    /**
     * 单包位移超过 4.0 格即视为传送/瞬移（例如 restore() 后的强制归位、管理员传送），
     * 时间线直接吸附到新位置，而不是插值出一条横穿井道的假轨迹。
     */
    public static final double MOTION_SNAP_DISTANCE = 4.0;
    /**
     * 到站后需稳定 4 刻（= 插值延迟 2 刻 + 2 刻）才认定运动结束，
     * 让带延迟的插值显示先追上真实到站值再停止修正，避免到站瞬间的位置抖动。
     */
    public static final int MOTION_SETTLE_TICKS = INTERPOLATION_DELAY_TICKS + 2;
    /** 一次性事件音效（开关门、到站等）的音量。 */
    public static final float EVENT_VOLUME = .8f;
    /** 运行中持续音效（轿厢移动）的音量：低于事件音量，以免盖过开关门等关键提示音。 */
    public static final float RUNNING_VOLUME = .6f;
    /** 音效基准音高 1f：不升调也不降调，保持一致听感。 */
    public static final float SOUND_PITCH = 1f;
}
