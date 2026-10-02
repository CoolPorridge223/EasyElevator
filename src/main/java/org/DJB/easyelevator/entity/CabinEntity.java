package org.DJB.easyelevator.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
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
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorLine;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.network.ElevatorNetworking;
import java.util.ArrayList;
import java.util.List;

/**
 * 电梯轿厢实体：3x3x3 的空心轿厢，也是整条线路的服务端权威载体。
 *
 * <p>在整体架构中的位置：所有运动学都委托给纯 Java 状态机 {@link ElevatorController}
 * （不引用任何 Minecraft 类，因而可以脱离游戏单测）。本实体每刻在 {@link #tick()} 中通过匿名
 * {@link ElevatorController.Environment} 把世界查询（valid / canMove / doorwayBlocked / arrived）
 * 注入状态机，再把状态机返回的 Y 应用到实体位置；实体自身不保存速度、加速度或插值轨迹。
 * 客户端只读 {@code DataTracker} 同步字段与运动包，不参与任何运动决策。
 *
 * <p>关键不变量与约束：
 * <ul>
 *   <li>线路唯一：一段竖直连续、朝向一致的轨道列（线路）最多一个轿厢；轿厢数不为 1 时不允许移动。</li>
 *   <li>门未完全关闭（DOOR 未降到 0）不得移动；楼层门联锁由 {@link LandingDoorBlock#refresh}
 *       依据本实体 Y（误差 &lt;= {@link ElevatorParameters#POSITION_EPSILON} 格）与门进度决定。</li>
 *   <li>位置用绝对 double，经 {@code MotionFrame} 包同步，绕过原版相对位置包的定点量化。</li>
 *   <li>碰撞体不是实体包围盒：{@link #isCollidable()} 返回 false，空心外壳由 EntityViewMixin 注入
 *       {@link #collisionBoxes()}，否则实心包围盒会把乘客挡在轿厢外。</li>
 *   <li>BLOCKED 表示受阻暂停（断轨、朝向不一致、井道有方块或实体障碍、区块未加载、目的站门被拆），
 *       不是失败；条件恢复后继续原行程。</li>
 * </ul>
 *
 * <p>几何与单位：局部坐标原点在轿厢底部中心，+Z 指向门口，长度单位一律为格（方块）。
 * 轿厢中心位于轨道朝向前方 2 格，底部 Y 与被点击的轨道相同，因而与站点 Y 对齐。
 */
public class CabinEntity extends Entity {

    /** 同步给客户端的 {@link ElevatorController.Phase} 序号（{@code ordinal()}）；客户端只读，用于渲染与面板显示。 */
    private static final TrackedData<Integer> PHASE = DataTracker.registerData(CabinEntity.class, TrackedDataHandlerRegistry.INTEGER);

    /** 同步给客户端的门进度：0 表示全关、1 表示全开，无单位；与 {@link ElevatorController#door()} 同刻写入，驱动门动画与门联锁。 */
    private static final TrackedData<Float> DOOR = DataTracker.registerData(CabinEntity.class, TrackedDataHandlerRegistry.FLOAT);

    /** 同步给客户端的轨道朝向（= 轿厢门朝向）id；决定轿厢正面方向与本地几何的旋转，客户端渲染必须与服务端一致。 */
    private static final TrackedData<Integer> FACING = DataTracker.registerData(CabinEntity.class, TrackedDataHandlerRegistry.INTEGER);

    /** 同步给客户端的当前目标站点 Y（格）；无目标时用 {@link Integer#MIN_VALUE} 作哨兵，避免与任何合法高度混淆。 */
    private static final TrackedData<Integer> TARGET_Y = DataTracker.registerData(CabinEntity.class, TrackedDataHandlerRegistry.INTEGER);

    /** 确定性状态机实例：Phase、门进度、当前目标与请求队列都存放在这里，实体内不重复保存。 */
    private final ElevatorController controller = new ElevatorController();

    /** 所属线路的轨道水平坐标（方块坐标）；与 {@link #getY()} 一起构成 {@link ElevatorLine#scan} 的种子，也是线路唯一性的判定依据。 */
    private int railX, railZ;

    /** 上一刻的门进度（0..1），与当刻 DOOR 一起供 {@link #doorProgress(float)} 插值，避免 20 TPS 下门动画逐刻跳变。 */
    private float previousDoor = 1;

