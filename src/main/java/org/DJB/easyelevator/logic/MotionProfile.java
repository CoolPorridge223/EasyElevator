package org.DJB.easyelevator.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端轿厢的 S 形速度曲线（Jerk-limited profile）：纯 Java、确定性、不引用任何 Minecraft 类。
 *
 * <p>职责：把"每刻走固定步长"换成"按时间参数化的速度曲线"。一条行程被编译成若干<b>常加加速度段</b>
 * （{@link Segment}：jerk 恒定），位置是时间的三次函数、速度是二次、加速度是一次，因此每刻只需
 * O(段数) 求值：既不存在逐刻累加误差，也不需要客户端外推。曲线形状就是真实电梯的 S 形：
 * 加加速（jerk 正）→ 匀加速 → 减加速 → 匀速巡航 → 加减速 → 匀减速 → 减减速，最后在站点精确静止。</p>
 *
 * <p>为什么需要它：轿厢以固定步长运行时，速度在启动与停止的当刻从 0 突变到巡航值（加速度与加加速度
 * 都是冲激），乘客感受到的是"一顿就起步、一顿就停住"。S 形曲线把这段突变摊到若干刻上——启动缓慢加速、
 * 中段匀速、到站前平滑减速，这是高层高速梯舒适度的核心。</p>
 *
 * <p>在整体架构中的位置：logic 包中与 {@link ElevatorController} 并列的运动学组件。状态机只负责
 * "什么时候可以走、往哪走、门怎么联动"，"怎么走"完全由本类回答——状态机每刻向它索取下一步的位移，
 * 再把位移交给 {@code AbstractCabinEntity} 应用。因此门时序、联锁、到站吸附、乘客承托、存档与同步
 * 的语义一个字都没有改动。</p>
 *
 * <p>关键不变量与约束：
 * <ul>
 *   <li><b>限速</b>：曲线速度恒 ≤ 巡航上限（即轿厢型号速度），因此单刻位移恒 ≤ 巡航上限，
 *       "单刻位移 + {@link ElevatorParameters#POSITION_EPSILON} ≤ 1 格"这一到站精度前提继续成立。</li>
 *   <li><b>限加速度</b>：|a| 恒 ≤ 生效的加速度上限。按型号构造（{@link #forCruiseSpeed}）时它就是
 *       "巡航速度 / {@link ElevatorParameters#CRUISE_RAMP_TICKS}"，因此曲线走的是<b>梯形</b>加速度波形
 *       （斜坡 T/2 升到峰值 → 峰值平台 T/2 → 斜坡 T/2 回落，合计 1.5·T 刻），
 *       加/减速段长度与舒适度由该时间与巡航速度唯一决定；{@link ElevatorParameters#MAX_ACCELERATION}
 *       只是兜底硬上限。</li>
 *   <li><b>限加加速度</b>：S 段的 |j| 恒等于生效的 jerk 上限（按型号构造时由加速度上限与过渡时间推出，
 *       {@code j = 2·aMax/T}）；收尾斜坡段的 j = 0，因此不存在无界的加速度跳变。</li>
 *   <li><b>精确到站</b>：曲线终点就是目标站点本身，因此 {@link #advance} 在最后一刻直接给出"恰好到站"
 *       的位移，不需要最小位移量子或容差兜底（与旧实现的精确吸附语义一致）。</li>
 *   <li><b>可重规划</b>：{@link #plan} 接受任意初速度/初加速度，因此"运行途中顺路改道到更近的楼层"
 *       可以立刻从当前状态重新规划，不会过冲、不会丢站。</li>
 *   <li><b>无外推</b>：本类只按时间求值，不做"按上一刻速度预测下一刻"的外推；客户端的插值同样只在已知样本之间进行
 *       （见 {@code client/CabinMotion} 的 {@code Track}：只在上一刻提交位置与当前提交位置之间插值）。</li>
 * </ul>
 *
 * <p>单位：位置为格（绝对值），速度为格/刻，加速度为格/刻²，加加速度为格/刻³；时间是刻（1 秒 = 20 刻）。
 * 一"刻"即一次服务端 tick，因此本类的离散采样点就是服务端刻。
 *
 * <p><b>曲线是怎么算出来的</b>（{@link #plan} → {@link #sPlan}）：整条曲线只由一个参数决定——峰值速度 vp。
 * 给定 vp，加速段与减速段的几何都能解析算出（{@link #planRamp}，两段互为时间反演），两段合计的距离
 * 随 vp 单调不减，于是对 vp 二分即可求出"不超过剩余距离的最大峰值速度"；余量用一段匀速巡航填满，
 * 曲线终点因此精确落在目标上。若连最短的 S 形都比剩余距离远（目标极近，或改道后只剩一点距离），
 * 则退回同样能精确到站的收尾斜坡（{@link #rampPlan}）。整条曲线在规划时一次算完，之后每刻只是按 t 求值。</p>
 */
public final class MotionProfile {

    /**
     * 一段常加加速度运动：在 [0, duration] 刻内 jerk 恒为 {@code jerk}。
     *
     * @param duration 本段时长（刻）；约定 ≤ 0 时视为"不存在"，求值时会跳过
     * @param jerk 本段的加加速度（格/刻³）
     * @param p0 本段起点的位置增量（格，相对整条曲线的起点）
     * @param v0 本段起点的速度（格/刻）
     * @param a0 本段起点的加速度（格/刻²）
     */
    private record Segment(double duration, double jerk, double p0, double v0, double a0) {
        /** @param t 段内时间（刻，0..duration） @return 相对段起点的位移（格） */
        double position(double t) { return p0 + v0 * t + a0 * t * t * .5 + jerk * t * t * t / 6; }
        /** @param t 段内时间（刻） @return 末端速度（格/刻） */
        double velocity(double t) { return v0 + a0 * t + jerk * t * t * .5; }
        /** @param t 段内时间（刻） @return 末端加速度（格/刻²） */
        double acceleration(double t) { return a0 + jerk * t; }
    }

    /** 整条曲线走过的总距离（格，沿运动方向，非负）；恒等于目标与起点的距离。 */
    private double distance;
    /** 运动方向：目标高于起点为 +1，低于起点为 -1，重合为 0。 */
    private double sign;
    /** 曲线终点位置（格，绝对值）= 目标站点高度。 */
    private double target;
    /** 分段表；按时间顺序排列。退化曲线（idle）为空表。 */
    private List<Segment> segments = new ArrayList<>();
    /** 曲线总时长（刻）；到达该时刻即精确静止于目标。 */
    private double totalTime;
    /** true 表示退化曲线：已经在目标上，不需要任何移动。 */
    private boolean idle = true;

    /** 巡航速度上限（格/刻），等于轿厢型号速度；只读，运行中不变。 */
    private final double cruiseSpeed;
    /** 加速度上限（格/刻²）；只读。 */
    private final double maxAcceleration;
    /** 加加速度上限（格/刻³）；只读。 */
    private final double maxJerk;

    /**
     * jerk 的兜底值：只在调用方显式传入非法 jerk（{@code forCruiseSpeed} 永远不会走到这里）时使用。
     *
     * <p>必须存在的原因与速度、加速度的兜底一样：jerk 一旦是 0 或 NaN，斜坡时长 {@code sqrt(Δv/j)}
     * 会变成除零或 NaN，曲线既规划不出来也走不到站。取值只需"与按型号推出的默认值同量级"，
     * 生产路径上的 jerk 一律由 {@link #forCruiseSpeed} 解出，不经过这里。
     */
    private static final double DEFAULT_RAMP_JERK = .0004;

    /**
     * 以显式的三条上限构造（供测试与特殊调参使用）。
     *
     * @param cruiseSpeed 巡航速度上限（格/刻）；非正、NaN 或无穷大时退化为 {@link ElevatorParameters#SPEED}
     * @param maxAcceleration 加速度上限（格/刻²）；非法时退化为 {@link ElevatorParameters#MAX_ACCELERATION}
     * @param maxJerk 加加速度上限（格/刻³）；非法时退化为 {@link #DEFAULT_RAMP_JERK}
     */
    public MotionProfile(double cruiseSpeed, double maxAcceleration, double maxJerk) {
        this.cruiseSpeed = positive(cruiseSpeed, ElevatorParameters.SPEED);
        this.maxAcceleration = positive(maxAcceleration, ElevatorParameters.MAX_ACCELERATION);
        this.maxJerk = positive(maxJerk, DEFAULT_RAMP_JERK);
    }

    /**
     * 以默认参数构造：巡航速度取 {@link ElevatorParameters#SPEED}，加速度与 jerk 上限按
     * {@link ElevatorParameters#CRUISE_RAMP_TICKS} 解出。
     *
     * <p>与 {@code forCruiseSpeed(SPEED)} 等价，只是 {@code this(...)} 必须是首句、不能写成工厂调用，
     * 因此把那条路径的三步算式就地展开（见 {@link #forCruiseSpeed(double, double)}）。
     */
    public MotionProfile() {
        this(ElevatorParameters.SPEED,
                Math.min(ElevatorParameters.SPEED / ElevatorParameters.CRUISE_RAMP_TICKS, ElevatorParameters.MAX_ACCELERATION),
                2 * Math.min(ElevatorParameters.SPEED / ElevatorParameters.CRUISE_RAMP_TICKS, ElevatorParameters.MAX_ACCELERATION)
                        / ElevatorParameters.CRUISE_RAMP_TICKS);
    }

    /**
     * 按轿厢型号构造：由"巡航速度 + {@link ElevatorParameters#CRUISE_RAMP_TICKS}"解出加/减速段的几何。
     *
     * <p>这是生产路径（{@link ElevatorController} 走的就是它）。给定巡航速度 v 与过渡时间 T（刻）：
     * 加速度上限 {@code aMax = v / T}、jerk 上限 {@code j = 2·aMax / T}。这条 jerk 斜率<b>不是</b>
     * "随便挑的舒服值"，而是与 aMax 配套：按最短形状解斜坡时长会得到
     * {@code t1 = sqrt(Δv/j) = T/√2}、{@code a1 = j·t1 = √2·aMax > aMax}，也就是<b>一定会撞上</b>
     * {@link #planRamp} 的"加速度触顶"分支。触顶之后：
     * <ul>
     *   <li>斜坡时长压到 {@code t1 = aMax/j = T/2}；</li>
     *   <li>剩下的速度差由<b>峰值平台</b>补足：{@code tHold = Δv/aMax − t1 = T/2}；</li>
     *   <li>因此加/减速段的总时长是 <b>1.5·T</b>（斜坡 T/2 + 平台 T/2 + 回落 T/2），
     *       加速度波形是<b>梯形</b>而不是三角形，峰值恰好 aMax；</li>
     *   <li>段内平均速度恰为 v/2，所以单段距离 = <b>0.75·v·T</b> 格（= 1.5 × v·T/2）。</li>
     * </ul>
     * 实测（T=32）：普通 48 刻 / 4.8 格 / 0.00625 格/刻²；重载 48 刻 / 3.2 格 / 0.0041667；
     * 高速 48 刻 / 12.0 格 / 0.015625。三型时长相同、距离与峰值随巡航速度成比例——
     * 这就是"加/减速几何由 T 与 v 唯一决定"的含义（站距太短时改用 {@link #rampPlan} 的收尾斜坡，见类注释）。
     *
     * <p>加速度仍与 {@link ElevatorParameters#MAX_ACCELERATION} 取较小值：把
     * {@code CRUISE_RAMP_TICKS} 调得特别小时（{@code v/T > 0.15}），工作点会先撞上硬上限，
     * 加/减速段比 1.5·T 更短——这是"不许把乘客甩出去"的底线，而不是正常工作点。
     *
     * @param cruiseSpeed 巡航速度上限（格/刻）；非法时退化为 {@link ElevatorParameters#SPEED}
     * @param rampTicks 从静止加到该巡航速度所需的刻数；非有限或非正时退化为
     *                  {@link ElevatorParameters#CRUISE_RAMP_TICKS}
     * @return 该型号的曲线参数
     */
    public static MotionProfile forCruiseSpeed(double cruiseSpeed, double rampTicks) {
        double speed = positive(cruiseSpeed, ElevatorParameters.SPEED);
        double ramp = Double.isFinite(rampTicks) && rampTicks > 0 ? rampTicks : ElevatorParameters.CRUISE_RAMP_TICKS;
        double acceleration = Math.min(speed / ramp, ElevatorParameters.MAX_ACCELERATION);
        // jerk 与加速度上限配套：由它解出的最短斜坡一定会触顶（见方法注释），
        // 于是实际波形是"斜坡 T/2 + 平台 T/2 + 回落 T/2"的梯形，而不是三角形。
        double jerk = 2 * acceleration / ramp;
        return new MotionProfile(speed, acceleration, jerk);
    }

    /**
     * 按轿厢型号构造，过渡时间取 {@link ElevatorParameters#CRUISE_RAMP_TICKS}。
     *
     * @param cruiseSpeed 巡航速度上限（格/刻）
     * @return 该型号的曲线参数
     */
    public static MotionProfile forCruiseSpeed(double cruiseSpeed) {
        return forCruiseSpeed(cruiseSpeed, ElevatorParameters.CRUISE_RAMP_TICKS);
    }

    /**
     * 取一个合法正数，非法值退回给定缺省值。
     *
     * <p>为什么必须校验：速度与 jerk 可能来自调参或坏存档，若允许 0/NaN 进入曲线计算，会出现除零与
     * "永远走不到站"的死循环。这里与 {@link ElevatorController} 的构造校验保持同一口径。
     *
     * @param value 待校验的值
     * @param fallback 非法时使用的缺省值
     * @return 合法时原样返回，否则返回 fallback
     */
    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }

    /** @return 巡航速度上限（格/刻），即轿厢型号速度 */
    public double cruiseSpeed() { return cruiseSpeed; }
    /** @return 加速度上限（格/刻²） */
    public double maxAcceleration() { return maxAcceleration; }
    /** @return 加加速度上限（格/刻³） */
    public double maxJerk() { return maxJerk; }
    /** @return 曲线终点位置（格），正常等于目标站点高度 */
    public double target() { return target; }
    /** @return 曲线总时长（刻）；0 表示退化曲线 */
    public double totalTime() { return totalTime; }
    /** @return 曲线从头到尾走过的距离（格） */
    public double totalDistance() { return distance; }
    /** @return 曲线是否退化（已经在目标上：不需要任何移动） */
    public boolean idle() { return idle; }
    /** @return 最近一次规划采用的峰值速度（格/刻，= 曲线巡航段速度）；供诊断与自检读取，不参与决策。 */
    public double peakSpeed() { return peakSpeed; }
    /** 最近一次规划采用的峰值速度（格/刻）；见 {@link #peakSpeed()}。 */
    private double peakSpeed;

    /**
     * 规划一条从 {@code (position, velocity, acceleration)} 出发、在 {@code target} 处精确静止的 S 形曲线。
     *
     * <p>每次"开始移动"都应当先规划：行程开始时（速度与加速度都是 0）、运行途中目的站被顺路改道时
     * （速度可能已经是巡航速度）、以及受阻后恢复时（速度已被清零）。规划是纯计算，不改动任何外部状态。
     *
     * @param position 起点位置（格，绝对值）
     * @param velocity 起点速度（格/刻，带符号；向下为负）
     * @param acceleration 起点加速度（格/刻²，带符号）。<b>有意不从它起步</b>：曲线一律以 0 加速度起算，
     *                     这样加速段自身时间对称，其时间反演（减速段）才能精确抵消速度增益。调用方
     *                     （{@link ElevatorController}）每次规划前都会清零加速度，因此这与运行行为一致；
     *                     代价是极端情况下改道瞬间可能有一个被 jerk 上限约束住的加速度台阶。
     * @param target 终点位置（格，绝对值），通常是站点高度
     * @return true 表示产生了一条非退化曲线（需要移动）；已在目标上时为 false（曲线退化为静止）
     */
    public boolean plan(double position, double velocity, double acceleration, double target) {
        this.target = target;
        double delta = target - position;
        double v = Double.isFinite(velocity) ? velocity : 0;
        segments = new ArrayList<>();
        if (!Double.isFinite(delta) || Math.abs(delta) <= ElevatorParameters.POSITION_EPSILON) {
            // 已经在目标上：退化曲线。advance 恒返回 0，velocityAt/accelerationAt 恒返回 0。
            idle = true;
            distance = 0;
            totalTime = 0;
            sign = 0;
            return false;
        }
        idle = false;
        sign = Math.signum(delta);
        distance = Math.abs(delta);
        // 把起点速度投影到"沿运动方向"的一维坐标上：上行与下行共用同一份数学，不存在第二套镜像实现。
        // 加速度按设计不作为起点状态传入（见本方法说明）。
        sPlan(distance, v * sign, 0);
        totalTime = 0;
        for (Segment segment : segments) totalTime += segment.duration();
        return true;
    }

    /**
     * 在当前曲线上推进 {@code dt} 刻，返回应当施加的位移。
     *
     * <p>返回的是"曲线在 [tick, tick+dt] 之间的位移"，因此调用方把当前位置加上它之后，与曲线同一时刻
     * 的位置之间只差一个<b>不累积</b>的浮点残差：每一步都按曲线自身的形状前进，残差既不会被放大也不会
     * 被继承，因而不会出现"越走越偏"或"卡在起点不动"。
     *
     * @param dt 推进时长（刻）；正常就是 1 刻
     * @param position 调用方当前的真实位置（格，绝对值）；只在终点分支里用来抹平残差
     * @param tick 曲线内的时间（刻，从 0 起算），由调用方在每次 {@link #plan} 后从 0 开始维护
     * @return 本刻应当施加的位移（格，带符号）；曲线结束时恰好等于"目标 - 当前位置"，因此最后一步精确到站
     */
    public double advance(double dt, double position, double tick) {
        if (idle) return 0;
        // 终点：直接返回"当前位置到目标"的整段残差。曲线在终点本来就静止于目标，
        // 这一步把浮点残差（通常 < 1e-12 格）一并抹平，保证 y == target 的精确到站判定成立。
        if (tick + 1e-9 >= totalTime) return target - position;
        double next = Math.min(tick + dt, totalTime);
        // 沿运动方向的"曲线位移"，乘回符号即世界坐标下的位移。
        return sign * (distanceAt(next) - distanceAt(tick));
    }

    /**
     * @param t 曲线内时间（刻）
     * @return 该时刻的速度（格/刻，带符号）；曲线终点严格为 0
     */
    public double velocityAt(double t) {
        if (idle || t >= totalTime) return 0;
        return sign * query(t, Query.VELOCITY);
    }

    /**
     * @param t 曲线内时间（刻）
     * @return 该时刻的加速度（格/刻²，带符号）；曲线终点严格为 0
     */
    public double accelerationAt(double t) {
        if (idle || t >= totalTime) return 0;
        return sign * query(t, Query.ACCELERATION);
    }

    /**
     * @param t 曲线内时间（刻）
     * @return 该时刻已走过的距离（格，沿运动方向，非负）
     */
    public double distanceAt(double t) {
        if (idle || t <= 0) return 0;
        return query(t, Query.POSITION);
    }

    /** 求值维度：同一套"找段 → 段内求值"的遍历服务位置/速度/加速度三种查询。 */
    private enum Query { POSITION, VELOCITY, ACCELERATION }

    /**
     * 按段求值：累计时间找到所在段，再在段内用三次/二次/一次多项式求值。
     *
     * @param t 曲线内时间（刻）
     * @param query 要取的量
     * @return 该时刻的位移（格）/ 速度（格/刻）/ 加速度（格/刻²），都沿运动方向、非镜像
     */
    private double query(double t, Query query) {
        if (segments.isEmpty()) return 0;
        double time = 0;
        for (Segment segment : segments) {
            if (time + segment.duration() >= t) {
                double local = t - time;
                return switch (query) {
                    case POSITION -> segment.position(local);
                    case VELOCITY -> segment.velocity(local);
                    case ACCELERATION -> segment.acceleration(local);
                };
            }
            time += segment.duration();
        }
        // 浮点余量：落在最后一段之后时用末段终点兜底，绝不做外推。
        Segment last = segments.getLast();
        return switch (query) {
            case POSITION -> last.position(last.duration());
            case VELOCITY -> last.velocity(last.duration());
            case ACCELERATION -> last.acceleration(last.duration());
        };
    }


    /**
     * 生成"从 (v0, a0) 出发、在剩余距离 d 内平滑加速、巡航、再平滑减速到静止"的完整曲线。
     *
     * <p>整条曲线由<b>一个参数</b>唯一决定：峰值速度 vp（= 巡航段速度）。给定 vp 之后，加速段与减速段
     * 的几何都能解析算出（见 {@link #planRamp}），两段合起来走过的距离 d(vp) 随 vp 严格单调不减，
     * 因此对 vp 做二分即可求出"不超过剩余距离的最大峰值速度"；剩下的余量用一段匀速巡航填满，
     * 于是曲线终点精确落在目标上。这一步是本类正确性的关键：峰值速度必须与生成曲线所用的那个
     * 速度完全一致，否则"算出来能停住、实际却停不住"。
     *
     * <p>距离比"最短 S 形"还近（目标极近，或改道后只剩零点几格）时，二分退化为 vp → 0，
     * 这时改用同样能精确到站的收尾斜坡（{@link #rampPlan}）。
     *
     * @param d 剩余距离（格，> 0）
     * @param v0 起点速度沿运动方向的分量（格/刻，≥ 0）
     * @param a0 起点加速度沿运动方向的分量（格/刻²，带符号；实际按 0 处理，见 {@link #planRamp}）
     */
    private void sPlan(double d, double v0, double a0) {
        // 起点速度沿运动方向可能是负的（改道时车正朝反方向开）：曲线只从"当前速度沿本方向的分量"起步，
        // 负分量说明本刻要先把反向速度收掉，那一部分由下一刻的重新规划接手，这里按 0 处理即可。
        double startVelocity = Math.max(0, v0);
        // ① 二分峰值速度：d(vp) 单调不减，取不超过剩余距离的最大值。峰值速度永远不超过巡航上限
        //    （这是"单刻位移不超巡航速度"的前提），因此改道时若入口速度已经接近上限，可行区间会很窄。
        double low = startVelocity, high = cruiseSpeed;
        for (int i = 0; i < 60; i++) {
            double mid = (low + high) * .5;
            if (sCurveDistance(mid, startVelocity) < d) low = mid; else high = mid;
        }
        double vp = high;
        double sDist = sCurveDistance(vp, startVelocity);
        // ② 无解：`sDist` 是"vp = 巡航上限"时两段 S 的距离，它比剩余距离还大，说明这一小段装不下
        //    任何合法的 S 形（入口速度太高、剩下的距离连减速都不够）。退回收尾斜坡，由它精确刹停在目标上。
        if (vp <= startVelocity + 1e-12 || sDist > d + 1e-9) { peakSpeed = 0; rampPlan(d, startVelocity); return; }
        peakSpeed = vp;
        // ③ 曲线 = 加速段 → 匀速巡航（填满余量）→ 减速段（加速段的严格时间镜像）。
        //    加速度一律从 0 起算（a0 被有意忽略）：这样加速段与减速段的时长与形状严格互为反演，
        //    终点速度精确回到起点速度。调用方每次规划前都会清零加速度，见 plan()。
        double[] ramp = new double[6];
        planRamp(vp, startVelocity, ramp);
        List<Segment> list = new ArrayList<>();
        double[] state = {0, startVelocity, 0};
        applyRamp(list, state, ramp, 1);                                   // 加速段
        append(list, state, Math.max(0, (d - sDist) / Math.max(vp, 1e-12)), 0); // 匀速巡航
        applyRamp(list, state, ramp, -1);                                  // 减速段（镜像）
        segments = list;
        if (segments.isEmpty()) segments.add(new Segment(0, 0, d, 0, 0));
        segments.add(new Segment(0, 0, d, 0, 0)); // 终段兜底：时长 0，只用于末尾求值
    }

    /**
     * 计算"从 v0 加速到峰值速度 vp、再对称减速回 v0"所需的距离（不含匀速巡航段）。
     *
     * <p>实现方式是"真的把加速段走一遍、把位移累加起来"，而不是另写一套解析近似：二分用的距离函数
     * 必须与最终生成的曲线逐位一致，否则会出现"二分认为停得住、实际却停不住"的过冲。位移是
     * 位置量，与方向无关，因此这里按满速方向（sign = +1）走一遍即可。
     *
     * @param vp 峰值速度（格/刻，≥ v0）
     * @param v0 起点速度沿运动方向的分量（格/刻，≥ 0）
     * @return 加速段 + 减速段的合计距离（格，≥ 0）
     */
    private double sCurveDistance(double vp, double v0) {
        double[] ramp = new double[6];
        planRamp(vp, v0, ramp);
        double[] state = {0, v0, 0};
        applyRamp(null, state, ramp, 1);
        return 2 * state[0]; // 减速段是加速段的严格时间反演，距离相同
    }

    /**
     * 解出一段"从 v0 加速到峰值速度 vp"的 jerk 受限加速段几何。
     *
     * <p>形状：jerk {@code +j} 持续 {@code t1}（加速度 0 → a1）→ 匀加速平台 {@code tHold}
     * → jerk {@code -j} 持续 {@code t1}（加速度 a1 → 0）。两段斜坡等长，加速度波形关于中点对称，
     * 因此把<b>三段时长照搬、jerk 整体取反</b>就得到"从 vp 减速回 v0"的减速段：两段耗时相同、
     * 速度变化相反，合起来终点速度精确回到 v0，位置也精确到站。
     *
     * <p>两个约束同时成立才能让斜坡"恰好用完"整个速度差：斜坡斜率就是 jerk（{@code a1 = j·t1}），
     * 而加速度曲线面积就是速度增益（{@code a1·t1 + a1·tHold = vp - v0}）。取最短形状
     * （{@code tHold = 0}）时面积 = {@code j·t1²}，于是
     * {@code t1 = sqrt((vp-v0)/maxJerk)}、{@code a1 = sqrt(maxJerk·(vp-v0))}；
     * 若 a1 超过加速度上限，则改用上限（斜坡取 {@code aMax/maxJerk}），剩余速度差交给平台补足。
     *
     * <p>注意"入口速度 v0 不为 0"这件事：曲线只关心 v0 这个<b>速度</b>，不关心当前加速度，
     * 因此改道（车已在运动中）与起步共用同一套构造，减速段也必然把速度收回 v0。
     *
     * @param vp 峰值速度（格/刻，≥ v0）
     * @param v0 起点速度沿运动方向的分量（格/刻，≥ 0）
     * @param out 输出参数，长度 ≥ 6：out[0..2] = 三段时长；out[3] = 速度增益；out[4] = 加速度峰值；
     *            out[5] = 单段总时长（三段之和）
     */
    private void planRamp(double vp, double v0, double[] out) {
        double j = maxJerk, aMax = maxAcceleration;
        double dv = vp - v0;
        if (dv <= 1e-12) {
            for (int i = 0; i < out.length; i++) out[i] = 0;
            return;
        }
        double t1 = Math.sqrt(dv / j); // 最短形状：平台为 0，面积 j·t1² 恰好等于 dv
        double a1 = j * t1;
        double tHold = 0;
        if (a1 > aMax) {
            // 加速度触顶：斜坡按上限所需的最短时长，剩下的速度差交给匀加速平台。
            a1 = aMax;
            t1 = a1 / j;
            tHold = Math.max(0, dv / a1 - t1);
        }
        out[0] = t1;
        out[1] = tHold;
        out[2] = t1;
        out[3] = dv;
        out[4] = a1;
        out[5] = t1 + tHold + t1;
    }

    /**
     * 把 {@link #planRamp} 解出的分段按给定方向写入 {@code list} 并推进 {@code state}。
     *
     * @param list 追加分段的目标表；为 null 时只推进 {@code state}
     * @param state 长度 3 的数组 {位置增量, 速度, 加速度}
     * @param ramp {@link #planRamp} 的输出（out[0..5]）
     * @param sign +1 = 加速段（jerk 序列 +j, 0, -j）；-1 = 减速段（jerk 序列 -j, 0, +j）
     */
    private void applyRamp(List<Segment> list, double[] state, double[] ramp, int sign) {
        double j = sign * rampJerkUp(ramp);
        append(list, state, ramp[0], j);
        append(list, state, ramp[1], 0);
        append(list, state, ramp[2], -j);
    }

    /**
     * @param ramp {@link #planRamp} 的输出
     * @return 加速段首段斜坡所用的 jerk（格/刻³），即"加速度峰值 / 斜坡时长"；恒 ≤ maxJerk
     */
    private double rampJerkUp(double[] ramp) {
        return ramp[0] > 1e-12 ? ramp[4] / ramp[0] : maxJerk;
    }

    /**
     * 生成"收尾斜坡"：在剩余距离不足以跑完一个完整 S 形时的兜底曲线，让车在这一小段里就停下，
     * 并且<b>恰好</b>停在目标上。
     *
     * <p>什么时候会走到这里：目标距离比"最短 S 形"（从静止出发、jerk 与加速度都取上限的那条曲线）
     * 还近——例如运行途中顺路改道，只剩零点几格。此时曲线并不是"按上限猛刹"，而是<b>解出</b>恰好
     * 能在这一小段内把速度收到 0 的减速度：距离越短、需要的减速度越大，位置与速度同时满足终点条件。
     * 因此它既不会过冲、也不会停不到站（两者都会破坏"精确到站"这条不变量）。
     *
     * <p>若起点速度已经是 0（退化到"只剩极小距离"的极端情形），则不做任何加速，只留一个极小的
     * 一次性位移，由 {@link #advance} 的终点分支抹平残差。
     *
     * @param d 剩余距离（格，> 0）
     * @param v0 起点速度沿运动方向的分量（格/刻，≥ 0）
     */
    private void rampPlan(double d, double v0) {
        List<Segment> list = new ArrayList<>();
        if (v0 <= 1e-12) {
            // 起点静止又几乎没有距离：不加速，直接给一个极小的位移，残差由终点分支吸附。
            list.add(new Segment(1, 0, 0, d, 0));
            list.add(new Segment(0, 0, d, 0, 0));
            segments = list;
            return;
        }
        // 解 v0·t + a·t²/2 = d 且 v0 + a·t = 0 ⇒ t = 2d/v0、a = -v0²/(2d)：
        // 一个匀减速段就能同时满足"走完 d"和"速度归零"。
        double time = 2 * d / v0;
        double decel = -v0 * v0 / (2 * d);
        if (!Double.isFinite(time) || time <= 1e-12) {
            // 数值极端（d 极小或 v0 极大）：退回"本刻尽量减速"，由下一刻的重新规划接手。
            time = 1;
            decel = -v0;
        }
        list.add(new Segment(time, 0, 0, v0, decel));
        list.add(new Segment(0, 0, d, 0, 0)); // 终段兜底：终点位置/速度/加速度都由它声明
        segments = list;
    }

    /**
     * 把一段常加加速度运动追加到分段表，并原地推进 {@code state}。
     *
     * @param list 分段表；为 null 时只推进 {@code state}（二分求值用，不产生任何对象）
     * @param state 长度 3 的数组 {位置增量, 速度, 加速度}
     * @param duration 本段时长（刻）；≤ 0 时直接跳过（不产生"零时长段"）
     * @param jerk 本段 jerk（格/刻³）
     */
    private static void append(List<Segment> list, double[] state, double duration, double jerk) {
        if (duration <= 1e-12) return;
        if (list != null) list.add(new Segment(duration, jerk, state[0], state[1], state[2]));
        // 运动学三式：位置是时间的三次、速度是二次、加速度是一次。
        state[0] += state[1] * duration + state[2] * duration * duration * .5 + jerk * duration * duration * duration / 6;
        state[1] += state[2] * duration + jerk * duration * duration * .5;
        state[2] += jerk * duration;
    }
}
