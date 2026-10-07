package org.DJB.easyelevator.logic;

import java.util.ArrayDeque;

/**
 * 单厢的"绝对高度样本历史"，是随厢移动坐标系补偿的<b>服务端权威</b>一侧。
 *
 * <p>要解决的问题：客户端与服务端之间存在单程延迟。客户端发出移动包时用的是"它那一刻看到的轿厢高度"，
 * 包到达服务端时轿厢已经又走了一段；若把包里的绝对 Y 直接当玩家位置，服务端会把这段差当成玩家自己的运动
 * （表现为拉回、悬浮判定或速度作弊）。本类记录轿厢每刻的绝对 Y，{@link #height} 让服务端按客户端携带的
 * 样本编号取回<b>当时</b>的高度，{@link #rebase} 再把玩家的 Y 换算到当前帧——于是"平台走了多远"由服务端说了算，
 * 而玩家的水平位移与跳跃高度被原样保留（见 {@code RiderMovementTests#historyRejectsStaleAndUnknownFrames}）。
 *
 * <p>在整体架构中的位置：纯 Java 的 logic 层工具，不引用任何 Minecraft 类；被
 * {@code AbstractCabinEntity} 持有（每刻在 {@code setPosition} 之后 {@code record} 一次），
 * 并被 {@code network/RiderMove#apply} 查询。客户端只上报"样本编号（世界时间刻）"，
 * <b>永远不提供平台位移量</b>——这正是换算必须留在服务端的原因。
 *
 * <p>关键不变量/约束：
 * <ul>
 *   <li>只接受<b>严格递增</b>的世界时间：重复或更早的样本一律丢弃，因此伪造包无法用旧编号覆盖权威值。</li>
 *   <li>只保留最近 {@link #MAX_AGE}+1 个样本，内存占用有界；更早的样本查不到就返回 {@link Double#NaN}，
 *       调用方必须据此退回原版行为而不是猜测。</li>
 *   <li>非有限值（NaN/无穷）不记录：坏样本不得成为权威高度。</li>
 *   <li>非线程安全，只允许服务端主线程访问（每辆轿厢一个实例，实例之间不共享）。</li>
 * </ul>
 */
public final class RiderMotionHistory {
    /** 样本最大年龄，单位：刻（60 刻 = 3 秒）；比这更早的编号视为过期，不再参与补偿。 */
    public static final int MAX_AGE = 60;
    /** 单个样本：采样时刻（世界时间，刻）→ 轿厢绝对 Y（格）。不可变。 */
    private record Frame(long tick, double y) { }
    /** 按时间递增排列的样本队列：队首最旧、队尾最新；长度上限见 {@link #MAX_AGE}。 */
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();

    /**
     * 记录一帧轿厢绝对高度；由轿厢在自己位置写回之后每刻调用一次。
     *
     * <p>副作用：向队尾追加样本，并在超出 {@link #MAX_AGE}+1 时从队首淘汰最旧样本。
     *
     * @param tick 采样时刻的世界时间，单位：刻；必须严格大于已记录的最后一帧，否则本次调用被完全忽略
     * @param y 轿厢本体的绝对 Y 坐标，单位：格；非有限值视为坏样本，直接忽略
     */
    public void record(long tick, double y) {
        if (!Double.isFinite(y)) return;
        if (!frames.isEmpty() && tick <= frames.getLast().tick()) return;
        frames.addLast(new Frame(tick, y));
        while (frames.size() > MAX_AGE + 1) frames.removeFirst();
    }

    /**
     * 查询某个历史时刻的轿厢高度。
     *
     * @param tick 客户端移动包携带的样本编号（世界时间），单位：刻
     * @param now 当前世界时间，单位：刻；用于判定样本是否过期
     * @return 该刻的轿厢绝对 Y，单位：格；样本尚未产生、已超过 {@link #MAX_AGE} 刻、或从未记录过时返回
     *         {@link Double#NaN}——调用方据此放弃补偿并走原版路径
     */
    public double height(long tick, long now) {
        if (tick > now || tick < now - MAX_AGE) return Double.NaN;
        for (Frame frame : frames) if (frame.tick() == tick) return frame.y();
        return Double.NaN;
    }

    /**
     * 把玩家的绝对 Y 从"客户端所见帧"换算到"服务端当前帧"。
     *
     * <p>换算只做一次加减、不引入衰减或插值，因此玩家相对地板的高度（含跳跃高度）被精确保留；
     * 纯函数、无副作用，便于单测与 GameTest 在多种延时/速度组合下反复验证。
     *
     * @param playerY 移动包里的玩家 Y，单位：格（相对于 {@code seenCabinY} 的旧帧）
     * @param seenCabinY 客户端生成该包时所用的轿厢样本高度（来自 {@link #height}），单位：格
     * @param currentCabinY 服务端当前的轿厢高度，单位：格
     * @return 换算到当前帧后的玩家 Y，单位：格，等于 {@code currentCabinY + (playerY - seenCabinY)}
     */
    public static double rebase(double playerY, double seenCabinY, double currentCabinY) {
        return currentCabinY + (playerY - seenCabinY);
    }
}
