package org.DJB.easyelevator.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.logic.CargoLoad;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.screen.CargoScreenHandler;

import java.util.List;

/**
 * 重载电梯轿厢（注册 ID {@code easyelevator:powerful_cabin}）：<b>带货舱的大载客量型号</b>。
 *
 * <p>本类是四种轿厢里唯一有自己状态的子类，因此它的职责比其余三个子类重得多。除了继承来的
 * 运动、门联锁、乘客名册与存档，它额外提供三件事：
 *
 * <h2>1. 持久化货舱（27 格）</h2>
 * 货物存在实体 NBT 的 {@code Cargo.Items} 里（原版 {@link Inventories} 格式，保留槽位、数量与
 * 物品组件），区块卸载、停服、跨维度都不会掉货；只有"回收"与 {@code /kill} 掉出来一次
 * （见 {@link #remove(RemovalReason)}）。旧存档没有 {@code Cargo} 键时按空舱读入。
 *
 * <h2>2. 货量 → 载客上限（动态限载）</h2>
 * {@link #passengerNumLimit()} 覆写了父类的只读值：现问 {@link CargoLoad#passengers(int)}，
 * 空舱 20 人、每开始一档 128 件少载 1 人、满载 1728 件仍可载 6 人。
 * 父类判超载（{@code overloaded()}）与渲染后壁铭牌读的都是这个方法，因此
 * "铭牌上的数字""面板上的数字""真正在判的数字"三者永远同一个来源。
 * 货量本身由 {@link #CARGO_ITEMS} 同步给客户端，客户端因此不需要（也拿不到）货舱内容就能算限载。
 *
 * <h2>3. 货箱：外观、碰撞与"不许长在人身上"</h2>
 * 后部按 {@link CargoLoad#box(int)} 摆最多 6 个木箱，渲染（{@code CabinRenderer.drawCargo}）与
 * 碰撞（{@link #collisionBoxesStatic()}）用的是同一份坐标，因此"看得见的箱子"就是"挡得住人的箱子"，
 * 站在箱顶也算有支撑（见 {@link #supportsPassenger(Entity)}）。
 * 新箱子出现的位置若已经站着人或动物，就<b>限制继续装货</b>而不是把箱子插进实体身体里
 * （见 {@link #cargoSlotLimit(int)} / {@link #cargoSpaceLimit()}）。
 *
 * <p>速度：本型号用 {@link ElevatorParameters#LOW_SPEED}（普通型号的 2/3 ≈ 2.67 格/秒）——
 * "重载"的代价是慢，而不是外壳更大。井道、门、碰撞盒尺寸仍与普通型号逐位相同，
 * 因此换型号不需要改任何建筑。
 */
public class PowerfulCabinEntity extends AbstractCabinEntity {
    /**
     * 同步给客户端的"货舱件数"。
     *
     * <p>为什么同步件数而不是同步 27 个槽位：客户端的货舱内容由原版容器菜单同步（打开货舱时才有），
     * 但<b>不打开面板</b>的观察者也要能看到货箱、看到铭牌上的限载人数，这两个表现都只依赖件数，
     * 因此用一个 4 字节整数跟着实体追踪一起走就够了（几百件货物不必逐格广播）。
     */
    private static final TrackedData<Integer> CARGO_ITEMS = DataTracker.registerData(
            PowerfulCabinEntity.class, TrackedDataHandlerRegistry.INTEGER);
    /** 货舱库存（27 格）。服务端是权威副本；变更时通过监听器重算并同步件数。 */
    private final SimpleInventory cargo = new SimpleInventory(CargoLoad.SLOTS);

