package org.DJB.easyelevator.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import org.DJB.easyelevator.client.CabinMotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** 相机混入：让乘坐电梯的本地玩家第一人称镜头随轿厢平滑升降。
 * 在整体架构中的位置：客户端表现层的最末端一步。轿厢的视觉 Y 由 CabinMotion 的时间线在已收到样本间插值得到，
 * 但玩家自身位置来自原版实体同步（相对位置包，存在定点量化），两者不重合会造成镜头抖动；
 * 这里在相机 setPos 之前把"视觉轿厢底 + 乘客偏移"与"玩家当前插值 Y"的差值补上去，使镜头与轿厢渲染严格对齐。
 * 关键约束：只改相机，不改玩家实体位置与碰撞；CabinMotion.cameraOffset 在不满足乘客条件时返回 0，效果即原版行为。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private Entity focusedEntity; // 相机跟随的实体，用于确认"是本机玩家且仍在轿厢内"（由 CabinMotion 再校验一次）
    @Shadow private float lastTickDelta; // 当前帧渲染插值系数（0..1，刻间比例），原版相机用它做插值，这里复用同一系数保证一致
    // Adjust the feet anchor before vanilla third-person wall clipping and eye-height/bobbing.
    // 在 setPos 的 Y 参数（脚下锚点，单位：格）上加补偿，时机选在 ordinal=0 的那次调用：
    // 之后的眼高、第一人称视角摆动、第三人称贴墙裁剪都会基于修正后的锚点计算，
    // 因此无需再挂钩这些后续步骤，也不会出现镜头先到墙里再被拉回的情况。
    @ModifyArg(method="update", at=@At(value="INVOKE", target="Lnet/minecraft/client/render/Camera;setPos(DDD)V", ordinal=0), index=1)
    private double easyelevator$smoothRiderHeight(double y) {
        // y 为相机脚部锚点原值（格）；cameraOffset 返回需要补偿的高度差（格），非本地乘客时为 0（恒等变换）。
        return y + CabinMotion.cameraOffset(focusedEntity, lastTickDelta);
    }
}
