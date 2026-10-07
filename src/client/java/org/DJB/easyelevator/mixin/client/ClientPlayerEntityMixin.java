package org.DJB.easyelevator.mixin.client;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.Packet;
import org.DJB.easyelevator.client.CabinMotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端玩家实体（{@code ClientPlayerEntity}）的混入：把"本刻如何移动"拆成
 * "先搬地板，再发带样本编号的移动包"两步，交由 {@link CabinMotion} 统一裁决。
 *
 * <p>两处注入（先后顺序就是原版一刻内的真实顺序）：
 * <ol>
 *   <li>{@code tick} 的 <b>HEAD</b> → {@link CabinMotion#beginPlayerTick}：在玩家本刻物理与移动包生成之前，
 *       把轿厢搬到最新的服务端样本高度，并用同一差值托起乘客。放在 HEAD 是硬要求——若等玩家物理之后再搬，
 *       本刻玩家可能先撞进还在下方/上方的地板里（穿模或卡住）。</li>
 *   <li>{@code sendMovementPackets} 里对 {@code ClientPlayNetworkHandler#sendPacket} 的 <b>Redirect</b>
 *       → {@link CabinMotion#sendMovement}：原版"这一刻要不要发、发哪个移动包"的逻辑完全保留，
 *       只把落点改成我方——随厢时换成携带样本编号的 {@code RiderMove}，否则原样转发。</li>
 * </ol>
 *
 * <p>为什么用 Redirect 而不是 Inject：这里必须<b>替换</b>外发包（不能既发原包又发自定义包），
 * 而 {@code sendMovementPackets} 内部有多处调用 {@code sendPacket}（位置、视角、载具等分支），
 * 重定向这一个方法调用点即可覆盖全部分支，既不必理解原版的"是否有变化"判定，也不会多发或漏发包。
 *
 * <p>关键不变量/约束：
 * <ul>
 *   <li>仅客户端类：登记在 {@code easyelevator.client.mixins.json} 的 client 列表（required），
 *       注入失败即客户端启动失败。</li>
 *   <li>本类<b>不改</b>玩家输入、速度与物理常量：客户端物理仍是原版，位移只由
 *       {@link CabinMotion#beginPlayerTick} 平移，权威换算留在服务端。</li>
 *   <li>{@code (ClientPlayerEntity)(Object)this} 的转型是混入类自身不继承目标类时的必然写法，不能省。</li>
 * </ul>
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
    /**
     * 在玩家本刻 tick 的最开头提交轿厢位置，并在本机乘客确实在厢内时随厢平移玩家。
     *
     * <p>必须早于原版玩家物理，理由见类注释第 1 条；具体判定与副作用全部在
     * {@link CabinMotion#beginPlayerTick} 内。
     *
     * @param ci 原版回调信息；本方法不取消调用
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void easyelevator$moveFloor(CallbackInfo ci) {
        CabinMotion.beginPlayerTick((ClientPlayerEntity) (Object) this);
    }

    /**
     * 接管 {@code sendPacket} 这一个调用点：随厢时改发 {@link org.DJB.easyelevator.network.RiderMove}，
     * 否则原样转发给原版连接处理器。
     *
     * <p>副作用：可能"少发"一个原版移动包（被替换成自定义包），但绝不重复发送；
     * 包不是 {@code PlayerMoveC2SPacket}、或本刻没有轿厢承载玩家时，行为与直接调用
     * {@code handler.sendPacket(packet)} 完全一致。
     *
     * @param handler 原版客户端连接处理器，最终仍由它发包（自定义包也走它）
     * @param packet 原版此刻决定发送的包
     */
    @Redirect(method = "sendMovementPackets", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayNetworkHandler;sendPacket(Lnet/minecraft/network/packet/Packet;)V"))
    private void easyelevator$relativeMove(ClientPlayNetworkHandler handler, Packet<?> packet) {
        CabinMotion.sendMovement(handler, packet);
    }
}
