package org.DJB.easyelevator.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.logic.ElevatorParameters;

/**
 * 普通电梯轿厢（默认型号，注册 ID {@code easyelevator:cabin}）。
 *
 * <p>在整体架构中的位置：{@link AbstractCabinEntity} 三个子类之一。本类只声明"我是哪一种轿厢"：
 * 速度为 {@link ElevatorParameters#SPEED}（0.20 格/刻 = 4 格/秒），回收时掉落普通轿厢物品，
 * 不启用玻璃外观。运动学、乘客处理、门联锁、存档与同步全部继承自父类，这里没有第二份实现。
 *
 * <p>为什么保持类名不变：{@code easyelevator:cabin} 是已发布的注册 ID，旧存档里的轿厢实体、
 * 旧物品堆与旧配方都指向它；把普通型号留在原来的名字上，可以让升级后的世界继续加载，
 * 同时新增的高速/观光型号各自使用新的注册 ID。
 */
public class CabinEntity extends AbstractCabinEntity {

    /**
     * 构造普通轿厢。
     *
     * @param type 实体类型（由 {@link Easyelevator#CABIN} 注册，ID 为 {@code easyelevator:cabin}）
     * @param world 所在世界
     */
    public CabinEntity(EntityType<?> type, World world) { super(type, world, ElevatorParameters.SPEED); }

    /** @return 普通轿厢物品 {@link Easyelevator#CABIN_ITEM}：回收后仍得到普通轿厢 */
    @Override protected Item cabinItem() { return Easyelevator.CABIN_ITEM; }
}