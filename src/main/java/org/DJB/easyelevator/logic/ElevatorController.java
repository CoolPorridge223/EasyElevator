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
 * <p>在整体架构中的位置：服务端权威。AbstractCabinEntity（及其三个子类：普通 / 高速 / 观光）每刻调用 {@link #tick(double, Environment)}，
 * 用匿名 Environment 实现把 valid/canMove/doorwayBlocked/arrived 四个世界查询喂进来；
 * 状态机产出的 phase/door/target 再由服务端同步给客户端，客户端只做只读渲染与镜头插值。
 * <b>运动形状委托给 {@link MotionProfile}</b>：状态机只决定"什么时候能走、往哪走"，每刻向曲线索取
 * 位移，因此门时序与联锁不受运动形状影响。</p>
 *
 * <p>状态迁移（{@link Phase}）：OPEN -> CLOSING -> MOVING -> OPENING -> OPEN 循环；
 * BLOCKED 表示受阻（断轨、线路朝向不一致、运行区域有方块或实体障碍、区块未加载、目的站门被拆），
 * 它不是失败而是暂停——条件恢复后继续原行程。</p>
 *
 * <p>两类请求（现实电梯的"集选控制"）：
 * <ul>
 *   <li><b>轿厢内选站</b> {@link Stop}：由选站面板发出，是"目的层"，没有方向，任何行程方向都能服务；</li>
 *   <li><b>厅外呼叫</b> {@link HallCall}：由楼层门上的上/下按钮发出，<b>带方向</b>——上行呼叫只由正在上行的
 *       轿厢顺路接走，下行呼叫同理。呼叫会一直保留（门上的按钮保持点亮）直到轿厢真的到站开门。</li>
 * </ul>
 * 调度规则（{@link #select(double)}，确定性、无饥饿、无空转）：
 * ① 当前层有请求就地开门；② 起始方向由最早的请求决定（选站看相对位置、厅外呼叫看按钮方向）；
 * ③ 先在本侧前方找"顺路可服务"的最近请求（选站总是可服务，厅外呼叫必须方向一致），找不到就掉头再找；
 * ④ 两侧都没有顺路请求时（例如只有反方向的厅外呼叫）按距离选最近的请求，并让服务方向跟随它，
 * 保证任何请求最终都会被服务。到站开门时清掉本站的厅外呼叫与同层选站，按钮随之熄灭。</p>
 *
 * <p>关键不变量：门未完全关闭（door == 0）不得移动；楼层门只在轿厢精确到站
 * （误差 &lt;= {@link ElevatorParameters#POSITION_EPSILON}）且轿厢门正在打开时才开启联锁；
 * 轿厢选站队列与厅外呼叫列表都不出现重复站点；同一时刻最多只有一个 target；
 * 单刻位移恒 ≤ 本型号巡航速度（S 形曲线的速度上限），因此"到站不跨格"的前提继续成立。</p>
 */
public final class ElevatorController {
    /**
     * 状态机阶段。
     * OPEN = 已开门并停留等待、CLOSING = 正在关门、MOVING = 正在运行、
     * OPENING = 正在开门、BLOCKED = 受阻暂停（可恢复，不是失败）。
     */
    public enum Phase { OPEN, CLOSING, MOVING, OPENING, BLOCKED }
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
         * 已精确到站的副作用回调：由实现方负责开启楼层门联锁、播放音效、更新方块状态等。
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
     * <p>状态机只管"什么时候能走、往哪走、门怎么联动"，运动形状全部委托给它。曲线按型号注入
     * 速度、加速度与 jerk 上限，因此高速梯天然拥有更长的加/减速段（见
     * {@link ElevatorParameters#HIGH_SPEED_JERK}）。
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
    /** 当前承诺的服务方向：由 {@link #select(double)} 维护，空闲（没有任何请求）时复位为 {@link Travel#NONE}。 */
    private Travel travel = Travel.NONE;
    /**
     * 当前目的站是"哪一条厅外呼叫"带来的（{@link Travel#NONE} = 目的站来自轿厢内选站）。
     *
     * <p>到站清扫时据此决定熄灭哪个方向的呼叫灯：空车跨越方向去接人（例如上行去接"下行"呼叫）时，
     * 按钮方向与行驶方向相反，只看 {@link #travel} 会清错那一盏灯。读档恢复出的行程没有这个信息，
     * 退回按行驶方向清扫（最多多开关一次门，不会留下死呼叫）。
     */
    private Travel targetHallDirection = Travel.NONE;

    /**
     * 用默认速度（{@link ElevatorParameters#SPEED}）构造状态机，即普通轿厢与观光轿厢。
     * 保留无参构造：既有测试与调用方按默认速度使用状态机，不必关心轿厢型号。
     */
    public ElevatorController() { this(ElevatorParameters.SPEED); }

    /**
     * 用指定巡航速度构造状态机。
     *
     * @param speed 巡航速度上限（单位：格/刻）；非正数、NaN 或无穷大时退化为
     *              {@link ElevatorParameters#SPEED}，避免存档载入的坏值让轿厢永远到不了站
     */
    public ElevatorController(double speed) { this(speed, MotionProfile.defaultJerk(speed)); }

    /**
     * 用指定巡航速度与加加速度上限构造状态机。
     *
     * @param speed 巡航速度上限（单位：格/刻）；非法时退化为 {@link ElevatorParameters#SPEED}
     * @param jerk 加加速度上限（单位：格/刻³）；非法时按型号推出（见 {@link MotionProfile#defaultJerk(double)}）。
     *             jerk 越小，加/减速段越长：高速梯默认取普通梯的 0.4 倍，因此同样升到加速度上限
     *             需要 2.5 倍的时间，10 格/秒的"推背感"被摊得更开。
     */
    public ElevatorController(double speed, double jerk) {
        this.speed = Double.isFinite(speed) && speed > 0 ? speed : ElevatorParameters.SPEED;
        // 曲线在构造时一次性注入速度/加速度/jerk 上限，运行中不变：状态机仍然完全确定、可脱离游戏单测。
        this.profile = new MotionProfile(this.speed, ElevatorParameters.MAX_ACCELERATION, jerk);
    }

    /** @return 本实例的巡航速度上限（单位：格/刻）：普通 0.20、高速 0.50；只读，运行中不变。 */
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
    /** @return 轿厢内选站队列的不可变快照（按处理顺序），供服务端同步与存档使用。 */
    public List<Stop> pending() { return List.copyOf(queue); }
    /** @return 待服务的厅外呼叫的不可变快照（按登记顺序），供服务端同步（按钮点亮）与存档使用。 */
    public List<HallCall> hallCalls() { return List.copyOf(hallCalls); }
    /** @return 当前承诺的服务方向；空闲时为 {@link Travel#NONE}。 */
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
        if (stop.equals(target) || queue.contains(stop)) return true;
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
     * 副作用：修改 queue。方向为空闲（{@link Travel#NONE}）时按高度升序，行为与旧版按到达顺序排队一致。
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
     * （见 {@link #select(double)}）；它会一直留在列表里，直到轿厢到站开门（{@link #tick} 的到站分支清理），
     * 期间门上的按钮保持点亮。
     *
     * <p>轿厢正停在本层且门已经打开 / 正在打开时，视为呼叫立即被服务：不入表、只续满停留时间
     * （等价于"按住按钮"），因此不会出现"车就在眼前，按钮却一直红着"。
     *
     * @param call 呼叫（站点 id / 高度 / 方向）
     * @param y 轿厢当前 Y（单位：格）
     * @return true 表示呼叫已被登记或已被现成状态覆盖；false 表示呼叫表已满（&gt;= {@link #MAX_REQUESTS}）
     */
    public boolean callHall(HallCall call, double y) {
        if (hallCalls.contains(call)) return true;
        // 车就在这一层且门开着/正在开：呼叫当场完成，不入表（否则按钮会先红一下再灭）
        if (Math.abs(y - call.y()) <= ElevatorParameters.POSITION_EPSILON && (phase == Phase.OPEN || phase == Phase.OPENING)) { dwell = DWELL_TICKS; return true; }
        if (hallCalls.size() >= MAX_REQUESTS) return false;
        hallCalls.add(call);
        return true;
    }

    /**
     * 面板"开门"键：重新打开轿厢门。
     *
     * <p>安全性前提：<b>调用方必须已经确认车体精确停靠在某个完整站点上</b>——本类不认识世界里的站点，
     * 无法自己判断"是不是在半空"，这个 1e-7 格的到站校验由 {@code AbstractCabinEntity} 用线路站点列表完成。
     *
     * <p>三种情形：门已全开 → 续满停留时间（相当于"按住开门键"）；正在开门 → 无事可做；
     * 正在关门或门已关闭但停在站点（例如刚手动关门、即将出发）→ 反向重新开门，目的站保持不变。
     *
     * <p>纯状态切换，不改 queue/target/hallCalls：因此"开门"不会取消已经排好的行程。
     *
     * @return 指令是否被接受（门已经全关且正在别处运行时返回 false）
     */
    public boolean forceOpen() {
        if (phase == Phase.OPEN) { dwell = DWELL_TICKS; return true; }   // 已开：续满停留时间
        if (phase == Phase.OPENING) return true;                          // 正在开：无需变动
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
     * 到站时清掉本站"本次服务方向"的厅外呼叫与同层选站（门开着，等这个方向的人都上得来，该方向的按钮随之熄灭；另一方向的呼叫保持点亮，等轿厢回头再来）。
     */
    public double tick(double y, Environment env) {
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
        switch (phase) {
            case OPEN -> {
                // 开门停留倒计时；归零就关门——无论还有没有请求。
                if (dwell > 0) dwell--;
                // 注意：这里**不**把 travel 复位为空闲。停车待命（门开着等乘客）时要保留"刚才是上行还是下行"的
                // 记忆，否则"被下行呼叫叫到 5 层、乘客进厢按 6 层、楼下还有呼叫"会丢掉下行方向而先去 6 层。
                if (dwell == 0) {
                    // 停留时间到：先派发下一站（有请求时才可能选出目标），然后一律关门。
                    // 一个请求都没有时 select() 返回 null，于是门关到全闭后停在 MOVING 且无目的站
                    // ——即"关着门停在本层待命"，与手动按关门键的结果完全一致（见 forceClose）。
                    // 以前这里是"无请求就保持开门"，于是空闲的轿厢会一直敞着门；现在改成关门。
                    target = select(y);
                    if (target != null && Math.abs(target.y() - y) <= ElevatorParameters.POSITION_EPSILON) {
                        // 目的站就是本层（例如门开着时另一方向有人按，第 ④ 步掉头后选中它）：
                        // 车已经在这一层、门也开着，就地把它认领掉并续满停留时间，不必先关一次门再重开。
                        // 清扫仍然只清这一条呼叫（见 serveStation），另一方向的呼叫各自独立。
                        serveStation(target.y()); target = null; dwell = DWELL_TICKS;
                    } else {
                        phase = Phase.CLOSING;
                    }
                }
            }
            case CLOSING -> {
                // 防夹：关门过程中门口出现活体则立即反向开门；target 保留，重开后由 OPENING 归队。
                if (env.doorwayBlocked()) { phase = Phase.OPENING; break; }
                door = Math.max(0, door - 1f / DOOR_TICKS);
                // 用 .0001f 而非 0 判定到位：浮点逐刻累减永远取不到精确 0，阈值避免卡在关门状态。
                if (door < .0001f) { door = 0; phase = Phase.MOVING; }
            }
            case MOVING, BLOCKED -> {
                // MOVING 与 BLOCKED 共用运行逻辑：BLOCKED 只是暂停，条件恢复后继续原行程。
                if (target == null) target = select(y);
                if (target == null) break;
                // 门未完全关闭不得移动；从 BLOCKED 恢复时若门还开着，先补一次关门。
                if (door > 0) { phase = Phase.CLOSING; break; }
                // 顺路改道：运行途中新插入的请求若在同方向前方且比当前目标更近，就先停它
                // （例如正驶向 10 层时有人在 5 层按了上行——不重排就会径直开过 5 层）。
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
                    y = target.y(); env.arrived(target); serveStation(target.y()); target = null; phase = Phase.OPENING;
                    stopMotion(); // 到站：曲线使命结束，下一次移动重新规划
                }
            }
            case OPENING -> {
                door = Math.min(1, door + 1f / DOOR_TICKS);
                // 同样用 .9999f 判定开到位，理由与 CLOSING 的到位阈值相同。
                if (door > .9999f) {
                    door = 1; phase = Phase.OPEN; dwell = DWELL_TICKS;
                    // Anti-crush reopening preserves the interrupted request.
                    // 防夹重开：被中断的目的站放回队首而不是丢弃，开门停留结束后它会最先被重新派发。
                    if (target != null) { queue.addFirst(target); target = null; targetHallDirection = Travel.NONE; stopMotion(); }
                }
            }
        }
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
     * 运行途中重排停站：把"顺路且更近"的请求插到当前目标之前。
     *
     * <p><b>为什么必须每刻做</b>：目标一旦定下，原来的实现只会在到站之后才重新选站，
     * 于是"正驶向 10 层时有人在 5 层按了上行（或选了 5 层）"会被径直开过——这不符合真实电梯的
     * 集选行为，玩家也会觉得"按了没用"。这里每刻检查一次：当前服务方向上、位于轿厢前方、
     * 且比当前目标更近的可服务请求，改道先去它。
     *
     * <p>处理细节：
     * <ul>
     *   <li>只考虑<b>当前服务方向</b>且<b>尚未驶过</b>的请求（{@link #nearest} 的 aheadOnly），
     *       因此反方向的呼叫不会让轿厢半路掉头，仍按"先走完这一趟再回头"的顺序服务；</li>
     *   <li>已被驶过的楼层不会被选中（上层判定带 1e-7 容差），所以不会出现"回头补停"；</li>
     *   <li>换目标时把原目标放回队列（若它本来就是厅外呼叫，呼叫表里的那条本来就还在，
     *       重复一条也只是多一次同层清扫，不会漏停），因此不会因为改道而丢站；</li>
     *   <li>严格更近才改道（同层或更远不动），因此不会在两层之间来回抖动。</li>
     * </ul>
     *
     * @param y 轿厢当前高度（格）
     * 副作用：可能修改 target/queue（放回原目标、取出新的选站），不改 phase/door。
     */
    private void retarget(double y) {
        if (target == null) return;
        // 读档恢复时可能"只有目标、没有服务方向"（旧存档）：按目标相对位置补一个，否则顺路判断会失去参照。
        // 服务方向 = 轿厢朝当前目标的实际运行方向。这样"空车去接反方向呼叫"途中，凡是与轿厢同向的
        // 厅外呼叫（例如车向下开时二层的"下行"）都会被顺路接走；读档恢复出与行驶方向不一致的旧方向时，
        // 这里也会在下一 tick 自动纠正。
        travel = directionTowards(y, target.y());
        Pick ahead = nearest(y, travel, true, false);
        if (ahead == null) return;
        if (ahead.stop().y() == target.y()) return; // 最近的就是当前目标：不动
        // 只有"严格更近"才改道：容差与到站判定同源，避免浮点误差导致来回切换。
        if (Math.abs(ahead.stop().y() - y) + ElevatorParameters.POSITION_EPSILON >= Math.abs(target.y() - y)) return;
        insertOrdered(target);   // 原目标放回队列，掉头或下一轮自然会停
        target = take(ahead);    // 新目标（选站会出队；厅外呼叫保留在呼叫表里直到到站）
    }

    /**
     * 到站清扫：门开着，本站的等待者都能上，因此本站的厅外呼叫（两个方向）与同层选站都视为已完成。
     *
     * <p>为什么连"同层选站"也一起清：目标站被选中时已经出队，但队列里可能还留着同层的重复请求
     * （先按了轿厢按钮、之后又按下厅外按钮），不清掉就会出现"到站开门 → 又选中同一层 → 再开一次门"的空转。
     *
     * @param stationY 刚刚到站的站点高度（格）
     */
    private void serveStation(int stationY) {
        // 只清"本次服务方向"上的厅外呼叫：同一层可以同时挂着上行与下行两条呼叫，上行到达只应熄灭上行
        // 那盏灯，下行的乘客还要等轿厢回头来接（真实电梯的上/下呼叫灯各自独立）。方向来自目的站本身——
        // 空车跨越方向去接人时按钮方向与行驶方向相反，按行驶方向会清错。
        Travel served = targetHallDirection != Travel.NONE ? targetHallDirection : travel;
        if (served == Travel.NONE) hallCalls.removeIf(c -> c.y() == stationY); // 方向未知（读档恢复的行程）：退回全清，避免留下死呼叫
        else hallCalls.removeIf(c -> c.y() == stationY && c.up() == (served == Travel.UP));
        queue.removeIf(s -> s.y() == stationY);
        targetHallDirection = Travel.NONE;
    }

    /**
     * 选站结果：站点 + 它的来源（厅外呼叫非空时表示这条请求带方向，选中后仍留在呼叫表里直到到站）。
     *
     * @param stop 要去的站点
     * @param hall 若来自厅外呼叫则为该呼叫，来自轿厢内选站时为 null
     */
    private record Pick(Stop stop, HallCall hall) { }

    /**
     * 选出下一个目的站并"消费"轿厢内选站（厅外呼叫保留在表里，直到 {@link #serveStation} 清理）。
     *
     * @param y 轿厢当前高度（格）
     * @return 下一个目的站；没有任何请求时返回 null（并把服务方向复位为空闲）
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
        //    b. 若没有，但前方还有反方向的厅外呼叫，也先去它——车已经往这边开了，顺路接上比掉头更符合预期。
        //       这里按<b>登记先后</b>取最早的那条，而不是"最近"的：同时挂着"1 层上行（先按）"和
        //       "2 层上行（后按）"时，车应当先下到 1 层（那是它被派去的目的地），再顺路上来接 2 层；
        //       取最近会让后按的 2 层抢走目的地（用户报告的正是这个现象）。真正的"顺路抢单"只发生在
        //       {@link #retarget} 里，且仅限同方向呼叫，因此不会破坏"先来先服务"。
        Pick servable = nearest(y, travel, true, false);
        if (servable != null) return take(servable);
        Pick aheadAny = oldestAheadHallCall(y, travel, false); // 严格在前：本层的反方向呼叫交给第 ④ 步
        if (aheadAny != null) return take(aheadAny);
        // ④ 该方向前方确实没有活了：掉头，服务另一侧（同样先来先服务，含反方向孤立呼叫，保证不饥饿、不空转）。
        travel = opposite(travel);
        Pick any = oldestAheadHallCall(y, travel, true); // 这一步含本层：停在本层、另一方向有人按 → 就地变成那个方向
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
     * @param direction 只考虑该方向的厅外呼叫；为 null（或 {@code sameFloorOnly}）时不做方向过滤
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
     * 当前方向上"前方"的任意请求（<b>含反方向的厅外呼叫</b>），取距离最近的一条。
     *
     * <p>只回答一个问题：这条方向还有没有活干（决定"继续往前"还是"掉头"）。真正顺路可服务的请求由
     * {@link #nearest} 挑（它会把反方向厅外呼叫过滤掉），因此顺序永远是"先服务顺路的，再谈掉头"。
     *
     * @param y 轿厢当前高度（格）
     * @param direction 当前服务方向
     * @return 前方最近的请求；该方向前方什么都没有时返回 null
     */
    private Pick oldestAheadHallCall(double y,Travel direction,boolean includeHere) {
        // hallCalls 本身就是登记顺序（新呼叫追加在尾部、到站时按下标删除），因此第一条命中即最早的那条。
        for (HallCall c : hallCalls) {
            if (!includeHere && Math.abs(c.y() - y) <= ElevatorParameters.POSITION_EPSILON) continue;
            if (along(c.y(), y, direction, true)) return new Pick(new Stop(c.id(), c.y()), c);
        }
        return null;
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
     * @return 起始方向：有轿厢内选站时看第一个选站与轿厢的相对位置（同层时沿用上行），
     *         否则看<b>最早登记的那条厅外呼叫</b>在轿厢的哪一侧（注意不是按钮自身的方向：底层按"上行"
     *         而轿厢在楼上时，车必须向下开过去）
     */
    private Travel initialTravel(double y) {
        if (!queue.isEmpty()) {
            double delta = queue.peekFirst().y() - y;
            if (delta > ElevatorParameters.POSITION_EPSILON) return Travel.UP;
            if (delta < -ElevatorParameters.POSITION_EPSILON) return Travel.DOWN;
        }
        if (!hallCalls.isEmpty()) return directionTowards(y, hallCalls.get(0).y());
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
     * 副作用：覆盖 phase/door/target/queue/hallCalls/travel/dwell；MOVING 一律降级为 BLOCKED 且门置 0。
     * 为什么：载入时不存在“正在运动”的合法状态（既没有上一刻位置也无从继续插值），
     * 降级为 BLOCKED 后由 tick() 先校验线路有效性再恢复运行，避免恢复出一条穿墙的行程。
     */
    public void restore(Phase phase, float door, Stop target, List<Stop> pending, List<HallCall> calls, Travel travel) {
        this.phase = phase; this.door = Math.max(0, Math.min(1, door)); this.target = target;
        // distinct() 去重、limit() 截断：防止被手工篡改或旧版本写坏的存档撑爆队列。
        queue.clear(); pending.stream().distinct().limit(MAX_REQUESTS).forEach(queue::addLast);
        hallCalls.clear(); calls.stream().distinct().limit(MAX_REQUESTS).forEach(hallCalls::add);
        this.travel = travel == null ? Travel.NONE : travel;
        dwell = DWELL_TICKS;
        // S 形曲线不从存档恢复：载入时不存在"正在运动"的合法状态（既没有上一刻位置也无从继续求值），
        // 因此曲线一律作废、速度归零，由 tick() 在重新校验线路后从静止重新规划。
        stopMotion();
        // 载入后门视为关闭，必须先完成关门流程才能移动（与“门未关闭不能移动”的不变量一致）。
        if (phase == Phase.MOVING) { this.phase = Phase.BLOCKED; this.door = 0; }
    }
}
