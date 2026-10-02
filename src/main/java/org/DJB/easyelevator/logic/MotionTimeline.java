package org.DJB.easyelevator.logic;

import java.util.ArrayDeque;

/**
 * Bounded, non-extrapolating double-precision timeline, shared by model and local rider camera.
 *
 * <p>职责：保存服务端同步来的双精度 Y 位置样本（服务端刻 -> 格），并按本机刻在已知样本之间做线性插值，
 * 同时供轿厢模型渲染与本地乘客镜头使用。之所以需要它：原版位置包用定点量化（1/4096 格）会呈现可见台阶感，
 * 而自定义 MotionFrame 包同步的是绝对 double，需要一个客户端侧的样本缓冲来把离散样本还原成连续运动。</p>
 *
 * <p>在整体架构中的位置：不引用任何 Minecraft 类的纯 Java 时间线，实际使用方是客户端 client/CabinMotion
 * （同时也因此可脱离游戏在单测中直接验证）；服务端为权威，本类只服务“显示与本地镜头”，
 * 任何插值结果都不回写服务端，也不参与碰撞或到站判定。</p>
 *
 * <p>关键不变量与约束：样本按服务端刻严格递增；样本数不超过 {@link ElevatorParameters#MOTION_HISTORY_SIZE}；
 * {@code clockOffset} 是“本机刻 - 服务端刻”的偏移估计，仅在不连续处重算；
 * 绝不外推——数据包停止时也不预测，宁可停在最后一个已知样本上。</p>
 */
public final class MotionTimeline {
    /** 单个位置样本：tick 为服务端游戏刻（单位：刻），y 为该刻的绝对 Y 坐标（单位：格）。 */
    private record Sample(long tick, double y) { }
    /** 样本队列：队首最旧、队尾最新，插值只在队列内相邻样本之间进行。 */
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    /** 本机刻与服务端刻的偏移估计（单位：刻），sample() 据此把本地时间换算回服务端时间轴。 */
    private double clockOffset;

    /**
     * 追加一个服务端位置样本。
     *
     * @param serverTick 样本对应的服务端游戏刻（单位：刻）
     * @param y 该刻的绝对 Y 坐标（单位：格）
     * @param localTick 收到该样本时的本机刻（单位：刻），用于估算 clockOffset
     * @param previousY 服务端给出的上一刻 Y（单位：格），用于判断是否发生瞬移
     * @return 无返回值
     * 副作用：可能清空整个时间线并重算 clockOffset；样本数超上限时丢弃最旧样本。
     * 说明：非有限数值（NaN/Inf）与乱序样本（刻号未递增）被静默忽略，避免污染插值曲线。
     */
    public void add(long serverTick, double y, double localTick, double previousY) {
        if (!Double.isFinite(y) || !Double.isFinite(localTick) || !Double.isFinite(previousY)) return;
        Sample last = samples.peekLast();
        // 乱序或重复刻号的样本直接丢弃：时间线要求刻号严格递增，否则插值区间会退化为 0 甚至为负。
        if (last != null && serverTick <= last.tick()) return;
        boolean snap = Math.abs(y - previousY) > ElevatorParameters.MOTION_SNAP_DISTANCE;
        // 三种情况重建时间线：首个样本、间隔过大（中断/重载）、单包位移过大（瞬移）。
        // 重建时用 localTick - serverTick 重估偏移，消除这段时间内累积的时钟漂移。
        if (last == null || serverTick - last.tick() > ElevatorParameters.MOTION_RESET_GAP_TICKS || snap) {
            samples.clear();
            clockOffset = localTick - serverTick;
            // 补一个“服务端刻 - 1”的起点样本，否则首个包与下一个包之间没有可插值区间。
            // 瞬移时起点直接取新值 y，避免从旧位置插值出一条横穿井道的假轨迹。
            samples.add(new Sample(serverTick - 1, snap ? y : previousY));
        }
        samples.add(new Sample(serverTick, y));
        // 超出上限后丢弃最旧样本：只保留近期历史，内存占用恒定。
        while (samples.size() > ElevatorParameters.MOTION_HISTORY_SIZE) samples.removeFirst();
    }

    /**
     * 取指定本机刻对应的插值 Y 坐标。
     *
     * @param localTick 本机当前刻（单位：刻）
     * @param fallback 时间线为空时的回退值（通常为服务端 DataTracker 同步的 Y，单位：格）
     * @return 插值后的 Y 坐标（单位：格）
     * 说明：先减去 clockOffset 与 INTERPOLATION_DELAY_TICKS 得到目标服务端刻，
     * 再在已有样本之间线性插值；目标刻早于队首取队首，晚于队尾取队尾——
     * 也就是说只做区间内插值，绝不外推。
     */
    public double sample(double localTick, double fallback) {
        if (samples.isEmpty()) return fallback;
        // 回退 INTERPOLATION_DELAY_TICKS 刻再采样：牺牲约 100 毫秒显示延迟换取抗抖动。
        double targetTick = localTick - clockOffset - ElevatorParameters.INTERPOLATION_DELAY_TICKS;
        Sample before = samples.getFirst();
        if (targetTick <= before.tick()) return before.y();
        for (Sample after : samples) {
            if (targetTick <= after.tick()) {
                // alpha 是目标刻在 [before, after] 区间内的归一化位置（0..1，无量纲）。
                double alpha = (targetTick - before.tick()) / (after.tick() - before.tick());
                return before.y() + (after.y() - before.y()) * alpha;
            }
            before = after;
        }
        // Never predict through an obstacle or beyond a station when packets stop.
        // 绝不外推：数据包停止后继续按速度预测可能穿墙或越过目的站，因此停在最后一个已知样本上。
        return samples.getLast().y();
    }
}
