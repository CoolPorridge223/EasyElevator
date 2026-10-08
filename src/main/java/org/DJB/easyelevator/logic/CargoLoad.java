package org.DJB.easyelevator.logic;

import net.minecraft.util.math.Box;

/** 货量按实际物品件数计，容器内嵌物品不递归称重。 */
public final class CargoLoad {
    public static final int SLOTS = 27;
    public static final int MAX_ITEMS = SLOTS * 64;
    public static final int ITEMS_PER_PASSENGER = 128;
    public static final int ITEMS_PER_CRATE = 288;
    public static final int MAX_CRATES = 6;

    private CargoLoad() {}

    public static int passengers(int items) {
        return Math.max(1, ElevatorParameters.HIGH_PASSENGER_NUM_LIMIT
                - (Math.max(0, items) + ITEMS_PER_PASSENGER - 1) / ITEMS_PER_PASSENGER);
    }

    public static int crates(int items) {
        return Math.min(MAX_CRATES, (Math.max(0, items) + ITEMS_PER_CRATE - 1) / ITEMS_PER_CRATE);
    }

    /** 三列两层，先铺满底层再叠第二层；箱顶低于铭牌。渲染与碰撞共用。 */
    public static Box box(int index) {
        if (index < 0 || index >= MAX_CRATES) throw new IllegalArgumentException("Invalid cargo crate");
        double x = -1.08 + (index % 3) * .76;
        double y = .2 + (index / 3) * .4;
        return new Box(x, y, -1.14, x + .64, y + .4, -.50);
    }
}
