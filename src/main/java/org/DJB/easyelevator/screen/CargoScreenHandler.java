package org.DJB.easyelevator.screen;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.PowerfulCabinEntity;
import org.DJB.easyelevator.logic.CargoLoad;

/**
 * 重载轿厢货舱的容器菜单（27 格货舱 + 玩家 36 格背包），注册在 {@link Easyelevator#CARGO_SCREEN}。
 *
 * <p>为什么用<b>原版容器事务</b>而不是自写面板逻辑：拖放分配、Shift 快捷搬运、数字键交换、
 * 光标物品、多人同时装卸、断线重连后的槽位同步、光标上的物品被服务端回收——这些原版
 * {@link ScreenHandler} 全都已经处理过一遍（包括各种复制漏洞的防护）。自写一套等于把这些
 * 边界重做一遍，且必须逐条重新验证。因此这里只做两件原版没有的事：
 * <ol>
 *   <li><b>动态单格上限</b>：每格的插入上限不是 64，而是"装到下一档货量之前最多还能放几件"
 *       （见 {@link #slotLimit}），用来保证新货箱不会出现在人或动物身上；</li>
 *   <li><b>装不进去时说明原因</b>：{@link #onSlotClick} 在"点了但件数没变、且后部被占用"时
 *       给玩家一条 actionbar 提示，而不是让人对着无反应的格子猜。</li>
 * </ol>
 *
 * <p><b>两个构造器分别服务两端</b>，这是理解本类所有 {@code cabin == null} 判断的关键：
 * <ul>
 *   <li>{@link #CargoScreenHandler(int, PlayerInventory)}（2 参）是 {@link Easyelevator#CARGO_SCREEN}
 *       的类型工厂，<b>只在客户端</b>被原版调用：客户端拿到服务端发来的 {@code syncId} 后新建一个
 *       空壳实例，槽位内容与"货物件数"属性随后由原版容器同步包填写。它<b>没有轿厢实体</b>
 *       （{@code cabin == null}），因此一切需要问轿厢的地方都必须退化：可开性不判定（
 *       {@link #canUse} 返回 true，真正的把关在服务端），单格上限固定 64（客户端不该替服务端
 *       猜货箱占用）。</li>
 *   <li>{@link #CargoScreenHandler(int, PlayerInventory, PowerfulCabinEntity)}（3 参）<b>只在服务端</b>
 *       由 {@code PowerfulCabinEntity.interact} 创建，直接持有轿厢：{@link #canUse} 因此每刻都能
 *       重新问"这个人还算不算厢内乘客"，单格上限也随实际货量实时变化。</li>
 * </ul>
 * 两者最终都汇到私有构造器里接线，槽位排布与属性注册只有一份。
 */
public class CargoScreenHandler extends ScreenHandler {
    /** 服务端实例持有的轿厢；客户端空壳为 null（见类注释）。 */
    private final PowerfulCabinEntity cabin;
    /** 货舱库存：服务端是轿厢那个持久化 {@link SimpleInventory}，客户端是等同步的空壳。 */
    private final Inventory cargo;
    /** 同步给客户端的"货物件数"（槽位 0）：面板上的件数、进度条与限载人数都由它算出来。 */
    private final PropertyDelegate properties;

    /**
     * 客户端空壳构造器：由 {@link net.minecraft.screen.ScreenHandlerType} 工厂调用。
     *
     * <p>槽位内容与货物件数由原版容器同步包填入；这里造的 {@link SimpleInventory} 与
     * {@link ArrayPropertyDelegate} 都只是"等着被填"的容器，因此不需要（也拿不到）轿厢。
     *
     * @param syncId 服务端分配的本会话编号，两端必须一致
     * @param playerInventory 查看者的背包
     */
    public CargoScreenHandler(int syncId, PlayerInventory playerInventory) {
        this(syncId, playerInventory, null, new SimpleInventory(CargoLoad.SLOTS), new ArrayPropertyDelegate(1));
    }