    /**
     * 构造重载轿厢。
     *
     * @param type 实体类型（由 {@link Easyelevator#POWERFUL_CABIN} 注册）
     * @param world 所在世界
     * 副作用：按 {@link ElevatorParameters#LOW_SPEED}（普通型号的 2/3）构造状态机，
     * 并把 {@link ElevatorParameters#HIGH_PASSENGER_NUM_LIMIT} 作为<b>空舱基准上限</b>交给父类；
     * 真正的当前上限由 {@link #passengerNumLimit()} 按货量实时算出来。
     */
    public PowerfulCabinEntity(EntityType<?> type, World world) {
        super(type, world, ElevatorParameters.LOW_SPEED, ElevatorParameters.HIGH_PASSENGER_NUM_LIMIT);
        cargo.addListener(inventory -> syncCargo());
    }

    /**
     * 注册同步字段：先让父类写它那五个（PHASE/DOOR/FACING/TARGET_Y/FLOOR），再加本类的件数。
     *
     * @param builder 原版实体构造流程传入的 DataTracker 构建器
     */
    @Override protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(CARGO_ITEMS, 0);
    }

    /** @return 货舱库存（27 格）：服务端为权威副本，客户端只有打开货舱后由菜单同步的那一份 */
    public SimpleInventory cargo() { return cargo; }

    /** @return 当前货舱件数（服务端为实时值，客户端读同步字段；负数不会出现） */
    public int cargoItems() { return dataTracker.get(CARGO_ITEMS); }

    /** @return 当前应显示的货箱数量（0..{@link CargoLoad#MAX_CRATES}）：渲染与碰撞都按它取 {@link #cargoBox(int)} */
    public int cargoCrates() { return CargoLoad.crates(cargoItems()); }

    /**
     * 重算并同步件数（库存监听器的落点）。
     *
     * <p>只在服务端写 {@link #CARGO_ITEMS}：客户端也会触发监听器（打开货舱后槽位由菜单填入），
     * 若两边都写就会出现"客户端用自己的局部计数覆盖服务端同步值"的抖动。
     * 件数只数<b>槽位里的数量</b>，不递归容器内容（见 {@link CargoLoad} 的计量口径）。
     *
     * 副作用：写同步字段（服务端）；客户端调用时什么都不做。
     */
    private void syncCargo() {
        if (!getWorld().isClient) {
            int count = 0;
            for (int i = 0; i < cargo.size(); i++) count += cargo.getStack(i).getCount();
            dataTracker.set(CARGO_ITEMS, count);
        }
    }

    /**
     * 当前限载人数：<b>由货量实时算出</b>，而不是构造时注入的那个固定值。
     *
     * <p>覆写点从 {@code final} 放开就是为了这一处；父类的超载判定与渲染铭牌都调本方法，
     * 因此"卸完货立刻恢复 20 人""装到 1728 件只剩 6 人"不需要任何额外的同步或刷新逻辑。
     *
     * @return {@link CargoLoad#passengers(int)} 的结果：1..20，永不返回 0（0 会被当成"不限载"）
     */
    @Override public int passengerNumLimit() { return CargoLoad.passengers(cargoItems()); }

    /** @return 重载轿厢物品 {@link Easyelevator#POWERFUL_CABIN_ITEM}：回收后仍得到重载轿厢 */
    @Override protected Item cabinItem() { return Easyelevator.POWERFUL_CABIN_ITEM; }

    /** @return 恒为 true：内饰用 {@code CabinRenderer.POWERFUL_PARTS} 的"重载"一套（双扶手、双顶灯、载重铭牌、防滑钢踏板） */
    @Override public boolean heavyDuty() { return true; }

    /**
     * 这个玩家此刻能不能操作货舱：<b>必须还是本厢的乘客</b>。
     *
     * <p>四个条件缺一不可：实体与玩家都活着、玩家不是旁观者、玩家与轿厢在同一个世界、
     * 且玩家的身体仍然落在轿厢内缘之内（{@link #containsPassenger}）。
     * 于是"走出厢门 / 被传走 / 死亡 / 跨维度 / 半途旁观"都会让已经打开的面板立刻失效——
     * 服务端每次点击都会重新问一遍（{@link CargoScreenHandler#canUse}），
     * 原版也会周期性检查并自动关掉失效的界面。
     *
     * <p>刻意<b>不</b>要求"门开着"或"已停稳"：装卸只在轿厢内进行，而"车在动的时候能不能整理货物"
     * 属于玩法选择——本型号的选择是允许（更省事），代价是装货期间 {@link #tick()} 会不断尝试把门
     * 重新打开、{@link #doorCommand(boolean)} 拒绝关门，所以只要有人开着货舱，车就不会开走。
     *
     * @param player 待判定的玩家
     * @return 允许操作货舱时为 true
     */
    public boolean canUseCargo(PlayerEntity player) {
        return isAlive() && player.isAlive() && !player.isSpectator() && player.getWorld() == getWorld()
                && containsPassenger(player);
    }

    /**
     * 是否有人正开着本厢的货舱（只问服务端世界的玩家，无副作用）。
     *
     * <p>判据是"这个玩家的当前菜单是本厢的 {@link CargoScreenHandler} 且他仍然可以操作货舱"，
     * 因此**不需要**额外的"谁开过箱子"登记表：玩家一走开、菜单一关、被判为无权，这里立刻为假，
     * 没有需要清理的残留状态（也就不会出现"人早走了、门却一直开着"）。
     *
     * @return 至少有一名玩家正在装卸本厢货物时为 true
     */
    private boolean cargoInUse() {
        return getWorld().getPlayers().stream().anyMatch(player ->
                player.currentScreenHandler instanceof CargoScreenHandler handler
                        && handler.cabin() == this && canUseCargo(player));
    }

    /**
     * 每刻驱动：有人装卸货舱时，先把门"顶住"，再走父类的正常 tick。
     *
     * <p>为什么每刻调用 {@code super.doorCommand(true)} 而不是只调一次：
     * 开门键的语义就是"续满停留时间"（见 {@link ElevatorController#forceOpen()}），
     * 每刻续一次等价于"装卸期间一直按住开门键"，于是停留倒计时永远到不了 0、门不会自己关上。
     * 这也复用了既有判据：车在运行途中（有目的站）时开门请求会被状态机拒绝，门保持关闭，
     * 但玩家仍然可以继续整理货物——一旦到站停车，下一次 tick 就会把门打开。
     *
     * <p>顺序（先顶门后 tick）是刻意的：本刻先续满停留，父类随后推进门动画与相位，
     * 同一刻内不会出现"倒计时先归零、门开始关"的窗口。
     */
    @Override public void tick() {
        if (!getWorld().isClient && cargoInUse()) super.doorCommand(true);
        super.tick();
    }

    /**
     * 关门指令：<b>有人装卸时拒绝关门</b>，其余交给父类。
     *
     * <p>为什么要在指令层再挡一次（{@link #tick()} 已经在顶门了）：关门键、超载/防夹逻辑与
     * 停留倒计时都可能发出关门请求，而"门关到一半又被顶开"会让门抖动。这里直接拒绝，
     * 语义也更清楚：装卸期间门就是不能关；所有人都关闭货舱后，下一次关门（手动或倒计时）
     * 立刻恢复正常——这也是"关闭面板后恢复运行"这句话的落点。
     *
     * @param open true = 开门，false = 关门
     * @return 指令被受理时为 true；装卸期间收到关门请求时 false
     */
    @Override public boolean doorCommand(boolean open) {
        if (!open && cargoInUse()) return false;
        return super.doorCommand(open);
    }

    /**
     * 右键交互：<b>潜行 + 身为乘客</b>时打开货舱；其余全部交回父类。
     *
     * <p>三种情况的落点：
     * <ul>
     *   <li><b>厢内潜行右键</b>（主手、任意手持物）→ 打开货舱面板（服务端开，客户端只收到界面）；</li>
     *   <li>如果此刻已不具备操作资格（例如刚被传出去）→ 只在客户端侧提示
     *       {@code message.easyelevator.cargo_stopped}，不打开面板；</li>
     *   <li>其它一切情况（非潜行、非乘客、副手）→ 交给父类：乘客普通右键开选站面板，
     *       轿厢外潜行空手右键仍是回收（父类的回收判据要求"厢内没有任何乘客"）。</li>
     * </ul>
     *
     * <p>为什么先判 {@code containsPassenger} 再判客户端：潜行右键在厢外是"回收"，
     * 在厢内是"开货舱"，两者必须由同一个判据分开，否则站在门口的玩家会同时触发两种语义。
     * 返回 {@link ActionResult#SUCCESS} 是为了吃掉这次点击，避免物品被放到舱壁上。
     *
     * @param player 交互玩家
     * @param hand 交互手
     * @return 处理了这次点击时 SUCCESS；交给父类处理时父类的返回值
     */
    @Override public ActionResult interact(PlayerEntity player, Hand hand) {
        if (hand == Hand.MAIN_HAND && player.isSneaking() && containsPassenger(player)) {
            if (!getWorld().isClient) {
                if (canUseCargo(player)) {
                    player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                            (syncId, inventory, viewer) -> new CargoScreenHandler(syncId, inventory, this),
                            Text.translatable("screen.easyelevator.cargo")));
                } else player.sendMessage(Text.translatable("message.easyelevator.cargo_stopped"), true);
            }
            return ActionResult.SUCCESS;
        }
        return super.interact(player, hand);
    }

    /**
     * 第 {@code index} 个货箱的<b>世界坐标</b>长方体。
     *
     * <p>由 {@link CargoLoad#box(int)} 的局部坐标经 {@link #localBox} 按当前朝向旋转/平移而来，
     * 因此渲染（客户端用同一个 {@code CargoLoad.box}）与碰撞/占用检查（服务端用本方法）
     * 在四种朝向下指的是同一块空间。越界序号会在 {@code CargoLoad.box} 里直接抛错——
     * 调用方必须先问 {@link #cargoCrates()}。
     *
     * @param index 货箱序号（0 起）
     * @return 世界坐标下的货箱长方体
     */
    public Box cargoBox(int index) {
        Box box = CargoLoad.box(index);
        return localBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /**
     * 本格最多还能放几件——<b>动态单格上限</b>，由 {@code CargoScreenHandler} 的槽位每次询问。
     *
     * <p>公式：{@code min(64, cargoSpaceLimit() − cargoItems() + 本格现有件数)}。
     * 三项的含义：
     * <ul>
     *   <li>{@code cargoSpaceLimit()}：在不把下一个箱子插进生物身体里的前提下，本厢最多能装的件数；</li>
     *   <li>减去 {@code cargoItems()} 再加上<b>本格现有件数</b>：得到"本格在不越界的前提下最多能装多少"，
     *       加回本格已有数量是为了让"已经装了 30 件的格子"仍然显示 30 而不是负数、也不会把已有货物算丢；</li>
     *   <li>与 64 取小：单格不超过一组的常规上限。</li>
     * </ul>
     * 结果是 0 时该格连插入尝试都会被拒绝（{@code Slot.canInsert}），因此玩家看到的是
     * "格子点不动 + 一条提示"，而不是"放进去又被弹出来"。
     *
     * @param slot 货舱槽位序号（0..{@link CargoLoad#SLOTS}）
     * @return 本格允许的最大堆叠数（0..64）
     */
    public int cargoSlotLimit(int slot) {
        return Math.max(0, Math.min(64, cargoSpaceLimit() - cargoItems() + cargo.getStack(slot).getCount()));
    }

    /**
     * 后部货箱区此刻是否<b>已经装不下更多</b>（供面板提示与点击反馈使用）。
     *
     * <p>含义与 {@link #cargoSlotLimit(int)} 一致，只是换个问法：货量已经顶到
     * {@link #cargoSpaceLimit()} 且还没满舱时，就是"要放新箱子但位置上有人"。
     * 满舱（件数已达 {@link CargoLoad#MAX_ITEMS}）时返回 false——那是"装满了"，
     * 不是"被挡住"，不该提示玩家去挪人。
     *
     * @return 需要玩家让开后部空间才能继续装货时为 true
     */
    public boolean cargoSpaceBlocked() {
        int limit = cargoSpaceLimit();
        return limit < CargoLoad.MAX_ITEMS && limit <= cargoItems();
    }

    /**
     * 在不与生物重叠的前提下，本厢最多能装的件数（<b>唯一的货箱防穿模判据</b>）。
     *
     * <p>算法：从"当前应显示的箱子数"开始往后数，遇到第一个<b>位置上有活体</b>的箱子就停在
     * 该箱子之前那一档（{@code i × ITEMS_PER_CRATE}）；一个都没被占就返回满舱 {@link CargoLoad#MAX_ITEMS}。
     *
     * <p>几个刻意的取舍：
     * <ul>
     *   <li>只看<b>还没出现</b>的箱子（从 {@link #cargoCrates()} 起算）：已经长出来的箱子旁边站着人
     *       不该反过来禁止继续装货，否则玩家会被"已经存在的东西"越卡越死；</li>
     *   <li>判据是活体（{@link LivingEntity}，排除旁观者）：动物与盔甲架都会挡住新箱子，
     *       因为箱子是实体碰撞——插进它们身体里会把乘客顶出去或造成挤压；</li>
     *   <li>返回的是件数而不是布尔：它同时被 {@link #cargoSlotLimit(int)} 用来算"本格还能放几件"，
     *       因此"刚好装到档位边界"也能被精确表达（例如上界 288 时，第 289 件会被拒绝）。</li>
     * </ul>
     *
     * @return 允许的件数上界：0..{@link CargoLoad#MAX_ITEMS} 之间的 {@code ITEMS_PER_CRATE} 倍数
     */
    private int cargoSpaceLimit() {
        for (int i = cargoCrates(); i < CargoLoad.MAX_CRATES; i++) {
            if (!getWorld().getOtherEntities(this, cargoBox(i), e -> e instanceof LivingEntity && !e.isSpectator()).isEmpty()) {
                return i * CargoLoad.ITEMS_PER_CRATE;
            }
        }
        return CargoLoad.MAX_ITEMS;
    }

    /**
     * 空心外壳 + 已出现的货箱 = 本厢的实体碰撞几何（由 {@code EntityViewMixin} 注入世界）。
     *
     * <p>覆写点只做一件事：在父类的五块外壳之后追加货箱，因此"看得见的箱子"就是"挡得住人的箱子"
     * ——乘客能站在箱顶、也能被箱子挡住，与外壳的语义完全一致。
     * 货箱<b>不</b>参与井道障碍扫描（{@code collisionBoxes} 是给玩家与实体用的；
     * 井道里没有货箱这种"随货物长出来的障碍"的概念，箱子始终在轿厢内部，不会伸出外壳）。
     *
     * @return 本刻的碰撞盒列表：外壳在前、货箱在后（顺序不参与判定，只影响调试可读性）
     */
    @Override public List<Box> collisionBoxesStatic() {
        List<Box> boxes = super.collisionBoxesStatic();
        for (int i = 0; i < cargoCrates(); i++) boxes.add(cargoBox(i));
        return boxes;
    }

    /**
     * 站在货箱顶部也算"被厢内地板托着"（父类只看地板面 0.2 格那一层）。
     *
     * <p>为什么必须覆写：原版会用"长时间不落地"的规则把浮空乘客踢出去，而站在 0.6 / 1.0 格高的
     * 箱顶的人脚下并不是地板面。判据与父类同源（必须是本厢乘客 + 脚底贴合 ±0.025 格），
     * 只是把"支撑面"从地板扩到每个箱顶，并要求水平投影确实落在那个箱子范围内
     * （否则站在箱子旁边的空中也会被误判为有支撑）。
     *
     * @param entity 待判定实体
     * @return 站在地板面或任一货箱顶面上时为 true
     */
    @Override public boolean supportsPassenger(Entity entity) {
        if (super.supportsPassenger(entity)) return true;
        if (!containsPassenger(entity)) return false;
        Box feet = entity.getBoundingBox();
        for (int i=0; i<cargoCrates(); i++) {
            Box box = cargoBox(i);
            if (Math.abs(entity.getY()-box.maxY) < .025 && feet.maxX > box.minX && feet.minX < box.maxX
                    && feet.maxZ > box.minZ && feet.minZ < box.maxZ) return true;
        }
        return false;
    }

    /**
     * 存档：父类写线路/状态机/乘客名册，本类补一个 {@code Cargo} 复合标签。
     *
     * <p>用原版 {@link Inventories#writeNbt} 的格式（{@code Items} 列表）而不是自定义结构：槽位、数量与
     * 物品组件（自定义名称、附魔、容器内容……）都由原版负责往返，本模组不需要跟着版本更新维护一份
     * 物品序列化。旧存档没有这个键时读入空舱（见 {@link #readCustomDataFromNbt}）。
     *
     * @param nbt 待写入的实体 NBT
     */
    @Override protected void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.put("Cargo", Inventories.writeNbt(new NbtCompound(), cargo.getHeldStacks(), getRegistryManager()));
    }

    /**
     * 读档：父类读完之后再恢复货舱，并立刻重算同步字段。
     *
     * <p>顺序不能反——{@link #syncCargo()} 依赖货舱内容已经填好；而它必须在这里调用一次，
     * 否则首个 tick 之前客户端会按"空舱"渲染（货箱消失、铭牌显示 20 人）闪一下。
     * {@code Cargo} 键缺失（旧存档）时 {@code Inventories.readNbt} 读到空复合标签，
     * 结果是空舱，行为与旧版本一致。
     *
     * @param nbt 已序列化的实体 NBT
     */
    @Override protected void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        cargo.clear();
        Inventories.readNbt(nbt.getCompound("Cargo"), cargo.getHeldStacks(), getRegistryManager());
        syncCargo();
    }

    /**
     * 实体移除：<b>回收与销毁时把货物掉出来，且只掉一次</b>；其余移除原因保留库存。
     *
     * <p>为什么要按原因区分（这里是最容易写出"货物凭空消失"或"复制一堆货"的地方）：
     * <ul>
     *   <li>{@code KILLED}（/kill、掉入虚空等销毁）与 {@code DISCARDED}（本模组的回收：父类
     *       {@code interact} 里调用 {@code discard()}）→ 把每格取空并 {@code dropStack}，
     *       玩家至少能把货捡回来；</li>
     *   <li>{@code UNLOADED_TO_CHUNK} / {@code UNLOADED_WITH_PLAYER} / {@code CHANGED_DIMENSION} /
     *       {@code CONVERTED} 等 → <b>什么都不做</b>：这些移除只是"换个地方继续存在"，
     *       库存要么马上写进存档、要么随实体一起转移，掉出来才是真的丢货。</li>
     * </ul>
     * 先取空再丢弃（{@code removeStack}）而不是遍历读取：取空本身就让库存归零，
     * 于是即便移除流程被重复调用（父类与调用方各调一次），第二次也只会看到空舱——
     * 这正是 CargoTests 里"回收只掉一次"那条断言保护的语义。
     *
     * @param reason 原版移除原因
     */
    @Override public void remove(RemovalReason reason) {
        if (!getWorld().isClient && !isRemoved()
                && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED)) {
            for (int i = 0; i < cargo.size(); i++) {
                var stack = cargo.removeStack(i);
                if (!stack.isEmpty()) dropStack(stack);
            }
            syncCargo();
        }
        super.remove(reason);
    }
}