    /** 停车后仍需补发静止运动包的剩余刻数（刻）；不补发则客户端插值会停在最后一个运动样本上无法收敛。 */
    private int motionSettleTicks;

    /**
     * 构造轿厢实体。所属线路与初始位置随后由 {@link #initialize(BlockPos, Direction)} 或存档载入设定。
     *
     * @param type 实体类型（注册 ID 为 {@code easyelevator:cabin}）
     * @param world 所在世界
     */
    public CabinEntity(EntityType<? extends CabinEntity> type, World world) { super(type, world); setNoGravity(true); } // 禁用重力：Y 完全由状态机决定，交给原版物理会被下拽并触发下落判定

    /**
     * 初始化 DataTracker 的默认同步值：Phase = OPEN（序号 0）、门全开 1、朝向 NORTH、无目标。
     * 这些默认值会在首个服务端 tick 后立即被 {@link #tick()} 的写回覆盖，仅用于客户端在收到首个包前的渲染兜底。
     *
     * @param b 原版实体构造流程传入的 DataTracker 构建器
     */
    @Override
    protected void initDataTracker(DataTracker.Builder b) {
        b.add(PHASE, 0); b.add(DOOR, 1f); b.add(FACING, Direction.NORTH.getId()); b.add(TARGET_Y, Integer.MIN_VALUE);
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

    /**
     * 以当前所在高度为种子扫描所属线路。
     *
     * @return 线路描述（含可停靠站点列表）；种子处不是轨道或区块未加载时返回 null
     */
    public ElevatorLine line() { return ElevatorLine.scan(getWorld(), new BlockPos(railX, MathHelper.floor(getY()+.0001), railZ)); } // +0.0001 抵消浮点误差：Y 恰好停在楼层高度时 floor 可能落到下一格而扫错高度

    /**
     * 判断实体是否算本轿厢的乘客。用于随厢移动、障碍扫描豁免、门口防夹豁免与面板/网络层的身份校验。
     *
     * @param e 待判定实体
     * @return 实体位于轿厢内部时 true；旁观者、骑乘其它载具者以及被抬出净高范围者均不算乘客
     *
     * <p>横向边界取内缘 1.3 格再加 0.01 格余量，脚部高度从 0.14 格（地板面 0.2 格减容差）到 2.7 格
     * （净高 2.6 格加容差），以避免站在地板或贴墙时因浮点误差被误判为非乘客。
     */
    public boolean containsPassenger(Entity e) {
        Box b = e.getBoundingBox();
        return !e.isSpectator() && !e.hasVehicle() && b.minX >= getX()-1.31 && b.maxX <= getX()+1.31
                && b.minZ >= getZ()-1.31 && b.maxZ <= getZ()+1.31 && e.getY() >= getY()+.14 && e.getY() < getY()+2.7;
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
     * 主手右键交互：满足条件时回收轿厢；乘客右键打开选站面板；其余情况只发一条提示。
     *
     * @param player 交互玩家
     * @param hand 交互手；非主手直接返回 PASS，让原版继续处理
     * @return 主手一律返回 SUCCESS 以吞掉后续同刻的方块/物品交互；非主手返回 PASS
     *
     * <p>副作用（均在服务端）：非创造模式掉落轿厢物品、移除本实体、给玩家发送消息，
     * 或通过 {@link ElevatorNetworking#open} 下发选站面板站点列表。
     * 回收要求潜行、空手、门完全打开且厢内无其它乘客，避免把厢内玩家一起删除。
     */
    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (!getWorld().isClient) {
            if (player.isSneaking() && player.getStackInHand(hand).isEmpty() && phase() == ElevatorController.Phase.OPEN
                    && getWorld().getOtherEntities(this, getBoundingBox(), this::containsPassenger).isEmpty()) {
                if (!player.isCreative()) dropItem(Easyelevator.CABIN_ITEM);
                discard();
            } else if (containsPassenger(player)) ElevatorNetworking.open((ServerPlayerEntity) player, this);
            else player.sendMessage(Text.translatable("message.easyelevator.enter"), true); // 非乘客只提示如何进入，不泄漏站点信息
        }
        return ActionResult.SUCCESS;
    }

    /**
     * 每刻驱动状态机并同步结果，是服务端权威的唯一入口。
     *
     * <p>执行顺序（不可调换）：先记录上一刻门进度（客户端与渲染插值用）；客户端 tick 到此结束，只读同步数据与运动包。
     * 服务端随后扫描当前线路并判定唯一性，调用 {@link ElevatorController#tick(double, ElevatorController.Environment)}
     * 取得本刻的目标 Y，再按位移带乘客一起移动，最后同刻刷新楼层门联锁、写回 DataTracker、发包、播音效、触发事件。
     *
     * <p>副作用（仅服务端）：修改本实体与乘客的位置/速度/下落距离、设置 DataTracker 字段、刷新线路内所有站点的
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
                if (!unique || !currentLine.stops().contains(BlockPos.fromLong(controller.target().id()))) return false; // 线路不再唯一、或目标站点已被拆走/移出线路时立即阻塞，等待新请求恢复
                int bottom = MathHelper.floor(Math.min(from, to)+.0001); // 扫过的整数层范围；±0.0001 抵消恰好落在整格高度时的浮点误差
                int top = MathHelper.ceil(Math.max(from, to)-.0001);
                for (int y = bottom; y <= top; y++)
                    if (!ElevatorLine.matches(getWorld(), new BlockPos(railX,y,railZ), facing())) return false; // 任一格断轨或朝向不一致即阻塞：不支持转弯、斜轨
                Box swept = getBoundingBox().union(getBoundingBox().offset(0, to-from, 0)).contract(.001); // 本刻扫掠体积；内缩 0.001 格避免与轨道/门框面接触被误判为障碍
                if (!spaceClear(swept)) return false;
                // Stop for non-riders in the swept shell rather than crushing them.
                for (Entity e : getWorld().getOtherEntities(CabinEntity.this, swept, e -> !e.isSpectator() && !(e instanceof CabinEntity))) { // 排除旁观者与其它轿厢（this 已被 getOtherEntities 排除）；本厢乘客在下一行单独放行
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
                return !getWorld().getOtherEntities(CabinEntity.this, localBox(-1.3,.2,ElevatorParameters.CABIN_DOOR_BACK_Z-.15,1.3,2.8,1.6),
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
                sound(Easyelevator.ARRIVAL); ElevatorEvents.ARRIVED.invoker().onArrival(CabinEntity.this, stop.y());
            }
        });
        double dy = nextY - getY(); // 本刻位移（格）：必须在 setPosition 之前算出，之后 getY() 已是新值
        if (dy != 0) {
            List<Entity> riders = getWorld().getOtherEntities(this, getBoundingBox(), this::containsPassenger); // 先按旧位置收集乘客：位置一变包围盒就选不中他们
            setPosition(getX(), nextY, getZ());
            for (Entity rider : riders) {
                // Explicit position sync prevents vanilla flying checks and descent fall damage.
                if (rider instanceof ServerPlayerEntity p)
                    p.networkHandler.requestTeleport(p.getX(), p.getY()+dy, p.getZ(), p.getYaw(), p.getPitch(),
                            java.util.EnumSet.of(PositionFlag.X_ROT, PositionFlag.Y_ROT)); // 只带相对旋转标志：保留玩家当前视角，且不走原版相对位置包以免触发"移动过快"回弹
                else rider.setPosition(rider.getX(), rider.getY()+dy, rider.getZ());
                rider.fallDistance = 0; // 清零下落距离：随厢上升或下降都不结算摔落伤害
                rider.setVelocity(rider.getVelocity().multiply(1, 0, 1)); // 抹掉纵向速度，避免乘客被上一刻的运动弹起或滞后
                rider.setOnGround(true); // 保持"站在地面"状态，乘客才能正常跳跃与停止水平移动
            }
        }
        dataTracker.set(PHASE, controller.phase().ordinal()); dataTracker.set(DOOR, controller.door()); // 写回同步字段：客户端据此渲染局部 Phase 表现（门动画、载客指示）
        dataTracker.set(TARGET_Y, controller.target() == null ? Integer.MIN_VALUE : controller.target().y()); // 目标高度供客户端显示目的楼层；Integer.MIN_VALUE 表示当前无目标
        // Update landing locks in the same server tick as the car's door/motion state.
        // 同刻刷新楼层门联锁：门只在轿厢精确到站且轿厢门正在打开时才开，放在移动之后可避免出现"轿厢还在动、门已开"的一刻差值
        if(currentLine!=null) for(BlockPos door:currentLine.stops()) LandingDoorBlock.refresh(getWorld(),door);
        if (dy != 0) motionSettleTicks = ElevatorParameters.MOTION_SETTLE_TICKS; // 移动中每刻重置：停车后再补发 MOTION_SETTLE_TICKS 个静止样本
        if (dy != 0 || motionSettleTicks > 0) {
            ElevatorNetworking.syncMotion(this); // 运动包带绝对 double 高度与乘客相对地板高度；客户端只在本机已知样本间插值，绝不外推
            if (dy == 0) motionSettleTicks--;
        }
        if (before != controller.phase()) {
            if (controller.phase() == ElevatorController.Phase.CLOSING) sound(Easyelevator.DOOR_CLOSE); // 关门音效在开始关门的当刻播放
            if (controller.phase() == ElevatorController.Phase.OPENING) sound(Easyelevator.DOOR_OPEN); // 开门音效在到站/防夹重新开门的当刻播放
            ElevatorEvents.PHASE_CHANGED.invoker().onChange(this, before, controller.phase()); // 事件在状态已全部写回后触发，订阅者看到自洽的状态
        }
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
        return getWorld().getEntitiesByClass(CabinEntity.class, box, e -> e != this && !e.isRemoved()).isEmpty(); // 同线路不应有第二个轿厢，这里兜底防止两台轿厢互相穿模
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
     * 避免门与门框重叠闪烁。门扇局部 Z 为 1.1..1.3（厚 0.2 格），随门进度 p 从中线向两侧缩回。
     *
     * @return 每刻新建的列表；调用方只读，不可缓存（门进度每刻变化）
     */
    public List<Box> collisionBoxes() {
        List<Box> boxes = new ArrayList<>();
        double front = ElevatorParameters.CABIN_FRONT_Z;
        double doorBack = ElevatorParameters.CABIN_DOOR_BACK_Z;
        boxes.add(localBox(-1.5,0,-1.5,1.5,.2,front));
        boxes.add(localBox(-1.5,2.8,-1.5,1.5,3,front));
        boxes.add(localBox(-1.5,.2,-1.5,-1.3,2.8,front));
        boxes.add(localBox(1.3,.2,-1.5,1.5,2.8,front));
        boxes.add(localBox(-1.3,.2,-1.5,1.3,2.8,-1.3));
        float p = dataTracker.get(DOOR); // 读同步字段而非 controller：客户端与服务端据同一份门进度生成碰撞与模型
        if (p < .999f) { // 全开（p 到 1）时不再生成门扇；0.999 阈值避免浮点残留导致门扇宽度不为 0
            boxes.add(localBox(-1.3,.2,doorBack,-1.3*p,2.8,front)); // 左扇：p=0 时覆盖 -1.3..0（半扇），p=1 时缩到中线宽度为 0
            boxes.add(localBox(1.3*p,.2,doorBack,1.3,2.8,front)); // 右扇与左扇镜像
        }
        return boxes;
    }

    /**
     * 把轿厢状态写入实体 NBT：RailX / RailZ / Facing / Phase / Door / Target / Queue。
     * 世界坐标由原版实体保存流程另行写出，这里只存状态机与线路绑定所需的最小信息。
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
        NbtList list = new NbtList();
        for (var stop : controller.pending()) { NbtCompound s = new NbtCompound(); s.putLong("Button",stop.id()); list.add(s); } // 队列只存打包坐标：Y 可从 BlockPos 解出
        nbt.put("Queue",list);
    }

    /**
     * 从实体 NBT 恢复线路绑定与状态机状态。
     *
     * @param nbt 已序列化的实体 NBT
     *
     * <p>副作用：写入 railX/railZ、同步字段 FACING/PHASE/DOOR/TARGET_Y 与 previousDoor，并重置状态机内部队列。
     * {@link ElevatorController#restore} 会把 MOVING 降级为 BLOCKED，先校验线路再恢复运行，
     * 避免读档瞬间在错误高度继续移动；同时同步门进度，防止客户端首帧插值跳变。
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
        controller.restore(phase,nbt.getFloat("Door"),nbt.contains("Target")?stop(nbt.getLong("Target")):null,queue);
        dataTracker.set(PHASE,controller.phase().ordinal()); dataTracker.set(DOOR,controller.door()); previousDoor=controller.door(); // 连 previousDoor 一起对齐，首帧门动画不插值
        dataTracker.set(TARGET_Y,controller.target()==null?Integer.MIN_VALUE:controller.target().y());
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
