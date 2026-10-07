package org.DJB.easyelevator.network;

/**
 * "随厢移动"能力的<b>适配接口</b>：让实体层在不接触原版网络处理类私有字段的前提下，
 * 平移服务端玩家并同步原版的移动基准。
 *
 * <p>背景：轿厢地板是实体而不是方块，玩家随厢上升/下降既不能"设置位置"也不能用传送完成
 * （都会被原版判成瞬移而被拉回）；正确做法是直接平移玩家坐标，同时把
 * {@code ServerPlayNetworkHandler} 中用于速度与悬浮判定的<b>移动基准</b>（{@code lastTickY} /
 * {@code updatedY}）按同一差值平移，否则服务端会把这段平台位移算成玩家自己的垂直速度。
 *
 * <p>在整体架构中的位置：唯一实现方是 {@link org.DJB.easyelevator.mixin.ServerPlayNetworkHandlerMixin}
 * （它混入原版 {@code ServerPlayNetworkHandler} 并直接改那两个私有字段）；调用方是
 * {@code AbstractCabinEntity#carryPassenger}（随厢移动）与 {@code AbstractCabinEntity#tickPassengers}
 * （读档等待期判断"此刻能否安全归位"）。之所以抽成接口而不是就地转型原版类：那两个字段是私有的、
 * 也只有混入类看得见，接口把字段访问留在混入类内部，实体层只表达"我要平移这具身体"这一意图；
 * GameTest（{@code RiderMovementTests}）同样通过它断言"整趟运行没有触发修正传送"。
 *
 * <p>关键不变量/约束：
 * <ul>
 *   <li>方法名带 {@code easyelevator$} 前缀，避免与后续原版或其它模组给同一目标类加的方法重名。</li>
 *   <li>只有逻辑服务端的连接对象会实现它；客户端玩家没有对应的 {@code networkHandler}，
 *       调用方必须先判空再转型（见 {@code carryPassenger}）。</li>
 *   <li>必须在服务端主线程调用：两个字段没有任何同步保护，且与当刻的移动包处理同属一个逻辑线程。</li>
 * </ul>
 */
public interface PlatformMovement {
    /**
     * 此刻是否可以安全地平移这具身体。
     *
     * @return 没有待确认的强制传送时为 true；有（目标类里 {@code requestedTeleportPos != null}）时 false——
     *         那期间平移会被随后的传送确认当成作弊速度，因此调用方必须放弃本次随厢位移
     *         （见 {@code genuineTeleportIsNotCarried} 用例）
     */
    boolean easyelevator$canCarry();

    /**
     * 平移原版的移动基准，使平台位移不被计入玩家自身的运动。
     *
     * <p>副作用：就地修改目标连接上的两个私有双精度字段，不改玩家位置、不发包、不重置速度。
     *
     * @param dy 平移量，单位：格（方块）；正为向上，与轿厢本刻位移同号、同值
     */
    void easyelevator$carried(double dy);
}
