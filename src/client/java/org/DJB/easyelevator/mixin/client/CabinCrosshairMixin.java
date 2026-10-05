package org.DJB.easyelevator.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 轿厢里的乘客：<b>点轿厢开面板</b>，同时<b>门开着时准星能从门洞穿出去</b>。
 *
 * <p>两条行为必须由同一处裁决，且必须同时成立；只做其中一条都会坏：
 *
 * <ol>
 *   <li><b>乘客点轿厢 → 一律命中本厢</b>（不分位置、不看手里拿什么）。<br>
 *       原版右键的判定是"准星命中的是什么"：只有 {@code crosshairTarget} 是 {@link EntityHitResult}
 *       时才走实体交互。轿厢是空心壳，乘客在厢内看侧壁/后壁/门框时射线常穿到后面的方块上，
 *       这次右键就成了"对世界用物品"——手里拿着方块时更是直接把方块放出去，实体交互根本轮不到。
 *       实机表现就是"手里有物品时唤不出面板、空手才行"。这里改写成命中本厢即可消除。</li>
 *   <li><b>门开着时，准星穿出轿厢</b>（见 {@link #doorsOpen}）。<br>
 *       门关着时门扇是真实阻挡，射线就该被挡住——这符合"点击射线不能穿透电梯轿厢"。
 *       门一开，门洞方向就该能穿过去；否则乘客朝门外点方块时，准星会先命中门扇/门框而变成实体，
 *       左键走 {@code attackEntity} 而不是 {@code attackBlock}，破坏方块的包根本发不出去
 *       （故障脱困时"门口有方块挡路却拆不掉"就是这么来的）。</li>
 * </ol>
 *
 * <p>做法上的关键：穿透分支是<b>另做一条只看方块/流体的射线</b>，而<b>不是</b>放弃第 1 条。
 * 因此两条能同时生效——门开时朝门外点，射线穿出去打外面的方块；门开时点舱壁，仍然开面板。
 *
 * <p>本类只改"准星的实体命中"，不改碰撞几何：玩家的身体照样被门扇与舱壁挡住；
 * 而且乘客的准星<b>永远</b>不会被改写到门外的方块上（穿透分支只在门开着、且原结果是"命中本厢"时才生效），
 * 所以"点轿厢开面板"在任何情况下都不会失效。
 *
 * <p><b>不要引入跨帧状态</b>：曾经用"客户端每帧刷新、限时若干刻的看面板标记"通知服务端，
 * 结果"看过一眼面板后转身点别处"被残留标记误判（实机反馈：手里拿着东西点哪都能唤出面板）。
 */
@Mixin(GameRenderer.class)
public abstract class CabinCrosshairMixin {
    /**
     * 准星求值返回后统一裁决：先按"门开着就让射线穿出轿厢"改写，否则一律命中本厢。
     *
     * @param camera 相机跟随的实体（参数类型是 {@code Entity}，不是 {@code Camera}——写错类型会让
     *               Mixin 在 APPLY 阶段抛 InvalidInjectionException 并使客户端启动崩溃）
     * @param blockInteractionRange 方块交互距离（格）：穿透时用它做方块射线长度
     * @param cir 原版返回值回调
     */
    @Inject(method = "findCrosshairTarget", at = @At("RETURN"), cancellable = true)
    private void easyelevator$resolveCabinCrosshair(net.minecraft.entity.Entity camera, double blockInteractionRange,
                                                    double entityInteractionRange, float tickDelta,
                                                    CallbackInfoReturnable<HitResult> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;
        if (player.isSpectator()) return;
        // 找到"自己正坐在里面"的那辆轿厢（正常至多一辆）；站在外面的玩家完全不受影响，
        // 因此"空手右键开门面板、潜行右键回收"照旧由原版准星决定。
        Box around = player.getBoundingBox().expand(4);
        AbstractCabinEntity own = null;
        for (AbstractCabinEntity cabin : client.world.getEntitiesByClass(AbstractCabinEntity.class, around, c -> true))
            if (cabin.containsPassenger(player)) { own = cabin; break; }
        if (own == null) return;
        // 穿透分支：门完全打开，且原结果就是"命中所处的这辆轿厢"。
        // 这时再打一条只看方块/流体的射线，并把它与"射线打到轿厢外壳"的距离作比较：
        //   方块更近 → 射线确实是穿出门洞打到了外面的东西（例如门框柱、门楣、外面的方块）→ 让给方块；
        //   轿厢外壳更近 → 射线其实是被舱壁/门框接住的 → 保持命中本厢，右键开面板。
        //
        // 为什么不能改成"判断方块坐标是否在轿厢包围盒之外"（曾经这么写，实机踩坑）：
        // 楼层门的方块坐标就落在轿厢 3×3×3 的包围盒**里面**（轿厢盒跨 ±1.5 格、门在正面 1.3~1.5 那一带），
        // 于是那条判据恒为假、穿透永远取消，表现为"从轿厢里怎么都打不到电梯门"。
        // 用"距离"就没有这个问题：门框/门楣在轿厢外壳之外，天然更近或更远都算得清楚。
        if (doorsOpen(own) && cir.getReturnValue() instanceof EntityHitResult hit && hit.getEntity() == own) {
            Vec3d eye = camera.getEyePos();
            Vec3d look = player.getRotationVec(tickDelta);
            Vec3d end = eye.add(look.multiply(blockInteractionRange));
            HitResult through = client.world.raycast(new RaycastContext(eye, end,
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
            if (through instanceof BlockHitResult blockHit) {
                // 同一条射线打到**静态舱体**（地板/顶板/侧壁/背板，不含轿厢自己的门扇）的最近距离。
                // 用静态舱体而不是完整碰撞集合：乘客朝门外看时射线必然先命中轿厢门扇，
                // 把门扇算作遮挡物就会让"从轿厢里打楼层门"永远失败（楼层门在门扇之外）。
                double shellDist = Double.MAX_VALUE;
                for (Box part : own.collisionBoxesStatic()) {
                    var shellHit = part.raycast(eye, end);
                    if (shellHit.isPresent()) shellDist = Math.min(shellDist, eye.distanceTo(shellHit.get()));
                }
                if (eye.distanceTo(blockHit.getPos()) < shellDist) {
                    cir.setReturnValue(blockHit); // 方块在舱壁之前 → 射线确实穿出去了
                    return;
                }
            }
        }
        // 默认：乘客点轿厢的任何地方都算命中本厢 → 右键必定走实体交互 → 服务端为乘客无条件开面板。
        cir.setReturnValue(new EntityHitResult(own, own.getBoundingBox().getCenter()));
    }

    /**
     * 轿厢门是否处于"完全打开"（可以让人与准星自由穿过门洞）。
     *
     * <p>判据与客户端其它门相关判定同源：门进度到 1，且相位在开门侧（到站开门后的第一刻是
     * {@code OPENING}，门其实已经全开）。关着门或正在开关时都不算——那时门扇是真实阻挡，射线不该穿出去。
     *
     * @param cabin 玩家所处的轿厢
     * @return 门完全打开时为 true
     */
    private static boolean doorsOpen(AbstractCabinEntity cabin) {
        if (cabin.doorProgress(1f) < .999f) return false;
        var phase = cabin.phase();
        return phase == ElevatorController.Phase.OPEN || phase == ElevatorController.Phase.OPENING;
    }
}
