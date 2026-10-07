package org.DJB.easyelevator.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.RiderMotionHistory;

/**
 * 乘客移动包（C2S，{@code easyelevator:rider_move}）：把一个原版移动包与"客户端做物理时所用的轿厢样本编号"
 * 原子地绑在一起。
 *
 * <p>为什么需要它：两端对"地板在哪"的认知有一趟单程延迟。客户端是在收到
 * {@link ElevatorNetworking.MotionFrame} 之后才把轿厢搬上去的，玩家在客户端算出的绝对 Y 是
 * "当时的轿厢 Y + 相对高度"；包到服务端时轿厢已经又走了一段，若原样交给原版 {@code onPlayerMove}，
 * 服务端会把这段差算成玩家自己在飞（触发拉回或悬浮判定）。因此客户端把"本包的 Y 是相对于哪个轿厢样本
 * 算出来的"（{@link #tick()}）一并送上，服务端再用自己权威的样本高度（
 * {@link AbstractCabinEntity#motionHeight}）把 Y 重新基准化——水平移动与跳跃高度原样保留，
 * 而"平台自身走了多远"由服务端说了算（见 {@code RiderMovementTests#delayedPacketsKeepWalkingAndJumping}）。
 *
 * <p>在整体架构中的位置：network 包中唯一的运动类 C2S 包。发送方是客户端
 * {@code client/CabinMotion#sendMovement}（由 {@code mixin/client/ClientPlayerEntityMixin} 重定向原版
 * {@code sendMovementPackets} 触发），接收方由本类 {@link #register()} 注册。服务端<b>仍然执行原版全部校验</b>
 * （速度、碰撞、飞行、传送判定一概保留），本包只在最外层补一个坐标系修正。
 *
 * <p>关键不变量/约束：
 * <ul>
 *   <li>客户端携带的一切都是<b>不可信输入</b>：样本编号必须能在轿厢历史里查到、玩家必须真是该厢乘客，
 *       否则 {@link #apply} 原样投递，不会凭空多给位移（见 {@code forgedSampleCannotAddPlatformDisplacement}）。</li>
 *   <li>{@link #tick()} 是服务端世界时间（刻），即"样本编号"而非墙钟；同一辆轿厢内唯一。</li>
 *   <li>本包不改玩家速度：随厢位移由 {@code AbstractCabinEntity.carryPassenger} 单独完成，这里只做坐标翻译。</li>
 * </ul>
 *
 * <p>单位约定：坐标与偏移量为格（方块），{@code tick} 为世界时间（刻），角度为度。
 *
 * @param cabinId 客户端认为自己所在的轿厢实体运行时 id（不写存档，跨存档不保证稳定）
 * @param tick 生成该移动包时客户端所用的轿厢样本编号（世界时间），单位：刻
 * @param x 玩家绝对 X，单位：格（由客户端物理算出）
 * @param y 玩家绝对 Y，单位：格（基准是客户端所见的那个轿厢样本）
 * @param z 玩家绝对 Z，单位：格
 * @param yaw 玩家朝向角，单位：度
 * @param pitch 玩家俯仰角，单位：度
 * @param ground 原版"是否在地面上"标志
 * @param position 原版标志：本包是否携带位置变化
 * @param look 原版标志：本包是否携带视角变化
 */
