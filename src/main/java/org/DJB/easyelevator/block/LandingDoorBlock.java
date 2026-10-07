package org.DJB.easyelevator.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorLine;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.network.ElevatorNetworking;

/**
 * A 3x3 landing door. The bottom centre is the one and only station/controller.
 *
 * <p>楼层电梯门：一件物品生成 3 格宽 × 3 格高 × 3/16 格厚的整扇门，占满 9 个方块。
 * 底部中心方块（{@code COLUMN=1, LEVEL=0}）是唯一根方块 root，也是唯一站点 stop
 * 与唯一控制器：所有几何换算、联锁判定、拆门与掉落都以它为准，其余 8 格只是部件。
 *
 * <p>门位于轨道朝向前方 {@link #RAIL_DISTANCE} 格、与轨道同 Y；根方块到轨道朝向
 * 反方向 3 格处即该站点对应的轨道段，因此门不能脱离轨道单独存在。
 *
 * <p>门面结构：常驻<b>门框</b> + 两扇可动<b>门扇</b>，框与扇的几何全部由 {@link LandingDoorGeometry}
 * 统一给出，碰撞、轮廓与渲染共用同一份数据。
 * 门框（左右立柱 3/16 格宽、门楣 3/16 格高，立柱一直顶到门楣，四角相连）永远保留；
 * 门扇关门时各占一半门洞（正中只留 {@link LandingDoorGeometry#SEAM} 宽的细门缝 = 1/16 格），
 * 开门时向两侧门框收拢到宽度归零，因此开关门是连续滑动而不是瞬间切换。
 * 门扇进度由根方块的方块实体 {@link LandingDoorBlockEntity} 逐刻跟随在站轿厢的门进度，
 * 与轿厢自带门扇同一动画。
 *
 * <p>联锁 interlock：9 格作为一个整体共用 OPEN 状态。只有轿厢精确到站
 * （误差 ≤ {@link ElevatorParameters#POSITION_EPSILON} 格）且轿厢门正在打开时，
 * 门才交出真实碰撞；否则保留碰撞（空井道不会被右键强制打开）。OPEN 现在只表示联锁是否解除，
 * 门面外观与碰撞都由连续进度决定。
 * 注册 ID 沿用旧的 {@code easyelevator:call_button}，用于兼容旧存档与旧物品。
 */
public final class LandingDoorBlock extends HorizontalFacingBlock implements BlockEntityProvider {

    /** 方块编解码器，仅用于数据生成与序列化；方块本身无额外构造参数。 */
    public static final MapCodec<LandingDoorBlock> CODEC = createCodec(LandingDoorBlock::new);

    /**
     * 门在整扇门内的横向位置，0..2（1 = 根所在中列）。
     * 轴方向为 {@code FACING.rotateYClockwise()}：列号增大即沿该水平方向 +1 格。
     */
    public static final IntProperty COLUMN = IntProperty.of("column", 0, 2);

    /** 门在整扇门内的高度层，0..2（0 = 底行，门区域的最下方 1 格）。 */
    public static final IntProperty LEVEL = IntProperty.of("level", 0, 2);

    /** 门联锁状态：由服务端 {@link #refresh} 写入，客户端只读。它决定门扇进度是否跟随轿厢门：
     * 为假时门扇必定全关（真实碰撞），为真时门扇进度等于 {@link #leafProgress}。门面模型本身
     * 不再随它切换，视觉与碰撞都来自连续进度。 */
    public static final BooleanProperty OPEN = Properties.OPEN;

    /** 根方块到所属轨道的水平距离，单位：格（沿 FACING 的反方向计数，按方块坐标算，不是中间空 3 格）。 */
    public static final int RAIL_DISTANCE = 3;

    /**
     * @param settings 方块设置（硬度、是否不透明等），由注册处给出
     */
    public LandingDoorBlock(Settings settings) {
        super(settings);
        // 默认状态取根方块：中列、底层、关闭；FACING 必须显式赋值，否则读取状态属性会抛异常
        setDefaultState(getStateManager().getDefaultState().with(FACING,Direction.NORTH).with(COLUMN,1).with(LEVEL,0).with(OPEN,false));
    }

    /** @return 本方块对应的编解码器 */
    @Override
    public MapCodec<LandingDoorBlock> getCodec() { return CODEC; }

