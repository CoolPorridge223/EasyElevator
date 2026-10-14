package org.DJB.easyelevator.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Optional;

/**
 * Keeps vanilla tracking packets from moving a cabin that has taken over its own client-side motion.
 *
 * <p>为什么需要它：轿厢在客户端的绝对高度由自定义运动包（{@code ElevatorNetworking.MotionFrame}，
 * 双精度、按刻对齐）决定，而原版追踪包只会把实体搬到服务端那一帧的位置。两者同时生效时，
 * 地板与乘客会各走各的、来回抖动（正是 1.21.1 时代 {@code AbstractCabinEntity} 覆写
 * {@code updateTrackedPositionAndAngles} 要解决的问题）。
 *
 * <p>为什么改成 mixin：1.21 之后原版把这个方法（以及它的 {@code Optional} 重载）标成了 {@code final}，
 * 实体再也不能覆写它；三个 {@code Vec3d}／角度重载最终都会汇聚到本 mixin 拦截的那个 {@code Optional} 重载，
 * 因此在这一处取消就能覆盖原版全部追踪位置包（绝对位置、相对位移、仅视角）。
 *
 * <p>关键不变量/约束：
 * <ul>
 *   <li>只在<b>客户端</b>、且该轿厢<b>已经收到过</b>自定义样本时取消（{@link AbstractCabinEntity#clientMotionControlled()}）：
 *       实体刚载入、还没有样本的那几刻仍走原版，避免轿厢在首帧没有定位。</li>
 *   <li>只影响 {@link AbstractCabinEntity} 这一族实体；其余实体的追踪行为逐位不变。</li>
 *   <li>取消的是"把原版坐标写进实体"，不改服务端权威位置、不改同步字段、不发包。</li>
 * </ul>
 */
@Mixin(Entity.class)
public abstract class TrackedPositionMixin {
    /**
     * 在 {@code Entity#updateTrackedPositionAndAngles(Optional, Optional, Optional)} 的入口处，
     * 对"已接管客户端定位的轿厢"取消这次更新。
     *
     * <p>选 {@code At("HEAD")}：必须在原版读取插值器、写入实体坐标之前拦下，否则那次写入已经发生。
     *
     * <p>副作用：取消本次追踪更新（{@code ci.cancel()}）。不改世界、不发包。
     *
     * @param pos 原版追踪包给出的绝对位置；空表示本包不含位置
     * @param yaw 原版追踪包给出的偏航角；空表示本包不含偏航
     * @param pitch 原版追踪包给出的俯仰角；空表示本包不含俯仰
     * @param ci 回调；对轿厢调用 {@code cancel()} 即跳过整次更新
     */
    @Inject(method="updateTrackedPositionAndAngles(Ljava/util/Optional;Ljava/util/Optional;Ljava/util/Optional;)V",
            at=@At("HEAD"), cancellable=true)
    private void easyelevator$skipWhenClientMotionControlled(Optional<Vec3d> pos, Optional<Float> yaw,
                                                            Optional<Float> pitch, CallbackInfo ci) {
        if ((Object)this instanceof AbstractCabinEntity cabin && cabin.getEntityWorld().isClient()
                && cabin.clientMotionControlled()) ci.cancel();
    }
}
