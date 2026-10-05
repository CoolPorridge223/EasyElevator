package org.DJB.easyelevator.logic;

import java.util.Locale;

/**
 * 显示用的轿厢运行状态：选站面板、轿厢内面板与楼层门框上显示的"电梯上行 / 电梯下行 / 停靠"。
 *
 * <p>完全由<b>已经同步到客户端</b>的数据推导（Phase + 目的站高度 + 当前高度），因此服务端与客户端
 * 得到同一个结果，不需要再增加同步字段。判定规则：
 * <ul>
 *   <li>只有"运行中（{@link ElevatorController.Phase#MOVING}）且存在目的站"才可能是上行或下行；</li>
 *   <li>目的站高于当前高度 → {@link #UP}，低于 → {@link #DOWN}，几乎同高（马上要开门）→ {@link #IDLE}；</li>
 *   <li>其余全部算 {@link #IDLE}：开门、开门中、关门中、暂停（BLOCKED）以及关着门停在某层等待呼叫。</li>
 * </ul>
 *
 * <p>纯逻辑，不引用任何 Minecraft 类，因此可以脱离游戏直接跑测试（见 {@code ElevatorStatusTest}）。
 */
public enum ElevatorStatus {
    /** 停靠：没有正在前往别的楼层（含开门、关门与暂停等所有静止状态）。 */
    IDLE,
    /** 上行：正在前往更高的楼层。 */
    UP,
    /** 下行：正在前往更低的楼层。 */
    DOWN;

    /** @return 翻译键后缀，取值就是 {@code status.easyelevator.up} / {@code down} / {@code idle} 的最后一段 */
    public String key() { return name().toLowerCase(Locale.ROOT); }

    /**
     * 由同步状态推导运行状态。
     *
     * @param phase 当前相位（客户端读同步字段，服务端读状态机）
     * @param targetY 目的站高度（格）；没有目的站时为 {@link Integer#MIN_VALUE} 哨兵值
     * @param y 当前高度（格）
     * @return 运行状态。高度比较用显示侧的 {@link ElevatorParameters#SYNC_POSITION_EPSILON}：
     *         客户端位置是插值出来的、会略微滞后，容差太小会把"刚到站"误判成还在上行/下行
     */
    public static ElevatorStatus of(ElevatorController.Phase phase,int targetY,double y) {
        if (phase!=ElevatorController.Phase.MOVING || targetY==Integer.MIN_VALUE) return IDLE;
        if (targetY>y+ElevatorParameters.SYNC_POSITION_EPSILON) return UP;
        if (targetY<y-ElevatorParameters.SYNC_POSITION_EPSILON) return DOWN;
        return IDLE; // 目的站就是当前高度：已经到站，接下来是开门，显示"停靠"
    }

    /**
     * 轿厢是否处于故障（受阻暂停）——断轨、线路朝向不一致、井道里有方块或实体障碍、区块未加载、
     * 目的站的门被拆，任何一种都会让状态机停在 {@link ElevatorController.Phase#BLOCKED}。
     *
     * <p>为什么只看"相位是 BLOCKED"就够：状态机里会出现 BLOCKED 的地方只有两类——
     * ① 目的站失效（门被拆/被换掉），此时目的站已被清空；
     * ② 本刻的移动被 {@code Environment.canMove} 否决（断轨、障碍、区块未加载、线路不再唯一），
     * 此时目的站仍然保留，等条件恢复后继续。
     * 两类都是"故障"：都不是车能自己走掉的状态。空闲待命（关着门排队等呼叫）走的是
     * {@code MOVING} 且无目的站，不在此列，因此不会把正常停靠误判成故障。
     *
     * <p>判据完全来自已同步的数据，因此服务端与客户端得到同一个结论。故障时乘客可能被困在两层之间，
     * 面板上的开门键必须可用（见 {@link ElevatorController#canOpenDoor}），让他们能主动脱困。
     *
     * @param phase 当前相位（客户端读同步字段，服务端读状态机）
     * @return 处于故障/受阻暂停时为 true
     */
    public static boolean faulted(ElevatorController.Phase phase) {
        return phase==ElevatorController.Phase.BLOCKED;
    }
}