    /**
     * 注册 4 个状态属性：朝向、列、层、开门状态。这里没有原版门的 POWERED，
     * 楼层门的开闭完全由服务端联锁推导，不接受红石直接驱动。
     *
     * @param b 状态属性构建器
     */
    @Override
    protected void appendProperties(StateManager.Builder<Block,BlockState> b) { b.add(FACING,COLUMN,LEVEL,OPEN); }

    /**
     * 只有根方块（底部中心）持有方块实体：整扇门共用一份进度样本、一次世界查询。
     *
     * <p>其余 8 格返回 null。原版 {@code WorldChunk} 在创建方块实体后会用 {@code ifnull}
     * 判断并跳过加入世界，因此这里返回 null 是受支持的行为，不会留下半个方块实体。
     *
     * @param pos 方块坐标（只有根方块位置才会真正创建）
     * @param state 方块状态
     * @return 根方块的 {@link LandingDoorBlockEntity}；非根部件返回 null
     */
    @Override
    public BlockEntity createBlockEntity(BlockPos pos,BlockState state) {
        return isRoot(state)?new LandingDoorBlockEntity(pos,state):null;
    }

    /**
     * 判断给定状态是否为根方块（底部中心）。
     *
     * @param s 待判定状态
     * @return 是本模组的楼层门且 COLUMN=1、LEVEL=0 时为 true
     */
    public static boolean isRoot(BlockState s) { return s.isOf(Easyelevator.LANDING_DOOR) && s.get(COLUMN)==1 && s.get(LEVEL)==0; }

    /**
     * 由任意部件反推根方块坐标：先沿 COLUMN 轴回退到中列（{@code 1-column} 格，
     * 因为列 1 才是原点），再按 LEVEL 下移回底行。
     *
     * @param s 该部件的方块状态
     * @param p 该部件的方块坐标
     * @return 根方块坐标（底部中心，单位：格）
     */
    public static BlockPos root(BlockState s, BlockPos p) {
        return p.offset(s.get(FACING).rotateYClockwise(),1-s.get(COLUMN)).down(s.get(LEVEL));
    }

    /**
     * 计算该站点对应的那一段轨道坐标：根方块沿 FACING 反方向偏移 {@link #RAIL_DISTANCE} 格。
     *
     * @param s 部件状态（用其 FACING）
     * @param p 任意部件坐标
     * @return 与门同 Y 的轨道方块坐标
     */
    public static BlockPos railPos(BlockState s,BlockPos p) { return root(s,p).offset(s.get(FACING).getOpposite(),RAIL_DISTANCE); }

    /**
     * 把门内相对坐标（列、层）换算成绝对方块坐标。
     *
     * @param root 根方块坐标（底部中心）
     * @param facing 门的朝向（即轨道朝向）
     * @param column 列 0..2，沿 facing.rotateYClockwise() 递增
     * @param level 层 0..2，向上递增
     * @return 该部件的绝对方块坐标
     */
    private static BlockPos part(BlockPos root,Direction facing,int column,int level) { return root.offset(facing.rotateYClockwise(),column-1).up(level); }

    /**
     * 校验整扇门是否完整：9 格都必须存在、FACING 一致、COLUMN/LEVEL 与相对位置相符。
     *
     * <p>区块未加载直接判为不完整，而不是当成空气或"暂时算通过"：未加载时读到的
     * 状态不可信，误判会让站点在半空中被确认或让联锁在错误位置打开。
     * 只有完整的门才算一个站点（{@link ElevatorLine#scan} 依赖此判定）。
     *
     * @param world 世界
     * @param root 候选根方块坐标（底部中心）
     * @return 9 格齐全且属性自洽时为 true
     */
    public static boolean complete(World world,BlockPos root) {
        BlockState state=world.getBlockState(root);
        if(!isRoot(state)) return false;
        Direction facing=state.get(FACING);
        for(int row=0;row<3;row++) for(int col=0;col<3;col++) {
            BlockPos p=part(root,facing,col,row);
            if(!world.isChunkLoaded(p)) return false;
            BlockState s=world.getBlockState(p);
            if(!s.isOf(Easyelevator.LANDING_DOOR) || s.get(FACING)!=facing || s.get(COLUMN)!=col || s.get(LEVEL)!=row) return false;
        }
        return true;
    }

