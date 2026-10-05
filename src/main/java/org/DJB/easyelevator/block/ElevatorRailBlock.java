package org.DJB.easyelevator.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

/**
 * 电梯轨道方块：同 X/Z 上垂直连续、朝向一致的一列轨道构成一条线路 line。
 *
 * <p>轨道朝向 FACING = 轿厢所在方向 = 轿厢门朝向：轿厢中心位于轨道朝向的前方 2 格，
 * 楼层门根方块位于轨道朝向的前方 {@link LandingDoorBlock#RAIL_DISTANCE} 格且与轨道同 Y。
 * 因此水平相邻的轨道列属于不同线路，本模组不支持转弯、斜轨与分岔。
 *
 * <p>关键不变量：整列轨道的 FACING 必须一致，否则 {@link org.DJB.easyelevator.logic.ElevatorLine}
 * 会在朝向不一致处断开线路（表现为断轨造成的 BLOCKED 暂停）。
 */
public class ElevatorRailBlock extends HorizontalFacingBlock {

    /** 方块编解码器，仅用于数据生成与序列化；方块本身无额外构造参数。 */
    public static final MapCodec<ElevatorRailBlock> CODEC = createCodec(ElevatorRailBlock::new);

    /**
     * @param settings 方块设置（硬度、是否不透明等），由注册处给出
     */
    public ElevatorRailBlock(Settings settings) {
        super(settings);
        // HorizontalFacingBlock 声明了 FACING，默认状态必须显式赋值，否则读取状态属性会抛异常
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    /** @return 本方块对应的编解码器 */
    @Override
    public MapCodec<ElevatorRailBlock> getCodec() { return CODEC; }

    /**
     * 只注册 FACING：轨道不是原版 AbstractRailBlock，没有 shape/waterlogged/powered，
     * 也不接受红石信号（红石不能开关楼层门，右键只发送呼叫请求）。
     *
     * @param builder 状态属性构建器
     */
    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING); }

    /**
     * 推导放置后的朝向。
     *
     * <p>先看正下方、再看正上方：只要相邻轨道是本方块就继承它的 FACING，
     * 保证整列朝向一致（这是线路连续性的前提）。若上下都没有轨道，则取
     * 玩家水平视线方向的反方向，即朝向放置者所在的一侧——这样轿厢与楼层门
     * 会生成在玩家面前，接着放置时不需要转身。
     *
     * @param ctx 放置上下文，含被放置位置与世界
     * @return 带最终 FACING 的方块状态
     */
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        for (Direction d : new Direction[]{Direction.DOWN, Direction.UP}) {
            BlockState adjacent = ctx.getWorld().getBlockState(ctx.getBlockPos().offset(d));
            if (adjacent.isOf(this)) return getDefaultState().with(FACING, adjacent.get(FACING));
        }
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    /**
     * 轮廓（选中框）形状：10/16 格见方、占满 1 格高的方柱，与轨道模型的可见范围
     * 3..13 × 0..16 × 3..13 保持一致（模型里有底座法兰、四颗地脚螺栓、双导轨、齿条与抱箍，
     * 最外沿就是 3/16 与 13/16）。轮廓形状同时决定右键射线命中，
     * 因此它必须覆盖整个看得见的模型——否则点得到法兰却点不到导轨。
     *
     * <p>只重写轮廓形状、保留默认的整格碰撞，是刻意的：选中框与模型一致，
     * 但轨道仍按 1 格整方块阻挡实体、参与碰撞与井道空间判定（井道豁免靠的是
     * {@code ElevatorLine.matches} 的断轨检查与实体包围盒内缩，不是缩小碰撞）。
     *
     * @param s 当前方块状态
     * @param w 世界视图
     * @param p 方块坐标
     * @param c 形状上下文
     * @return 轮廓体素形状，单位为格
     */
    @Override
    protected VoxelShape getOutlineShape(BlockState s, BlockView w, BlockPos p, ShapeContext c) {
        return Block.createCuboidShape(3, 0, 3, 13, 16, 13);
    }
}
