package org.DJB.easyelevator.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.MathHelper;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.network.ElevatorNetworking;
import org.DJB.easyelevator.network.RiderMove;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 客户端"平台坐标系"的唯一持有者：接收服务端样本、按样本搬运轿厢与乘客、并把样本编号塞进移动包。
 *
 * <p>要解决的问题：原版实体位置同步把坐标量化到 1/4096 格并逐包做相对加法，低速运行时会出现台阶跳动与
 * 累计漂移；而轿厢地板还必须与乘客、碰撞、渲染三者严格一致。于是模组改为下发绝对 double 高度样本
 * （{@link ElevatorNetworking.MotionFrame}），由本类承担客户端的全部落地工作：
 * <ol>
 *   <li>{@link #receive}：收样本，只记目标高度与到达时刻，<b>绝不外推</b>；</li>
 *   <li>{@link #beginPlayerTick}：在玩家物理之前把轿厢写到位，并把乘客平移同一个差值；</li>
 *   <li>{@link #sendMovement}：把原版要发的移动包换成携带"本包基于哪个样本"的 {@link RiderMove}；</li>
 *   <li>{@link #renderY}：给渲染层提供两帧之间的插值高度。</li>
 * </ol>
 *
 * <p>在整体架构中的位置：client 包的中枢。上游是 {@code MotionFrame} 的接收器（{@link EasyelevatorClient}）
 * 与两个客户端混入（{@link org.DJB.easyelevator.mixin.client.ClientPlayerEntityMixin}），下游是
 * {@link CabinRenderer} 的渲染位移与 {@link AbstractCabinEntity#carryPassenger} 的玩家平移。
 * 服务端一侧的对应物是 {@code logic/RiderMotionHistory}：那边是权威高度表，这边是"只认样本、
 * 不外推"的消费方，两边正好互补。
 *
 * <p>关键不变量/约束：
 * <ul>
 *   <li><b>不外推</b>：没有新样本时轿厢保持最后收到的目标高度；样本超过 {@code MOTION_STALE_TICKS}
 *       未更新就不再托举乘客。宁可少动也不猜——这正是服务端无需下发速度或轨迹的原因。</li>
 *   <li><b>单厢托举</b>：一个玩家一刻至多被一辆轿厢承载（{@code tickCabin} 只记第一辆），
 *       避免重叠判定让相邻轿厢重复平移玩家。</li>
 *   <li><b>只允许客户端主线程访问</b>：{@code TRACKS}、{@code tickCabin}/{@code tickFrame} 都没有同步。
 *       {@code tickFrame} 是"本刻"的临时状态：由 {@link #beginPlayerTick} 写入、同一刻的
 *       {@link #sendMovement} 取用（原版 {@code ClientPlayerEntity#tick} 在末尾调用
 *       {@code sendMovementPackets}，因此两者一定在同一刻内配对）。</li>
 *   <li>{@code TRACKS} 用 {@link WeakHashMap} 且键是实体：不阻止区块卸载后轿厢被回收，也不跨存档累积；
 *       {@link #beginPlayerTick} 还会顺手清掉已移除或换了世界的条目。</li>
 *   <li>换世界或断线必须 {@link #clear}（见 {@link EasyelevatorClient} 的断开处理），
 *       否则会把上个世界的目标高度带进新世界的第一刻。</li>
 * </ul>
 */
public final class CabinMotion {
    /** 轿厢实体 → 本机维护的运动轨迹。弱键，见类注释"关键不变量"。 */
    private static final Map<AbstractCabinEntity, Track> TRACKS = new WeakHashMap<>();
    /** 本刻承载本机玩家的那辆轿厢；null = 本刻未随厢。由 {@link #beginPlayerTick} 写入、{@link #sendMovement} 读取。 */
    private static AbstractCabinEntity tickCabin;
    /** 本刻托举玩家所用的服务端样本编号（世界时间，刻）；只在 {@link #tickCabin} 非 null 时有意义，两者同生共死。 */
    private static long tickFrame;

    /**
     * 单辆轿厢的本机状态：服务端样本给出的目标高度 + 本地已提交的物理高度。
     *
     * <p>插值只在渲染时做（{@link #renderY}），物理与碰撞永远使用 {@code physicalY}，
     * 因此"看到的"与"撞到的"不会互相污染。
     */
    private static final class Track {
        /** {@code receivedAt} = 最近一次样本的接收时刻（客户端世界时间，刻），用于 {@code MOTION_STALE_TICKS} 超时判定；
         * {@code serverTick} = 已应用的最新样本编号（世界时间，刻），{@code Long.MIN_VALUE} 表示还没收到过样本。 */
        long receivedAt, serverTick = Long.MIN_VALUE;
        /** {@code targetY} = 服务端样本给出的目标高度（格），本机物理位置最终要对齐到它；
         * {@code previousY} = 上一刻提交的物理高度（格），与 {@code physicalY} 组成渲染插值的两端；
         * {@code physicalY} = 当前已提交的物理高度（格），承托、碰撞与渲染位移都以它为准。 */
        double targetY, previousY, physicalY;
        /** 首帧构造：三个高度都从实体当下位置起步，避免刚建立轨迹就把轿厢拉向 0。 */
        Track(double y) { targetY = previousY = physicalY = y; }
    }

    private CabinMotion() { }

    /**
     * 收到一帧服务端样本（S2C {@code MotionFrame}）：只记下目标高度与接收时刻，搬运留到本刻玩家 tick。
     *
     * <p>为什么不在这里直接 {@code setPosition}：搬轿厢必须与托举乘客、玩家物理在同一刻按固定顺序发生
     * （见 {@link #beginPlayerTick}），否则会出现"地板动了、人没动"的一帧穿模。
     *
     * <p>副作用：更新该厢的 {@link Track}，并调用 {@link AbstractCabinEntity#useClientMotion()}，
     * 使原版位置包<b>不再</b>参与本厢定位（此后只认绝对样本）。
     *
     * @param frame 服务端样本：轿厢实体 id、样本编号（世界时间，刻）、轿厢绝对 Y（格），
     *              以及已废弃的乘客偏移量（2.1.2 起客户端自行判定乘客，不再使用）
     */
    public static void receive(ElevatorNetworking.MotionFrame frame) {
        var client = MinecraftClient.getInstance();
        // 只在客户端世界已就绪、样本高度有限、且该 id 仍是本世界的轿厢时接受；否则静默丢弃（网络包一律视为不可信输入）。
        if (client.world == null || !Double.isFinite(frame.y())
                || !(client.world.getEntityById(frame.entityId()) instanceof AbstractCabinEntity cabin)) return;
        Track track = TRACKS.computeIfAbsent(cabin, c -> new Track(c.getY()));
        // 乱序或重复的编号一律忽略：只认更新的样本，旧编号可能来自更早的一条链路。
        if (frame.tick() <= track.serverTick) return;
        track.serverTick = frame.tick();
        track.targetY = frame.y();
        track.receivedAt = client.world.getTime();
        cabin.useClientMotion();
    }

    /**
     * 每个客户端玩家 tick 的最开头调用（由 {@link org.DJB.easyelevator.mixin.client.ClientPlayerEntityMixin}
     * 注入 {@code ClientPlayerEntity#tick} 的 HEAD）：提交全部轿厢位置，并把本机玩家托举到新的地板高度。
     *
     * <p>必须早于原版玩家物理，否则移动中的地板会先进到玩家脚里（穿模、卡住或直接被顶飞）。
     *
     * <p>副作用：改客户端轿厢实体坐标、可能改玩家坐标（{@link AbstractCabinEntity#carryPassenger}），
     * 并重置本刻的 {@link #tickCabin}/{@link #tickFrame}（每次调用都是一次全新的"本刻裁决"）。
     *
     * @param player 本机客户端玩家；必须与当前客户端世界一致，且只应在客户端主线程调用
     */
    public static void beginPlayerTick(ClientPlayerEntity player) {
        tickCabin = null;
        // 先清理轨迹：已移除（区块卸载/实体回收）或已不在本玩家世界的（换维度）条目一律丢弃，
        // 否则会把上个世界的目标高度继续套用到新世界的第一刻。
        TRACKS.entrySet().removeIf(e -> e.getKey().isRemoved() || e.getKey().getWorld() != player.getWorld());
        for (var entry : TRACKS.entrySet()) {
            AbstractCabinEntity cabin = entry.getKey();
            Track track = entry.getValue();
            // 上一刻的物理高度成为插值起点：每刻只推进一步，渲染插值因此只跨这一小步。
            track.previousY = track.physicalY;
            // 本刻位移（格）：从实体当前（客户端预测的）位置到服务端目标位置；这个量也要原样加在乘客身上。
            double dy = track.targetY - cabin.getY();
            boolean fresh = player.getWorld().getTime() - track.receivedAt <= ElevatorParameters.MOTION_STALE_TICKS;
            // 样本够新（链路没断，绝不外推）、玩家存活且确实在厢内、位移未超瞬移阈值，三者缺一不可；
            // 位移超阈值说明是传送/归位而不是平台运动，此时把乘客一起拽走会把人甩出轿厢。
            // tickCabin == null 保证本刻只认第一辆轿厢，避免重叠判定重复平移玩家。
            boolean riding = tickCabin == null && fresh && player.isAlive() && cabin.containsPassenger(player)
                    && Math.abs(dy) <= ElevatorParameters.MOTION_SNAP_DISTANCE;
            // 无论是否托举乘客都必须提交位置：轿厢本体要跟住服务端样本，否则下一帧的 dy 会把这点误差累积进去。
            cabin.setPosition(cabin.getX(), track.targetY, cabin.getZ());
            track.physicalY = track.targetY;
            if (riding) {
                tickCabin = cabin;
                tickFrame = track.serverTick;
                AbstractCabinEntity.carryPassenger(player, dy);
            }
        }
    }

    /**
     * 原版发送移动包时的落点：本刻随厢且发的确实是移动包时，改发携带样本编号的 {@link RiderMove}，
     * 否则原样交给原版连接处理器。
     *
     * <p>为什么要包一层：服务端需要知道"这个坐标是相对于哪个轿厢样本算出来的"才能做权威换算
     * （见 {@code logic/RiderMotionHistory}），而原版移动包没有这个字段。
     *
     * <p>副作用：可能不发原版移动包（被自定义包取代）。<b>绝不重复发送</b>，也不会额外触发传送。
     *
     * @param handler 原版客户端连接处理器，转发与自定义包的发送都走它
     * @param packet 原版此刻决定发送的包
     */
    public static void sendMovement(ClientPlayNetworkHandler handler, Packet<?> packet) {
        if (tickCabin != null && packet instanceof PlayerMoveC2SPacket move
                && ClientPlayNetworking.canSend(RiderMove.ID)) {
            // 只包住原版自己选中的那个包：既不重复发包，也不额外制造一次传送。
            ClientPlayNetworking.send(RiderMove.of(tickCabin.getId(), tickFrame, move));
        } else handler.sendPacket(packet);
    }

    /**
     * 渲染用的轿厢 Y：在"上一刻提交位置"与"当前提交位置"之间按帧内进度插值，供 {@link CabinRenderer} 做纯视觉位移。
     *
     * <p>只影响外观：实体坐标仍由原版渲染管线提供，碰撞与服务端位置不受影响。
     *
     * @param cabin 要渲染的轿厢
     * @param delta 帧内进度 0..1（原版 tickDelta）
     * @return 视觉 Y，单位：格；该轿厢还没有运动轨迹（例如静止的旧实体）时退回原版
     *         {@code lastRenderY → getY()} 的插值
     */
    public static double renderY(AbstractCabinEntity cabin, float delta) {
        Track track = TRACKS.get(cabin);
        return track == null ? MathHelper.lerp(delta, cabin.lastRenderY, cabin.getY())
                : MathHelper.lerp(delta, track.previousY, track.physicalY);
    }

    /**
     * 清空全部本机运动状态：换世界、断线或连接重建时调用（见 {@link EasyelevatorClient}）。
     *
     * <p>防的是"把上个世界的目标高度带进新世界"——不清的话，新世界第一刻就可能按旧样本搬运轿厢。
     */
    public static void clear() { TRACKS.clear(); tickCabin = null; }
}
