package org.DJB.easyelevator.logic;

import net.minecraft.util.math.Box;

/**
 * 货舱的<b>纯算术模型</b>：件数与"占几个人的载重"、"显示几个货箱"的换算，以及货箱的几何。
 *
 * <p>职责：把"货舱里装了多少件"这一个事实换算成另外三个表现——限载人数、货箱数量、货箱位置。
 * 服务端（{@code PowerfulCabinEntity} 的限载与碰撞）、客户端（{@code CabinRenderer.drawCargo} 的
 * 货箱外观）与界面（{@code CargoScreenHandler.passengerLimit()}）读的都是这里的同一份公式与坐标，
 * 因此"面板上写的限载人数""铭牌上的数字""实际判超载用的上限""看得见的货箱数"不可能互相分叉。
 *
 * <p><b>计量口径：按实际件数，不按槽位、不递归。</b>一组 64 个圆石算 64 件，一把剑算 1 件；
 * 潜影盒之类的容器只算容器本身那 1 件，盒内物品<b>不</b>另计。理由：递归称重会让"装箱"变成
 * 免费的重量规避（把 27 格塞进一个潜影盒），而本模组要表达的是"这部电梯装了多少货"。
 *
 * <p>只依赖 {@link Box} 这一个 Minecraft 类型（用于货箱几何），不引用世界、实体或注册表，
 * 因此与 {@code LandingDoorGeometry} 一样属于"需要 MC 类路径、但不需要世界"的可单测类。
 */
public final class CargoLoad {
    /**
     * 货舱槽位数：27 格 = 原版大箱子的大小。
     *
     * <p>刻意与原版容器一致：{@code CargoScreenHandler} 因此能直接复用原版的槽位事务
     * （拖放分配、Shift 快捷搬运、数字键交换、光标物品），不需要自写一套搬运逻辑；
     * 玩家也不必重新学一套"货舱怎么操作"。
     */
    public static final int SLOTS = 27;
    /**
     * 货舱能装的<b>最大件数</b>：27 × 64 = 1728 件（每格都按 64 上限算）。
     *
     * <p>它同时是界面进度条的满刻度（{@code CargoScreen}）和"再也不需要新的货箱档位"的上界：
     * 货量到 1728 就是这个货舱的物理上限，不会再有"装不进去但槽位空着"的状态——
     * 每格仍遵循物品自身的堆叠上限（剑、工具、药水等 1 件/格），因此实际装满的件数可能低于 1728。
     */
    public static final int MAX_ITEMS = SLOTS * 64;
    /**
     * 每多少件货物占掉一个人的载重：128 件/人（不足一档也按一档算，见 {@link #passengers(int)}）。
     *
     * <p>取值口径：满载 1728 件正好吃掉 13.5 个人的位置，配合 20 人的空载上限与"至少留 1 人"的保底，
     * 得到"满载仍能载 6 人"这个可玩结论；同时 128 件 = 两格满堆叠，因此"搬两格货少载一个人"
     * 是玩家嘴上说得清、心里算得出的规则。
     */
    public static final int ITEMS_PER_PASSENGER = 128;
    /**
     * 每多少件货物显示一个货箱：288 件/箱（同样不足一档也显示，见 {@link #crates(int)}）。
     *
     * <p>288 = 4.5 格满堆叠，是"六个箱子刚好铺满 1728 件"的解：1728 / 288 = {@link #MAX_CRATES}。
     * 取值比 {@link #ITEMS_PER_PASSENGER} 大是刻意的——<b>箱子长得比载重慢</b>：
     * 只放 128 件时已经少载一个人，但后部只出现一个箱子，玩家不会觉得"装一点就堆满舱"。
     */
    public static final int ITEMS_PER_CRATE = 288;
    /**
     * 后部最多摆几个货箱：6 个（三列 × 两层，见 {@link #box(int)}）。
     *
     * <p>为什么是 6 而不是按 1728/288 = 6 之外再多给几个：3×3 的净空里，后部这块区域要同时满足
     * "不占门口（Z 不越过 {@code CABIN_DOOR_BACK_Z}）""不遮住后壁载重铭牌（高度压在 1.1 格以下）"
     * "箱子之间与外壳之间互不共面"，6 个是能摆下的上限；再多必然要动门口或铭牌。
     */
    public static final int MAX_CRATES = 6;

    /** 工具类，禁止实例化：全是静态换算与几何。 */
    private CargoLoad() {}