    /**
     * 放置校验与朝向推导：被点击的位置就是根方块（底部中心）。
     *
     * <p>按 4 个水平朝向依次尝试：要求朝向反方向 {@link #RAIL_DISTANCE} 格处存在同朝向的轨道
     * （{@link ElevatorLine#matches}），并且 3x3 的 9 格都可用——不超世界高度、在世界边界内、
     * 区块已加载、方块可替换，且玩家有修改权限与放置权限。
     *
     * <p>任一格不满足就返回 null（不放置任何方块），四个朝向都不匹配时给玩家发一条
     * 提示消息。为什么不逐格"尽力放置"：9 格是一个整体，缺格会破坏 complete 判定，
     * 也会把站点变成永远打不开的残门，所以宁可不生成。
     *
     * @param ctx 放置上下文，{@code ctx.getBlockPos()} 即根方块位置
     * @return 根方块状态（带推导出的 FACING），无法放置时返回 null
     */
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockPos root=ctx.getBlockPos();
        World world=ctx.getWorld();
        for(Direction facing:Direction.Type.HORIZONTAL) {
            // 仅当该朝向下 3 格处是轨道时才接受，轨道朝向反过来决定门的朝向
            if(!ElevatorLine.matches(world,root.offset(facing.getOpposite(),RAIL_DISTANCE),facing)) continue;
            for(int row=0;row<3;row++) for(int col=0;col<3;col++) {
                BlockPos p=part(root,facing,col,row);
                if(world.isOutOfHeightLimit(p) || !world.getWorldBorder().contains(p) || !world.isChunkLoaded(p)
                        || !world.getBlockState(p).isReplaceable()
                        || (ctx.getPlayer()!=null && (!world.canPlayerModifyAt(ctx.getPlayer(),p)
                        || !ctx.getPlayer().canPlaceOn(p,ctx.getSide(),ctx.getStack())))) return null;
            }
            return getDefaultState().with(FACING,facing);
        }
        // actionbar 提示：告诉玩家门必须放在轨道前方 3 格、同 Y 的底部中心，而不是静默失败
        if(!world.isClient && ctx.getPlayer()!=null) ctx.getPlayer().sendMessage(Text.translatable("message.easyelevator.door_placement"),true);
        return null;
    }

    /**
     * 根方块放置完成后补齐其余 8 格，并启动门的 1 刻周期自检。
     *
     * <p>其余 8 格由根状态派生 COLUMN/LEVEL（同一 FACING 与 OPEN），跳过根自身。
     * 使用 {@code NOTIFY_ALL} 让形状与碰撞立刻生效。随后 refresh 决定初始联锁状态，
     * 并 scheduleBlockTick(1) 进入轮询：门的开闭取决于轿厢位置与门进度，没有可靠的
     * 方块更新可依赖，只能靠服务端每刻自检。
     *
     * <p>副作用：改 8 格方块状态（写世界）、排程方块刻。仅在服务端生效。
     *
     * @param world 世界
     * @param p 根方块坐标（{@code ctx.getBlockPos()}）
     * @param s 根方块状态
     * @param placer 放置者，可能为 null（例如 GameTest 直接调用）
     * @param stack 使用的物品堆
     */
    @Override
    public void onPlaced(World world,BlockPos p,BlockState s,LivingEntity placer,ItemStack stack) {
        super.onPlaced(world,p,s,placer,stack);
        if(world.isClient || !isRoot(s)) return;
        for(int row=0;row<3;row++) for(int col=0;col<3;col++) {
            if(row==0 && col==1) continue;
            world.setBlockState(part(p,s.get(FACING),col,row),s.with(COLUMN,col).with(LEVEL,row),Block.NOTIFY_ALL);
        }
        refresh(world,p);
        world.scheduleBlockTick(p,this,1);
    }

    /**
     * 门的 1 刻周期自检：只有根方块负责驱动。
     *
     * <p>每刻重新求值联锁（{@link #refresh}）并把自己再次排程，形成轮询循环；
     * 非根部件被拆、根被替换后循环自然停止（{@link #isRoot} 不成立）。
     * 门不完整时循环仍继续，但联锁恒为关闭，门保持真实碰撞。
     *
     * <p>副作用：可能改写 9 格的 OPEN 状态并重排方块刻。
     *
     * @param s 根方块状态
     * @param world 服务端世界
     * @param p 根方块坐标
     * @param random 随机源，未使用
     */
    @Override
    protected void scheduledTick(BlockState s,ServerWorld world,BlockPos p,Random random) {
        if(!isRoot(s)) return;
        refresh(world,p);
        world.scheduleBlockTick(p,this,1);
    }

    /**
     * 找出停靠在本站点的唯一轿厢；只要有任何一条几何/线路/相位条件不满足就返回 null。
     *
     * <p>依次要求：整扇门 9 格完整；站点对应的那段轨道仍属于本线路（朝向一致）；
     * 该轨道前方 2 格处恰好有一辆朝向与轨道相同的轿厢，且车体中心与站点高度、中心位置的误差
     * 都不超过给定容差。这里<b>刻意不按相位排除</b>运行中或受阻（BLOCKED）的轿厢：
     * 正常运行时轿厢门必然全关，"现在到底能不能开门"由调用方按<b>轿厢门进度</b>把关
     * （见 {@link #mayOpen} 与 {@link #leafProgress}），而卡在楼层之间时上面那套高度过滤已经返回 null，
     * 因此不会半空开门。
     *
     * <p>为什么按车体世界坐标匹配、而不是 {@link AbstractCabinEntity#railX()} / {@code railZ()}：
     * 那两个字段只是普通成员、没有进 DataTracker，客户端上恒为 0；用它匹配会让客户端永远找不到
     * 在站轿厢，表现就是"轿厢门开得好好的、楼层门却不动"。车体中心由轨道坐标 +0.5 + FACING*2 复算
     * （与 {@link ElevatorLine#centerX} 一致，也与 {@link AbstractCabinEntity#initialize} 写入的位置一致），
     * 同一朝向下这个中心唯一对应一条轨道，因此按位置匹配与服务端按轨道字段匹配等价，
     * 而且两边都成立。
     *
     * <p>容差由调用方给出：服务端联锁传 {@link ElevatorParameters#POSITION_EPSILON}（1e-7 格）
     * 保持"精确到站"语义；门扇进度还要在客户端成立，那里坐标经过原版位置包量化，传
     * {@link ElevatorParameters#SYNC_POSITION_EPSILON}。
     *
     * <p>纯查询，无副作用。联锁判定与门扇进度共用它，保证"门开着的时刻"与"门扇跟着轿厢门动的时刻"一致。
     *
     * @param world 世界
     * @param origin 站点根方块坐标
     * @param tolerance 位置匹配容差（格），X / Y / Z 三个方向都用它
     * @return 在站轿厢；没有或不唯一时返回 null
     */
    private static AbstractCabinEntity dockedCabin(World world,BlockPos origin,double tolerance) {
        if(!complete(world,origin)) return null;
        BlockState state=world.getBlockState(origin);
        BlockPos rail=railPos(state,origin);
        Direction facing=state.get(FACING);
        if(!ElevatorLine.matches(world,rail,facing)) return null;
        // 轿厢中心水平位置 = 轨道中心 + 朝向 * 2 格；查询盒取 ±1.6 格（比轿厢 3 格略宽）以容纳边界情况
        double x=rail.getX()+.5+facing.getOffsetX()*2,z=rail.getZ()+.5+facing.getOffsetZ()*2;
        var cars=world.getEntitiesByClass(AbstractCabinEntity.class,new Box(x-1.6,origin.getY()-.01,z-1.6,x+1.6,origin.getY()+3,z+1.6),
                c->!c.isRemoved() && c.facing()==facing
                        && Math.abs(c.getX()-x)<=tolerance && Math.abs(c.getZ()-z)<=tolerance
                        && Math.abs(c.getY()-origin.getY())<=tolerance);
        if(cars.size()!=1) return null; // 该线路必须恰好一辆；0 辆或数据异常时保持关门
        AbstractCabinEntity car=cars.getFirst();
        // 不再按相位排除"运行中 / 受阻暂停"，而是交给调用方用"轿厢门是否真的在开"来把关
        // （见 mayOpen / leafProgress 里的 doorProgress > 0）。理由有两个：
        // ① 运行时门必然全关（"门未完全关闭不得移动"），因此相位守卫对正常行程是多余的；
        // ② 故障（BLOCKED）时允许乘客开门脱困，而那时相位恰恰就是 BLOCKED——若在这里排除，
        //    楼层门会拒绝交出碰撞，人虽然开了轿厢门却仍然走不出去，脱困功能形同虚设。
        //    轿厢停在楼层之间时这里本来就返回 null（上面 ±1e-7 的高度过滤），所以不会半空开门。
        return car;
    }

    /**
     * 门联锁判定：现在是否允许这扇门交出真实碰撞（即解除锁闭）。
     *
     * <p>这里坚持用 {@link ElevatorParameters#POSITION_EPSILON}（1e-7 格）判定到站：
     * 服务端权威、精度足够，联锁语义不因为客户端同步精度而放松。
     *
     * @param world 世界（服务端调用）
     * @param origin 站点根方块坐标
     * @return 有唯一在站轿厢且其门进度 &gt; 0（正在打开或已打开）时为 true
     */
    private static boolean mayOpen(World world,BlockPos origin) {
        AbstractCabinEntity car=dockedCabin(world,origin,ElevatorParameters.POSITION_EPSILON);
        return car!=null && car.doorProgress(1)>0;
    }

    /**
     * 楼层门门扇的目标进度 0..1：等于在站轿厢的门进度，因此两层门逐刻同步滑动；没有轿厢时 0。
     *
     * <p>这是"楼层门与轿厢门同一动画"的唯一来源：{@link LandingDoorBlockEntity} 每刻采样它，
     * 渲染器与碰撞形状都从样本读进度，门本身不再维护任何独立的开关计时。
     *
     * <p>用 {@link ElevatorParameters#SYNC_POSITION_EPSILON} 而不是联锁用的 1e-7：本方法在客户端也要成立，
     * 那里的轿厢坐标来自原版位置包（量化到 1/4096 格）。放宽的只是"门扇跟到哪个进度"，
     * 服务端联锁（{@link #mayOpen}）仍然坚持 1e-7 的精确到站判定。
     *
     * @param world 世界（服务端与客户端都会调用）
     * @param origin 站点根方块坐标
     * @return 0（全关）..1（全开）
     */
    public static float leafProgress(World world,BlockPos origin) {
        AbstractCabinEntity car=dockedCabin(world,origin,ElevatorParameters.SYNC_POSITION_EPSILON);
        return car==null?0f:MathHelper.clamp(car.doorProgress(1),0f,1f);
    }

    /**
     * 重新求值联锁并把 OPEN 写到整扇门 9 格上。
     *
     * <p>由轿厢每刻（同一服务端刻内更新轿厢门状态之后）与门自身的 scheduledTick 调用；
     * 客户端直接返回，OPEN 只由服务端同步。写状态用 {@code NOTIFY_LISTENERS} 而非
     * NOTIFY_ALL：更新由轮询驱动，不需要再触发邻居/比较器更新以免递归刷新。
     *
     * <p>写之前用 {@code root(cell,p).equals(origin)} 确认该格确实属于本扇门，
     * 防止把相邻门的部件（或恰好在同一位置的其他门）误改。
     *
     * <p>副作用：可能改写最多 9 格的 OPEN 状态（含客户端视觉同步）。
     *
     * @param world 世界；客户端调用无效果
     * @param origin 站点根方块坐标
     */
    public static void refresh(World world,BlockPos origin) {
        if(world.isClient) return;
        BlockState state=world.getBlockState(origin);
        if(!isRoot(state)) return;
        boolean open=mayOpen(world,origin);
        for(int row=0;row<3;row++) for(int col=0;col<3;col++) {
            BlockPos p=part(origin,state.get(FACING),col,row);
            BlockState cell=world.getBlockState(p);
            if(cell.isOf(Easyelevator.LANDING_DOOR) && root(cell,p).equals(origin) && cell.get(OPEN)!=open)
                world.setBlockState(p,cell.with(OPEN,open),Block.NOTIFY_LISTENERS);
        }
    }

    /**
     * Only this car's own landing faces are permitted to overlap its front shell.
     *
     * <p>供 {@link AbstractCabinEntity#spaceClear} 使用：轿厢 3×3 井道与自家楼层门门框必然重叠，
     * 这类方块要放行；判定条件是同一 FACING、同一轨道 XZ，并且该部件所属的整扇门完整
     * （残门不享有豁免，仍算障碍）。其他线路或其他方向的门一律视为障碍。
     *
     * @param world 世界
     * @param p 待检查的方块坐标
     * @param state 该方块状态
     * @param car 正在移动的轿厢
     * @return 该方块是本轿厢自己的完整楼层门部件时为 true
     */
    public static boolean belongsToCabin(World world,BlockPos p,BlockState state,AbstractCabinEntity car) {
        BlockPos rail=railPos(state,p);
        return state.get(FACING)==car.facing() && rail.getX()==car.railX() && rail.getZ()==car.railZ()
                && complete(world,root(state,p));
    }

    /**
     * 右键门上的任意部件：普通右键 = 厅外呼叫面板，潜行右键 = 该门自己的设置面板。
     *
     * <p><b>两种右键的分工</b>：
     * <ul>
     *   <li><b>普通右键</b>（{@link #openHallPanel}）：照旧弹出厅外呼叫面板（▲ / ▼ / ×），
     *       行为与 1.5.x 完全一致，本次改动<b>不碰它</b>；</li>
     *   <li><b>潜行右键</b>（{@link #openDoorSettings}）：弹出这扇门专属的设置面板——开关门音效的开关、
     *       两个音效文件的选择/上传/试听，以及"设为基准层"。原来潜行右键是"直接设为基准层"，
     *       现在那个动作变成新面板里的一个按钮（判据与实现完全保留，见 {@link #setFloorBase}）。</li>
     * </ul>
     *
     * <p>服务端权威：客户端分支不做事，只统一返回 {@code SUCCESS}（播放手臂摆动、阻止后续交互）。
     *
     * @param state 被点击部件的状态
     * @param world 世界
     * @param pos 被点击部件坐标
     * @param player 点击的玩家
     * @param hit 命中信息（未使用）
     * @return 恒为 SUCCESS
     */
    @Override
    protected ActionResult onUse(BlockState state,World world,BlockPos pos,PlayerEntity player,BlockHitResult hit) {
        if(!world.isClient) {
            BlockPos origin=root(state,pos);
            if(player.isSneaking()) openDoorSettings(world,origin,player);
            else openHallPanel(world,origin,player);
        }
        return ActionResult.SUCCESS;
    }

    /**
     * 弹出该扇门自己的设置面板（服务端 -> 客户端）。
     *
     * <p>为什么面板内容要由服务端给：门的设置存在方块实体里（服务端权威），
     * "这一站是第几层"也要按线路现算。客户端只画界面，因此别人改过的设置关掉再打开就能看到。
     *
     * <p>整扇门不完整时照样打开面板（与厅外呼叫面板同一策略）：玩家能看到层号占位与两个音效开关，
     * 点按钮时会收到服务端的"无效站点"提示，比"潜行右键没反应"好理解得多。
     *
     * @param world 世界（服务端）
     * @param origin 被点击门的根方块坐标（站点）
     * @param player 点击的玩家
     */
    private static void openDoorSettings(World world,BlockPos origin,PlayerEntity player) {
        if(!(player instanceof ServerPlayerEntity serverPlayer)) return; // 只有服务端玩家实体能收包
        // 只发给发起者：打开面板是个人操作，别人正开着同一扇门的面板也会在改动时被 broadcastDoorPanel 刷新。
        ElevatorNetworking.sendDoorPanel(serverPlayer,origin,ElevatorNetworking.OpenDoorPanel.PREVIEW_NONE);
    }

    /**
     * 弹出厅外呼叫面板（服务端 -> 客户端）。
     *
     * <p>为什么要面板而不是直接呼叫：真实电梯的厅外按钮是"上行 / 下行"两个方向，方向决定调度
     * （见 {@link ElevatorController} 的集选规则），因此必须先让玩家选方向。面板初值由服务端给出，
     * 关掉再打开也能看到该站当前的呼叫是否仍然点亮。
     *
     * <p>纯客户端表现 + 一次发包：不改方块状态、不改状态机。线路还不完整或没有轿厢时照样打开面板，
     * 玩家按按钮后会收到"没有轿厢 / 多个轿厢"的提示，比"右键没反应"更容易理解。
     *
     * @param world 世界（服务端）
     * @param origin 被点击门的根方块坐标（站点）
     * @param player 点击的玩家
     */
    private static void openHallPanel(World world,BlockPos origin,PlayerEntity player) {
        if(!(player instanceof ServerPlayerEntity serverPlayer)) return; // 只有服务端玩家实体能收包
        boolean up=false,down=false,showUp=true,showDown=true;
        if(complete(world,origin)) {
            ElevatorLine line=ElevatorLine.scan(world,railPos(world.getBlockState(origin),origin));
            if(line!=null) {
                // 端站只有一个有意义的呼叫方向：最底层下面没有站（只能向上），最顶层上面没有站（只能向下）；
                // 中间层两个方向都显示；整条线路只有一站时保留"向上"（等价于"把车叫到本层"）。
                var stops=line.stops();
                int index=stops.indexOf(origin);
                if(index>=0) {
                    showDown=index>0;
                    showUp=index<stops.size()-1||stops.size()==1;
                }
            }
            var cabins=line==null?java.util.List.<AbstractCabinEntity>of():line.cabins(world);
            if(cabins.size()==1) { up=cabins.getFirst().hasHallCall(origin,true); down=cabins.getFirst().hasHallCall(origin,false); }
        }
        ServerPlayNetworking.send(serverPlayer,new ElevatorNetworking.OpenHallPanel(origin.toImmutable(),up,down,showUp,showDown));
    }

    /**
     * 把这一站设为 / 取消整条线路的基准层（1 层）；由设置面板上的按钮触发。
     *
     * <p><b>与旧版的关系</b>：这个动作原来的入口是"潜行右键门"，现在搬进了设置面板，
     * 判据与实现逐条保留——标记写在基准门自己的方块实体里（{@link LandingDoorBlockEntity#setBaseFloor}），
     * 因此随区块存档；一条线路最多一扇门带标记，设置时会把同线其它门的标记清掉；
     * 拆掉基准门就回到默认编号（最低站点 = 1 层）。额外支持"取消"：把本门的标记清掉，同样回到默认编号。</p>
     *
     * <p>副作用：改动最多 N 个门的方块实体 NBT（N = 站点数）；推一次选站面板让已打开的按钮立刻重排；
     * 再刷一次门设置面板，让里面的层号文本跟上新编号。轿厢内层号与门框顶部层号由每刻重算的同步字段驱动，
     * 下一个服务端刻自动跟上。
     *
     * @param world 世界（服务端）
     * @param origin 被操作的门的根方块坐标
     * @param player 操作的玩家
     * @param on true = 把这一站设为基准层；false = 取消本门的基准层标记
     */
    public static void setFloorBase(World world,BlockPos origin,PlayerEntity player,boolean on) {
        if(!complete(world,origin)) { player.sendMessage(Text.translatable("message.easyelevator.invalid_stop"),true); return; }
        ElevatorLine line=ElevatorLine.scan(world,railPos(world.getBlockState(origin),origin));
        if(line==null || !line.stops().contains(origin)) { player.sendMessage(Text.translatable("message.easyelevator.invalid_stop"),true); return; }
        // on 时本门为基准层、同线其余门一律清除；取消时全部清除（回到"最低站点 = 1 层"的默认编号）。
        for(BlockPos stop:line.stops())
            if(world.getBlockEntity(stop) instanceof LandingDoorBlockEntity door) door.setBaseFloor(on && stop.equals(origin));
        // 编号变了：让已经打开的选站面板立刻重排（门框与轿厢内的层号由每刻重算的同步字段驱动）。
        for(AbstractCabinEntity cabin:line.cabins(world)) ElevatorNetworking.syncPanel(cabin,cabin.plannedStops());
        // 设置面板自己也要刷新，否则里面的层号文本还停在旧编号上。
        ElevatorNetworking.broadcastDoorPanel(world,origin,ElevatorNetworking.OpenDoorPanel.PREVIEW_NONE);
        player.sendMessage(Text.translatable(on?"message.easyelevator.floor_base_set":"message.easyelevator.floor_base_cleared"),true);
    }

    /**
     * 拆掉门的任意一格 → 整扇门一起拆除。
     *
     * <p>只处理非根部件：反推根，若根仍在就转交 {@code world.breakBlock(根, ...)}，
     * 让掉落只由根方块产生一次（整扇门只掉 1 个门物品，生存模式才掉落）。
     * 之所以不在这里自己 setBlockState 清格，是为了复用根方块正常的破坏流程
     * （掉落、进度、事件）并由 {@link #onStateReplaced} 负责清除残块。
     *
     * <p>副作用：破坏根方块（可能产生掉落物）；返回的 {@code super} 结果继续处理被点击格。
     *
     * @param world 世界
     * @param pos 被拆部件坐标
     * @param state 被拆部件状态
     * @param player 破坏者（创造模式不掉落）
     * @return 父类结果
     */
    @Override
    public BlockState onBreak(World world,BlockPos pos,BlockState state,PlayerEntity player) {
        if(!world.isClient && !isRoot(state)) {
            BlockPos origin=root(state,pos);
            if(isRoot(world.getBlockState(origin))) world.breakBlock(origin,!player.isCreative(),player);
        }
        return super.onBreak(world,pos,state,player);
    }

    /**
     * 任一格离开本方块（被拆、被替换、爆炸、活塞推动等）时清除同门其余部件，避免留下残块。
     *
     * <p>守卫 {@code !next.isOf(this)} 区分两种情形：放置时的 9 格与 OPEN 属性变化都属于
     * "仍是本方块"，不能触发清拆；只有真正消失才清理。清理写空气（不掉落），掉落只由
     * 根方块的破坏流程负责；{@code NOTIFY_ALL} 让形状与碰撞立即更新。
     *
     * <p>副作用：最多把同门 8 格写成空气并触发方块更新。服务端专属。
     *
     * @param state 变化前状态
     * @param world 世界
     * @param pos 变化位置
     * @param next 变化后的新状态
     * @param moved 是否为移动（活塞相关），未使用
     */
    @Override
    protected void onStateReplaced(BlockState state,World world,BlockPos pos,BlockState next,boolean moved) {
        if(!world.isClient && !next.isOf(this)) {
            BlockPos origin=root(state,pos);
            for(int row=0;row<3;row++) for(int col=0;col<3;col++) {
                BlockPos p=part(origin,state.get(FACING),col,row);
                if(p.equals(pos)) continue; // 自身由调用方处理，跳过以免递归
                BlockState cell=world.getBlockState(p);
                if(cell.isOf(this) && root(cell,p).equals(origin)) world.setBlockState(p,Blocks.AIR.getDefaultState(),Block.NOTIFY_ALL);
            }
        }
        super.onStateReplaced(state,world,pos,next,moved);
    }

    /**
     * 门格子的轮廓形状（选中高亮用的几何）。
     *
     * <p>与碰撞共用同一份几何，所以高亮框会跟着门扇一起收拢，不会在门已经半开时还框住整个门洞。
     *
     * @param s 方块状态
     * @param w 方块视图
     * @param p 方块坐标
     * @param c 形状上下文（未使用）
     * @return 该格当前的体素形状
     */
    @Override
    protected VoxelShape getOutlineShape(BlockState s,BlockView w,BlockPos p,ShapeContext c) {
        return LandingDoorGeometry.shape(s.get(FACING),s.get(COLUMN),s.get(LEVEL),progressAt(s,w,p));
    }

    /**
     * 门格子的碰撞形状：随门扇进度连续变化，并在这里完成联锁兜底。
     *
     * <p>为什么需要兜底：碰撞查询可能发生在两次 {@code scheduledTick} 之间，而方块状态的刷新有 1 刻延迟。
     * 若状态仍写着 OPEN、但 {@link #mayOpen} 此刻已不成立（轿厢已离开、目的站被拆），
     * {@link #progressAt} 会把本次查询按全关处理——不回写世界、不改方块状态，因此既不会与
     * scheduledTick 的刷新竞争，也不会出现"门开着而轿厢不在"的穿越窗口。
     *
     * @param s 方块状态
     * @param w 方块视图；只有世界视图才拿得到方块实体与联锁复检结果
     * @param p 方块坐标
     * @param c 形状上下文（未使用）
     * @return 关闭时为门扇封住门洞加门框，开启时为纯门框，门洞处为空形状
     */
    @Override
    protected VoxelShape getCollisionShape(BlockState s,BlockView w,BlockPos p,ShapeContext c) {
        return LandingDoorGeometry.shape(s.get(FACING),s.get(COLUMN),s.get(LEVEL),progressAt(s,w,p));
    }

    /**
     * 取该格此刻的门扇进度 0..1。
     *
     * <p>优先读根方块的方块实体 {@link LandingDoorBlockEntity}：它逐刻跟随在站轿厢的门进度，
     * 因此楼层门与轿厢门同刻同值。没有方块实体时（未放置的门、被替换掉的状态、非世界视图、
     * 区块尚未建立方块实体）退回 OPEN 的静态近似 0 / 1，保证任何查询都有确定结果且不抛异常。
     *
     * @param s 门格子状态
     * @param w 方块视图
     * @param p 门格子坐标
     * @return 0..1 的门扇进度
     */
    private static float progressAt(BlockState s,BlockView w,BlockPos p) {
        if(!(w instanceof World world)) return s.get(OPEN)?1f:0f;
        BlockPos origin=root(s,p);
        // 服务端联锁兜底：状态还写着 OPEN 但此刻已不允许开门，本次碰撞就按全关算（不回写状态）
        if(!world.isClient && s.get(OPEN) && !mayOpen(world,origin)) return 0f;
        if(world.getBlockEntity(origin) instanceof LandingDoorBlockEntity door) return door.openProgress();
        return s.get(OPEN)?1f:0f;
    }
}
