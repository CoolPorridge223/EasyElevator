package org.DJB.easyelevator.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-authoritative, deterministic controller; deliberately independent of rendering/Minecraft.
 *
 * <p>职责：电梯核心状态机。它只操作“轿厢当前 Y（格）”“目标站点”“门联锁进度”“请求队列”“厅外呼叫”这些纯数据，
 * 所有世界查询都通过 {@link Environment} 回调注入，因此整个类不引用任何 Minecraft 类，
 * 可以脱离游戏做单元测试，并且行为完全确定（同一输入序列必然得到同一输出）。</p>
 *
 * <p>在整体架构中的位置：服务端权威。AbstractCabinEntity（及其四个子类：普通 / 高速 / 观光 / 重载）每刻调用 {@link #tick(double, Environment)}，
 * 用匿名 Environment 实现把 valid/canMove/doorwayBlocked/arrived/outOfPassengerNumLimit 这些世界查询喂进来；
 * 状态机产出的 phase/door/target 再由服务端同步给客户端，客户端只做只读渲染与镜头插值。
 * <b>运动形状委托给 {@link MotionProfile}</b>：状态机只决定"什么时候能走、往哪走"，每刻向曲线索取
 * 位移，因此门时序与联锁不受运动形状影响。</p>
 *
 * <p>状态迁移（{@link Phase}）：OPEN -> CLOSING -> MOVING -> OPENING -> OPEN 循环；
 * BLOCKED 表示受阻（断轨、线路朝向不一致、运行区域有方块或实体障碍、区块未加载、目的站门被拆），
 * 它不是失败而是暂停——条件恢复后继续原行程；OVERLOAD 表示超载（门开着、不派发行程，人走出去就回到 OPEN），
 * 同样不是失败。</p>
 *
 * <p>两类请求（现实电梯的"集选控制"）：
 * <ul>
 *   <li><b>轿厢内选站</b> {@link Stop}：由选站面板发出，是"目的层"，没有方向，任何行程方向都能服务；</li>
 *   <li><b>厅外呼叫</b> {@link HallCall}：由楼层门上的上/下按钮发出，<b>带方向</b>——上行呼叫只由正在上行的
 *       轿厢顺路接走，下行呼叫同理。呼叫会一直保留（门上的按钮保持点亮）直到轿厢真的到站开门。</li>
 * </ul>
 * 调度规则（{@link #select(double)}，确定性、无饥饿、无空转）：
 * ① 本次停靠的厅外请求单独认领；② 起始方向看最早请求与轿厢的相对位置；
 * ③ 先找当前方向前方可服务的最近请求，再到前方反向厅呼的最远端开始回程接人；
 * ④ 前方没有任务后才换向，另一侧同样先顺路、再选回程起点，
 * 保证任何请求最终都会被服务。同层双向呼叫到站时暂留两灯，关门前按下一程方向认领一条，
 * 另一条留待返回；没有外层任务时才允许分两次停留原地换向服务。</p>
 *
 * <p>关键不变量：门未完全关闭（door == 0）不得移动；楼层门只在轿厢精确到站
 * （误差 &lt;= {@link ElevatorParameters#POSITION_EPSILON}）且轿厢门正在打开时才开启联锁；
 * 轿厢选站不重复，厅外呼叫按站点和方向去重；同一时刻最多只有一个 target；
 * 单刻位移恒 ≤ 本型号巡航速度（S 形曲线的速度上限），因此"到站不跨格"的前提继续成立。</p>
 */
public final class ElevatorController {
    /**
     * 状态机阶段。
     * OPEN = 已开门并停留等待、CLOSING = 正在关门、MOVING = 正在运行、
     * OPENING = 正在开门、BLOCKED = 受阻暂停（可恢复，不是失败）、
     * OVERLOAD = 超载（门保持全开、不派发行程，人走出去就恢复 OPEN）。
     *
     * <p>OVERLOAD 由 {@link Environment#outOfPassengerNumLimit()} 驱动，只可能从 OPEN 进入、也只能回到 OPEN：
     * 它<b>不是</b>故障（{@code ElevatorStatus.faulted} 只看 BLOCKED），门在整段时间里都是全开 1.0，
     * 因此它不参与门联锁与开门键可用性的判定——恰恰相反，乘客必须能走出去，门必须一直开着。
     * 关门途中发现超载会先切回 OPENING 把门重新打开（见 {@link #tick}），
     * 因此「门正在关、又挤进来把人挤超限」的实机表现就是"门关到一半又开回去"（与防夹同一套反向动作）；
     * 而在 OVERLOAD 相位按关门键会被直接拒收（{@link #forceClose} 只受理 OPEN/OPENING），
     * 客户端面板上那个键也本来就是灰的。
     *
     * <p>枚举顺序即同步字段的序号（{@code AbstractCabinEntity.PHASE} 存 {@code ordinal()}），
     * 因此<b>只能往后追加</b>、不能插队或改名；旧存档按名字读回（{@code Phase.valueOf}），
     * 不认识的阶段一律降级为 BLOCKED。
     */
    public enum Phase { OPEN, CLOSING, MOVING, OPENING, BLOCKED, OVERLOAD }
    /**
     * 当前承诺的服务方向（现实电梯的"集选方向"）。
     * UP/DOWN = 正在按这个方向顺路接人，NONE = 空闲、下一次请求到来时重新决定。
     */
    public enum Travel { UP, DOWN, NONE }
    /**
     * 一个站点（stop）：一扇完整 3x3 楼层门的底部中心方块位置。
     *
     * @param id 站点唯一标识，取根方块 BlockPos.asLong()，用于去重、查表与存档
     * @param y 站点高度（单位：格），即轿厢停靠时的底部中心 Y，与根方块同 Y
     */
    public record Stop(long id, int y) { }
    /**
     * 一条厅外呼叫：楼层门上的"上行 / 下行"按钮。
     *
     * @param id 站点唯一标识（同 {@link Stop#id()}），到站清扫与存档都用它
     * @param y 站点高度（单位：格）
     * @param up true = 上行按钮，false = 下行按钮；同一站点两个方向是两条独立呼叫
     */
    public record HallCall(long id, int y, boolean up) { }

    /**
     * <b>本次开门停留</b>（一次停靠会话）的厅外服务状态：停在哪个站点、已认领了哪个方向。
     *
     * <p>为什么需要它：同一层可能有上行与下行两条独立呼叫，轿厢到站时<b>不能</b>把两盏灯一起熄掉
     * （那等于"我还没打算往那边走就替乘客撤销了请求"）。于是到站先把这场"停靠会话"记下来：
     * <ul>
     *   <li>只有一条呼叫 → 直接认领它的方向（{@link #arriveAtStation} 立刻清灯）；</li>
     *   <li>两条呼叫都有 → {@code served = NONE}：两盏灯都留着，等这一趟<b>实际要往哪边开</b>
     *       在 {@link #prepareDeparture} 里明确（或等轿厢在某层换向时）再认领其中一条；</li>
     *   <li>会话持续到轿厢离开该站（{@link #atServiceStation} 变假）为止；期间
     *       {@link #deferredHere} 会让"最近站选择"跳过这条呼叫，避免把它当成零距离任务就地消费掉。</li>
     * </ul>
     *
     * <p>随存档保存（{@code StopService} + {@code StopService.Served}），因为"这次停靠还没认领方向"
     * 这件事跨越保存/重进必须延续：读档后若把它当成"没停过"，轿厢会重新选站、把已经服务过的方向
     * 再跑一趟。旧存档没有这个字段时有 {@link #recoverStopService} 兜底。
     *
     * @param station 本次停靠的站点（不可为 null）
     * @param served 已认领的方向；{@link Travel#NONE} = 还没认领，等这一程方向明确
     */
    public record StopService(Stop station, Travel served) {
        public StopService {
            java.util.Objects.requireNonNull(station);
            java.util.Objects.requireNonNull(served);
        }
    }

    /**
     * 世界查询回调：让纯 Java 状态机在不引用 Minecraft 类的前提下感知世界。
     * 实现方是 AbstractCabinEntity 中的匿名类，在每次 tick() 内同步调用，返回结果只对本次调用有效。
     */
    public interface Environment {
        /**
         * 站点是否仍然有效（根方块还在、整扇 3x3 门完整且朝向一致、区块已加载）。
         *
         * @param stop 待校验的站点
         * @return true 表示可以继续把它当作目的站或排队站点
         */
        boolean valid(Stop stop);
        /**
         * 从 from 运行到 to 的这一步是否允许（井道 3x3 预留空间内无方块/实体障碍、相关区块已加载）。
         *
         * @param from 当前 Y（单位：格）
         * @param to 提议的下一 Y（单位：格）
         * @return true 表示允许移动这一步；false 会让状态机转入 BLOCKED 而不是硬闯
         */
        boolean canMove(double from, double to);
        /**
         * 轿厢门口是否有活体（防夹判定）。
         *
         * @return true 表示此时关门会夹到实体，必须改为重新开门
         */
        boolean doorwayBlocked();
        /**
         * 当前让轿厢走不动的那些原因是否已经消失（"故障是否已解除"）。
         *
         * <p>为什么不能只看相位：故障期间乘客可以按开门键脱困（见 {@link #canOpenDoor}），
         * 那一按会把相位从 BLOCKED 改成 OPENING/OPEN，于是"相位不是 BLOCKED"既可能表示"故障没了"，
         * 也可能表示"门开着"。要判断"故障期间开的门该不该关回去"，必须由实现方直接回答
         * "现在能不能继续走"，而不是从相位猜。
         *
         * <p>实现方只需回答"上一次让 {@link #canMove} 返回 false 的原因还在不在"：
         * 断轨、朝向不一致、井道有方块或实体障碍、区块未加载、目的站的门被拆、读档后名册乘客尚未归位。
         * 本方法必须<b>无副作用</b>，且允许在 {@code target == null}（目的站已被清空）时调用。
         *
         * @return 可以立刻继续原行程时为 true；仍被故障挡住时为 false
         */
        boolean canResume();
        /**
         * 本厢此刻是否超载（厢内人数超过限载人数）。
         *
         * <p>只由轿厢型号决定：状态机不认识"限载几人"，它只问这一句。实现方（{@code AbstractCabinEntity}）
         * 负责把"限载 0 或负数 = 不限载"这条规则落实掉，因此普通 / 高速 / 观光三型恒返回 false，
         * 只有重载型号会在人数超过 {@link ElevatorParameters#HIGH_PASSENGER_NUM_LIMIT} 时为真。
         *
         * <p>调用时机：{@link #tick} 在 OPEN 与 CLOSING 两个阶段各问一次（门开着的时候才需要判超载）。
         * 本方法必须<b>无副作用</b>，且对同一次 tick 内的重复调用返回同一个值。
         *
         * @return 超载时为 true；不限载的型号恒为 false
         */
        boolean outOfPassengerNumLimit();
        /**
         * 已精确到站的副作用回调：由实现方负责开启楼层门联锁、播放音效、更新方块状态等。
         *
         * <p><b>调用时机有个坑</b>：本回调发生在"最后一步的位移被实现方写回实体之前"——
         * {@link ElevatorController#tick} 先 {@code y = target.y(); env.arrived(target);}，
         * 实现方随后才算 {@code dy = nextY - getY()} 并 {@code setPosition}。
         * 因此回调里读"实体的当前坐标"拿到的仍是<b>最后一步之前</b>的位置，与站点高度相差
         * 恰好是这一步的位移。需要按楼层定位什么（例如找这一站的门）时，请直接用
         * {@code stop.y()}，不要拿实现方自己的坐标去反推。
         *
         * @param stop 到达的站点
         */
        void arrived(Stop stop);
    }
    /** 默认速度的只读副本（单位：格/刻），转发自 {@link ElevatorParameters#SPEED}；普通轿厢即用此值。 */
    public static final double SPEED = ElevatorParameters.SPEED;
    /**
     * 本实例的巡航速度上限（单位：格/刻）。
     *
     * <p>为什么要做成实例字段：普通 / 高速 / 观光三种轿厢共用同一个状态机，差别只有速度
     * （高速 = {@link ElevatorParameters#HIGH_SPEED} = SPEED 的 2.5 倍）。速度恒定，因此这里
     * 是 final —— 状态机仍然确定、仍然可以脱离游戏单测，只是不再假设"全世界只有一个速度"。
     *
     * <p>自 S 形曲线（{@link MotionProfile}）接入后，它不再等于"每刻固定步长"，而是曲线的
     * <b>巡航速度上限</b>：启动与到站的若干刻里实际步长小于它，中段才等于它。因此
     * "单刻位移 ≤ speed"这一到站精度前提仍然成立，门时序与联锁完全不受影响。
     */
    private final double speed;
    /**
     * 本实例的 S 形速度曲线（Jerk-limited profile）：负责回答"本刻该走多远"。
     *
     * <p>状态机只管"什么时候能走、往哪走、门怎么联动"，运动形状全部委托给它。曲线在构造时
     * 按巡航速度与 {@link ElevatorParameters#CRUISE_RAMP_TICKS} 解出加速度与 jerk 上限
     * （见 {@link MotionProfile#forCruiseSpeed(double)}），因此换型号只改变巡航速度与加/减速段的长度。
     */
    private final MotionProfile profile;
    /**
     * 当前曲线内的时间（刻），每次 {@link MotionProfile#plan} 之后从 0 开始重新计时。
     *
     * <p>用它按时间求值而不是"逐刻累加位移"：曲线每刻的位置由 t 唯一决定，因此不会有累加误差，
     * 也绝不外推；到站时曲线终点就是站点本身，最后一步自然精确落在站点高度上。
     */
    private double profileTick;
    /** 当前曲线的计划终点（格）：用来判断目的站是否变过（顺路改道 / 读档恢复），变了就重新规划。 */
    private double plannedTarget = Double.NaN;
    /** 曲线上一次求值得到的速度（格/刻，带符号）；只用于重规划时给曲线一个正确初值。 */
    private double velocity;
    /** 曲线上一次求值得到的加速度（格/刻²，带符号）；只用于重规划时给曲线一个正确初值。 */
    private double acceleration;
    /** 门时序与队列上限的只读副本，转发自 ElevatorParameters（DOOR_TICKS/DWELL_TICKS/MAX_REQUESTS）。 */
    public static final int DOOR_TICKS = ElevatorParameters.DOOR_TICKS,
            DWELL_TICKS = ElevatorParameters.DWELL_TICKS, MAX_REQUESTS = ElevatorParameters.MAX_REQUESTS;
    /** 轿厢内选站队列（FIFO）：同一站点不重复入队，长度受 MAX_REQUESTS 限制。 */
    private final ArrayDeque<Stop> queue = new ArrayDeque<>();
    /** 厅外呼叫列表（按登记顺序）：同一站点同一方向不重复；到站开门或门被拆时清除。 */
    private final List<HallCall> hallCalls = new ArrayList<>();
    /** 当前正在执行的目的站；为 null 表示空闲（开门停留中、或关着门停在本层待命、或受阻等待请求）。 */
    private Stop target;
    /** 当前阶段；初始为 OPEN，即轿厢落成时门是开的，便于立即上人。 */
    private Phase phase = Phase.OPEN;
    /** 门联锁进度：0 = 完全关闭（保留真实碰撞），1 = 完全打开；无量纲。 */
    private float door = 1;
    /** 开门后的剩余停留刻数；归零就关门（没有请求时也一样，关上门停在本层待命）。 */
    private int dwell = DWELL_TICKS;
    /** 当前承诺的服务方向：由 {@link #select(double)} 与 {@link #retarget(double)} 维护。空闲（没有任何请求）时<b>刻意保留</b>上一次的方向——那是停车待命期间的"上/下"记忆；只有 {@link #restore} 读档或从未收到过请求时才是 {@link Travel#NONE}。 */
    private Travel travel = Travel.NONE;
    /**
     * 当前目的站是"哪一条厅外呼叫"带来的（{@link Travel#NONE} = 目的站来自轿厢内选站）。
     *
     * <p>单向呼叫到站时据此决定熄灭哪个方向的呼叫灯：空车跨越方向去接人（例如上行去接"下行"呼叫）时，
     * 按钮方向与行驶方向相反，只看 {@link #travel} 会清错那一盏灯。此字段随目标存档；旧存档缺失时
     * 保守地把目标当成必须停靠的选站，不延长接客行程。双向呼叫由 StopService 等待下一程决定。
     */
    private Travel targetHallDirection = Travel.NONE;
    /**
     * 当前"停靠会话"（{@link StopService}）；null = 车体不在任何刚停靠过的站点上。
     *
     * <p>它由 {@link #arriveAtStation} 在到站时登记、在 {@link #prepareDeparture} 里结算，
     * 并在车体离开该站后自然失效（判据是"高度还对不对得上"，因此不需要额外的清理时机）。
     * 随存档保存——见 {@link StopService} 的说明。
     */
    private StopService stopService;
    /**
     * 旧存档第一次 tick 时，根据<b>真实位置</b>恢复停靠状态，而不是猜测完成了哪个方向。
     *
     * <p>2.3.0 之前没有 {@code StopService} 字段：这样的存档读进来时不知道"这一层是不是刚停过、
     * 认领过哪一边"，于是置位本标志，由 {@link #tick} 在第一刻用当前位置补出会话，之后清掉。
     * 保守原则：宁可把目标当成"必须停靠的选站"（不延长接客行程），也不凭空补一条已服务的呼叫——
     * 后者会让一盏灯永远亮着（请求看起来被服务了，实际没人来）。
     */
    private boolean recoverStopService;
    /**
     * 是否正处于故障（受阻暂停）之中，见 {@link #faulted()} 与 {@link Environment#canResume()}。
     *
     * <p>与 {@link #faulted()} 的区别：本字段是"故障期"的粘性记忆——从相位第一次变成 BLOCKED 起为真，
     * 一直到 {@code Environment.canResume()} 报告"能继续走了"才复位。用它的原因是相位会被门的开关改掉
     * （乘客按开门键脱困），不能拿相位判断故障是否仍在。
     */
    private boolean faulted;
    /**
     * 故障期间是否开过一扇"脱困门"：{@link #forceOpen} 在 {@link #faulted()} 时置位，
     * 故障解除后由 {@link #tick} 把这扇门关回去，再清掉它。
     *
     * <p>为什么需要它：故障时面板上的开门键可用，乘客可以主动开门走出轿厢。故障恢复后必须先把这扇门
     * 关回去再继续原行程——否则就会出现"门开着就开走"，违反"门未完全关闭不得移动"这条基本不变量。
     *
     * <p>它不写存档：读档后相位一律降级为 BLOCKED 且门置 0，不存在"存档里门开着"的情况。
     */
    private boolean recoveryOpen;

    /**
     * 用默认速度（{@link ElevatorParameters#SPEED}）构造状态机，即普通轿厢与观光轿厢。
     * 保留无参构造：既有测试与调用方按默认速度使用状态机，不必关心轿厢型号
     * （重载与高速型号分别用 {@link ElevatorParameters#LOW_SPEED} / {@link ElevatorParameters#HIGH_SPEED} 显式构造）。
     */
    public ElevatorController() { this(ElevatorParameters.SPEED); }

    /**
     * 用指定巡航速度构造状态机。
     *
     * @param speed 巡航速度上限（单位：格/刻）；非正数、NaN 或无穷大时退化为
     *              {@link ElevatorParameters#SPEED}，避免存档载入的坏值让轿厢永远到不了站
     */
    public ElevatorController(double speed) { this(speed, ElevatorParameters.CRUISE_RAMP_TICKS); }

    /**
     * 用指定巡航速度与加/减速过渡时间构造状态机。
     *
     * @param speed 巡航速度上限（单位：格/刻）；非法时退化为 {@link ElevatorParameters#SPEED}
     * @param rampTicks 从静止加到该巡航速度所需的刻数；越大加/减速段越长、越绵软，
     *                  非法时退化为 {@link ElevatorParameters#CRUISE_RAMP_TICKS}。
     *                  加速度与 jerk 上限都由它与速度推出（见 {@link MotionProfile#forCruiseSpeed}）。
     */
    public ElevatorController(double speed, double rampTicks) {
        this.speed = Double.isFinite(speed) && speed > 0 ? speed : ElevatorParameters.SPEED;
        // 曲线在构造时一次性注入速度/加速度/jerk 上限，运行中不变：状态机仍然完全确定、可脱离游戏单测。
        this.profile = MotionProfile.forCruiseSpeed(this.speed, rampTicks);
    }

    /** @return 本实例的巡航速度上限（单位：格/刻）：重载 0.1333、普通/观光 0.20、高速 0.50；只读，运行中不变。 */
    public double speed() { return speed; }

    /**
     * @return 本刻的瞬时速度（单位：格/刻，带符号；向下为负）。
     *         曲线未运行时为 0；它只反映当前曲线，不参与任何调度或门联锁判定。
     */
    public double currentSpeed() { return profile.idle() ? 0 : velocity; }

    /** @return 当前曲线的总时长（刻）：0 表示静止（门开着、已到站、或受阻等待）。 */
    public double profileTime() { return profile.totalTime(); }

    /** @return 当前曲线内的时间（刻），随每次 tick 前进，到站/重新规划时归零。 */
    public double profileTick() { return profileTick; }

    /** @return 当前状态机阶段（服务端权威，客户端只读同步用于渲染）。 */
    public Phase phase() { return phase; }
    /** @return 门联锁进度 0..1（0 关闭 / 1 打开，无量纲）。 */
    public float door() { return door; }
    /** @return 当前目的站；空闲或受阻等待时为 null。 */
    public Stop target() { return target; }
    /** 当前目标的厅呼来源；NONE 表示车内选站或来源未知，不能延后该站。 */
    public Travel targetHallDirection() { return targetHallDirection; }
    /** @return 轿厢内选站队列的不可变快照（按处理顺序），供服务端同步与存档使用。 */
    public List<Stop> pending() { return List.copyOf(queue); }
    /** @return 待服务的厅外呼叫的不可变快照（按登记顺序），供服务端同步（按钮点亮）与存档使用。 */
    public List<HallCall> hallCalls() { return List.copyOf(hallCalls); }
    /** @return 可空的停靠服务快照；存档必须与 HallCalls 一起保存。 */
    public StopService stopService() { return stopService; }
    /** @return 当前运行方向记忆；从未派车时为 {@link Travel#NONE}。 */
    public Travel travel() { return travel; }
    /** @return 是否还有任何未完成的请求（轿厢内选站或厅外呼叫）。 */
    public boolean hasRequests() { return !queue.isEmpty() || !hallCalls.isEmpty(); }

    /**
     * 提交一个乘梯请求（轿厢内选站）。
     *
     * @param stop 请求的站点（含 id 与高度）
     * @param y 轿厢当前 Y（单位：格），用于判断是否已经停在同一楼层
     * @return true 表示请求已被接受或已被现成行程覆盖；false 表示队列已满（&gt;= {@link #MAX_REQUESTS}）
     * 副作用：修改 queue；若轿厢已在本层且门处于 OPEN/OPENING，则把 dwell 续满以延长停留。
     * 说明：已等于 target 或已在 queue 中的站点直接视为成功，不重复入队；
     * 本层请求不再入队而只续满停留时间，否则会出现“刚关门又立刻开门”的抖动。
     */
    public boolean request(Stop stop, double y) {
        if (stop.equals(target)) {
            // 厅呼目的层现在也承载车内送客请求，必须停靠，不能再延长到更远的接客起点。
            targetHallDirection = Travel.NONE;
            return true;
        }
        if (queue.contains(stop)) return true;
        if (Math.abs(y - stop.y()) <= ElevatorParameters.POSITION_EPSILON && (phase == Phase.OPEN || phase == Phase.OPENING)) { dwell = DWELL_TICKS; return true; }
        if (queue.size() >= MAX_REQUESTS) return false;
        insertOrdered(stop); // 插入即重排序：队列始终按服务方向的位置顺序排列（见 insertOrdered）
        return true;
    }

    /**
     * 把一条轿厢内选站<b>按服务顺序</b>插入队列，而不是简单追加到队尾。
     *
     * <p>为什么插入就要重排：调度是按"运行方向上的位置顺序"停靠的（见 {@link #select(double)}），
     * 因此队列的顺序就代表实际停站顺序。运行途中新按的楼层如果顺路且更近，必须排到队首之前；
     * 反方向的请求排在当前方向的后面，等掉头之后自然轮到它。队列有序之后，
     * {@link #pending()}（面板的"停靠计划"、异常时的排查）读到的就是真实停站顺序。
     *
     * @param stop 待插入的站点
     * 副作用：修改 queue。方向为空闲（{@link Travel#NONE}）时直接追加到队尾（保持插入顺序，
     * 让"最早的请求"去决定起始方向），行为与旧版按到达顺序排队一致；方向已确定时才按该方向的
     * 停站顺序重排。
     */
    private void insertOrdered(Stop stop) {
        // 方向还没定（空闲、门开着等第一个请求）：保持插入顺序，让"最早的请求"决定起始方向
        // （initialTravel 读的就是队首），这样与旧版"按请求顺序"的手感一致。
        if (travel == Travel.NONE) { queue.addLast(stop); return; }
        var list = new ArrayList<Stop>(queue);
        list.add(stop);
        // 稳定排序：同高度（正常情况下不会出现）保持插入先后。
        list.sort(travel == Travel.DOWN
                ? (a, b) -> Integer.compare(b.y(), a.y())   // 下行：先到高的，再到低的
                : (a, b) -> Integer.compare(a.y(), b.y())); // 上行：先到低的
        queue.clear();
        queue.addAll(list);
    }

    /**
     * 登记一条厅外呼叫（楼层门上的上行 / 下行按钮）。
     *
     * <p>与轿厢内选站的区别：呼叫<b>带方向</b>，只有正在按该方向运行的轿厢才会顺路接走它
     * （见 {@link #select(double)}）；它留在列表里直到到站服务，或双向停靠时按下一程方向认领，
     * 期间门上的按钮保持点亮。
     *
     * <p>本层开门时，只有本次已认领方向的呼叫可以直接续满停留；另一方向必须登记。
     *
     * @param call 呼叫（站点 id / 高度 / 方向）
     * @param y 轿厢当前 Y（单位：格）
     * @return true 表示呼叫已被登记或已被现成状态覆盖；false 表示呼叫表已满（&gt;= {@link #MAX_REQUESTS}）
     */
    public boolean callHall(HallCall call, double y) {
        if (hallCalls.contains(call)) return true;
        if (Math.abs(y - call.y()) <= ElevatorParameters.POSITION_EPSILON
                && (phase == Phase.OPEN || phase == Phase.OPENING)) {
            if (stopService == null) stopService = new StopService(new Stop(call.id(), call.y()), Travel.NONE);
            if (stopService.station().id() == call.id() && stopService.served() == callDirection(call)) {
                dwell = DWELL_TICKS;
                return true;
            }
        }
        if (hallCalls.size() >= MAX_REQUESTS) return false;
        hallCalls.add(call);
        return true;
    }

    /**
     * 受阻暂停（故障）：断轨、朝向不一致、井道有方块或实体障碍、区块未加载、目的站的门被拆、
     * 或读档后名册乘客尚未归位。
     *
     * <p>本方法读的是 {@link #faulted} 这个"故障期"记忆，而不是相位——乘客开门脱困会把相位改成
     * OPENING/OPEN，那时故障依然存在（门开着不等于路通了）。故障期一直持续到
     * {@link Environment#canResume()} 报告能继续走为止。
     *
     * @return 当前处于故障期中时为 true
     */
    public boolean faulted() { return faulted; }

    /**
     * 开门键此刻是否应当可用。<b>服务端与客户端面板共用这一条判据</b>，避免"按钮亮着点了没反应"
     * 或"能开的时候按钮却是灰的"。
     *
     * <p>三种可用情形：
     * <ol>
     *   <li><b>正常停靠</b>：关着门停在某个站点、或停在站点待命（相位不是 MOVING，或 MOVING 但没有目的站）
     *       ——即到达某层之后的正常开门；</li>
     *   <li><b>故障脱困</b>：{@link ElevatorStatus#faulted} 为真，即"目的地还在却走不动"。这时刻意不看
     *       轿厢是不是正好停在站点上：恰恰是被卡在两层之间时才最需要开门；</li>
     *   <li><b>无站线路脱困</b>（{@code !hasStations}）：这条线路上连一扇完整的楼层门都没有
     *       （典型情形是"轿厢刚放到轨道上、还没建门"）。此时 {@link #select(double)} 永远选不出目的站，
     *       这辆车<b>再也不会动</b>，而 {@code hasStations} 为假时 {@code atStation} 必然为假——
     *       若只看"停在站点上才给开门"，门一关乘客就被永久锁在厢内。所以这种线路一律允许开门。</li>
     * </ol>
     *
     * <p>运行途中（{@link Phase#MOVING} 且目的站还没到）三种情形都不成立，因此"运行时禁止开门"
     * 这条语义完全保留——电梯运行时开门键会重新变灰。
     *
     * <p>做成 static 且<b>只吃入参、绝不查世界</b>的原因：客户端只有 {@code DataTracker} 同步出来的
     * phase / 目标高度，以及面板自己那份站点列表；它<b>拿不到线路</b>——
     * {@code AbstractCabinEntity.railX/railZ} 不进 DataTracker（客户端恒 0），
     * 所以客户端调用 {@code cabin.line()} 会去扫 (0, y, 0) 并（正常情况下）返回 null。
     * 一旦把世界查询写进这个判据，客户端就会永远落进"没有线路"那一支：开门键常亮、
     * 点下去却被服务端拒绝（这正是 2.2.1 修掉的那个 bug）。
     * 因此"有没有线路/站点"必须由<b>调用方</b>算好传进来：服务端自己扫线路，客户端用面板的站点列表。
     *
     * @param phase 当前相位（服务端读状态机，客户端读同步字段）
     * @param targetY 目的站高度（格）；没有目的站时为 {@link Integer#MIN_VALUE} 哨兵值
     * @param atStation 调用方提供的"车体是否精确停在某个完整站点上"（轿厢需要查线路站点，本类查不到）
     * @param hasStations 调用方提供的"这条线路上至少有一扇完整的楼层门"：
     *                    服务端 = 扫出来的线路非 null 且 {@code stops()} 非空；
     *                    客户端 = 选站面板里的站点列表非空（服务端在 {@code OpenPanel} 里下发的就是它）
     * @return 开门键可用时为 true
     */
    public static boolean canOpenDoor(Phase phase, int targetY, boolean atStation, boolean hasStations) {
        if (!hasStations) return true;                                    // 无站线路：车永远不会动，必须能开门出来
        if (ElevatorStatus.faulted(phase)) return true;                  // 故障脱困：不要求在站点上
        if (phase == Phase.MOVING && targetY != Integer.MIN_VALUE) return false; // 运行途中一律不可开门
        return atStation;
    }

    /**
     * 开门键此刻是否可用，判据见 {@link #canOpenDoor(Phase, int, boolean, boolean)} 的静态版本
     * （本方法只是把状态机里的 {@link #faulted} 与 {@link #target} 换成更准确的说法后转发过去，
     * 因此两边永远不可能分叉）。
     *
     * <p>这是<b>服务端权威版本</b>：它用 {@link #faulted} 这个准确的故障期记忆，因此在"乘客已经开门脱困、
     * 相位已变成 OPENING/OPEN"时也依然为真（门开着本来就是可开的）。客户端没有这个字段，用同步相位近似，
     * 见静态版本。
     *
     * <p><b>只允许服务端调用</b>：两个入参都由调用方从世界算出来；客户端的
     * {@code AbstractCabinEntity.railX/railZ} 不进 DataTracker（恒 0），算不出这两个事实（见静态版本说明）。
     *
     * @param atStation 车体是否精确停在某个完整站点上；轿厢用线路站点列表算好传进来（本类查不到世界）
     * @param hasStations 这条线路上是否至少有一扇完整的楼层门；同样由轿厢扫线路后传进来
     * @return 开门键可用时为 true
     */
    public boolean canOpenDoor(boolean atStation, boolean hasStations) {
        if (faulted) return true;                                  // 故障脱困：不要求在站点上（服务端独有的准确记忆）
        return canOpenDoor(phase, target == null ? Integer.MIN_VALUE : target.y(), atStation, hasStations);
    }

    /**
     * 面板"开门"键：重新打开轿厢门。
     *
     * <p>安全性前提：<b>调用方必须已经确认车体精确停靠在某个完整站点上</b>，或者在故障时
     * （{@link #canOpenDoor} 为真）调用。本类不认识世界里的站点，无法自己判断"是不是在半空"，
     * 那个 1e-7 格的到站校验由 {@code AbstractCabinEntity} 用线路站点列表完成。
     *
     * <p>三种情形：门已全开 → 续满停留时间（相当于"按住开门键"）；正在开门 → 无事可做；
     * 正在关门或门已关闭但停在站点（例如刚手动关门、即将出发）→ 反向重新开门，目的站保持不变。
     *
     * <p>纯状态切换，不改 queue/target/hallCalls：因此"开门"不会取消已经排好的行程。
     * 若此刻处于故障（{@link #faulted()}），这扇门就是"脱困门"——等故障解除时 {@link #tick} 会把它
     * 关回去再继续行程（判据是"上一刻还在故障、这一刻不在"，因此刚按下的开门不会被立刻关掉）。
     *
     * @return 指令是否被接受（门已经全关且正在别处运行时返回 false）
     */
    public boolean forceOpen() {
        if (faulted()) recoveryOpen = true; // 故障期间开门 = 脱困门，故障解除后由 tick 关回去
        if (phase == Phase.OPEN) { dwell = DWELL_TICKS; return true; }   // 已开：续满停留时间
        if (phase == Phase.OPENING) return true;                          // 正在开：无需变动
        if (phase == Phase.OVERLOAD) { dwell = DWELL_TICKS; return true; } // 超载时门本来就全开：只续停留时间（见 Phase#OVERLOAD）
        if (phase == Phase.CLOSING) { phase = Phase.OPENING; return true; } // 正在关：反向打开
        // 门已全关（door == 0）时直接开门：调用方已确认车体就在某一层，因此不会出现半空开门
        if (door <= 0) { phase = Phase.OPENING; return true; }
        return false;
    }

    /**
     * 面板"关门"键：立刻结束开门停留并关门；门已经关着时无事可做。
     *
     * <p>与"停留时间自然结束"的区别只有时机：本方法把剩余停留一次清零，因此按下去立刻关门，
     * 而正常流程是等 {@link ElevatorParameters#DWELL_TICKS} 走完再关。两者关门后的落点完全一致
     * ——门关到全闭后停在站点（相位停在 MOVING、target 为空），关着门等下一次呼叫。
     * 关门过程中仍然每刻检查门口是否有人，被夹住会重新开门（防夹不因手动操作而失效）。
     *
     * @return 指令是否被接受（门处于打开或开门过程中才接受）
     */
    public boolean forceClose() {
        if (phase == Phase.OPEN) { dwell = 0; phase = Phase.CLOSING; return true; }
        if (phase == Phase.OPENING) { phase = Phase.CLOSING; return true; }  // 正在开：反向关闭
        return false;
    }

    /**
     * 推进一个服务端刻。
     *
     * @param y 本刻开始时的轿厢底部中心 Y（单位：格）
     * @param env 世界查询回调，本方法内同步调用（valid/canMove/doorwayBlocked/arrived）
     * @return 本刻结束时的 Y（单位：格）；未发生移动时原样返回
     * 副作用：修改 phase/door/dwell/target/queue/hallCalls/travel；可能经 env.arrived 开门、播放音效、改方块状态。
     * 说明：到站时先把位置精确吸附到 target.y() 再回调 arrived，保证门联锁的 1e-7 到站判定一定成立；
     * 同层选站到站即完成；双向厅外呼叫延后到关门前按下一程方向认领，残留方向必须保留。
     */
    public double tick(double y, Environment env) {
        if (recoverStopService) {
            recoverStopService = false;
            if (stopService == null && (door > 0 || phase == Phase.CLOSING)) {
                final double restoredY = y;
                hallCalls.stream().filter(c -> Math.abs(c.y() - restoredY) <= ElevatorParameters.POSITION_EPSILON)
                        .findFirst().ifPresent(c -> stopService = new StopService(new Stop(c.id(), c.y()), Travel.NONE));
            }
        }
        if (stopService != null && (!atServiceStation(y) || !env.valid(stopService.station())
                || (phase == Phase.MOVING && door == 0 && target == null))) stopService = null;
        // 先剔除已失效的请求（门被拆、区块卸载）：避免把行程派发给已经不存在的站。
        queue.removeIf(s -> !env.valid(s));
        hallCalls.removeIf(c -> !env.valid(new Stop(c.id(), c.y())));
        if (target != null && !env.valid(target)) {
            // Never open between floors or forget a cancelled trip. A replacement request can recover it.
            // 目的站失效时绝不就地开门（会停在楼层之间），也不静默丢弃行程：
            // 清空 target 并转入 BLOCKED；新的请求（或站点恢复）可在 MOVING/BLOCKED 分支恢复行程。
            target = null;
            targetHallDirection = Travel.NONE;
            phase = Phase.BLOCKED;
            stopMotion(); // 目的站没了：曲线作废，速度/加速度清零，恢复行程时从静止重新规划
        }
        // 故障与"脱困门"的判定**必须放在 switch 之前**：switch 里任何一个分支都可能提前 break
        // （例如空闲待命时 `select(y)` 返回 null 直接 break），放在末尾就会被跳过，
        // 于是"停着但线路被拆光"这种故障永远登记不上，开门键虽然亮了、服务端却拒收指令。
        //
        // 判"故障还在不在"用的是 Environment.canResume()（= "上一次让 canMove 返回 false 的原因是否消失"），
        // 而不是相位：乘客按开门键脱困会把相位从 BLOCKED 改成 OPENING/OPEN，若拿相位当判据，
        // 就会被误判成"故障已解除"，门还没开就被同一刻关回去。
        boolean canResume = env.canResume();
        if (!canResume) {
            // 只要"现在走不了"就登记为故障。刻意不看相位，于是四类绝境都被覆盖：
            //   · 运行途中被挡（相位已是 BLOCKED）；
            //   · 目的站的门被拆（目标被清空、相位 BLOCKED）；
            //   · 停着却连线路都扫不出来（轨道或整条线路的门被拆光）——此时相位停在 MOVING 且无目的站，
            //     这辆车永远不会再动了，生存模式里进来的人必须能开门出去；
            //   · 读档后名册乘客还没归位（相位同样停在 MOVING，目的站还在）。
            faulted = true;
        }
        if (faulted) {
            // 故障**仍在**时：绝不在这里 return，也不能把相位改成 CLOSING。
            //   · 门有动作（OPENING/OPEN/CLOSING）就交给下面的 switch 继续推进——否则门会永远停在
            //     起始位置，面板却一直显示"正在开门"（实机踩过：早退把开门动画本身挡掉了）。
            //   · 运行时被挡的那种故障（相位 MOVING 且无目的站）才摆成 BLOCKED，让客户端显示"暂停"。
            //   · 门开着时不用额外阻止移动：MOVING/BLOCKED 分支开头就有 `if (door > 0) break;`。
            if (!canResume && phase == Phase.MOVING && target == null) phase = Phase.BLOCKED;
            // 故障**已解除**：若故障期间乘客开过脱困门（recoveryOpen），现在才把它关回去。
            // "门未完全关闭不得移动"不能因为故障恢复而破例；门关到全闭后 CLOSING 分支自己会切回
            // MOVING，目标与队列都还在，行程照原计划继续。
            // 注意 `canResume` 在玩家把门打开之后常常变真（门开着时路径本来就通畅），所以这个判断
            // 必须在"门已经开着"时才生效——否则会出现"按了开门键，门一晃就又关上"。
            if (canResume) {
                if (recoveryOpen && (door > 0 || phase == Phase.OPEN || phase == Phase.OPENING)) {
                    phase = Phase.CLOSING; // 关回脱困门；门关到全闭后自动续行
                } else {
                    recoveryOpen = false; // 没开过门，或门已经关好：脱困结束
                    faulted = false;      // 故障也解除：本刻起可以继续行程
                    // BLOCKED 是"故障期"的显示相位，故障没了就必须复位——否则会出现
                    // "客户端看相位仍是 BLOCKED（暂停）→ 开门键保持可用，服务端 faulted 已是 false
                    // → 拒收开门键"，按钮亮着却弹出"开门键只在轿厢停在某一层时有效"（实机踩过）。
                    // 复位成 MOVING 后由 MOVING 分支自然重新选站/继续原行程；门若还开着，那一分支的
                    // `if (door > 0) break;` 会先把它关完再走。
                    if (phase == Phase.BLOCKED) phase = Phase.MOVING;
                }
            }
        }

        switch (phase) {
            case OPEN -> {
                // 存在超载情况：门保持全开、不派发行程（也不走停留倒计时），等有人走出去再回来
                if (env.outOfPassengerNumLimit()) {
                    phase = Phase.OVERLOAD;
                    break;
                }
                // 开门停留倒计时；归零就关门——无论还有没有请求。
                if (dwell > 0) dwell--;
                // 注意：这里**不**把 travel 复位为空闲。停车待命（门开着等乘客）时要保留"刚才是上行还是下行"的
                // 记忆，否则"被下行呼叫叫到 5 层、乘客进厢按 6 层、楼下还有呼叫"会丢掉下行方向而先去 6 层。
                if (dwell == 0) {
                    // 停留时间到：先派发下一站（有请求时才可能选出目标），然后一律关门。
                    // 一个请求都没有时 select() 返回 null，于是门关到全闭后停在 MOVING 且无目的站
                    // ——即"关着门停在本层待命"，与手动按关门键的结果完全一致（见 forceClose）。
                    // 以前这里是"无请求就保持开门"，于是空闲的轿厢会一直敞着门；现在改成关门。
                    if (prepareDeparture(y)) phase = Phase.CLOSING;
                }
            }
            case CLOSING -> {
                // 超载：把正在关的门重新打开（先切 OPENING，门开到 1 之后由 OPEN 分支转入 OVERLOAD）。
                // 与防夹同一套做法——都是在"关门"这个过程里发现不该关，于是反向。
                if (env.outOfPassengerNumLimit()) { phase = Phase.OPENING; break; }
                // 防夹：关门过程中门口出现活体则立即反向开门；target 保留，重开后由 OPENING 归队。
                if (env.doorwayBlocked()) { phase = Phase.OPENING; break; }
                // 手动关门与自动关门采用同一次方向认领；重开后不能再消费另一方向。
                if (atServiceStation(y) && (target == null || stopService.served() == Travel.NONE)
                        && !prepareDeparture(y)) break;
                door = Math.max(0, door - 1f / DOOR_TICKS);
                // 用 .0001f 而非 0 判定到位：浮点逐刻累减永远取不到精确 0，阈值避免卡在关门状态。
                if (door < .0001f) {
                    door = 0; phase = Phase.MOVING;
                    if (target == null) stopService = null; // 本次停靠结束；后来的同层呼叫必须能重新开门。
                }
            }
            case MOVING, BLOCKED -> {
                // MOVING 与 BLOCKED 共用运行逻辑：BLOCKED 只是暂停，条件恢复后继续原行程。
                // 故障脱困期间（乘客开门走出被困的轿厢）不派发新行程：门一开就"继续任务"会违反
                // "门未完全关闭不得移动"。等门关回去之后这里自然会重新选站、继续原计划。
                if (door > 0) break;
                if (target == null) target = select(y);
                if (target == null) break;
                // 顺路改道：同向送客/接客可插停；纯反向接客可延长到最远端，再掉头顺路接人。
                retarget(y);
                // 规划 S 形曲线：目的站变了（开始、改道、读档恢复）就从"当前位置 + 当前速度/加速度"
                // 重新规划。曲线接受任意初速度，因此改道不会过冲；曲线终点就是站点本身，
                // 因此最后一步精确到站，无需最小位移量子或容差兜底。
                if (profile.idle() || plannedTarget != target.y()) {
                    plannedTarget = target.y();
                    profileTick = 0;
                    // 初速度/初加速度取自当前曲线（不是直接清零）：改道时车可能正以巡航速度前进，
                    // 必须让新曲线从真实状态接着算，否则会出现"瞬间刹停再起步"或"来不及减速"。
                    profile.plan(y, velocity, acceleration, target.y());
                }
                // 本刻位移由曲线按时间给出：启动时缓慢加速、中段巡航、到站前平滑减速，全程受
                // 速度/加速度/jerk 三重上限约束（见 MotionProfile）。
                double step = profile.advance(1, y, profileTick);
                double next = y + step;
                velocity = profile.velocityAt(Math.min(profileTick + 1, profile.totalTime()));
                acceleration = profile.accelerationAt(Math.min(profileTick + 1, profile.totalTime()));
                if (!env.canMove(y, next)) {
                    // 受阻：位置与时间轴都不前进（曲线按时间求值，不能"偷偷溜过去"），
                    // 并把曲线整体作废，恢复后从静止重新规划。
                    phase = Phase.BLOCKED;
                    stopMotion();
                    break;
                }
                phase = Phase.MOVING;
                profileTick++;
                y = next;
                // 曲线走完（曲线已静止在终点）时把它抹平到站点高度：距离小于到站容限的行程会被
                // MotionProfile 直接判为"已经在目标上"，此时本刻位移为 0，需要由这里补上最后这一丝
                // 残差（≤ POSITION_EPSILON = 1e-7 格，肉眼与碰撞都不可见），保证精确到站语义不变。
                // 只在曲线确实走到终点后才吸附：运行中恰好掠过该容限范围时必须继续正常行驶。
                if (profileTick >= profile.totalTime() && Math.abs(target.y() - y) <= ElevatorParameters.POSITION_EPSILON)
                    y = target.y();
                // 到站判定用 ==（而不是 epsilon）：曲线终点就是 target.y()，advance 的终点分支
                // 会把浮点残差一并抹平，因此这里能精确成立；其余情况由双精度精确比较即可。
                if (y == target.y()) {
                    // 先对齐再回调：保证 arrived 里做门联锁判定时位置已精确落在站点上。
                    y = target.y(); env.arrived(target); arriveAtStation(target); target = null; phase = Phase.OPENING;
                    stopMotion(); // 到站：曲线使命结束，下一次移动重新规划
                }
            }
            case OPENING -> {
                door = Math.min(1, door + 1f / DOOR_TICKS);
                // 同样用 .9999f 判定开到位，理由与 CLOSING 的到位阈值相同。
                if (door > .9999f) {
                    door = 1; phase = Phase.OPEN; dwell = DWELL_TICKS;
                    // Anti-crush reopening preserves the interrupted request.
                    // 已认领的停靠保留目的站，防止重开改变出发方向；途中脱困仍按原规则归队。
                    if (target != null && stopService == null) {
                        if (targetHallDirection == Travel.NONE) queue.addFirst(target);
                        target = null; targetHallDirection = Travel.NONE; stopMotion();
                    }
                }
            }
            case OVERLOAD -> {
                // 不再超载就回到 OPEN：门本来就是全开的（1.0），因此不需要任何开门动画，
                // 停留计时也从头开始（乘客走回去两个人再进来就要重新算时间）。
                // 这一步刻意不派发行程：即使队列里已经排好了目的站，也要等回到 OPEN 之后
                // 由 OPEN 分支的停留倒计时决定何时关门出发——"门没关就走"永远不成立。
                if (!env.outOfPassengerNumLimit()) { phase = Phase.OPEN; dwell = DWELL_TICKS; }
            }
        }
        if (stopService != null && !atServiceStation(y)) stopService = null;
        return y;
    }

    /**
     * 作废当前 S 形曲线：把速度、加速度与曲线内时间全部清零，并标记"下次移动要重新规划"。
     *
     * <p>调用时机都是"行程被中断/取消"：受阻、目的站被拆、防夹重开把目的站放回队列、以及读档恢复。
     * 曲线本身不接受"半途作废"，因此这里必须一并清零，否则恢复行程时会带着一段属于旧曲线、
     * 与新目的站方向都未必一致的速度继续跑。
     */
    private void stopMotion() {
        plannedTarget = Double.NaN;
        velocity = 0;
        acceleration = 0;
        profileTick = 0;
    }

    /**
     * 运行途中只在实际行驶方向前方重排，不回头追已驶过的请求。
     * 车内选站或同向厅呼目标只允许插入更近的顺路停靠；纯反向厅呼目标尚未接客，
     * 可以先服务前方的送客/同向呼叫，再到反向呼叫最远端开始回程。
     * 原厅呼始终留在 hallCalls，不得将它放入无方向的车内队列，否则会错误地提前停靠。
     */
    private void retarget(double y) {
        if (target == null) return;
        // 读档恢复时可能"只有目标、没有服务方向"（旧存档）：按目标相对位置补一个，否则顺路判断会失去参照。
        // 服务方向 = 轿厢朝当前目标的实际运行方向。这样"空车去接反方向呼叫"途中，凡是与轿厢同向的
        // 厅外呼叫（例如车向下开时二层的"下行"）都会被顺路接走；读档恢复出与行驶方向不一致的旧方向时，
        // 这里也会在下一 tick 自动纠正。
        travel = directionTowards(y, target.y());
        Pick ahead = nearest(y, travel, true, false);
        if (targetHallDirection != Travel.NONE && targetHallDirection != travel) {
            if (ahead == null) ahead = furthestReverseHallCall(y, travel, false);
            if (ahead != null && !ahead.stop().equals(target)) target = take(ahead);
            return;
        }
        if (ahead == null) return;
        if (ahead.stop().y() == target.y()) return; // 最近的就是当前目标：不动
        // 只有"严格更近"才改道：容差与到站判定同源，避免浮点误差导致来回切换。
        if (Math.abs(ahead.stop().y() - y) + ElevatorParameters.POSITION_EPSILON >= Math.abs(target.y() - y)) return;
        if (targetHallDirection == Travel.NONE) insertOrdered(target); // 只放回真正的车内选站；厅呼仍在 hallCalls
        target = take(ahead);    // 新目标（选站会出队；厅外呼叫保留在呼叫表里直到到站）
    }

    /** 双向到站先保留两灯，等待下一程；单向到站保持原来的服务时机。 */
    private void arriveAtStation(Stop station) {
        boolean up = hasHallAt(station, Travel.UP), down = hasHallAt(station, Travel.DOWN);
        Travel served = up && down ? Travel.NONE : targetHallDirection != Travel.NONE ? targetHallDirection : travel;
        // 轿厢选站到达换向层时，唯一的反向厅呼尚未服务；允许随下一程认领，保留原有端站换向行为。
        if ((up || down) && served != Travel.NONE && !hasHallAt(station, served)) served = Travel.NONE;
        stopService = new StopService(station, served);
        if (served != Travel.NONE) clearHallDirection(station, served);
        queue.removeIf(s -> s.y() == station.y());
        targetHallDirection = Travel.NONE;
    }

    private static Travel callDirection(HallCall call) { return call.up() ? Travel.UP : Travel.DOWN; }

    private boolean hasHallAt(Stop station, Travel direction) {
        return hallCalls.stream().anyMatch(c -> c.id() == station.id() && callDirection(c) == direction);
    }

    private void clearHallDirection(Stop station, Travel direction) {
        hallCalls.removeIf(c -> c.id() == station.id() && callDirection(c) == direction);
    }

    private boolean atServiceStation(double y) {
        return stopService != null && Math.abs(stopService.station().y() - y) <= ElevatorParameters.POSITION_EPSILON;
    }

    /** 本次停靠的厅外呼叫由 prepareDeparture 结算，不能被普通最近站选择当成零距离任务。 */
    private boolean deferredHere(HallCall call, double y) {
        return atServiceStation(y) && call.id() == stopService.station().id();
    }

    /** 返回 true 才能关门出发；false 表示原地认领了一个方向，需要完整等待一次。 */
    private boolean prepareDeparture(double y) {
        // 防夹/超载重开保留已承诺的目标；后续同向的沿途插单仍由 retarget 处理。
        if (target == null) target = select(y);
        if (target != null) {
            if (Math.abs(target.y() - y) <= ElevatorParameters.POSITION_EPSILON) {
                arriveAtStation(target);
                target = null;
                dwell = DWELL_TICKS;
                phase = door >= .9999f ? Phase.OPEN : Phase.OPENING;
                return false;
            }
            if (atServiceStation(y) && stopService.served() == Travel.NONE) {
                Travel departure = directionTowards(y, target.y());
                clearHallDirection(stopService.station(), departure);
                stopService = new StopService(stopService.station(), departure);
                travel = departure;
            }
            return true;
        }
        if (atServiceStation(y)) {
            // 没有外层任务才允许原地服务，每次最多清一条，并重置完整停留时间。
            Stop station = stopService.station();
            Travel direction = travel == Travel.NONE ? Travel.UP : travel;
            if (!hasHallAt(station, direction)) direction = opposite(direction);
            if (hasHallAt(station, direction)) {
                clearHallDirection(station, direction);
                stopService = new StopService(station, direction);
                travel = direction;
                dwell = DWELL_TICKS;
                phase = door >= .9999f ? Phase.OPEN : Phase.OPENING;
                return false;
            }
        }
        return true;
    }

    /**
     * 选站结果：站点 + 它的来源（厅外呼叫非空时表示这条请求带方向，选中后仍留在呼叫表里直到到站）。
     *
     * @param stop 要去的站点
     * @param hall 若来自厅外呼叫则为该呼叫，来自轿厢内选站时为 null
     */
    private record Pick(Stop stop, HallCall hall) { }

    /**
     * 选出下一个目的站并消费轿厢内选站；本次停靠的厅外呼叫由 prepareDeparture 单独结算。
     *
     * @param y 轿厢当前高度（格）
     * @return 下一个目的站；没有任何请求时返回 null。<b>注意不会复位服务方向</b>：
     *         停车待命期间的"上/下"记忆要留到下一次请求（真实电梯的厅外指示灯同理）。
     */
    private Stop select(double y) {
        // 没有任何请求：保持当前服务方向。停车待命期间的"上/下"记忆要留到下一次请求
        //（真实电梯的厅外指示灯同理），否则刚被下行呼叫叫来、乘客一按上层就丢掉了方向。
        if (!hasRequests()) return null;
        // ① 起始方向：由最早的请求决定——选站看它与轿厢的相对位置，厅外呼叫看车实际要往哪边开。
        if (travel == Travel.NONE) travel = initialTravel(y);
        // ② 当前层有请求就地开门：轿厢内选站（零距离行程）与"与本趟方向一致"的同层厅外呼叫。
        //    反方向的同层厅外呼叫故意不在这里处理——前方还有活时留给它自己的方向（等轿厢回头），
        //    前方确实没活了会在第 ④ 步掉头后就地服务。两种都不会把另一方向的呼叫连带清掉。
        Pick here = nearest(y, travel, false, true);
        if (here != null) return take(here);
        // ③ 只要"当前方向的前方还有请求"就继续这个方向（真实电梯的"跑完这一趟再掉头"）：
        //    a. 先挑该方向上真正顺路可服务的（轿厢内选站 + 同向厅外呼叫）；
        //    b. 前方只剩反向厅呼时，先到最远端再掉头顺路接人，与登记先后无关。
        //       例如车在 4 层，2 层和 1 层都按上行：无论谁先按，都先下到 1 层再上行接 2 层。
        Pick servable = nearest(y, travel, true, false);
        if (servable != null) return take(servable);
        Pick aheadAny = furthestReverseHallCall(y, travel, false); // 严格在前：本层的反方向呼叫交给第 ④ 步
        if (aheadAny != null) return take(aheadAny);
        // ④ 前方没有任务才掉头；另一侧仍先服务顺路请求，再选择反向接客的回程起点。
        travel = opposite(travel);
        Pick any = nearest(y, travel, true, false); // 含本层：掉头后同层呼叫也可就地服务
        if (any == null) any = furthestReverseHallCall(y, travel, true);
        if (any == null) any = nearest(y, null, false, false);
        if (any == null) { travel = opposite(travel); return null; }
        // 服务方向取"轿厢实际要走的那个方向"，而不是按钮自身的方向：车在 10 层、底层按了"上行"时，
        // 车必须向下开过去；若把服务方向记成 UP，一路上所有"与轿厢同向"的下行呼叫都会被漏接
        //（这正是"底层上行 + 二层下行，却先到底层再折返二层"的根因）。
        travel = directionTowards(y, any.stop().y());
        return take(any);
    }

    /**
     * 求从 {@code y} 到 {@code stationY} 的<b>实际运行方向</b>（同层时沿用当前服务方向）。
     *
     * <p>为什么需要它：厅外呼叫带的是"乘客想去的方向"，而轿厢的服务方向必须是"它实际要开的方向"，
     * 两者在"空车去接反方向呼叫"时正好相反。服务方向一旦记错，{@link #retarget} 就会把与轿厢同向的
     * 中途呼叫全部漏掉，看起来就是"明明顺路却不接"。
     *
     * @param y 轿厢当前高度（格）
     * @param stationY 目标楼层高度（格）
     * @return {@link Travel#UP} / {@link Travel#DOWN}；同层时返回当前服务方向（空闲则按上行）
     */
    private Travel directionTowards(double y,int stationY) {
        if (stationY > y + ElevatorParameters.POSITION_EPSILON) return Travel.UP;
        if (stationY < y - ElevatorParameters.POSITION_EPSILON) return Travel.DOWN;
        return travel == Travel.NONE ? Travel.UP : travel;
    }

    /**
     * 消费一次选中结果：轿厢内选站出队（成为 target；被防夹中断时由 OPENING 分支放回队首），
     * 厅外呼叫留在表里等待到站清扫。
     *
     * @param pick 选中结果
     * @return 目的站
     */
    private Stop take(Pick pick) {
        if (pick.hall() == null) queue.remove(pick.stop());
        // 记住这一趟回应的呼叫方向，供到站清扫使用（见 targetHallDirection）。
        targetHallDirection = pick.hall() == null ? Travel.NONE : (pick.hall().up() ? Travel.UP : Travel.DOWN);
        return pick.stop();
    }

    /**
     * 在候选集里挑距离 {@code y} 最近的请求。
     *
     * @param y 轿厢当前高度（格）
     * @param direction 只考虑该方向的厅外呼叫；为 null 时不做方向过滤。注意方向过滤在
     *                  {@code sameFloorOnly} 分支里<b>同样生效</b>（反方向那条呼叫必须留给它自己的行程，
     *                  否则停在本层时会把它就地"服务"掉，等于到站清错了灯）
     * @param aheadOnly true = 只考虑该方向上"前方"的楼层（上行含当前层，下行含当前层）
     * @param sameFloorOnly true = 只考虑与当前层同高的请求（用于"就地开门"分支）
     * @return 最近的请求；没有符合条件的返回 null
     *
     * <p>遍历顺序固定：先轿厢内选站（FIFO）再厅外呼叫（登记顺序），且只在严格更近时替换，
     * 因此同距离时"轿厢内选站优先、先登记者优先"，结果完全确定。
     */
    private Pick nearest(double y, Travel direction, boolean aheadOnly, boolean sameFloorOnly) {
        Pick best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Stop s : queue) {
            double distance = Math.abs(s.y() - y);
            if (sameFloorOnly ? distance > ElevatorParameters.POSITION_EPSILON : !along(s.y(), y, direction, aheadOnly)) continue;
            if (distance < bestDistance) { bestDistance = distance; best = new Pick(s, null); }
        }
        for (HallCall c : hallCalls) {
            if (deferredHere(c, y)) continue;
            double distance = Math.abs(c.y() - y);
            // 方向过滤对"同层"分支同样生效：厅外呼叫是带方向的，反方向那条必须留给它自己的行程
            //（否则停在本层时会把反方向呼叫就地"服务"掉，等于到站清错了灯）。
            if (direction != null && c.up() != (direction == Travel.UP)) continue;
            if (sameFloorOnly) { if (distance > ElevatorParameters.POSITION_EPSILON) continue; }
            else if (!along(c.y(), y, direction, aheadOnly)) continue;
            if (distance < bestDistance) { bestDistance = distance; best = new Pick(new Stop(c.id(), c.y()), c); }
        }
        return best;
    }

    /**
     * 选择当前行驶方向前方、按钮方向相反的最远呼叫，作为回程接客起点。
     * 下行接上行乘客先到最低呼叫层，上行接下行乘客先到最高呼叫层；同高度保持登记顺序。
     *
     * @param y 轿厢当前高度（格）
     * @param direction 当前服务方向
     * @param includeHere false = 跳过与轿厢同高的呼叫（本层的反方向呼叫留给"就地开门"那一步处理）
     * @return 最远的反向呼叫；没有符合条件的呼叫时返回 null
     */
    private Pick furthestReverseHallCall(double y, Travel direction, boolean includeHere) {
        Pick best = null;
        double bestDistance = -1;
        for (HallCall c : hallCalls) {
            if (deferredHere(c, y) || callDirection(c) != opposite(direction)) continue;
            if (!includeHere && Math.abs(c.y() - y) <= ElevatorParameters.POSITION_EPSILON) continue;
            double distance = Math.abs(c.y() - y);
            if (along(c.y(), y, direction, true) && distance > bestDistance) {
                best = new Pick(new Stop(c.id(), c.y()), c);
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * 判断某个楼层是否位于 {@code y} 沿 {@code direction} 的"前方"。
     *
     * @param stationY 楼层高度（格）
     * @param y 轿厢当前高度（格）
     * @param direction 服务方向；为 null 时视为"任意方向"
     * @param aheadOnly false = 不看前后，任何楼层都算
     * @return true 表示该楼层在该方向的前方（含与当前层同高）
     */
    private static boolean along(int stationY, double y, Travel direction, boolean aheadOnly) {
        if (!aheadOnly || direction == null) return true;
        double epsilon = ElevatorParameters.POSITION_EPSILON;
        return direction == Travel.UP ? stationY >= y - epsilon : stationY <= y + epsilon;
    }

    /**
     * 空闲时由最早的请求决定起始服务方向。
     *
     * @param y 轿厢当前高度（格）
     * @return 起始方向：有轿厢内选站时看第一个选站与轿厢的相对位置（与轿厢同层时这一条判不出方向，
     *         继续往下看厅外呼叫）；否则看<b>最早登记的那条厅外呼叫</b>在轿厢的哪一侧（注意不是按钮
     *         自身的方向：底层按"上行"而轿厢在楼上时，车必须向下开过去）；两者都判不出时按上行。
     */
    private Travel initialTravel(double y) {
        if (!queue.isEmpty()) {
            double delta = queue.peekFirst().y() - y;
            if (delta > ElevatorParameters.POSITION_EPSILON) return Travel.UP;
            if (delta < -ElevatorParameters.POSITION_EPSILON) return Travel.DOWN;
        }
        for (HallCall call : hallCalls) if (!deferredHere(call, y)) return directionTowards(y, call.y());
        return Travel.UP; // 只剩同层请求：调用点已先行处理，这里给一个确定值
    }

    /**
     * @param travel 当前方向
     * @return 相反方向；{@link Travel#NONE} 保持为 NONE
     */
    private static Travel opposite(Travel travel) {
        return travel == Travel.UP ? Travel.DOWN : travel == Travel.DOWN ? Travel.UP : Travel.NONE;
    }

    /**
     * 从存档恢复状态（轿厢载入 NBT 后调用）：不含厅外呼叫的旧版重载，等价于"没有厅外呼叫、方向未知"。
     *
     * @param phase 存档中保存的阶段
     * @param door 存档中保存的门进度
     * @param target 存档中保存的目的站，可为 null
     * @param pending 存档中保存的选站队列
     */
    public void restore(Phase phase, float door, Stop target, List<Stop> pending) {
        restore(phase, door, target, pending, List.of(), Travel.NONE);
    }

    /**
     * 从存档恢复状态（轿厢载入 NBT 后调用）。
     *
     * @param phase 存档中保存的阶段
     * @param door 存档中保存的门进度，会被夹取到 0..1 的合法区间
     * @param target 存档中保存的目的站，可为 null
     * @param pending 存档中保存的选站队列，会去重并截断到 {@link #MAX_REQUESTS}
     * @param calls 存档中保存的厅外呼叫，会去重并截断到 {@link #MAX_REQUESTS}
     * @param travel 存档中保存的服务方向；null 视为 {@link Travel#NONE}
     * 副作用：覆盖 phase/door/target/queue/hallCalls/travel/dwell，旧入口保守重建停靠状态；MOVING 降级为 BLOCKED 且门置 0。
     * 为什么：载入时不存在“正在运动”的合法状态（既没有上一刻位置也无从继续插值），
     * 降级为 BLOCKED 后由 tick() 先校验线路有效性再恢复运行，避免恢复出一条穿墙的行程。
     * @see #restore(Phase, float, Stop, List, List, Travel, StopService, Travel)
     */
    public void restore(Phase phase, float door, Stop target, List<Stop> pending, List<HallCall> calls, Travel travel) {
        restore(phase, door, target, pending, calls, travel, null);
    }

    /**
     * 含"停靠服务快照"的恢复入口：多传一个 {@link StopService}。
     *
     * <p>旧存档（或调用方拿不到会话时）传 {@code null}：本方法会把 {@code recoverStopService} 置位，
     * 由第一次 {@link #tick} 按真实位置保守恢复停靠状态，而不是凭空认领一个方向。
     * 目标来源按"未知"处理（{@link Travel#NONE}），即把当前目标当成必须停靠的选站。
     *
     * @param service 存档中保存的停靠会话；null = 旧存档/未知
     */
    public void restore(Phase phase, float door, Stop target, List<Stop> pending, List<HallCall> calls,
                        Travel travel, StopService service) {
        restore(phase, door, target, pending, calls, travel, service, Travel.NONE);
    }

    /**
     * 完整恢复入口（2.3.0 起的生产路径）：多传"当前目标来自哪条方向的厅外呼叫"。
     *
     * <p>为什么连这个也要存：它能决定"到站时熄哪一盏灯"。若读档后丢掉它，纯厅呼接客的行程
     * （目标来自厅外呼叫、不是车内选站）就会被当成车内选站，{@link #retarget} 于是不再允许它被顺路改写，
     * 双方向的剩余呼叫也只能等到最后一起清——表现为"读档后那盏灯要等很久才熄"。
     *
     * <p>保守校验：只有当目标确实还在、且该方向上确实还有呼叫时才采用存档里的来源，
     * 否则退回 {@link Travel#NONE}（不猜）。
     *
     * @param targetHallDirection 存档中保存的目标来源方向；null 或与实际不符时按 {@link Travel#NONE}
     */
    public void restore(Phase phase, float door, Stop target, List<Stop> pending, List<HallCall> calls,
                        Travel travel, StopService service, Travel targetHallDirection) {
        this.phase = phase; this.door = Math.max(0, Math.min(1, door)); this.target = target;
        // distinct() 去重、limit() 截断：防止被手工篡改或旧版本写坏的存档撑爆队列。
        queue.clear(); pending.stream().distinct().limit(MAX_REQUESTS).forEach(queue::addLast);
        hallCalls.clear(); calls.stream().distinct().limit(MAX_REQUESTS).forEach(hallCalls::add);
        this.travel = travel == null ? Travel.NONE : travel;
        this.stopService = service;
        this.recoverStopService = service == null;
        this.targetHallDirection = target != null && targetHallDirection != null && hasHallAt(target, targetHallDirection)
                ? targetHallDirection : Travel.NONE;
        dwell = DWELL_TICKS;
        // S 形曲线不从存档恢复：载入时不存在"正在运动"的合法状态（既没有上一刻位置也无从继续求值），
        // 因此曲线一律作废、速度归零，由 tick() 在重新校验线路后从静止重新规划。
        stopMotion();
        // 载入后门视为关闭，必须先完成关门流程才能移动（与“门未关闭不能移动”的不变量一致）。
        if (phase == Phase.MOVING) { this.phase = Phase.BLOCKED; this.door = 0; }
    }
}