    /**
     * 当前货量对应的限载人数。
     *
     * <p>公式：{@code max(1, HIGH_PASSENGER_NUM_LIMIT − ceil(件数 / ITEMS_PER_PASSENGER))}。
     * 空舱 = {@link ElevatorParameters#HIGH_PASSENGER_NUM_LIMIT}（20 人），每开始一档 128 件少载 1 人。
     *
     * <p>为什么要 {@code ceil}（不足一档也计一档）：{@link #MAX_ITEMS} 是 1728 而 1728 / 128 = 13.5，
     * 用整数除法会白送半个人的重量；向上取整让"第 129 件"立刻生效，规则才好解释。
     *
     * <p>为什么保底 1 人而<b>不能</b>是 0：{@code AbstractCabinEntity.overloaded()} 把"限载 ≤ 0"
     * 解释为<b>不限载</b>（普通/高速/观光就是靠这个表示"不限"）。如果货装到某一步让限载算成 0，
     * 重载轿厢就会突然变成"永远不超载"，货越多越能塞人——正是最不能出现的方向。
     * 因此这里宁可留 1 个人的位置，也不返回 0 或负数（同时保证"满载也有 6 人"这条可玩结论）。
     *
     * @param items 货物件数（负数按 0 处理；非本次调用方负责校验上限）
     * @return 1..{@link ElevatorParameters#HIGH_PASSENGER_NUM_LIMIT} 之间的限载人数
     */
    public static int passengers(int items) {
        return Math.max(1, ElevatorParameters.HIGH_PASSENGER_NUM_LIMIT
                - (Math.max(0, items) + ITEMS_PER_PASSENGER - 1) / ITEMS_PER_PASSENGER);
    }

    /**
     * 当前货量该显示几个货箱。
     *
     * <p>公式：{@code min(MAX_CRATES, ceil(件数 / ITEMS_PER_CRATE))}——空舱 0 个、
     * 1..288 件 1 个、289..576 件 2 个……1728 件满 6 个。
     *
     * <p>返回 0 表示"没有箱子"：{@link #box(int)} 就会被调用 0 次，
     * 渲染与碰撞自然回到"没有货舱"的样子（空舱时后部完全恢复原内饰）。
     *
     * @param items 货物件数（负数按 0 处理）
     * @return 0..{@link #MAX_CRATES}
     */
    public static int crates(int items) {
        return Math.min(MAX_CRATES, (Math.max(0, items) + ITEMS_PER_CRATE - 1) / ITEMS_PER_CRATE);
    }

    /**
     * 第 {@code index} 个货箱在<b>轿厢局部坐标</b>下的长方体（单位：格）：三列两层，先铺满底层再叠第二层。
     *
     * <p>布局：列间距 0.76 格、箱宽 0.64 格，左右各留 0.06 格缝；层高 0.4 格，第一层贴地板面 0.2、
     * 第二层 0.6；Z 取 −1.14..−0.50，即后壁内侧（−1.3）到距门口还有 0.5 格的地方。
     * 三个约束同时成立，缺一个就会穿帮：
     * <ul>
     *   <li><b>不占门口</b>：Z 上界 −0.50 远在门背 {@link ElevatorParameters#CABIN_DOOR_BACK_Z}（1.1）之前；</li>
     *   <li><b>不遮铭牌</b>：最高一层箱顶 1.0 格 < 后壁铭牌下沿 1.10 格，红字始终看得见；</li>
     *   <li><b>不与外壳共面</b>：六个坐标都躲开外壳内表面（±1.3 / 0.2 / 2.8），因此不会出现共面抢深度。</li>
     * </ul>
     *
     * <p><b>渲染与碰撞共用本方法</b>：{@code CabinRenderer.drawCargo} 按它画木箱，
     * {@code PowerfulCabinEntity.cargoBox(index)} 把它转到世界坐标后既进碰撞集合、
     * 又用于"新箱子不许出现在人身上"的检查，因此"看得见的箱子"永远是"挡得住人的箱子"。
     *
     * @param index 货箱序号（0 起，按 {@link #crates(int)} 的数量逐个数）
     * @return 该货箱在轿厢局部坐标下的长方体
     * @throws IllegalArgumentException 序号越界（&lt;0 或 ≥{@link #MAX_CRATES}）——调用方应当先问
     *         {@link #crates(int)}，越界说明渲染与货量已经不同步，宁可当场报错也不要画出错位的箱子
     */
    public static Box box(int index) {
        if (index < 0 || index >= MAX_CRATES) throw new IllegalArgumentException("Invalid cargo crate");
        double x = -1.08 + (index % 3) * .76;
        double y = .2 + (index / 3) * .4;
        return new Box(x, y, -1.14, x + .64, y + .4, -.50);
    }
}
