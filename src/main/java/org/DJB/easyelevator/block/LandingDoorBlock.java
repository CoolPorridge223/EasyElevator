package org.DJB.easyelevator.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.*;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorLine;
import org.DJB.easyelevator.logic.ElevatorParameters;

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
 * <p>联锁 interlock：9 格作为一个整体共用 OPEN 状态。只有轿厢精确到站
 * （误差 ≤ {@link ElevatorParameters#POSITION_EPSILON} 格）且轿厢门正在打开时，
 * 门才开启并交出真实碰撞；否则保留碰撞（空井道不会被右键强制打开）。
 * 注册 ID 沿用旧的 {@code easyelevator:call_button}，用于兼容旧存档与旧物品。
 */
public final class LandingDoorBlock extends HorizontalFacingBlock {

    /** 方块编解码器，仅用于数据生成与序列化；方块本身无额外构造参数。 */
    public static final MapCodec<LandingDoorBlock> CODEC = createCodec(LandingDoorBlock::new);

    /**
     * 门在整扇门内的横向位置，0..2（1 = 根所在中列）。
     * 轴方向为 {@code FACING.rotateYClockwise()}：列号增大即沿该水平方向 +1 格。
     */
    public static final IntProperty COLUMN = IntProperty.of("column", 0, 2);

    /** 门在整扇门内的高度层，0..2（0 = 底行，门区域的最下方 1 格）。 */
    public static final IntProperty LEVEL = IntProperty.of("level", 0, 2);

    /** 门联锁状态：由服务端 {@link #refresh} 写入，客户端只读，控制门面模型与碰撞形状。 */
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
     * 门联锁的唯一判定入口：现在是否允许这扇门打开。
     *
     * <p>依次要求：整扇门 9 格完整；站点对应的那段轨道仍属于本线路（朝向一致）；
     * 该轨道 XZ 上恰好有一辆朝向与轨道相同的轿厢；轿厢底部 Y 与站点 Y 的误差
     * ≤ {@link ElevatorParameters#POSITION_EPSILON} 格（1e-7 格，即"精确到站"）；
     * 轿厢不处于 MOVING/BLOCKED；且轿厢门进度 &gt; 0（正在打开或已打开）。
     *
     * <p>车体中心用轨道坐标 +0.5 + FACING*2 复算（与 {@link ElevatorLine#centerX} 一致），
     * 不读实体自身坐标，避免浮点抖动改变查询范围；实体查询的 Y 范围带 0.01 格粗筛余量，
     * 真正的到站精度由返回前的 POSITION_EPSILON 复检保证。
     *
     * <p>纯查询，无副作用。
     *
     * @param world 世界（服务端调用）
     * @param origin 站点根方块坐标
     * @return 允许开启楼层门时为 true
     */
    private static boolean mayOpen(World world,BlockPos origin) {
        if(!complete(world,origin)) return false;
        BlockState state=world.getBlockState(origin);
        BlockPos rail=railPos(state,origin);
        Direction facing=state.get(FACING);
        if(!ElevatorLine.matches(world,rail,facing)) return false;
        // 轿厢中心水平位置 = 轨道中心 + 朝向 * 2 格；查询盒取 ±1.6 格（比轿厢 3 格略宽）以容纳边界情况
        double x=rail.getX()+.5+facing.getOffsetX()*2,z=rail.getZ()+.5+facing.getOffsetZ()*2;
        var cars=world.getEntitiesByClass(CabinEntity.class,new net.minecraft.util.math.Box(x-1.6,origin.getY()-.01,z-1.6,x+1.6,origin.getY()+3,z+1.6),
                c->!c.isRemoved() && c.railX()==rail.getX() && c.railZ()==rail.getZ() && c.facing()==facing
                        && Math.abs(c.getY()-origin.getY())<=ElevatorParameters.POSITION_EPSILON);
        if(cars.size()!=1) return false; // 该线路必须恰好一辆；0 辆或数据异常时保持关门
        CabinEntity car=cars.getFirst();
        // 到站精度与门进度都必须满足；BLOCKED（断轨/障碍/目的站被拆）时门一律关闭
        return Math.abs(car.getY()-origin.getY())<=ElevatorParameters.POSITION_EPSILON
                && car.phase()!=ElevatorController.Phase.MOVING && car.phase()!=ElevatorController.Phase.BLOCKED
                && car.doorProgress(1)>0;
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
     * <p>供 {@link CabinEntity#spaceClear} 使用：轿厢 3×3 井道与自家楼层门门框必然重叠，
     * 这类方块要放行；判定条件是同一 FACING、同一轨道 XZ，并且该部件所属的整扇门完整
     * （残门不享有豁免，仍算障碍）。其他线路或其他方向的门一律视为障碍。
     *
     * @param world 世界
     * @param p 待检查的方块坐标
     * @param state 该方块状态
     * @param car 正在移动的轿厢
     * @return 该方块是本轿厢自己的完整楼层门部件时为 true
     */
    public static boolean belongsToCabin(World world,BlockPos p,BlockState state,CabinEntity car) {
        BlockPos rail=railPos(state,p);
        return state.get(FACING)==car.facing() && rail.getX()==car.railX() && rail.getZ()==car.railZ()
                && complete(world,root(state,p));
    }

    /**
     * 右键门上的任意部件 = 向本线路唯一轿厢发送呼叫请求（发往站点根方块）。
     *
     * <p>服务端权威：客户端分支不做事，只统一返回 {@code SUCCESS}（播放手臂摆动、
     * 阻止后续交互）。请求前先要求整扇门完整、才能扫描出线路；线路不存在或不是
     * 恰好一辆轿厢时发提示（无轿厢 / 多轿厢），否则把 {@link CabinEntity#requestStop}
     * 的受理结果反馈给玩家（已排队 / 无效站点）。
     *
     * <p>副作用：给玩家发 actionbar 消息；受理成功时改动轿厢请求队列（不直接开门——
     * 门仍然只由联锁推导）。
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
            ElevatorLine line=complete(world,origin)?ElevatorLine.scan(world,railPos(state,pos)):null;
            var cabins=line==null?java.util.List.<CabinEntity>of():line.cabins(world);
            if(cabins.size()!=1) player.sendMessage(Text.translatable(cabins.isEmpty()?"message.easyelevator.no_cabin":"message.easyelevator.multiple_cabins"),true);
            else player.sendMessage(Text.translatable(cabins.getFirst().requestStop(origin)?"message.easyelevator.called":"message.easyelevator.invalid_stop"),true);
        }
        return ActionResult.SUCCESS;
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

    /** @return 门格子的轮廓形状，见 {@link #shape}；轮廓与碰撞共用同一几何。 */
    @Override
    protected VoxelShape getOutlineShape(BlockState s,BlockView w,BlockPos p,ShapeContext c) { return shape(s); }

    /**
     * 门格子的碰撞形状，同时充当联锁的兜底检查。
     *
     * <p>若服务端发现该格状态为 OPEN、但 {@link #mayOpen} 此刻已不成立（例如轿厢已离开、
     * 目的站被拆），就在本次查询里把状态临时降级为关闭再取形状——不回写世界、不改方块，
     * 因此不会与 scheduledTick 的刷新竞争。之所以需要：碰撞查询可能发生在两次
     * scheduledTick 之间，方块状态刷新有 1 刻延迟，这段时间不允许出现"门开着但轿厢不在"
     * 的穿越窗口。
     *
     * @param s 方块状态
     * @param w 世界视图；只有服务端世界才做联锁复检
     * @param p 方块坐标
     * @param c 形状上下文
     * @return 关闭时为 3/16 格厚的门面，开启时为门框，门洞处为空形状
     */
    @Override
    protected VoxelShape getCollisionShape(BlockState s,BlockView w,BlockPos p,ShapeContext c) {
        // Enforce the landing interlock even before a scheduled visual-state refresh runs.
        if(w instanceof World world && !world.isClient && s.get(OPEN) && !mayOpen(world,root(s,p))) s=s.with(OPEN,false);
        return shape(s);
    }

    /**
     * 门面几何：单位格，局部坐标轴为"沿门宽"（即 COLUMN 轴）与 Y。
     *
     * <p>关闭时每格都是朝外侧、厚 3/16 格（0.1875 格）的完整门面（minX=0、maxX=16、minY=0），
     * 9 格拼成一堵 3 宽 3 高的门墙并带真实碰撞。
     *
     * <p>开启时按部件保留门框：顶行（LEVEL=2）只留 Y=13..16 格的门楣，与列无关；
     * 左列（COLUMN=0）留 3/16 格宽的立柱；右列（COLUMN=2）留另一侧立柱；
     * 中列底层与中层就是门洞，返回空形状。
     *
     * <p>NORTH/EAST 直接用 minX..maxX 表示列轴范围，SOUTH/WEST 用 16-maxX..16-minX 做镜像，
     * 保证形状与 {@link #part} 定义的列轴（{@code FACING.rotateYClockwise()}）方向一致，
     * 否则两侧立柱会出现在错误的格子上。
     *
     * @param s 门格子状态
     * @return 该格子的体素形状
     */
    private static VoxelShape shape(BlockState s) {
        double minX=0,maxX=16,minY=0;
        if(s.get(OPEN)) {
            if(s.get(LEVEL)==2) minY=13; // 顶行保留上框（门楣），门洞高度变为 13/16 格
            else if(s.get(COLUMN)==0) maxX=3; // 左列留门框立柱
            else if(s.get(COLUMN)==2) minX=13; // 右列留门框立柱
            else return VoxelShapes.empty(); // 中列门洞，无碰撞
        }
        // 门面厚 3/16 格，贴在朝外（FACING 方向）的一侧；列轴范围按朝向旋转或镜像
        return switch(s.get(FACING)) {
            case NORTH -> Block.createCuboidShape(minX,minY,0,maxX,16,3);
            case EAST -> Block.createCuboidShape(13,minY,minX,16,16,maxX);
            case SOUTH -> Block.createCuboidShape(16-maxX,minY,13,16-minX,16,16);
            default -> Block.createCuboidShape(0,minY,16-maxX,3,16,16-minX);
        };
    }
}
