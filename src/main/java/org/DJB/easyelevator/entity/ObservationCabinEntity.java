package org.DJB.easyelevator.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.logic.ElevatorParameters;

/**
 * 观光电梯轿厢（注册 ID {@code easyelevator:observation_cabin}）。
 *
 * <p>外观：除了轿厢<b>四个支撑边</b>（四根角柱）以及地板、顶板与操作面板，
 * 左右侧墙与后墙都换成类似玻璃的半透明材质，两扇门也是"周围钢框、中间玻璃"（见 `client/FramedGlassDoor`），因此厢内可以看到外面的景色，
 * 外面也能看见厢内的乘客。四个角柱保持不透明，玻璃只贴在两柱之间的墙面上，
 * 这样既有"整面玻璃"的通透感，又保留了轿厢的结构轮廓。
 *
 * <p>性能与普通轿厢<b>完全相同</b>：速度、门时序、碰撞、井道预留、乘客判定与门联锁逐条一致。
 * 玻璃只改客户端绘制（{@link #glassWalls()} 是纯渲染提示），碰撞外壳仍然按
 * {@link AbstractCabinEntity#collisionBoxes()} 生成——也就是玻璃墙照样是实心墙，
 * 不会因为"看得见"就掉出轿厢，井道与站点也无需任何改动。
 */
public class ObservationCabinEntity extends AbstractCabinEntity {

    /**
     * 构造观光轿厢。
     *
     * @param type 实体类型（由 {@link Easyelevator#OBSERVATION_CABIN} 注册）
     * @param world 所在世界
     */
    public ObservationCabinEntity(EntityType<?> type, World world) { super(type, world, ElevatorParameters.SPEED, ElevatorParameters.PASSENGER_NUM_LIMIT); }

    /** @return 观光轿厢物品 {@link Easyelevator#OBSERVATION_CABIN_ITEM}：回收后仍得到观光轿厢 */
    @Override protected Item cabinItem() { return Easyelevator.OBSERVATION_CABIN_ITEM; }

    /** @return 恒为 true：渲染时把墙面与门扇画成半透明玻璃（四个角柱、地板、顶板仍不透明） */
    @Override public boolean glassWalls() { return true; }
}
