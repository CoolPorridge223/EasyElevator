package org.DJB.easyelevator.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.logic.ElevatorParameters;

/**
 * 高速电梯轿厢（注册 ID {@code easyelevator:high_speed_cabin}）。
 *
 * <p>与普通轿厢的唯一差别是速度：{@link ElevatorParameters#HIGH_SPEED} = {@link ElevatorParameters#SPEED} × 2.5
 * = 0.50 格/刻 = <b>10 格/秒</b>（普通为 4 格/秒）。
 *
 * <p><b>外观与普通轿厢完全相同</b>：几何、碰撞、门序、白模渲染一个像素都不变；
 * 因为外观不体现速度，玩家只能从到站时间（和选站面板里的高度读数）察觉差别。
 *
 * <p>不变的约束（速度只影响"每刻走多远"，不影响别的任何语义）：
 * <ul>
 *   <li>门时序：开关门各 {@link ElevatorParameters#DOOR_TICKS} 刻、停留 {@link ElevatorParameters#DWELL_TICKS} 刻，
 *       与普通轿厢一致；提高速度不会缩短这些固定时间。</li>
 *   <li>到站：最后一步仍会把 Y 精确吸附到站点高度，误差容限仍是 {@link ElevatorParameters#POSITION_EPSILON}；
 *       单刻位移 0.5 格 &lt; 1 格，因此不会跨过整格站点。</li>
 *   <li>井道预留、乘客包围盒、门口防夹、障碍扫掠与线路唯一性判定都不变；扫掠体积按本刻实际位移
 *       （最大 0.5 格）逐刻检查，所以更快的车不会"跳过"一格障碍。</li>
 * </ul>
 */
public class HighSpeedCabinEntity extends AbstractCabinEntity {

    /**
     * 构造高速轿厢。
     *
     * @param type 实体类型（由 {@link Easyelevator#HIGH_SPEED_CABIN} 注册）
     * @param world 所在世界
     */
    public HighSpeedCabinEntity(EntityType<?> type, World world) { super(type, world, ElevatorParameters.HIGH_SPEED); }

    /** @return 高速轿厢物品 {@link Easyelevator#HIGH_SPEED_CABIN_ITEM}：回收后仍得到高速轿厢 */
    @Override protected Item cabinItem() { return Easyelevator.HIGH_SPEED_CABIN_ITEM; }
}