public record RiderMove(int cabinId, long tick, double x, double y, double z,
                        float yaw, float pitch, boolean ground, boolean position, boolean look)
        implements CustomPayload {
    /** 负载类型 id，注册与路由键：{@code easyelevator:rider_move}。 */
    public static final Id<RiderMove> ID = new Id<>(Easyelevator.id("rider_move"));
    /** 线格式编解码器：字段顺序必须与 {@link #decode}/{@link #encode} 严格一致，否则两端静默错位。 */
    public static final PacketCodec<RegistryByteBuf, RiderMove> CODEC = new PacketCodec<>() {
        /** 从字节流按固定顺序读回一包移动：VarInt 实体 id、long 样本编号、3 个 double 坐标、两个 float 视角、3 个布尔标志。 */
        @Override public RiderMove decode(RegistryByteBuf b) {
            return new RiderMove(b.readVarInt(), b.readLong(), b.readDouble(), b.readDouble(),
                    b.readDouble(), b.readFloat(), b.readFloat(), b.readBoolean(), b.readBoolean(), b.readBoolean());
        }
        /** 按与解码完全对称的顺序写出；顺序一旦改动即为协议不兼容。 */
        @Override public void encode(RegistryByteBuf b, RiderMove p) {
            b.writeVarInt(p.cabinId); b.writeLong(p.tick);
            b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z);
            b.writeFloat(p.yaw); b.writeFloat(p.pitch);
            b.writeBoolean(p.ground); b.writeBoolean(p.position); b.writeBoolean(p.look);
        }
    };

    /**
     * 用客户端实际发出的那个原版移动包构造本包：除了补上轿厢实体 id 与样本编号，其余字段逐项照抄。
     *
     * <p>之所以"照抄"而不是重新拼装：服务端最终要还原成一个原版移动包，只有原样保留三个布尔标志
     * （{@link #ground}/{@link #position}/{@link #look}），才能让服务端看到与原版完全相同的包类型与
     * 语义（例如纯 onGround 包不会被当成位置更新）。
     *
     * @param cabinId 本机玩家所处的轿厢实体运行时 id
     * @param tick 本包所用的轿厢样本编号（世界时间），单位：刻
     * @param p 原版此刻决定发送的移动包
     * @return 可直接发送的乘客移动包
     */
    public static RiderMove of(int cabinId, long tick, PlayerMoveC2SPacket p) {
        return new RiderMove(cabinId, tick, p.getX(0), p.getY(0), p.getZ(0),
                p.getYaw(0), p.getPitch(0), p.isOnGround(), p.changesPosition(), p.changesLook());
    }

    /** @return 负载类型 id，框架据此把包分发到对应接收器 */
    @Override public Id<? extends CustomPayload> getId() { return ID; }

    /**
     * 还原成原版移动包，但 Y 换成调用方给定的值。
     *
     * <p>四种分支覆盖原版全部移动包形态：位置+视角（{@code Full}）、仅位置、仅视角、只有 onGround。
     * 必须保持分支与 {@link #position}/{@link #look} 一一对应，否则会把"只转头"的包变成一次位置更新
     * （服务端据此判定作弊或误触发移动）。
     *
     * @param adjustedY 已经换算回服务端当前帧的玩家 Y，单位：格
     * @return 可直接交给 {@code ServerPlayNetworkHandler#onPlayerMove} 的原版移动包
     */
    public PlayerMoveC2SPacket vanilla(double adjustedY) {
        if (position && look) return new PlayerMoveC2SPacket.Full(x, adjustedY, z, yaw, pitch, ground);
        if (position) return new PlayerMoveC2SPacket.PositionAndOnGround(x, adjustedY, z, ground);
        if (look) return new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, ground);
        return new PlayerMoveC2SPacket.OnGroundOnly(ground);
    }

    /**
     * 服务端处理入口：把客户端带来的 Y 从"客户端所见帧"换算回"服务端当前帧"，再交给原版处理。
     *
     * <p>补偿需要同时满足：本包携带位置变化、玩家存活且非旁观且未骑乘、实体 id 确实是本世界的轿厢、
     * 玩家确实是该厢乘客、样本编号能查到、玩家相对该样本的高度在 -0.1..2.8 格之间（站地板到顶板净高）、
     * 水平位置距轿厢中心不超过 1.9 格。任一不满足就<b>原样投递</b>——等价于纯原版行为，绝不会凭空多给位移。
     *
     * <p>副作用：调用 {@code ServerPlayNetworkHandler#onPlayerMove}，因此会真实更新玩家位置、
     * 触发原版速度/碰撞/传送校验（本包不豁免任何一条）。
     *
     * @param player 发包的玩家；<b>只允许在服务端主线程调用</b>（{@link #register()} 的接收器已用
     *               {@code context.server().execute} 切回主线程）
     */
    public void apply(ServerPlayerEntity player) {
        double adjustedY = y;
        if (position && player.isAlive() && !player.isSpectator() && !player.hasVehicle()
                && player.getWorld().getEntityById(cabinId) instanceof AbstractCabinEntity cabin
                && cabin.containsPassenger(player)) {
            double seenY = cabin.motionHeight(tick);
            // 只接受"确实存在的近期服务端样本"，并且身体必须贴近这辆轿厢：
            // 样本查不到（motionHeight 返回 NaN）说明客户端在伪造/使用了过期编号，此时退回原版；
            // 相对高度与水平距离的阈值就是"乘客可能在厢内"的范围，超出即视为普通移动或传送。
            // 最终那个包仍要过原版全部速度与碰撞校验。
            double relativeY = y - seenY;
            if (Double.isFinite(seenY) && relativeY >= -.1 && relativeY <= 2.8
                    && Math.abs(x - cabin.getX()) <= 1.9 && Math.abs(z - cabin.getZ()) <= 1.9) {
                adjustedY = RiderMotionHistory.rebase(y, seenY, cabin.getY());
            }
        }
        player.networkHandler.onPlayerMove(vanilla(adjustedY));
    }

    /**
     * 注册负载类型与服务端接收器；由模组初始化时调用一次（经 {@code ElevatorNetworking.register()}）。
     *
     * <p>注册必须先于任何收发，否则两端会因缺少 id 而断连。接收器在网络线程被调用，故整体切回服务端主线程
     * 再执行 {@link #apply}——实体查询与玩家位置更新都只能在主线程做。
     */
    public static void register() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (packet, context) ->
                context.server().execute(() -> packet.apply(context.player())));
    }
}
