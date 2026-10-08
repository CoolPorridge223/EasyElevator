package org.DJB.easyelevator.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.api.ElevatorEvents;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.block.LandingDoorBlockEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorLine;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.logic.ElevatorStatus;
import org.DJB.easyelevator.logic.FloorIndicator;
import org.DJB.easyelevator.logic.SlidingDoor;
import org.DJB.easyelevator.network.ElevatorNetworking;
import org.DJB.easyelevator.network.PlatformMovement;
import org.DJB.easyelevator.logic.RiderMotionHistory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 电梯轿厢的共同父类：3x3x3 的空心轿厢，也是整条线路的服务端权威载体。
 *
 * <p>为什么要有这一层：模组提供四种轿厢——普通轿厢 {@link CabinEntity}、
 * 高速轿厢 {@link HighSpeedCabinEntity}（巡航速度是普通的 {@link ElevatorParameters#HIGH_SPEED} 倍，
 * 且加/减速段更长）、观光轿厢 {@link ObservationCabinEntity}（四面墙换成玻璃）、
 * 强力轿厢 {@link PowerfulCabinEntity}（限载 {@link ElevatorParameters#HIGH_PASSENGER_NUM_LIMIT} 人，
 * 外壳不变、内饰换重载件）。四者的运动学、乘客处理、
 * 门联锁、存档与同步<b>完全相同</b>，差别只有三项：构造时传入的巡航速度与限载人数，以及子类覆写的
 * {@link #cabinItem()}（回收时掉落哪一种物品）、{@link #glassWalls()} 与 {@link #heavyDuty()}
 * （两项纯客户端渲染提示）。
 * 因此全部逻辑集中在这里，四个子类各自只有十几行，不存在第二份需要同步维护的运动代码。
 *
 * <p>在整体架构中的位置：所有运动学都委托给纯 Java 状态机 {@link ElevatorController}
 * （不引用任何 Minecraft 类，因而可以脱离游戏单测）；状态机再把"每刻走多远"委托给 S 形速度曲线
 * {@link org.DJB.easyelevator.logic.MotionProfile}。本实体每刻在 {@link #tick()} 中通过匿名
 * {@link ElevatorController.Environment} 把世界查询（valid / canMove / doorwayBlocked / arrived /
 * outOfPassengerNumLimit）注入状态机，再把状态机返回的 Y 应用到实体位置；实体自身不保存速度、
 * 加速度或插值轨迹。
 * 客户端读取权威运动包，在玩家物理更新前承托乘客；行程决策仍只在服务端执行。
 *
 * <p>关键不变量与约束：
 * <ul>
 *   <li>线路唯一：一段竖直连续、朝向一致的轨道列（线路）最多一个轿厢（四种型号一起计数）；
 *       轿厢数不为 1 时不允许移动。</li>
 *   <li>门未完全关闭（DOOR 未降到 0）不得移动；楼层门联锁由 {@link LandingDoorBlock#refresh}
 *       依据本实体 Y（误差 &lt;= {@link ElevatorParameters#POSITION_EPSILON} 格）与门进度决定。</li>
 *   <li>位置用绝对 double，经 {@code MotionFrame} 包同步，绕过原版相对位置包的定点量化。</li>
 *   <li>碰撞体不是实体包围盒：{@link #isCollidable()} 返回 false，空心外壳由 EntityViewMixin 注入
 *       {@link #collisionBoxes()}，否则实心包围盒会把乘客挡在轿厢外。</li>
 *   <li>BLOCKED 表示受阻暂停（断轨、朝向不一致、井道有方块或实体障碍、区块未加载、目的站门被拆），
 *       不是失败；条件恢复后继续原行程。</li>
 *   <li>限载人数为 0 或负数 = 不限载（限载只能在构造时给出、运行中不变），见 {@link #overloaded()}；
 *       超载只影响"门开着、不派发行程"，不改变碰撞、承托与存档。</li>
 *   <li>乘客名册（{@link #passengers}）随存档保存：读档后必须先等这些人回到世界，才能继续原行程，
 *       否则轿厢会抢在玩家实体载入之前开走，把乘客留在空掉的井道里（见 {@link #tickPassengers()}）。</li>
 * </ul>
 *
 * <p>几何与单位：局部坐标原点在轿厢底部中心，+Z 指向门口，长度单位一律为格（方块）。
 * 轿厢中心位于轨道朝向前方 2 格，底部 Y 与被点击的轨道相同，因而与站点 Y 对齐。
 * 速度单位为格/刻（1 秒 = 20 刻），见 {@link #speed()}；它是 S 形曲线的<b>巡航速度上限</b>，
 * 启动与到站的若干刻里实际步长小于它。四个型号的几何、碰撞与同步字段
 * 完全一致，因此换乘任意型号都不会改变井道尺寸、站点位置或门联锁语义。
 */
public abstract class AbstractCabinEntity extends Entity {

    /** 同步给客户端的 {@link ElevatorController.Phase} 序号（{@code ordinal()}）；客户端只读，用于渲染与面板显示。 */
    private static final TrackedData<Integer> PHASE = DataTracker.registerData(AbstractCabinEntity.class, TrackedDataHandlerRegistry.INTEGER);

    /** 同步给客户端的门进度：0 表示全关、1 表示全开，无单位；与 {@link ElevatorController#door()} 同刻写入，驱动门动画与门联锁。 */
    private static final TrackedData<Float> DOOR = DataTracker.registerData(AbstractCabinEntity.class, TrackedDataHandlerRegistry.FLOAT);

    /** 同步给客户端的轨道朝向（= 轿厢门朝向）id；决定轿厢正面方向与本地几何的旋转，客户端渲染必须与服务端一致。 */
    private static final TrackedData<Integer> FACING = DataTracker.registerData(AbstractCabinEntity.class, TrackedDataHandlerRegistry.INTEGER);

    /** 同步给客户端的当前目标站点 Y（格）；无目标时用 {@link Integer#MIN_VALUE} 作哨兵，避免与任何合法高度混淆。 */
    private static final TrackedData<Integer> TARGET_Y = DataTracker.registerData(AbstractCabinEntity.class, TrackedDataHandlerRegistry.INTEGER);

    /**
     * 同步给客户端的"当前楼层号"（1 起，站点按高度升序，最底层 = 1 层；0 = 尚未经过任何站点）。
     * 选站面板、楼层门框顶部与轿厢内的模拟面板都显示它，因此统一由服务端算好再同步，避免三处口径不一致。
     */
    private static final TrackedData<Integer> FLOOR = DataTracker.registerData(AbstractCabinEntity.class, TrackedDataHandlerRegistry.INTEGER);

    /** 确定性状态机实例：Phase、门进度、当前目标与请求队列都存放在这里，实体内不重复保存。速度在构造时注入。 */
    private final ElevatorController controller;

    /**
     * 本型轿厢的限载人数（构造时注入，运行中不变）：<b>0 或负数 = 不限载</b>（普通 / 高速 / 观光），
     * 强力型号用 {@link ElevatorParameters#HIGH_PASSENGER_NUM_LIMIT}。判超载见 {@link #overloaded()}。
     */
    private final int passengerNumLimit;

    /** 本型轿厢的巡航速度上限（格/刻）：普通与观光 0.20、高速 0.50；与 {@link ElevatorController#speed()} 同值，供外部读取。 */
    private final double speed;

    /** 所属线路的轨道水平坐标（方块坐标）；与 {@link #getY()} 一起构成 {@link ElevatorLine#scan} 的种子，也是线路唯一性的判定依据。 */
    private int railX, railZ;

    /** 上一刻的门进度（0..1），与当刻 DOOR 一起供 {@link #doorProgress(float)} 插值，避免 20 TPS 下门动画逐刻跳变。 */
    private float previousDoor = 1;

    /** 停车后仍需补发静止运动包的剩余刻数（刻）；不补发则客户端插值会停在最后一个运动样本上无法收敛。 */
    private int motionSettleTicks;

    /**
     * 上一刻已经下发给客户端的"停靠计划"（见 {@link #plannedStops()}）。
     * 只用于判断是否变化：变化才发 {@link ElevatorNetworking#syncPanel}，避免每刻刷包；不写存档。
     */
    private List<BlockPos> lastPlan = List.of();

    /**
     * 上一刻已经下发给附近客户端的"厅外呼叫位掩码"：站点打包坐标 → bit0 = 上行、bit1 = 下行。
     * 只用于判断是否变化（见 {@link #syncHallStates()}），不写存档。
     */
    private final Map<Long, Integer> lastHallMask = new HashMap<>();

    /** 上一刻用于判断运行方向的高度（格）；只服务于楼层显示，不参与任何运动或联锁判定。 */
    private double floorDirectionY;

    /** 当前楼层号（1 起；0 = 尚未经过任何站点）；与同步字段 FLOOR 保持一致，便于服务端自身读取。 */
    private int floorNumber;

    /**
     * 厢内玩家数（服务端每刻重数一次，不写存档、不同步）。
     *
     * <p>只服务于 {@link #overloaded()}：数的是"这一刻站在厢内的玩家"，与乘客名册
     * （{@link #passengers}，用于读档归位）是两件事——名册会保留掉线的人，超载只看此刻车里的人。
     * 数在 {@link #tick()} 里、状态机之前，因此本刻的超载判定与车门动作用的是同一份计数。
     */
    private int passengerNum;

    /**
     * 本厢负责的乘客名册：玩家 UUID -> 相对轿厢底部中心的偏移（格，与 {@link #localBox} 同一坐标系）。
     *
     * <p>为什么需要它：轿厢是区块实体，随区块载入；玩家实体由登录流程单独载入，一定晚于区块实体。
     * 运行时存档后再次进入游戏时，若轿厢立刻恢复行程，就会在乘客回到世界之前先开走，乘客随后被放回
     * 自己存档坐标——已经空掉的井道——于是掉出电梯。名册把"这辆车上的乘客"连同相对偏移一起写进存档，
     * 读档后据此先等人、再走（见 {@link #tickPassengers()}）。
     *
     * <p>维护规则（每服务端刻执行一次，见 {@link #tickPassengers()}）：
     * <ul>
     *   <li>当刻站在厢内的玩家：每刻刷新其相对偏移（用相对量，因此与轿厢之后走到哪里无关）；</li>
     *   <li>读档或离线后回到同一井道的玩家：按保存偏移归位，等待传送确认后继续；</li>
     *   <li>在线玩家正常走出或传送离开：立即划掉，不把移动同步误差当成读档恢复；</li>
     *   <li>掉线的玩家一律保留在名册里等他回来：他们没有能力自己走出轿厢，只可能是被行程丢下的人。</li>
     * </ul>
     */
    private final Map<UUID, Vec3d> passengers = new LinkedHashMap<>();
    /** Recovery is only for loaded/offline riders, never an online player walking out. */
    private final Set<UUID> recoveringPassengers = new HashSet<>();
    private final RiderMotionHistory motionHistory = new RiderMotionHistory();
    private boolean clientMotionControlled;

    /**
     * 取"客户端生成那个移动包时看到的轿厢高度"（服务端权威，用于随厢移动的坐标系补偿）。
     *
     * <p>客户端的移动包携带"它是按哪个高度样本算出来的"（见 {@code network/RiderMove}），服务端据此回查
     * 当时的高度，再把玩家的 Y 换算到当前帧；否则这段单程延迟会被当成玩家自己在往上飞
     * （表现为拉回、悬浮判定或速度校验误报）。
     *
     * @param tick 样本编号，即客户端生成移动包时的世界时间（单位：刻）
     * @return 该刻的轿厢绝对 Y（单位：格）；样本尚未产生、已超过
     *         {@link RiderMotionHistory#MAX_AGE} 刻或从未记录过时返回 {@link Double#NaN}，
     *         调用方必须据此退回原版处理，绝不能拿 NaN 或猜测值继续换算
     */
    public double motionHeight(long tick) { return motionHistory.height(tick, getWorld().getTime()); }

    /** Once custom double-precision frames arrive, vanilla tracking must not move the floor separately. */
    public void useClientMotion() { clientMotionControlled = true; }

    /**
     * 接管原版位置包对本厢的定位：收到过自定义绝对高度样本之后再让原版追踪包单独搬动轿厢，
     * 两套坐标就会互相打架（地板与乘客各走各的、来回抖动）。
     *
     * <p>只在客户端、且已经收到过样本时拦截；服务端与"还没收到过样本"的客户端仍走父类实现，
     * 因此实体刚载入的那几刻不会失去定位。
     *
     * @param x 原版追踪包给出的 X（单位：格）
     * @param y 原版追踪包给出的 Y（单位：格）
     * @param z 原版追踪包给出的 Z（单位：格）
     * @param yaw 原版追踪包给出的偏航角（单位：度）
     * @param pitch 原版追踪包给出的俯仰角（单位：度）
     * @param steps 原版要求的插值步数（单位：刻）
     */
    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
        if (!getWorld().isClient || !clientMotionControlled)
            super.updateTrackedPositionAndAngles(x, y, z, yaw, pitch, steps);
    }

    /** 读档后等待名册乘客归位的剩余刻数（刻）；0 = 本刻无需等待（名册已齐或等待窗口已用尽）。 */
    private int passengerWaitTicks;

    /**
     * 构造轿厢实体。所属线路与初始位置随后由 {@link #initialize(BlockPos, Direction)} 或存档载入设定。
     *
     * @param type 实体类型（由子类传入各自的注册类型，例如 {@code easyelevator:cabin}、
     *             {@code easyelevator:high_speed_cabin}、{@code easyelevator:observation_cabin}、
     *             {@code easyelevator:powerful_cabin}）
     * @param world 所在世界
     * @param speed 本型轿厢的巡航速度上限（格/刻）：普通 / 观光 / 强力用 {@link ElevatorParameters#SPEED}，
     *              高速用 {@link ElevatorParameters#HIGH_SPEED}。用构造参数而不是子类覆写方法，
     *              是为了避免"父类构造期间调用子类方法"，也让速度天然成为 final 的只读事实。
     *              加加速度（jerk）由状态机按该速度推出：高速档更小 ⇒ 加/减速段更长。
     * @param passengerNumLimit 本型轿厢的限载人数：<b>0 或负数 = 不限载</b>
     *              （普通 / 高速 / 观光），强力型号用 {@link ElevatorParameters#HIGH_PASSENGER_NUM_LIMIT}。
     *              超过它时状态机进入 {@link ElevatorController.Phase#OVERLOAD}（门保持全开、不走车），
     *              判据见 {@link #overloaded()}；它同时是渲染后壁载重铭牌上那个数字的来源
     */
    protected AbstractCabinEntity(EntityType<?> type, World world, double speed, int passengerNumLimit) {
        super(type, world);
        setNoGravity(true); // 禁用重力：Y 完全由状态机决定，交给原版物理会被下拽并触发下落判定
        this.speed = speed;
        this.passengerNumLimit = passengerNumLimit;
        this.controller = new ElevatorController(speed); // 速度在构造时一次性注入状态机，运行中不变
    }

    /** @return 本型轿厢的巡航速度上限（格/刻）：普通与观光 0.20，高速 0.50；只读，供渲染/面板/测试读取。 */
    public final double speed() { return speed; }

    /**
     * @return 本型轿厢的限载人数；<b>0 或负数表示不限载</b>（普通 / 高速 / 观光三型的原有行为：
     *         只要人站得进来就能走）。强力型号为 {@link ElevatorParameters#HIGH_PASSENGER_NUM_LIMIT}。
     *         只读，渲染（后壁载重铭牌上的数字）与状态机（超载判定）读的是同一个值。
     */
    public final int passengerNumLimit() { return passengerNumLimit; }

    /**
     * 本厢此刻是否<b>超载</b>：厢内乘客数超过 {@link #passengerNumLimit()}。
     *
     * <p>唯一判据是"限载人数为正"：{@code limit <= 0} 一律视为不限载，因此把
     * {@link ElevatorParameters#PASSENGER_NUM_LIMIT} 改回 0 就完全回到没有这个功能的旧行为，
     * 不会因为"站进来一个人"就误判超载（{@code 人数 > 0} 在限载 0 时会恒真——那会让普通轿厢再也开不走）。
     *
     * <p>计数口径：只数<b>玩家</b>（{@link #containsPassenger} 里算乘客的生物也占地方，但"准乘几人"
     * 说的是人；召唤一堆动物不会触发超载）。服务端每刻刷新一次，见 {@link #tick()}。
     *
     * <p>超载的表现由状态机决定：门保持全开、不派发行程，面板上显示
     * {@code phase.easyelevator.overload}，直到有人走出厢门（见 {@link ElevatorController.Phase#OVERLOAD}）。
     *
     * @return 超载时为 true；不限载的型号恒为 false
     */
    public final boolean overloaded() { return passengerNumLimit > 0 && passengerNum > passengerNumLimit; }

    /**
     * 本型轿厢回收时掉落的生成物品。四种轿厢共用同一个回收交互（潜行、空手、门全开、厢内无人），
     * 只有"掉哪一件"由子类决定；不覆写则回收会产出错误型号的物品。
     *
     * @return 对应的生成物品单例，例如 {@link org.DJB.easyelevator.Easyelevator#CABIN_ITEM}
     */
    protected abstract Item cabinItem();

    /**
     * 是否是观光轿厢：四面墙为玻璃材质。**纯客户端渲染提示**，不参与任何服务端判定。
     *
     * <p>为什么不做成同步字段：这是型号的静态属性（由实体类型唯一决定，存档与网络两端都已知），
     * 客户端拿到实体后按类判断即可，不需要额外的 DataTracker 位；玻璃墙也不改碰撞——
     * 观光轿厢的井道预留、乘客包围盒、门口防夹与门联锁与普通轿厢逐位相同。
     *
     * @return true 时 {@code CabinRenderer} 用半透明材质画侧墙/后墙/门扇，并保留四个角柱
     */
    public boolean glassWalls() { return false; }

    /**
     * 是否是强力轿厢：外壳与普通型号完全相同，但内饰换成"重载"那一套（双扶手、双顶灯、载重铭牌、
     * 防滑钢踏板）。**纯客户端渲染提示**，与 {@link #glassWalls()} 同一性质、同样不写同步字段。
     *
     * <p>为什么不直接用 {@link #passengerNumLimit()} 判断：限载人数是状态机的数据，
     * "画哪一套内饰"是型号的静态属性；两者恰好同源（只有强力型号限载），但把它们绑在一起
     * 会让"给普通型号加个限载"这种改动意外改掉它的外观。渲染只认型号，读数据只读数据。
     *
     * @return true 时 {@code CabinRenderer} 在内饰表上叠加 {@code POWERFUL_PARTS} 的强化件
     */
    public boolean heavyDuty() { return false; }

    /**
     * 初始化 DataTracker 的默认同步值：Phase = OPEN（序号 0）、门全开 1、朝向 NORTH、无目标。
     * 这些默认值会在首个服务端 tick 后立即被 {@link #tick()} 的写回覆盖，仅用于客户端在收到首个包前的渲染兜底。
     *
     * @param b 原版实体构造流程传入的 DataTracker 构建器
     */
    @Override
    protected void initDataTracker(DataTracker.Builder b) {
        b.add(PHASE, 0); b.add(DOOR, 1f); b.add(FACING, Direction.NORTH.getId()); b.add(TARGET_Y, Integer.MIN_VALUE);
        b.add(FLOOR, 0); // 楼层号在第 0 刻由 updateFloorNumber 算出，默认 0 表示"还不知道"
    }

    /**
     * 放置轿厢后由物品调用：把轿厢绑定到一条线路并摆到初始位置。
     *
     * @param rail 被点击的轨道位置，取其中的水平坐标作为线路标识
     * @param facing 轨道朝向，同时也是轿厢门朝向
     *
     * <p>副作用：写入同步字段 FACING，并直接设置实体位置。实体自身不校验线路合法性，
     * 由 {@link ElevatorLine} 与 {@link #requestStop(BlockPos)} 在使用时校验。
     */
    public void initialize(BlockPos rail, Direction facing) {
        railX = rail.getX(); railZ = rail.getZ(); dataTracker.set(FACING, facing.getId());
        // 轿厢中心 = 轨道中心 + 朝向前方 2 格；底部 Y 与轨道同高，也就是与站点 Y 对齐
        setPosition(railX + .5 + facing.getOffsetX()*2, rail.getY(), railZ + .5 + facing.getOffsetZ()*2);
        floorDirectionY = getY(); // 楼层显示的方向基准：刚落成时视为静止，第一次刷新按"上行规则"取所在层
        passengers.clear(); recoveringPassengers.clear(); passengerWaitTicks = 0;
    }

    /** @return 所属线路的轨道 X（方块坐标） */
    public int railX() { return railX; }

    /** @return 所属线路的轨道 Z（方块坐标） */
    public int railZ() { return railZ; }

    /** @return 轨道（= 轿厢门）朝向；读同步字段，因此客户端也能得到与服务端一致的朝向 */
    public Direction facing() { return Direction.byId(dataTracker.get(FACING)); }
    /** @return 当前状态机 Phase；读同步字段，客户端只能用这个值做表现，不能据此驱动运动 */
    public ElevatorController.Phase phase() { return ElevatorController.Phase.values()[dataTracker.get(PHASE)]; }

    /**
     * 取渲染用的门进度。
     *
     * @param tickDelta 距上一刻的部分刻（0..1），由渲染帧提供
     * @return 在上一刻与当刻门进度之间线性插值的结果，0 表示全关、1 表示全开
     */
    public float doorProgress(float tickDelta) { return MathHelper.lerp(tickDelta, previousDoor, dataTracker.get(DOOR)); }

    /** @return 当前目标站点 Y（格）；无目标时返回 {@link Integer#MIN_VALUE} 哨兵值 */
    public int targetY() { return dataTracker.get(TARGET_Y); }

    /** @return 是否正在执行某个目的站（用于区分"运行途中"与"关着门停在某层"） */
    public boolean hasTarget() { return targetY() != Integer.MIN_VALUE; }

    /**
     * @return 当前楼层号（1 起，站点按高度升序、最底层 = 1 层）；0 表示尚未经过任何站点（显示端应显示占位符）。
     *         读同步字段，因此客户端渲染门框/轿厢面板与选站面板时拿到的是同一个值。
     */
    public int floorNumber() { return dataTracker.get(FLOOR); }

    /**
     * @return 显示用的运行状态（上行/下行/停靠）。由已同步的 Phase、目的站高度与当前位置推导，
     *         因此轿厢内面板与楼层门框在服务端、客户端得到同一结果，不需要额外的同步字段
     */
    public ElevatorStatus status() { return ElevatorStatus.of(phase(),targetY(),getY()); }

    /**
     * 刷新当前楼层号并写入同步字段。
     *
     * <p>编号与更新规则见 {@link FloorIndicator}：上行取"已到过/经过的最高一层"、下行取"已经过的最低一层"，
     * 因此层号只在经过或到达一层时变化一次，不会因为轿厢恰好经过整数高度而闪动。
     *
     * <p>副作用：更新 floorDirectionY/floorNumber，并在变化时写 DataTracker 的 FLOOR 字段（同步给客户端）。
     *
     * @param line 当前线路；为 null（轨道被拆或区块未加载）时保持 0，表示暂时无法判断
     */
    private void updateFloorNumber(ElevatorLine line) {
        var stationYs = new ArrayList<Integer>();
        if (line != null) for (BlockPos stop : line.stops()) stationYs.add(stop.getY());
        int floor = FloorIndicator.floorNumber(stationYs, getY(), floorDirectionY, baseFloorY(line));
        floorDirectionY = getY();
        if (floor != floorNumber) { floorNumber = floor; dataTracker.set(FLOOR, floor); }
    }

    /**
     * 本线路的基准层高度（"1 层"所在的高度，单位格）。
     *
     * <p>由线路里带 {@code BaseFloor} 标记的那扇楼层门决定（玩家潜行右键设置）；没有任何门带标记时返回
     * {@link Integer#MIN_VALUE}，{@link FloorIndicator} 会退回默认编号（最低站点 = 1 层），因此旧存档行为不变。
     *
     * @return 基准层高度；未设置时为 {@link Integer#MIN_VALUE}
     */
    public int baseFloorY() { return baseFloorY(line()); }

    /**
     * 在线路里找基准层门。
     *
     * @param line 当前线路；为 null（轨道被拆或区块未加载）时返回 {@link Integer#MIN_VALUE}
     * @return 带基准层标记的那扇门的高度；没有则为 {@link Integer#MIN_VALUE}
     */
    private int baseFloorY(ElevatorLine line) {
        if (line == null) return Integer.MIN_VALUE;
        for (BlockPos stop : line.stops())
            if (getWorld().getBlockEntity(stop) instanceof org.DJB.easyelevator.block.LandingDoorBlockEntity door && door.baseFloor())
                return stop.getY();
        return Integer.MIN_VALUE;
    }

    /**
     * 受理一次<b>厅外呼叫</b>（楼层门面板上的上行 / 下行按钮）。
     *
     * @param station 站点根方块位置（楼层门底部中心）
     * @param up true = 上行按钮，false = 下行按钮
     * @return 被受理、或与已有呼叫重复时 true；线路不存在、朝向不一致、本线路轿厢数不为 1、
     *         目标不是本线路站点，或呼叫表已满时 false
     *
     * <p>与 {@link #requestStop(BlockPos)} 的区别：厅外呼叫<b>带方向</b>，只由正在按该方向运行的轿厢顺路接走
     * （调度规则见 {@link ElevatorController}）；单向到站完成，双向停靠按下一程方向认领，残留按钮保持点亮。
     */
    public boolean requestHallCall(BlockPos station, boolean up) {
        ElevatorLine line = line();
        if (line == null || line.facing() != facing() || line.cabins(getWorld()).size() != 1 || !line.stops().contains(station)) return false;
        return controller.callHall(new ElevatorController.HallCall(station.asLong(), station.getY(), up), getY());
    }

    /**
     * @param station 站点根方块位置
     * @param up true 查上行按钮、false 查下行按钮
     * @return 该站点的这个方向是否还有未完成的厅外呼叫（门面板据此把按钮标红）
     */
    public boolean hasHallCall(BlockPos station, boolean up) {
        return controller.hallCalls().contains(new ElevatorController.HallCall(station.asLong(), station.getY(), up));
    }

    /** @return 当前所有未完成的厅外呼叫（不可变快照）；厅外面板与楼层门联锁之外的展示都用它。 */
    public List<ElevatorController.HallCall> hallCalls() { return controller.hallCalls(); }

    /**
     * 把"哪些站点的哪个方向还有呼叫"推给附近客户端，让楼层门面板上的按钮点亮 / 熄灭。
     *
     * <p>只在集合真的变化时发包（登记、到站或离站认领、门被拆）：与停靠计划 {@link #lastPlan} 同一套"变化才推"思路，
     * 因此静止时没有额外流量。
     *
     * <p>副作用：向站点附近玩家发送 {@link ElevatorNetworking#syncHallState}；不发包时什么都不做。
     */
    private void syncHallStates() {
        Map<Long, Integer> mask = new HashMap<>();
        for (var call : controller.hallCalls()) mask.merge(call.id(), call.up() ? 1 : 2, (a, b) -> a | b);
        if (mask.equals(lastHallMask)) return;
        Set<Long> keys = new HashSet<>(mask.keySet());
        keys.addAll(lastHallMask.keySet());
        for (long id : keys) {
            int now = mask.getOrDefault(id, 0), before = lastHallMask.getOrDefault(id, 0);
            if (now == before) continue; // 没变化的站点不发包
            ElevatorNetworking.syncHallState(getWorld(), BlockPos.fromLong(id), (now & 1) != 0, (now & 2) != 0);
        }
        lastHallMask.clear(); lastHallMask.putAll(mask);
    }

    /**
     * 以当前所在高度为种子扫描所属线路。
     *
     * @return 线路描述（含可停靠站点列表）；种子处不是轨道或区块未加载时返回 null
     */
    public ElevatorLine line() { return ElevatorLine.scan(getWorld(), new BlockPos(railX, MathHelper.floor(getY()+.0001), railZ)); } // +0.0001 抵消浮点误差：Y 恰好停在楼层高度时 floor 可能落到下一格而扫错高度

    /**
     * 判断实体的水平投影是否落在轿厢内缘之内。
     *
     * @param e 待判定实体
     * @return 水平方向完全位于内缘 ±1.31 格（内缘 1.3 格 + 0.01 格浮点余量）之内时 true；不判定高度
     *
     * <p>单独抽出这条水平判定的原因：它同时是"是否算乘客"与"掉队乘客是否还在这条井道里"的判据，
     * 两处必须完全一致，否则会出现"轿厢不认这个乘客、却又拽不动他"的分叉。站在楼层门口或走廊上的
     * 玩家横向就已经在内缘之外（门槛在本地 Z=1.3..1.5 格），因此不会被误判成厢内或井道内的人。
     */
    private boolean insideFootprint(Entity e) {
        Box b = e.getBoundingBox();
        return b.minX >= getX()-1.31 && b.maxX <= getX()+1.31 && b.minZ >= getZ()-1.31 && b.maxZ <= getZ()+1.31;
    }

    /**
     * 判断实体是否算本轿厢的乘客。用于随厢移动、障碍扫描豁免、门口防夹豁免与面板/网络层的身份校验。
     *
     * @param e 待判定实体
     * @return 实体位于轿厢内部时 true；旁观者、骑乘其它载具者以及被抬出净高范围者均不算乘客
     *
     * <p>横向边界取内缘 1.3 格再加 0.01 格余量（见 {@link #insideFootprint}），脚部高度从 0.14 格
     * （地板面 0.2 格减容差）到 2.7 格（净高 2.6 格加容差），以避免站在地板或贴墙时因浮点误差被误判为非乘客。
     */
    public boolean containsPassenger(Entity e) {
        return !e.isSpectator() && !e.hasVehicle() && insideFootprint(e)
                && e.getY() >= getY()+.14 && e.getY() < getY()+2.7;
    }

    /**
     * 判定实体此刻是否<b>被厢内地板托着</b>：{@link #containsPassenger} 再要求脚底贴在地板面上。
     *
     * <p>为什么光有 {@link #containsPassenger} 不够：那个判定覆盖整段净高（脚部 0.14 格以上都算），
     * 于是"站在厢内跳起来"的玩家也算乘客。原版"长时间不落地"的踢出判定需要区分这两种情形
     * （见 {@code mixin/ServerPlayNetworkHandlerMixin}）：只有真的踩在地板上才豁免，
     * 腾空的玩家照旧按原版规则处理，跳跃因此不会被随厢移动抹平。
     *
     * @param e 待判定实体
     * @return 是本厢乘客、且脚部落在离地板面 0.2 格 ±0.025 格（2.5 厘米，容差覆盖浮点与同步误差）内时 true
     */
    public boolean supportsPassenger(Entity e) {
        return containsPassenger(e) && Math.abs(e.getY() - getY() - .2) < .025;
    }

    /** Translate the platform frame without teleport acknowledgements or changing input velocity. */
    public static void carryPassenger(Entity rider, double dy) {
        PlatformMovement connection = rider instanceof ServerPlayerEntity p && p.networkHandler != null
                ? (PlatformMovement) p.networkHandler : null;
        if (connection != null && !connection.easyelevator$canCarry()) return;
        rider.setPosition(rider.getX(), rider.getY() + dy, rider.getZ());
        if (connection != null) connection.easyelevator$carried(dy);
        rider.fallDistance = 0;
    }

    /** Maintain persistence separately from normal walking. Only loaded/offline riders may be recovered. */
    private boolean tickPassengers() {
        Set<UUID> inside = new HashSet<>();
        for (Entity e : getWorld().getOtherEntities(this, getBoundingBox(), this::containsPassenger)) {
            if (e instanceof PlayerEntity p) {
                inside.add(p.getUuid());
                recoveringPassengers.remove(p.getUuid());
                passengers.put(p.getUuid(), p.getPos().subtract(getPos()));
            }
        }
        for (UUID uuid : List.copyOf(passengers.keySet())) {
            if (inside.contains(uuid)) continue;
            PlayerEntity online = getWorld().getPlayerByUuid(uuid);
            if (online == null) {
                recoveringPassengers.add(uuid);
                continue;
            }
            if (!recoveringPassengers.contains(uuid)) {
                passengers.remove(uuid); // Normal online exit, including OPENING and fault escape.
                continue;
            }
            PlayerEntity found = findLostPassenger(uuid);
            if (found != null) {
                putPassengerBack(found, passengers.get(uuid));
                recoveringPassengers.remove(uuid);
                inside.add(uuid);
            } else {
                passengers.remove(uuid);
                recoveringPassengers.remove(uuid);
            }
        }
        for (UUID uuid : inside) {
            if (getWorld().getPlayerByUuid(uuid) instanceof ServerPlayerEntity p && p.networkHandler != null
                    && !((PlatformMovement) p.networkHandler).easyelevator$canCarry()) return true;
        }
        if (passengers.keySet().stream().allMatch(inside::contains)) {
            passengerWaitTicks = 0;
            return false;
        }
        if (passengerWaitTicks <= 0 || !inside.isEmpty()) return false;
        passengerWaitTicks--;
        return true;
    }
    /**
     * 在井道范围内找回名册里丢失的乘客实体。
     *
     * @param uuid 乘客 UUID
     * @return 找到的玩家；尚未登录（实体不在世界里）、在别的世界、旁观或骑乘载具时返回 null
     *
     * <p>为什么只按"水平落在轿厢内缘内"判定（{@link #insideFootprint}）：轿厢只能沿井道上下走，
     * 掉队的乘客水平位置不变，一定还在同一条竖直井道里；反过来说，站在楼层门口或走廊上的玩家
     * 横向就已经在内缘之外，因此不会被误当成掉队乘客拽进轿厢。竖直方向不设限：乘客可能已经掉到
     * 井道深处，也可能轿厢已经走到很远的高度（例如掉线期间行程照常走完）。
     */
    private PlayerEntity findLostPassenger(UUID uuid) {
        Box column = new Box(getX()-2, getWorld().getBottomY(), getZ()-2, getX()+2, getWorld().getTopY(), getZ()+2);
        var found = getWorld().getEntitiesByClass(PlayerEntity.class, column,
                e -> uuid.equals(e.getUuid()) && !e.isSpectator() && !e.hasVehicle() && insideFootprint(e));
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 把掉队的乘客放回名册记录的相对位置，并清掉速度与下落距离。
     *
     * @param p 待归位的玩家
     * @param offset 名册里保存的相对偏移（格）
     *
     * <p>偏移按玩家宽高夹到厢内合法范围，给整个身体留出余量，因此被写坏或旧版本的
     * 存档也不会把人塞进地板或顶板，归位之后下一刻的 {@link #containsPassenger} 必然成立，乘客自此随厢移动。
     *
     * <p>恢复时才调用 {@code requestTeleport}，相对旋转增量为零以保留视角。
     * 正常运行不会调用本方法；恢复后复位纵向速度与下落距离，并按实际脚高决定落地状态。
     * 尚未建立连接的服务端玩家（没有网络处理器，例如测试里的替身实体）直接改坐标，避免在服务端刻里空指针。
     */
    private void putPassengerBack(PlayerEntity p, Vec3d offset) {
        double margin = Math.max(0, 1.3 - p.getWidth() / 2.0 - .01);
        double maxFeet = Math.max(.2, 2.8 - p.getHeight() - .01);
        double x = getX()+MathHelper.clamp(Double.isFinite(offset.x) ? offset.x : 0,-margin,margin);
        double y = getY()+MathHelper.clamp(Double.isFinite(offset.y) ? offset.y : .2,.2,maxFeet);
        double z = getZ()+MathHelper.clamp(Double.isFinite(offset.z) ? offset.z : 0,-margin,margin);
        if (p instanceof ServerPlayerEntity sp && sp.networkHandler != null)
            sp.networkHandler.requestTeleport(x, y, z, 0, 0,
                    java.util.EnumSet.of(PositionFlag.X_ROT, PositionFlag.Y_ROT)); // 相对旋转增量为零，保留视角
        else p.setPosition(x, y, z);
        p.fallDistance = 0; // 清零下落距离：掉队期间累积的下落高度不能在归位瞬间结算成摔落伤害
        p.setVelocity(p.getVelocity().multiply(1, 0, 1)); // 抹掉纵向速度，避免归位后仍带着下落速度
        p.setOnGround(Math.abs(y - getY() - .2) < .025);
    }

    /**
     * 受理一次停靠请求。请求来源可以是楼层门右键、线路内其它控制点或轿厢内选站面板的网络包。
     *
     * @param button 目标站点根方块位置（楼层门底部中心方块）
     * @return 被受理、或与当前目标/队列中已有条目重复时 true；线路不存在、朝向不一致、
     *         本线路轿厢数不为 1、目标不是本线路站点，或队列已达 {@link ElevatorParameters#MAX_REQUESTS} 时 false
     *
     * <p>副作用：修改状态机内部的请求队列（不改方块、不发包）。重复请求被合并，因此连续右键不会撑满队列。
     */
    public boolean requestStop(BlockPos button) {
        ElevatorLine line = line();
        if (line == null || line.facing() != facing() || line.cabins(getWorld()).size() != 1 || !line.stops().contains(button)) return false;
        return controller.request(new ElevatorController.Stop(button.asLong(), button.getY()), getY());
    }

    /**
     * 当前的"停靠计划"：正在执行的目的站在前，排队中的站点随后，都用楼层门根方块坐标表示。
     *
     * <p>面板用它把"已经加入计划"的按钮标红；顺序就是实际处理顺序，因此客户端不需要自己推断。
     *
     * @return 不可变坐标列表；没有计划时返回空列表
     */
    public List<BlockPos> plannedStops() {
        var plan = new ArrayList<BlockPos>();
        var target = controller.target();
        if (target != null) plan.add(BlockPos.fromLong(target.id()));
        for (var stop : controller.pending()) plan.add(BlockPos.fromLong(stop.id()));
        return List.copyOf(plan);
    }

    /**
     * 处理选站面板上的"开门 / 关门"按键（服务端调用；调用方已核对乘客身份）。
     *
     * <p>开门键的语义是<b>"开门 / 中断关门"</b>，不是"呼叫本层"：
     * <ul>
     *   <li>门已全开 → 续满停留时间（相当于按住开门键）；</li>
     *   <li>正在关门 → <b>反向重新打开</b>（中断关门）；</li>
     *   <li>门已全关但轿厢停在本层（例如刚手动关门、即将出发）→ 直接开门；</li>
     *   <li>整个过程中<b>不改呼叫队列与目的站</b>：不会把本层排进队列，因此不会出现"先开走、之后再回来"。</li>
     * </ul>
     *
     * <p><b>三种可开门的情形</b>（判据统一在 {@link ElevatorController#canOpenDoor(boolean, boolean)}，
     * 客户端面板用同一条纯函数判据，因此不会出现"按钮亮着点了没反应"）：
     * <ol>
     *   <li><b>正常停靠</b>：轿厢停稳在某个完整站点上，{@link ElevatorStatus#IDLE} 且高度精确对齐。
     *       为什么必须要求"停稳"：轿厢以 0.20 或 0.50 格/刻为巡航上限运行（见 {@link #speed()}），
     *       S 形曲线的巡航段会让高度精确经过整数楼层；只看高度会让"运行途中恰好经过某层"也被判成
     *       在站点上，从而半空开门，因此这里必须看相位，不能只看坐标。</li>
     *   <li><b>故障脱困</b>：{@link ElevatorController#faulted()} 为真——断轨、朝向不一致、井道里有
     *       方块或实体障碍、区块未加载、目的站的门被拆。这时轿厢多半卡在两层之间，正常规则一律不成立，
     *       而乘客很可能被困在里面，因此<b>刻意不要求停在站点上</b>：开门键必须可用，让人能自己走出来。
     *       故障解除后 {@link ElevatorController#tick} 会把这扇门关回去，再继续原行程。</li>
     *   <li><b>无站线路脱困</b>：这条线路上<b>一扇完整的楼层门都没有</b>（典型情形：轿厢刚放到轨道上、
     *       还没建门）。此时状态机永远选不出目的站，这辆车再也不会动，而"停在站点上"必然不成立——
     *       只看前两条的话，门一关乘客就被永久锁在厢内。因此 {@code hasStations} 为假时一律受理开门。</li>
     * </ol>
     *
     * <p>两个事实（{@code atStation} 与 {@code hasStations}）都<b>在这里扫线路算出来</b>再交给状态机：
     * 只有服务端才拿得到线路（{@code railX/railZ} 不进 DataTracker），状态机侧因此保持"只吃入参、不查世界"。
     *
     * <p>关门键交给 {@link ElevatorController#forceClose()}：允许在队列为空时先把门关上、停在本层等待呼叫。
     *
     * @param open true = 开门，false = 关门
     * @return 指令被接受时 true；既没有停稳在站点上、也不处于故障、线路上又有站点时 false（调用方据此提示玩家）
     */
    public boolean doorCommand(boolean open) {
        if (getWorld().isClient) return false; // 指令只在服务端执行，客户端点击后会收到服务端下发的面板刷新
        if (open) {
            // 车体是否精确停在某个完整站点上：站点高度是整数、到站时会精确吸附到该值，
            // 因此这里的 1e-7 判定等价于"就在这一层"。线路扫不到（轨道被拆/区块未加载）时不算停在站点。
            //
            // 另外要把"这条线路上到底有没有完整的门"一并算出来交给状态机（hasStations）：
            // 刚放下的轿厢还没来得及建门时，这辆车永远选不出目的站，门一关就必须允许乘客开出来，
            // 否则人就被锁死在厢内（见 ElevatorController.canOpenDoor 的第 3 种可用情形）。
            // 注意这两个事实都只能在这里（服务端）算：客户端的 railX/railZ 不进 DataTracker，恒为 0。
            ElevatorLine currentLine = line();
            boolean atStation = false;
            if (currentLine != null)
                for (BlockPos stop : currentLine.stops())
                    if (Math.abs(stop.getY() - getY()) <= ElevatorParameters.POSITION_EPSILON) { atStation = true; break; }
            boolean hasStations = currentLine != null && !currentLine.stops().isEmpty();
            boolean allowed = controller.canOpenDoor(atStation, hasStations);
            if (!allowed) return false; // 运行途中、且没有故障：不受理
            return controller.forceOpen();
        }
        return controller.forceClose();
    }

    /** @return 恒为 true：轿厢可被准星选中，否则无法右键打开选站面板 */
    @Override
    public boolean canHit() { return true; }

    /** @return 恒为 false：空心外壳碰撞由 EntityViewMixin 注入 {@link #collisionBoxes()}，实心包围盒会把乘客挡在轿厢外 */
    @Override
    public boolean isCollidable() { return false; } // Hollow collision supplied by EntityViewMixin.

    /** @return 恒为 false：禁止被玩家或活塞推动，否则位置会脱离状态机控制而破坏到站精度与门联锁 */
    @Override
    public boolean isPushable() { return false; }

    /**
     * 轿厢门此刻是否完全打开。
     *
     * <p>为什么同时接受 {@link ElevatorController.Phase#OPENING}、{@link ElevatorController.Phase#OPEN}
     * 与 {@link ElevatorController.Phase#OVERLOAD}：
     * 三者在门进度上都是"已经全开"——{@code OPENING} 是门开到 1 之后、状态机还没切到 {@code OPEN} 的
     * 那一小段（<b>到站开门后的第一刻就是 OPENING</b>），而 {@code OVERLOAD} 是超载期间门一直保持全开
     * 的状态（见 {@code Phase#OVERLOAD}）。只看 {@code OPEN} 会让"刚到站"或"超载中"那几刻的判定
     * 莫名其妙地失败（例如楼层门联锁、开门键可用性、回收）。
     *
     * <p>为什么还要看相位而不是只看 {@code door == 1}：故障脱困时门可能停在半开，而"门正在关"的
     * {@code CLOSING} 相位下进度也可能刚好还等于 1；把相位一并检查，语义才是"完全打开且不是在关"。
     *
     * @return 门进度为 1 且相位处于开门侧时为 true
     */
    private boolean doorsOpen() {
        return dataTracker.get(DOOR) >= .999f
                && (phase() == ElevatorController.Phase.OPENING || phase() == ElevatorController.Phase.OPEN
                    || phase() == ElevatorController.Phase.OVERLOAD);
    }

    /**
     * 主手右键交互：满足条件时回收轿厢；乘客右键打开选站面板；其余情况把这次点击<b>放行</b>给身后的方块。
     *
     * @param player 交互玩家
     * @param hand 交互手；非主手直接返回 PASS，让原版继续处理
     * @return 真正用掉了这次点击时返回 SUCCESS（回收、或打开选站面板）；否则返回 PASS
     *
     * <p>副作用（均在服务端）：非创造模式掉落本型号的轿厢物品（{@link #cabinItem()}）、移除本实体，
     * 或通过 {@link ElevatorNetworking#open} 下发选站面板站点列表。
     *
     * <p>回收条件：潜行、主手空、厢内没有其它实体算作乘客（见 {@link #containsPassenger}，
     * 生物同样计入；玩家自己站在厢内时也算一名乘客，因此这也等于要求人在轿厢外）。
     * <b>刻意不看相位与门进度</b>：运行中、门正在开或正在关、故障暂停且有未完成行程时都能回收——
     * 判据只有上面那三条，没有"正在去某层就收不走"这类守卫（文档早期版本曾如此描述，那是错的）。
     * 另一条硬性约束是<b>只能由服务端判定</b>：客户端一律返回 PASS，否则单机/联机会出现两处各自 discard。
     *
     * <p><b>为什么其余情况要返回 PASS 而不是 SUCCESS</b>：轿厢是 3 格大的空心壳，实心包围盒会把整个
     * 门洞也不算进去，玩家站在厢内朝门外点方块时，射线往往先命中轿厢的外壳/门板，于是这次点击被轿厢
     * 吞掉、身后的方块完全收不到。故障脱困时尤其要命：门只开了一半、外面正好有方块挡住门口，
     * 玩家必须先把那块方块拆掉才能出去，而"点不动"会让人以为卡死了。放行之后，原版会继续把这次右键
     * 派发给射线打到的方块——拆方块、开门、放方块都恢复正常。
     *
     * <p><b>乘客点轿厢的任何地方都开面板</b>——不分位置、也不看手里拿什么（客户端
     * {@code CabinCrosshairMixin} 会把乘客的准星一律改写成本厢，因此这里对乘客是无条件开面板）。
     * 站在外面的非乘客只收到一条"请进入轿厢"的提示，并返回 PASS 把点击让给身后的方块。
     *
     * @see #doorsOpen() 门是否完全打开
     */
    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (getWorld().isClient) return ActionResult.PASS; // 客户端不判定，一律放行：真正是否消费由服务端回包决定

        // 回收：潜行 + 主手空 + 厢内没有任何乘客。这里不看相位、不看门进度（运行中同样允许），
        // 也不要在这里加"正在运行就拒绝"之类的守卫——见方法注释里的说明。
        if (player.isSneaking() && player.getStackInHand(hand).isEmpty()
                && getWorld().getOtherEntities(this, getBoundingBox(), this::containsPassenger).isEmpty()) {
            if (!player.isCreative()) dropItem(cabinItem()); // 掉回本型号物品：高速车回收成高速车，观光车回收成观光车
            discard();
            return ActionResult.SUCCESS;
        }
        if (containsPassenger(player)) { // 乘客点轿厢的任何地方 = 打开选站面板（不分位置、不看手里拿什么）
            ElevatorNetworking.open((ServerPlayerEntity) player, this);
            return ActionResult.SUCCESS;
        }
        player.sendMessage(Text.translatable("message.easyelevator.enter"), true); // 非乘客只提示如何进入，不泄漏站点信息
        return ActionResult.PASS; // 只发了条提示、没消费这次点击：放行给身后的方块
    }

    /**
     * 每刻驱动状态机并同步结果，是服务端权威的唯一入口。
     *
     * <p>执行顺序（不可调换）：先记录上一刻门进度（客户端与渲染插值用）；客户端 tick 到此结束，只读同步数据与运动包。
     * 服务端随后扫描当前线路并判定唯一性，先按乘客名册把归位的乘客放回厢内（必要时本刻保持静止，见
     * {@link #tickPassengers()}），再数一遍厢内玩家数（限载判定用，见 {@link #overloaded()}），
     * 然后调用 {@link ElevatorController#tick(double, ElevatorController.Environment)}
     * 取得本刻的目标 Y，再按位移带乘客一起移动，最后同刻刷新楼层门联锁、写回 DataTracker、发包、播音效、触发事件。
     *
     * <p>副作用（仅服务端）：修改本实体与乘客的位置/下落距离、设置 DataTracker 字段、刷新线路内所有站点的
     * 楼层门方块状态、发送运动包、播放音效、触发 {@link ElevatorEvents} 回调。
     */
    @Override
    public void tick() {
        previousDoor = dataTracker.get(DOOR); // 先把当刻门进度留作下一刻的插值起点
        super.tick();
        if (getWorld().isClient) return; // 客户端不跑状态机，只消费 DataTracker 与 motion_frame 包
        var before = controller.phase(); // 记下旧 Phase，用于本刻末尾判断是否需要播放开关门音效与触发事件
        final ElevatorLine currentLine = line();
        final boolean unique = currentLine != null && currentLine.facing() == facing() && currentLine.cabins(getWorld()).size() == 1; // 线路存在、朝向一致、且恰好一个轿厢，才允许运动
        // 乘客名册必须在状态机之前处理：本刻是否允许移动、以及归位乘客下一刻能否被承托，都取决于这一趟的结果。
        final boolean waitingForPassengers = tickPassengers();

        // 厢内乘客名单必须在状态机之前、更要在 setPosition 之前收集：位置一变包围盒就选不中他们，
        // 而这份名单下面还要用来带乘客一起移动。顺带在同一趟里数出厢内玩家数——
        // 限载判定（Environment.outOfPassengerNumLimit）与门动作用的是同一份计数，
        // 不能数两次（两次数出来的结果可能在读档归位那一瞬不同）。
        // 数的是"这一刻站在厢内的玩家"（containsPassenger），与上面维护的乘客名册不是一回事：
        // 名册保留掉线的人，超载只看此刻车里的人。
        List<Entity> riders = getWorld().getOtherEntities(AbstractCabinEntity.this, getBoundingBox(), AbstractCabinEntity.this::containsPassenger);
        int ridersInCabin = 0;
        for (Entity e : riders)
            if (e instanceof PlayerEntity) ridersInCabin++;
        passengerNum = ridersInCabin;

        double nextY = controller.tick(getY(), new ElevatorController.Environment() {
            /**
             * 站点是否仍然有效：门被拆、3x3 不完整、或该门已不再对应本轿厢所在轨道与高度时判定失效。
             * 失效站点会被状态机从请求队列移除；若它是当前目标，则行程暂停为 BLOCKED 而不会半空开门。
             *
             * @param stop 待校验的站点
             * @return 有效或暂时无法判定（区块未加载）时 true，确定失效时 false
             */
            @Override
            public boolean valid(ElevatorController.Stop stop) {
                // A temporary gap must pause the trip, not erase its destination.
                BlockPos p = BlockPos.fromLong(stop.id());
                if (!getWorld().isChunkLoaded(p)) return true; // 区块卸载只是暂时无法判定：返回 true 让行程暂停而不是丢失目的地
                var s = getWorld().getBlockState(p);
                return LandingDoorBlock.isRoot(s) && LandingDoorBlock.complete(getWorld(),p)
                        && LandingDoorBlock.railPos(s, p).equals(new BlockPos(railX, stop.y(), railZ)); // 门必须仍属于本线路且高度一致：防止同高度被换成别的轨道/朝向的门后误停
            }
            /**
             * 从 from 移动到 to 是否被允许：断轨、朝向改变、目标失效、井道有方块或实体障碍都返回 false。
             *
             * @param from 当前轿厢底部 Y（格）
             * @param to 本刻目标底部 Y（格）
             * @return 允许移动时 true；false 会让状态机进入 BLOCKED（暂停而非失败，条件恢复后继续原行程）
             */
            @Override
            public boolean canMove(double from, double to) {
                // 读档后的等待窗口里名册乘客还没归位：本刻一步都不能走，否则轿厢先开走、乘客被留在空掉的井道里。
                // 与其它阻塞条件一样走 BLOCKED，因此行程、队列、门联锁语义完全不变，人一到齐就自动继续。
                if (waitingForPassengers) return false;
                return pathClear(from, to);
            }
            /**
             * 当前让轿厢走不动的原因是否已经消失（"故障是否已解除"）。
             *
             * <p>状态机在故障期间**每一步**都会问一次：乘客按开门键脱困会把相位改成 OPENING/OPEN，
             * 所以"故障还在不在"不能看相位，只能由这里回答。必须无副作用。
             *
             * <p><b>探测范围必须覆盖"到目的站那一段"</b>（实机踩坑）：曾经写成
             * {@code pathClear(getY(), getY())}（"原地一步"），而 {@code pathClear} 的扫掠体积是
             * "本刻位移"——原地一步位移为 0，井道障碍**根本不参与判定**，于是只要目的站还在，
             * 它就恒返回 true、故障立刻被判为"已解除"，表现成"按了开门键、门一晃又被关上"。
             * 现在改成把 {@code to} 取成目的高度（没有目的站时退回原地），障碍仍然存在时
             * 返回 false，故障期得以保持、门也就稳定开着。
             *
             * <p>目的站为空的两种情况仍然区分对待：线路还在（{@code currentLine != null}）= 空闲待命，
             * 不算故障；线路扫不出来（轨道或整条线路的门被拆光）= 这辆车永远不会再动了，
             * 返回 false 以开放"开门脱困"。
             *
             * @return 可以立刻继续原行程时为 true
             */
            @Override
            public boolean canResume() {
                if (waitingForPassengers) return false; // 还在等读档前的乘客归位：仍算受阻
                if (controller.target() == null) {
                    // 目的站为空时**不能看相位**（这正是"门开了又立刻关"的真凶，实测逐刻日志定位）：
                    // 乘客按开门键会把相位改成 OPENING/OPEN，若拿"相位是不是 BLOCKED"当判据，
                    // 门一开这条就不再生效、canResume 翻成 true，于是把刚开满的脱困门立刻关回去。
                    //
                    // 正确判据只看"有没有可恢复的**行程**"：
                    //   · 相位仍是 MOVING = 真正的空闲待命（关着门停在本层等呼叫）→ 线路还在就不算故障；
                    //   · 相位在 OPEN/OPENING/CLOSING = 门正开着或正在动，这本身就说明处于脱困流程中，
                    //     目的站又为空（门被拆 / 整条线路被拆光）→ 没有任何可恢复的行程，返回 false。
                    if (controller.phase() != ElevatorController.Phase.MOVING) return false;
                    return currentLine != null && unique;
                }
                // 有目的站：按"从现在到目的站"整段判可通行（含井道障碍），而不是只看脚下那一格
                return pathClear(getY(), controller.target().y());
            }

            /**
             * 本厢此刻是否超载。判据只有一条，写在 {@link AbstractCabinEntity#overloaded()} 里：
             * 限载人数为正、且厢内玩家数超过它。<b>限载 0 或负数 = 不限载</b>，
             * 因此普通 / 高速 / 观光三型永远不会因为"车里有个人"而被判超载。
             *
             * @return 超载时为 true；状态机会据此把相位切到
             *         {@link ElevatorController.Phase#OVERLOAD}（门保持全开、不派发行程）
             */
            @Override
            public boolean outOfPassengerNumLimit() { return overloaded(); }

            /**
             * 井道在 {@code [from, to]} 这一段是否可通行：线路唯一且朝向一致、扫过的每一格轨道都在、
             * 井道预留空间内没有方块或非乘客实体。
             *
             * <p>抽出来供 {@link #canMove} 与 {@link #canResume} 共用，保证"能不能走"与"故障好没好"
             * 永远是同一套判据，不会出现"状态机以为通了、实际 canMove 仍然拒绝"的分叉。
             * 纯查询、无副作用。
             *
             * @param from 起点底部 Y（格）
             * @param to 终点底部 Y（格）
             * @return 可通行时 true
             */
            private boolean pathClear(double from, double to) {
                if (!unique || controller.target() == null
                        || !currentLine.stops().contains(BlockPos.fromLong(controller.target().id()))) return false; // 线路不再唯一、或目标站点已被拆走/移出线路
                int bottom = MathHelper.floor(Math.min(from, to)+.0001); // 扫过的整数层范围；±0.0001 抵消恰好落在整格高度时的浮点误差
                int top = MathHelper.ceil(Math.max(from, to)-.0001);
                for (int y = bottom; y <= top; y++)
                    if (!ElevatorLine.matches(getWorld(), new BlockPos(railX,y,railZ), facing())) return false; // 任一格断轨或朝向不一致即阻塞：不支持转弯、斜轨
                Box swept = getBoundingBox().union(getBoundingBox().offset(0, to-from, 0)).contract(.001); // 本刻扫掠体积；内缩 0.001 格避免与轨道/门框面接触被误判为障碍
                if (!spaceClear(swept)) return false;
                // Stop for non-riders in the swept shell rather than crushing them.
                for (Entity e : getWorld().getOtherEntities(AbstractCabinEntity.this, swept, e -> !e.isSpectator() && !(e instanceof AbstractCabinEntity))) { // 排除旁观者与其它轿厢（this 已被 getOtherEntities 排除）；本厢乘客在下一行单独放行
                    if (containsPassenger(e)) continue; // 乘客随厢移动，不算障碍
                    for (Box shell : collisionBoxes()) // 用进出前后两段外壳求交，避免高速移动时穿过实体
                        if (shell.union(shell.offset(0, to-from, 0)).intersects(e.getBoundingBox())) return false;
                }
                return true;
            }
            /**
             * 轿厢门口是否有活体（防夹检测）。
             *
             * @return 门口区域内存在非旁观活体时 true；状态机据此在关门过程中改为重新开门，并保留被中断的请求
             */
            @Override
            public boolean doorwayBlocked() {
                // 检测区域：局部 X ±1.3、Y 0.2..2.8、Z 从门扇后缘 CABIN_DOOR_BACK_Z-0.15（=0.95）到 1.6 格，略伸出轿厢正面，以便发现贴着门框站立的活体
                return !getWorld().getOtherEntities(AbstractCabinEntity.this, localBox(-1.3,.2,ElevatorParameters.CABIN_DOOR_BACK_Z-.15,1.3,2.8,1.6),
                        e -> !e.isSpectator() && e instanceof LivingEntity).isEmpty(); // 只算 LivingEntity：掉落物、矿车等不触发防夹
            }
            /**
             * 精确到站回调，由状态机在 Y 等于站点 Y 的当刻调用一次。
             *
             * @param stop 已到达的站点（id 为根方块打包坐标，y 为站点高度）
             *
             * <p>副作用：在轿厢位置播放到站音效，并触发 {@link ElevatorEvents#ARRIVED} 供扩展使用。
             */
            @Override
            public void arrived(ElevatorController.Stop stop) {
                // 到站提示音只此一处：音色由轿厢停靠的那扇门决定（见 arrivalChime）。
                // 整个模组不再有"硬编码的到站音效"——那会让"我把这扇门的提示音关了"变成一句空话。
                // 必须把 stop.y() 传进去：本回调执行时轿厢坐标<b>还没</b>被写回这一小步的位移
                // （ElevatorController 先回调、AbstractCabinEntity 后 setPosition），拿 getY() 去猜楼层会漏音。
                arrivalChime(stop.y()); ElevatorEvents.ARRIVED.invoker().onArrival(AbstractCabinEntity.this, stop.y());
            }
        });
        double dy = nextY - getY(); // 本刻位移（格）：必须在 setPosition 之前算出，之后 getY() 已是新值
        if (dy != 0) {
            setPosition(getX(), nextY, getZ());

            // Include the final arrival step even when the controller already switched to OPENING.
            for (Entity rider : riders) carryPassenger(rider, dy);
        }
        motionHistory.record(getWorld().getTime(), getY());
        dataTracker.set(PHASE, controller.phase().ordinal()); dataTracker.set(DOOR, controller.door()); // 写回同步字段：客户端据此渲染局部 Phase 表现（门动画、载客指示）
        dataTracker.set(TARGET_Y, controller.target() == null ? Integer.MIN_VALUE : controller.target().y()); // 目标高度供客户端显示目的楼层；Integer.MIN_VALUE 表示当前无目标
        // 停靠计划（目的站 + 队列）变化时推给客户端：面板里用红色标出"已加入计划"的站点。
        // 只在变化那一刻发包（新请求、到站消耗、站点失效都算），因此不会每刻刷包，也不会给没开面板的乘客弹出界面。
        List<BlockPos> plan = plannedStops();
        if (!plan.equals(lastPlan)) { lastPlan = plan; ElevatorNetworking.syncPanel(this, plan); }
        // 厅外呼叫（楼层门上的上/下按钮）的点亮状态：只在集合变化的那一刻推给站点附近的客户端。
        syncHallStates();
        // 楼层显示：只在经过或到达一层时变化（见 FloorIndicator），写进同步字段供选站面板、
        // 楼层门框顶部与轿厢内的模拟面板显示；同一 tick 内先更新运动、再算层号，显示始终跟得上。
        updateFloorNumber(currentLine);
        // Update landing locks in the same server tick as the car's door/motion state.
        // 同刻刷新楼层门联锁：门只在轿厢精确到站且轿厢门正在打开时才开，放在移动之后可避免出现"轿厢还在动、门已开"的一刻差值
        if(currentLine!=null) for(BlockPos door:currentLine.stops()) LandingDoorBlock.refresh(getWorld(),door);
        if (dy != 0) motionSettleTicks = ElevatorParameters.MOTION_SETTLE_TICKS; // 移动中每刻重置：停车后再补发 MOTION_SETTLE_TICKS 个静止样本
        if (dy != 0 || motionSettleTicks > 0) {
            ElevatorNetworking.syncMotion(this); // 运动包带绝对 double 高度与乘客相对地板高度；客户端只在本机已知样本间插值，绝不外推
            if (dy == 0) motionSettleTicks--;
        }
        if (before != controller.phase()) {
            ElevatorEvents.PHASE_CHANGED.invoker().onChange(this, before, controller.phase()); // 事件在状态已全部写回后触发，订阅者看到自洽的状态
        }
    }

    /**
     * 播放"本层那扇楼层门"配置的<b>到站提示音</b>——全模组唯一决定到站响什么的地方。
     *
     * <p>为什么音色要由楼层门决定、而不是由轿厢决定：提示音是<b>每扇门各不相同</b>的装修属性
     * （同一栋楼里大堂那扇门到站想响一声铃、设备层想安静），而轿厢是一台会跑遍所有楼层的设备。
     * 因此这里按"轿厢停在哪一站"找到那扇门的方块实体，由它自己解释成音效事件
     * （{@code LandingDoorBlockEntity#arrivalEvent}）。</p>
     *
     * <p><b>为什么楼层高度要由调用方传进来，而不是用 {@code getY()} 现取</b>：
     * {@link ElevatorController} 的到站回调发生在"这一小步的位移被写回实体之前"——
     * 状态机先 {@code y = target.y(); env.arrived(target);}，本类随后才算
     * {@code dy = nextY - getY()} 并 {@code setPosition}。于是回调执行时 {@code getY()} 还停在
     * <b>最后一步之前</b>的位置，与站点高度的差恰好是"最后一步迈了多远"。
     * 拿它去按 1e-7 容差匹配站点，就会出现"最后一步很小就响、最后一步大就不响"的时好时坏——
     * 实机表现正是"在这一层叫梯有声、从上一层坐下来没声"。回调本来就拿到了权威的站点高度，
     * 直接用它是唯一稳妥的做法。</p>
     *
     * <p>为什么只认"同一高度上的完整门"：轿厢在楼层之间时不应该由某扇门替它发声，
     * 而 {@link ElevatorLine#stops()} 给出的正是"完整 3×3 门"的根坐标，用它匹配天然排除了残门。
     * 找不到门（线路被拆、区块未加载、故障脱困停在半层）时安静返回，不做任何兜底发声——
     * 那种情况下本来也没有"哪扇门的设置"可以遵循。</p>
     *
     * <p>出厂默认是"开启 + 默认音效"（{@link DoorArrivalSound#DEFAULT}），因此新放置的门到站会响；
     * 本功能之前放置的门存档里没有这两个字段，{@link DoorArrivalSound#readNbt} 对缺失的开关回退到
     * 出厂默认（发声），所以那些门升级后照旧会响。玩家可以随时把每扇门的提示音关掉或换成别的声音。</p>
     *
     * <p>纯服务端：声音由 {@code World.playSound} 广播给附近客户端；客户端只按音效 ID 播放。
     *
     * @param stationY 刚到达的站点高度（格），由状态机在到站回调里给出
     */
    private void arrivalChime(int stationY) {
        if (getWorld().isClient) return; // 只由服务端权威播放：客户端各播一次会变成双重回声
        LandingDoorBlockEntity door = dockedDoorAt(stationY);
        if (door == null) return;
        SoundEvent event = door.arrivalEvent(); // 该门没开提示音、或自定义槽位还没有音频时返回 null
        if (event != null) sound(event);
    }

    /**
     * 找出高度为 {@code stationY} 的那扇楼层门根方块实体。
     *
     * <p>匹配条件是<b>同一高度</b>（容差 {@link ElevatorParameters#POSITION_EPSILON} 格，与到站判定同源）
     * 且那扇门确实在 {@link ElevatorLine#stops()} 里。用高度而不是坐标去认：一条线路上每个高度至多一扇门
     * （见 {@code LandingDoorBlock#getPlacementState} 的更远距离校验），而且轿厢的水平位置由轨道朝向唯一决定，
     * 因此"同高度"等价于"同一扇门"，但不必再算一遍几何。
     *
     * <p>注意入参是<b>站点高度</b>而不是轿厢当前位置：到站回调执行时轿厢坐标还没写回最后一步的位移，
     * 拿 {@code getY()} 会时好时坏（详见 {@link #arrivalChime} 的说明）。
     *
     * <p>纯查询，无副作用；线路无效或没有任何一站在该高度时返回 null。
     *
     * @param stationY 站点高度（格）
     * @return 该高度上的门方块实体；没有则返回 null
     */
    private LandingDoorBlockEntity dockedDoorAt(int stationY) {
        ElevatorLine line = line();
        if (line == null) return null;
        for (BlockPos stop : line.stops()) {
            if (Math.abs(stop.getY() - stationY) > ElevatorParameters.POSITION_EPSILON) continue;
            return getWorld().getBlockEntity(stop) instanceof LandingDoorBlockEntity door ? door : null;
        }
        return null;
    }

    /**
     * 在轿厢当前位置播放一次性音效。
     *
     * @param event 音效事件
     *
     * <p>副作用：向周围所有客户端广播声音，作用于原版“方块”音量分类，使用
     * {@link ElevatorParameters#EVENT_VOLUME} 与 {@link ElevatorParameters#SOUND_PITCH} 作为音量与音高。
     */
    private void sound(SoundEvent event) { getWorld().playSound(null, getX(), getY(), getZ(), event, SoundCategory.BLOCKS, ElevatorParameters.EVENT_VOLUME, ElevatorParameters.SOUND_PITCH); }

    /**
     * 检查井道预留空间（3x3 格）是否可通行。
     *
     * @param box 世界坐标下待检查的体积（格）
     * @return 完全空闲时 true；越界、区块未加载、存在碰撞方块、或范围内有其它轿厢时 false
     *
     * <p>不主动加载区块：任一被覆盖的区块未加载就返回 false，让状态机进入 BLOCKED 等待，
     * 因此电梯不会为了通行而触发区块加载或额外的世界生成。
     */
    public boolean spaceClear(Box box) {
        if (box.minY < getWorld().getBottomY() || box.maxY > getWorld().getTopY() || !getWorld().getWorldBorder().contains(box)) return false; // 高度与世界边界外一律不可通行
        for (BlockPos p : BlockPos.iterate(MathHelper.floor(box.minX), MathHelper.floor(box.minY), MathHelper.floor(box.minZ),
                MathHelper.floor(box.maxX), MathHelper.floor(box.maxY), MathHelper.floor(box.maxZ)))
            if (!getWorld().isChunkLoaded(p)) return false;
        for (BlockPos p : BlockPos.iterate(MathHelper.floor(box.minX),MathHelper.floor(box.minY),MathHelper.floor(box.minZ),
                MathHelper.floor(box.maxX),MathHelper.floor(box.maxY),MathHelper.floor(box.maxZ))) {
            var state=getWorld().getBlockState(p);
            if(state.isOf(Easyelevator.LANDING_DOOR) && LandingDoorBlock.belongsToCabin(getWorld(),p,state,this)) continue; // 忽略本线路自己的楼层门：到站时轿厢正面必然与门框重叠，否则永远无法停靠
            VoxelShape shape=state.getCollisionShape(getWorld(),p,net.minecraft.block.ShapeContext.of(this)); // 按方块真实碰撞形状判定；ShapeContext.of(this) 保证依赖实体的形状（如栅栏）计算正确
            for(Box part:shape.getBoundingBoxes()) if(part.offset(p).intersects(box)) return false;
        }
        return getWorld().getEntitiesByClass(AbstractCabinEntity.class, box, e -> e != this && !e.isRemoved()).isEmpty(); // 同线路不应有第二个轿厢（任何型号都算），这里兜底防止两台轿厢互相穿模
    }

    /** Geometry is in blocks. The local front (+Z) is rotated to the rail facing.
     *  把轿厢局部坐标盒（原点 = 底部中心，+Z = 门口，单位格）按轨道朝向旋转到世界坐标。
     *
     * @param x1 局部坐标一角的 X（格）
     * @param y1 局部坐标一角的 Y（格，相对轿厢底部）
     * @param z1 局部坐标一角的 Z（格，+Z 为门口）
     * @param x2 对角点的 X（格）
     * @param y2 对角点的 Y（格）
     * @param z2 对角点的 Z（格）
     * @return 世界坐标下的轴对齐包围盒；两个角点顺序可任意，方法内部取 min/max 归一
     */
    public Box localBox(double x1, double y1, double z1, double x2, double y2, double z2) {
        int fx = facing().getOffsetX(), fz = facing().getOffsetZ();
        // 绕 Y 轴旋转，使局部 +Z 对齐轨道朝向 (fx,fz)：x' = x*fz + z*fx，z' = -x*fx + z*fz
        double ax = x1*fz + z1*fx, az = -x1*fx + z1*fz;
        double bx = x2*fz + z2*fx, bz = -x2*fx + z2*fz;
        return new Box(getX()+Math.min(ax,bx), getY()+y1, getZ()+Math.min(az,bz), getX()+Math.max(ax,bx),getY()+y2,getZ()+Math.max(az,bz));
    }

    /**
     * 轿厢的空心外壳碰撞盒集合（世界坐标，单位格）；供 EntityViewMixin 注入碰撞、障碍扫描与实体防夹判定使用。
     *
     * <p>外壳构成：地板 Y 0..0.2、顶板 Y 2.8..3.0（净高 2.6 格）、两侧壁 |X| 1.3..1.5、背板局部 Z=-1.5..-1.3；
     * 正面一律止于 {@link ElevatorParameters#CABIN_FRONT_Z}=1.3 格，比楼层门后缘 1.3125 格内收 0.0125 格，
     * 避免门与门框重叠闪烁。
     *
     * <p>门是<b>两扇对开滑门</b>（{@link SlidingDoor}），门洞就是整个正面（|X| ≤ 1.3）：
     * 两扇门扇外缘固定在侧壁内侧，内缘随进度向两侧移开，全开时宽度归零、门洞全通。
     * 与楼层门同一套做法，因此里外两道门看起来一致。碰撞与渲染取自同一份纯算术，
     * 所以"看得见的门"就是"挡得住人的门"：关门时两扇拼满正面（只留中缝），全开时不再生成门扇。
     *
     * @return 每刻新建的列表；调用方只读，不可缓存（门进度每刻变化）
     */
    public List<Box> collisionBoxes() {
        List<Box> boxes = new ArrayList<>(collisionBoxesStatic());
        double front = ElevatorParameters.CABIN_FRONT_Z;
        double doorBack = ElevatorParameters.CABIN_DOOR_BACK_Z;
        float p=dataTracker.get(DOOR); // 读同步字段而非 controller：客户端与服务端据同一份门进度生成碰撞与模型
        if(SlidingDoor.visible(p)) { // 全开时两扇宽度归零，不再生成门扇（0.999 阈值避免浮点残留）
            for(boolean right:new boolean[]{false,true}) {
                double[] x=SlidingDoor.panelX(right,p);
                boxes.add(localBox(x[0],.2,doorBack,x[1],2.8,front));
            }
        }
        return boxes;
    }

    /**
     * 只含<b>静态舱体</b>（地板、顶板、两侧壁、背板）的碰撞盒，不含两扇滑门。
     *
     * <p>为什么要和 {@link #collisionBoxes()} 分开——<b>给准星穿透用</b>：
     * 乘客在厢内朝门外看时，射线会先命中<b>轿厢自己的门扇</b>（无论它是关着还是半开）。
     * 判断"射线是不是真的穿出门洞"时若把门扇也算作遮挡物，那么"从轿厢里打楼层门"永远会被
     * 轿厢门扇挡下（楼层门在轿厢门扇之外）。而门扇只是这辆车的活动部件，不该挡住乘客对外界的操作：
     * 门开着时人本来就要走出去；门关着时那也是同一辆车的门，挡不挡都不影响"这扇门能不能被从里面打掉"。
     *
     * <p>碰撞仍用完整集合 {@link #collisionBoxes()}（玩家身体照样被门扇挡住），只有"准星穿透判定"用本方法。
     *
     * @return 每刻新建的列表；不含门扇
     */
    public List<Box> collisionBoxesStatic() {
        List<Box> boxes = new ArrayList<>();
        double front = ElevatorParameters.CABIN_FRONT_Z;
        boxes.add(localBox(-1.5,0,-1.5,1.5,.2,front));      // 地板
        boxes.add(localBox(-1.5,2.8,-1.5,1.5,3,front));     // 顶板
        boxes.add(localBox(-1.5,.2,-1.5,-1.3,2.8,front));   // 左壁
        boxes.add(localBox(1.3,.2,-1.5,1.5,2.8,front));     // 右壁
        boxes.add(localBox(-1.3,.2,-1.5,1.3,2.8,-1.3));     // 背板
        return boxes;
    }

    /**
     * 把轿厢状态写入实体 NBT：RailX / RailZ / Facing / Phase / Door / Target / Queue / HallCalls /
     * Travel / TargetHallDirection / StopService / Riders。
     * 世界坐标由原版实体保存流程另行写出，这里只存状态机、线路绑定与乘客名册所需的最小信息。
     *
     * @param nbt 待写入的实体 NBT
     *
     * <p>副作用：仅填充传入的 NBT，不改运行时状态。
     */
    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putInt("RailX",railX); nbt.putInt("RailZ",railZ); nbt.putInt("Facing",facing().getId());
        nbt.putString("Phase",controller.phase().name()); nbt.putFloat("Door",controller.door());
        if (controller.target()!=null) nbt.putLong("Target",controller.target().id()); // 无目标时不写字段，读档以 contains 判定
        nbt.putString("TargetHallDirection", controller.targetHallDirection().name());
        NbtList list = new NbtList();
        for (var stop : controller.pending()) { NbtCompound s = new NbtCompound(); s.putLong("Button",stop.id()); list.add(s); } // 队列只存打包坐标：Y 可从 BlockPos 解出
        nbt.put("Queue",list);
        // 厅外呼叫：站点打包坐标 + 方向，与队列一样只存坐标（Y 从 BlockPos 解出）；
        // 服务方向一并保存，读档后不必重新判断就能继续顺路接人。
        NbtList calls = new NbtList();
        for (var call : controller.hallCalls()) { NbtCompound c = new NbtCompound(); c.putLong("Button",call.id()); c.putBoolean("Up",call.up()); calls.add(c); }
        nbt.put("HallCalls",calls);
        nbt.putString("Travel",controller.travel().name());
        var service = controller.stopService();
        if (service != null) {
            NbtCompound savedService = new NbtCompound();
            savedService.putLong("Station", service.station().id());
            savedService.putString("Served", service.served().name());
            nbt.put("StopService", savedService);
        }
        // 乘客名册：读档后必须先等这些人回到世界才能继续行程，否则轿厢会先开走、乘客落到井道里（见 tickPassengers）。
        // 只存 UUID 与相对轿厢的偏移：玩家各自的世界坐标由原版玩家存档负责，offsets 与轿厢之后走到哪里无关，
        // 因此即使是"运行时存档、再次进入时轿厢停在别处"也仍然有效。
        NbtList riders = new NbtList();
        for (var entry : passengers.entrySet()) {
            NbtCompound rider = new NbtCompound();
            // UUID 拆成两个 long 而不是 UUID 字符串：往返无损、长度固定，也不需要额外的解析容错。
            rider.putLong("Most",entry.getKey().getMostSignificantBits()); rider.putLong("Least",entry.getKey().getLeastSignificantBits());
            rider.putDouble("X",entry.getValue().x); rider.putDouble("Y",entry.getValue().y); rider.putDouble("Z",entry.getValue().z);
            riders.add(rider);
        }
        nbt.put("Riders",riders);
    }

    /**
     * 从实体 NBT 恢复线路绑定、状态机状态与乘客名册。
     *
     * @param nbt 已序列化的实体 NBT
     *
     * <p>副作用：写入 railX/railZ、同步字段 FACING/PHASE/DOOR/TARGET_Y 与 previousDoor，重置状态机内部队列，
     * 并用存档里的乘客名册打开"等待乘客归位"窗口。
     * {@link ElevatorController#restore} 会把 MOVING 降级为 BLOCKED，先校验线路再恢复运行，
     * 避免读档瞬间在错误高度继续移动；乘客名册则保证这次"继续"不会抢在乘客回到世界之前发生
     * （玩家实体一定晚于区块实体载入）；同时同步门进度，防止客户端首帧插值跳变。
     */
    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        railX=nbt.getInt("RailX"); railZ=nbt.getInt("RailZ");
        Direction direction = Direction.byId(nbt.getInt("Facing"));
        dataTracker.set(FACING, (direction.getAxis().isHorizontal()?direction:Direction.NORTH).getId()); // 非法或竖直朝向降级为 NORTH：避免后续局部坐标旋转得到零向量/非法几何
        ElevatorController.Phase phase;
        try { phase=ElevatorController.Phase.valueOf(nbt.getString("Phase")); } catch (IllegalArgumentException e) { phase=ElevatorController.Phase.BLOCKED; } // 枚举名不存在（旧存档或损坏）时按 BLOCKED 处理，暂停而不是让异常中断实体加载
        var queue = new ArrayList<ElevatorController.Stop>(); var list=nbt.getList("Queue",10); // 10 = NbtElement.COMPOUND_TYPE，标识列表元素类型
        for (int i=0; i<Math.min(list.size(), ElevatorController.MAX_REQUESTS); i++) queue.add(stop(list.getCompound(i).getLong("Button"))); // 上限 MAX_REQUESTS：旧存档可能超限，多余条目直接丢弃
        // 厅外呼叫与服务方向：旧存档没有 HallCalls/Travel 字段，getList 返回空表、valueOf 抛异常后按 NONE 处理，
        // 因此行为与旧版完全一致（没有厅外呼叫、方向由下一次请求决定）。
        var calls = new ArrayList<ElevatorController.HallCall>(); var callList=nbt.getList("HallCalls",10); // 10 = NbtElement.COMPOUND_TYPE
        for (int i=0; i<Math.min(callList.size(), ElevatorController.MAX_REQUESTS); i++) {
            NbtCompound call=callList.getCompound(i); long packed=call.getLong("Button");
            calls.add(new ElevatorController.HallCall(packed, BlockPos.fromLong(packed).getY(), call.getBoolean("Up")));
        }
        ElevatorController.Travel travel;
        try { travel=ElevatorController.Travel.valueOf(nbt.getString("Travel")); } catch (IllegalArgumentException e) { travel=ElevatorController.Travel.NONE; }
        ElevatorController.StopService service = null;
        if (nbt.contains("StopService", 10)) {
            NbtCompound savedService = nbt.getCompound("StopService");
            if (savedService.contains("Station", 4)) {
                ElevatorController.Travel served;
                try { served = ElevatorController.Travel.valueOf(savedService.getString("Served")); }
                catch (IllegalArgumentException e) { served = ElevatorController.Travel.NONE; }
                service = new ElevatorController.StopService(stop(savedService.getLong("Station")), served);
            }
        }
        ElevatorController.Travel targetHallDirection;
        try { targetHallDirection = ElevatorController.Travel.valueOf(nbt.getString("TargetHallDirection")); }
        catch (IllegalArgumentException e) { targetHallDirection = ElevatorController.Travel.NONE; } // 旧存档不能猜目标来源，保留停靠
        controller.restore(phase,nbt.getFloat("Door"),nbt.contains("Target")?stop(nbt.getLong("Target")):null,
                queue,calls,travel,service,targetHallDirection);
        dataTracker.set(PHASE,controller.phase().ordinal()); dataTracker.set(DOOR,controller.door()); previousDoor=controller.door(); // 连 previousDoor 一起对齐，首帧门动画不插值
        dataTracker.set(TARGET_Y,controller.target()==null?Integer.MIN_VALUE:controller.target().y());
        recoveringPassengers.clear();
        passengers.clear(); // 名册整份来自存档；旧存档没有 Riders 字段时 getList 返回空表，因此行为与旧版一致（不等待）
        var riders=nbt.getList("Riders",10); // 10 = NbtElement.COMPOUND_TYPE
        for (int i=0; i<riders.size(); i++) {
            NbtCompound rider=riders.getCompound(i);
            passengers.put(new UUID(rider.getLong("Most"),rider.getLong("Least")),
                    new Vec3d(rider.getDouble("X"),rider.getDouble("Y"),rider.getDouble("Z")));
        }
        recoveringPassengers.addAll(passengers.keySet());
        // 有乘客才开等待窗口：等待期间轿厢保持静止，乘客一出现就被放回厢内（见 tickPassengers）。
        passengerWaitTicks = passengers.isEmpty() ? 0 : ElevatorParameters.RIDER_WAIT_TICKS;
        // 楼层显示的方向基准取读档后的实际高度：第一刻按"上行规则"取所在层，不会因为旧的方向残留而先跳一下。
        floorDirectionY=getY(); floorNumber=0;
    }

    /**
     * 由打包坐标还原站点。
     *
     * @param packed {@link BlockPos#asLong()} 打包的方块坐标；{@link ElevatorController.Stop#id()} 用的就是它，
     *               Y 可直接解出，因此存档只需一个 long 就能完整表达站点
     * @return 对应的站点描述（id 与站点高度 Y）
     */
    private static ElevatorController.Stop stop(long packed) { return new ElevatorController.Stop(packed,BlockPos.fromLong(packed).getY()); }
}