    /**
     * 服务端构造器：由 {@code PowerfulCabinEntity.interact} 在玩家潜行右键舱壁时创建。
     *
     * <p>三个入参都直接指向活着的轿厢：库存就是它那两个持久化 {@link SimpleInventory}，
     * "货物件数"属性则每次都现问 {@link PowerfulCabinEntity#cargoItems()}——因此装卸过程中
     * 面板上的数字与实体真正用于判超载的数字永远是同一个来源，不存在两份计数。
     *
     * @param syncId 服务端分配的会话编号
     * @param playerInventory 查看者的背包
     * @param cabin 提供货舱与限载的重载轿厢
     */
    public CargoScreenHandler(int syncId, PlayerInventory playerInventory, PowerfulCabinEntity cabin) {
        this(syncId, playerInventory, cabin, cabin.cargo(), new PropertyDelegate() {
            public int get(int index) { return cabin.cargoItems(); }
            /** 服务端不需要被写回：属性同步是单向的（服务端 → 客户端）。 */
            public void set(int index, int value) {}
            public int size() { return 1; }
        });
    }

    /**
     * 真正的接线：注册 27 个货舱槽 + 36 个背包槽，并把"货物件数"接上属性同步。
     *
     * <p>槽位坐标是原版大箱子的布局（货舱 3×9 在 y=54，背包 3×9 在 y=128，快捷栏在 y=186），
     * 与 {@code CargoScreen} 的背景尺寸 176×210 配套；改这里必须同步改那个类的前景文字坐标。
     *
     * @param syncId 会话编号
     * @param playerInventory 查看者的背包
     * @param cabin 服务端实例的轿厢；客户端空壳为 null
     * @param cargo 货舱库存
     * @param properties 货物件数属性（大小 1）
     */
    private CargoScreenHandler(int syncId, PlayerInventory playerInventory, PowerfulCabinEntity cabin,
                               Inventory cargo, PropertyDelegate properties) {
        super(Easyelevator.CARGO_SCREEN, syncId);
        this.cabin = cabin;
        this.cargo = cargo;
        this.properties = properties;
        addProperties(properties);
        for (int row = 0; row < 3; row++) for (int column = 0; column < 9; column++) {
            final int index = column + row * 9;
            addSlot(new Slot(cargo, index, 8 + column * 18, 54 + row * 18) {
                /** 上限为 0 的格子连"放不进去"都不该被点亮，因此直接按 0 上限拒绝。 */
                @Override public boolean canInsert(ItemStack stack) {
                    return getMaxItemCount(stack) > 0;
                }
                /** 本格当前允许的堆叠上限：服务端随货量与货箱占用实时变化，客户端固定 64（见类注释）。 */
                @Override public int getMaxItemCount() {
                    return cabin == null ? 64 : cabin.cargoSlotLimit(index);
                }
                /** 再与物品自身的堆叠上限取较小值：剑仍然是 1 格 1 件。 */
                @Override public int getMaxItemCount(ItemStack stack) {
                    return Math.min(stack.getMaxCount(), getMaxItemCount());
                }
            });
        }
        for (int row = 0; row < 3; row++) for (int column = 0; column < 9; column++)
            addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, 128 + row * 18));
        for (int column = 0; column < 9; column++)
            addSlot(new Slot(playerInventory, column, 8 + column * 18, 186));
    }

    /** @return 服务端实例绑定的轿厢；客户端空壳返回 null（调用方必须容忍，见类注释） */
    public PowerfulCabinEntity cabin() { return cabin; }

    /** @return 当前货物件数（同步自服务端；面板与限载换算都用它） */
    public int cargoItems() { return properties.get(0); }

    /** @return 当前限载人数：直接由件数换算（{@link CargoLoad#passengers(int)}），与实体判超载同源 */
    public int passengerLimit() { return CargoLoad.passengers(cargoItems()); }

    /**
     * 这个玩家此刻还能不能操作本菜单：服务端用实体的乘客判定重新问一次，客户端空壳一律放行。
     *
     * <p>为什么客户端也返回 true：真正的把关必须在服务端（{@code onSlotClick} 会再判一次），
     * 客户端提前说"不行"只会让界面莫名关闭；而客户端的空壳根本问不到轿厢，
     * 因此它的职责就是"如实反映服务端的结论"——服务端关闭菜单时原版会把客户端的界面一起关掉。
     *
     * @param player 查看者
     * @return 允许继续操作时为 true
     */
    @Override public boolean canUse(PlayerEntity player) { return cabin == null || cabin.canUseCargo(player); }

    /**
     * 槽位点击的统一入口（原版所有操作——点、Shift 点、数字键、拖放分配——最终都走这里）。
     *
     * <p>额外做两件事：
     * <ol>
     *   <li>{@code cargo.markDirty()}：让轿厢重算并同步"货物件数"，因此面板数字与限载在同一刻更新；</li>
     *   <li>若本次点击后件数<b>没变</b>、且后部货箱空间正被占用，就发一条 actionbar 提示
     *       （{@code message.easyelevator.cargo_space}）。这是"装不进去"唯一的可见原因：
     *       单格上限被 {@code cargoSlotLimit} 压到 0 时，原版只会安静地拒绝，玩家无从知道要挪开谁。</li>
     * </ol>
     * 提示可能出现在"点了别的格子"这种无关场景（只要件数没变且后部被占），本类刻意不细分——
     * 界面上能改的只有货舱与背包，玩家照着提示挪开厢内生物即可。
     *
     * @param slotIndex 槽位序号（原版约定：负数表示界面外/光标操作）
     * @param button 鼠标键
     * @param action 原版动作类型（拾取、Shift 搬运、数字键交换、拖放分配……）
     * @param player 操作者
     */
    @Override public void onSlotClick(int slotIndex, int button, SlotActionType action, PlayerEntity player) {
        if (!canUse(player)) return;
        int before = cabin == null ? 0 : cabin.cargoItems();
        super.onSlotClick(slotIndex, button, action, player);
        cargo.markDirty();
        if (cabin != null && cabin.cargoItems() == before && cabin.cargoSpaceBlocked())
            player.sendMessage(Text.translatable("message.easyelevator.cargo_space"), true);
    }

    /**
     * Shift 快捷搬运。两个方向不对称，是刻意的：
     *
     * <ul>
     *   <li><b>货舱 → 背包</b>：一次 {@code insertItem} 交给原版在整段背包范围里找位置，
     *       放不下就原样返回（不丢货）；</li>
     *   <li><b>背包 → 货舱</b>：<b>逐格</b>搬运，而且每搬完一格就 {@code cargo.markDirty()}。
     *       原因见 {@code PowerfulCabinEntity.cargoSlotLimit}：每格的插入上限取决于<b>当前</b>货量
     *       （装到下一档就会出现新货箱），如果先算好"这批货能放进去"再逐格塞，就会出现
     *       "重量已经涨到下一档、箱子却还没被计入占用"的窗口，于是箱子可能长在人或动物身上。
     *       逐格刷新重量就是把这个窗口压成一格。</li>
     * </ul>
     *
     * @param player 操作者
     * @param index 被 Shift 点击的槽位序号
     * @return 原版约定：被移动的那一堆（未移动时返回 {@link ItemStack#EMPTY}）
     */
    @Override public ItemStack quickMove(PlayerEntity player, int index) {
        if (!canUse(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasStack()) return ItemStack.EMPTY;
        ItemStack stack = slot.getStack();
        ItemStack original = stack.copy();
        if (index < CargoLoad.SLOTS) {
            if (!insertItem(stack, CargoLoad.SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // 先让货量刷新，再评估下一格的货箱占用（见方法注释）。
            boolean moved = false;
            for (int i = 0; i < CargoLoad.SLOTS && !stack.isEmpty(); i++) {
                moved |= insertItem(stack, i, i + 1, false);
                cargo.markDirty();
            }
            if (!moved) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setStack(ItemStack.EMPTY);
        else slot.markDirty();
        cargo.markDirty();
        return original;
    }
}
