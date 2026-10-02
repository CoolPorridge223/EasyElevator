package org.DJB.easyelevator.logic;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Server-authoritative, deterministic controller; deliberately independent of rendering/Minecraft.
 *
 * <p>职责：电梯核心状态机。它只操作“轿厢当前 Y（格）”“目标站点”“门联锁进度”“请求队列”这些纯数据，
 * 所有世界查询都通过 {@link Environment} 回调注入，因此整个类不引用任何 Minecraft 类，
 * 可以脱离游戏做单元测试，并且行为完全确定（同一输入序列必然得到同一输出）。</p>
 *
 * <p>在整体架构中的位置：服务端权威。CabinEntity 每刻调用 {@link #tick(double, Environment)}，
 * 用匿名 Environment 实现把 valid/canMove/doorwayBlocked/arrived 四个世界查询喂进来；
 * 状态机产出的 phase/door/target 再由服务端同步给客户端，客户端只做只读渲染与镜头插值。</p>
 *
 * <p>状态迁移（{@link Phase}）：OPEN -> CLOSING -> MOVING -> OPENING -> OPEN 循环；
 * BLOCKED 表示受阻（断轨、线路朝向不一致、运行区域有方块或实体障碍、区块未加载、目的站门被拆），
 * 它不是失败而是暂停——条件恢复后继续原行程。</p>
 *
 * <p>关键不变量：门未完全关闭（door == 0）不得移动；楼层门只在轿厢精确到站
 * （误差 &lt;= {@link ElevatorParameters#POSITION_EPSILON}）且轿厢门正在打开时才开启联锁；
 * queue 中不出现重复站点；同一时刻最多只有一个 target。</p>
 */
public final class ElevatorController {
    /**
     * 状态机阶段。
     * OPEN = 已开门并停留等待、CLOSING = 正在关门、MOVING = 正在运行、
     * OPENING = 正在开门、BLOCKED = 受阻暂停（可恢复，不是失败）。
     */
    public enum Phase { OPEN, CLOSING, MOVING, OPENING, BLOCKED }
    /**
     * 一个站点（stop）：一扇完整 3x3 楼层门的底部中心方块位置。
     *
     * @param id 站点唯一标识，取根方块 BlockPos.asLong()，用于去重、查表与存档
     * @param y 站点高度（单位：格），即轿厢停靠时的底部中心 Y，与根方块同 Y
     */
    public record Stop(long id, int y) { }
    /**
     * 世界查询回调：让纯 Java 状态机在不引用 Minecraft 类的前提下感知世界。
     * 实现方是 CabinEntity 中的匿名类，在每次 tick() 内同步调用，返回结果只对本次调用有效。
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
    /** 速度的只读副本（单位：格/刻），转发自 {@link ElevatorParameters#SPEED}，便于外部对齐客户端预测。 */
    public static final double SPEED = ElevatorParameters.SPEED;
    /** 门时序与队列上限的只读副本，转发自 ElevatorParameters（DOOR_TICKS/DWELL_TICKS/MAX_REQUESTS）。 */
    public static final int DOOR_TICKS = ElevatorParameters.DOOR_TICKS,
            DWELL_TICKS = ElevatorParameters.DWELL_TICKS, MAX_REQUESTS = ElevatorParameters.MAX_REQUESTS;
    /** 待处理请求队列（FIFO）：同一站点不重复入队，长度受 MAX_REQUESTS 限制。 */
    private final ArrayDeque<Stop> queue = new ArrayDeque<>();
    /** 当前正在执行的目的站；为 null 表示空闲（开门停留中或受阻等待请求）。 */
    private Stop target;
    /** 当前阶段；初始为 OPEN，即轿厢落成时门是开的，便于立即上人。 */
    private Phase phase = Phase.OPEN;
    /** 门联锁进度：0 = 完全关闭（保留真实碰撞），1 = 完全打开；无量纲。 */
    private float door = 1;
    /** 开门后的剩余停留刻数；归零且队列非空时才开始关门。 */
    private int dwell = DWELL_TICKS;

    /** @return 当前状态机阶段（服务端权威，客户端只读同步用于渲染）。 */
    public Phase phase() { return phase; }
    /** @return 门联锁进度 0..1（0 关闭 / 1 打开，无量纲）。 */
    public float door() { return door; }
    /** @return 当前目的站；空闲或受阻等待时为 null。 */
    public Stop target() { return target; }
    /** @return 请求队列的不可变快照（按处理顺序），供服务端同步与存档使用。 */
    public List<Stop> pending() { return List.copyOf(queue); }

    /**
     * 提交一个乘梯请求。
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
        queue.addLast(stop);
        return true;
    }

    /**
     * 面板"开门"键：重新打开轿厢门。
     *
     * <p>安全性前提：<b>调用方必须已经确认车体精确停靠在某个完整站点上</b>——本类不认识世界里的站点，
     * 无法自己判断"是不是在半空"，这个 1e-7 格的到站校验由 {@code CabinEntity} 用线路站点列表完成。
     *
     * <p>三种情形：门已全开 → 续满停留时间（相当于"按住开门键"）；正在开门 → 无事可做；
     * 正在关门或门已关闭但停在站点（例如刚手动关门、即将出发）→ 反向重新开门，目的站保持不变。
     *
     * <p>纯状态切换，不改 queue/target：因此"开门"不会取消已经排好的行程。
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
     * <p>与"到站停留结束"的区别：这里不要求队列非空——真实电梯的关门键可以先把门关上、让轿厢停在
     * 本层等待下一次呼叫，因此允许把门关到全闭后停在站点（相位停在 MOVING、target 为空）。
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
     * 副作用：修改 phase/door/dwell/target/queue；可能经 env.arrived 开门、播放音效、改方块状态。
     * 说明：到站时先把位置精确吸附到 target.y() 再回调 arrived，保证门联锁的 1e-7 到站判定一定成立。
     */
    public double tick(double y, Environment env) {
        // 先剔除已失效的排队站点（门被拆、区块卸载）：避免把行程派发给已经不存在的站。
        queue.removeIf(s -> !env.valid(s));
        if (target != null && !env.valid(target)) {
            // Never open between floors or forget a cancelled trip. A replacement request can recover it.
            // 目的站失效时绝不就地开门（会停在楼层之间），也不静默丢弃行程：
            // 清空 target 并转入 BLOCKED；新的请求（或站点恢复）可在 MOVING/BLOCKED 分支恢复行程。
            target = null;
            phase = Phase.BLOCKED;
        }
        switch (phase) {
            case OPEN -> {
                // 开门停留倒计时；dwell 归零且队列非空才派发下一站并开始关门。
                if (dwell > 0) dwell--;
                if (dwell == 0 && !queue.isEmpty()) { target = queue.removeFirst(); phase = Phase.CLOSING; }
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
                if (target == null && !queue.isEmpty()) target = queue.removeFirst();
                if (target == null) break;
                // 门未完全关闭不得移动；从 BLOCKED 恢复时若门还开着，先补一次关门。
                if (door > 0) { phase = Phase.CLOSING; break; }
                double remaining = target.y() - y;
                // No minimum movement quantum: even a sub-micrometre final distance is preserved.
                // 不设“最小位移量子”：最后不足一步的残差也直接走到目标值（而不是原地判到站），
                // 这样 y 能精确等于 target.y()，双精度到站判定与门联锁的严格相等才有意义。
                double next = Math.abs(remaining) <= SPEED + ElevatorParameters.POSITION_EPSILON
                        ? target.y() : y + Math.copySign(SPEED, remaining);
                if (!env.canMove(y, next)) { phase = Phase.BLOCKED; break; }
                phase = Phase.MOVING;
                y = next;
                // 到站判定用 ==（而不是 epsilon）：接近时已把 next 吸附为 target.y()，
                // 其余情况由双精度精确比较即可，容差反而会掩盖线路高度不一致的问题。
                if (y == target.y()) {
                    // 先对齐再回调：保证 arrived 里做门联锁判定时位置已精确落在站点上。
                    y = target.y(); env.arrived(target); target = null; phase = Phase.OPENING;
                }
            }
            case OPENING -> {
                door = Math.min(1, door + 1f / DOOR_TICKS);
                // 同样用 .9999f 判定开到位，理由与 CLOSING 的到位阈值相同。
                if (door > .9999f) {
                    door = 1; phase = Phase.OPEN; dwell = DWELL_TICKS;
                    // Anti-crush reopening preserves the interrupted request.
                    // 防夹重开：被中断的目的站放回队首而不是丢弃，开门停留结束后它会最先被重新派发。
                    if (target != null) { queue.addFirst(target); target = null; }
                }
            }
        }
        return y;
    }

    /**
     * 从存档恢复状态（轿厢载入 NBT 后调用）。
     *
     * @param phase 存档中保存的阶段
     * @param door 存档中保存的门进度，会被夹取到 0..1 的合法区间
     * @param target 存档中保存的目的站，可为 null
     * @param pending 存档中保存的请求队列，会去重并截断到 {@link #MAX_REQUESTS}
     * 副作用：覆盖 phase/door/target/queue/dwell；MOVING 一律降级为 BLOCKED 且门置 0。
     * 为什么：载入时不存在“正在运动”的合法状态（既没有上一刻位置也无从继续插值），
     * 降级为 BLOCKED 后由 tick() 先校验线路有效性再恢复运行，避免恢复出一条穿墙的行程。
     */
    public void restore(Phase phase, float door, Stop target, List<Stop> pending) {
        this.phase = phase; this.door = Math.max(0, Math.min(1, door)); this.target = target;
        // distinct() 去重、limit() 截断：防止被手工篡改或旧版本写坏的存档撑爆队列。
        queue.clear(); pending.stream().distinct().limit(MAX_REQUESTS).forEach(queue::addLast);
        dwell = DWELL_TICKS;
        // 载入后门视为关闭，必须先完成关门流程才能移动（与“门未关闭不能移动”的不变量一致）。
        if (phase == Phase.MOVING) { this.phase = Phase.BLOCKED; this.door = 0; }
    }
}
