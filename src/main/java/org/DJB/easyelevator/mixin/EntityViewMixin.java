package org.DJB.easyelevator.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.EntityView;
import org.DJB.easyelevator.entity.CabinEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds only the hollow cabin shell; a solid entity bounding box would prevent entering the car.
 *
 * <p>把电梯轿厢的"空心外壳"接进世界碰撞查询：{@code EntityView#getEntityCollisions} 是方块/实体移动
 * 在服务端与客户端都会走到的碰撞汇总点，我们把外壳的若干长方体追加进它返回的 {@link VoxelShape} 列表。
 *
 * <p>为什么不直接依赖 {@link CabinEntity} 自身的实体碰撞箱：3x3x3 轿厢必须是空心的，玩家要从正面走进井道；
 * 而实心实体框会把整个 3x3x3 填满，导致无法进入。因此实体框只保留原版用途，真正的可碰撞几何由
 * {@link CabinEntity#collisionBoxes()} 给出的地板、顶板、侧壁与两扇轿厢门组成。
 *
 * <p>关键不变量/约束：
 * <ul>
 *   <li>轿厢实体自身的碰撞查询不做处理（{@code entity instanceof CabinEntity} 时直接返回），
 *       否则轿厢会被自己的外壳卡住而无法沿井道运行。</li>
 *   <li>本 mixin 只<b>追加</b>形状，不移除原版结果，其它实体的碰撞行为保持不变。</li>
 *   <li>外壳几何带旋转（随线路朝向），全部取自实体自身坐标，因此这里只做坐标搬运，不做几何推导。</li>
 *   <li>本类注册于 {@code easyelevator.mixins.json} 的必装 mixin 列表，注入失败即模组启动失败（required）。</li>
 * </ul>
 */
@Mixin(EntityView.class)
public interface EntityViewMixin {
    /**
     * 在 {@code EntityView#getEntityCollisions} 返回原版结果之后追加轿厢外壳的碰撞形状。
     *
     * <p>选 {@code At("RETURN")} 而非 {@code HEAD}：没有轿厢参与时可以直接使用原版返回值，
     * 不为普通实体增加任何额外开销。
     *
     * <p>副作用：改写本次碰撞查询的返回值（{@code cir.setReturnValue}）。不改实体位置、不改方块状态、不发包。
     *
     * @param entity 正在查询碰撞的实体；是轿厢时跳过，避免轿厢与自身外壳互卡
     * @param box 查询范围（通常为实体本刻的包围盒），单位：格（方块）
     * @param cir 原版返回值回调；入参为原版碰撞形状列表，出参为追加外壳后的列表
     */
    @Inject(method="getEntityCollisions", at=@At("RETURN"), cancellable=true)
    private void easyelevator$collisions(Entity entity, Box box, CallbackInfoReturnable<List<VoxelShape>> cir) {
        if (entity instanceof CabinEntity) return;
        EntityView view = (EntityView)this;
        // 查询盒外扩 .001 格：相邻外壳恰好边缘相接时，浮点误差可能让 intersects 判定为不相交而漏掉外壳。
        var cabins = view.getEntitiesByClass(CabinEntity.class, box.expand(.001), c -> !c.isRemoved());
        if (cabins.isEmpty()) return;
        // 复制一份再追加：原版返回的列表可能被缓存或由调用方共享，不能就地修改。
        List<VoxelShape> result = new ArrayList<>(cir.getReturnValue());
        // 每个外壳部件先与查询盒相交测试再转换，避免把整座轿厢的形状全部塞进结果；不在范围内的部件一律跳过。
        for (var cabin : cabins) for (Box part : cabin.collisionBoxes()) if (part.intersects(box)) result.add(VoxelShapes.cuboid(part));
        cir.setReturnValue(result);
    }
}
