package org.DJB.easyelevator.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.logic.ElevatorParameters;

/**
 * 强力电梯轿厢（注册 ID {@code easyelevator:powerful_cabin}）：<b>大载客量</b>型号。
 *
 * <p>与普通轿厢的差别只有两项，其余全部继承父类：
 * <ul>
 *   <li><b>限载人数</b>：{@link ElevatorParameters#HIGH_PASSENGER_NUM_LIMIT}（20 人）。厢内玩家数超过它时，
 *       状态机把相位切到 {@link org.DJB.easyelevator.logic.ElevatorController.Phase#OVERLOAD}：
 *       门保持全开、不派发行程，轿内面板与门框上的显示都变成"超载"，直到有人走出厢门。
 *       普通 / 高速 / 观光三型的限载是 {@link ElevatorParameters#PASSENGER_NUM_LIMIT}（0 = 不限载），
 *       因此只有这一型会被判超载；判据见 {@link AbstractCabinEntity#overloaded()}。</li>
 *   <li><b>内饰外观</b>：{@link #heavyDuty()} 返回 true，渲染时在内饰表上换成
 *       {@code CabinRenderer.POWERFUL_PARTS}——在普通内饰之上再叠一组重载件（第二道不锈钢扶手、
 *       后壁两根立柱、第二块顶灯、后壁载重铭牌、门槛内侧的防滑钢踏板），铭牌上的红字就是这个限载人数。</li>
 * </ul>
 *
 * <p><b>速度、外壳、门与井道尺寸都与普通轿厢逐位相同</b>（巡航速度 = {@link ElevatorParameters#SPEED}，
 * 同一个 {@code drawStandardShell} 外壳与同一套滑门）：建模、井道预留、门口防夹、门联锁与站点位置
 * 都不需要为了这个型号改动——把普通轿厢换成强力轿厢，只是"这一趟能多站几个人"。
 *
 * <p>限载人数<b>不写进存档</b>：它由实体类型唯一决定（读档时按注册类型重建），
 * 因此不会出现"存档里写坏了载客量"的情况。
 */
public class PowerfulCabinEntity extends AbstractCabinEntity {

    /**
     * 构造强力轿厢。
     *
     * @param type 实体类型（由 {@link Easyelevator#POWERFUL_CABIN} 注册，ID 为 {@code easyelevator:powerful_cabin}）
     * @param world 所在世界
     */
    public PowerfulCabinEntity(EntityType<?> type, World world) {
        super(type, world, ElevatorParameters.SPEED, ElevatorParameters.HIGH_PASSENGER_NUM_LIMIT);
    }

    /** @return 强力轿厢物品 {@link Easyelevator#POWERFUL_CABIN_ITEM}：回收后仍得到强力轿厢 */
    @Override protected Item cabinItem() { return Easyelevator.POWERFUL_CABIN_ITEM; }

    /** @return 恒为 true：渲染时叠加"重载"内饰（双扶手、双顶灯、载重铭牌、防滑钢踏板），外壳与普通型号相同 */
    @Override public boolean heavyDuty() { return true; }
}
