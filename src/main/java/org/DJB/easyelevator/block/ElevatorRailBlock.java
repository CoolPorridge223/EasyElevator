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

public class ElevatorRailBlock extends HorizontalFacingBlock {
    public static final MapCodec<ElevatorRailBlock> CODEC = createCodec(ElevatorRailBlock::new);
    public ElevatorRailBlock(Settings settings) { super(settings); setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH)); }
    @Override public MapCodec<ElevatorRailBlock> getCodec() { return CODEC; }
    @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
        for (Direction d : new Direction[]{Direction.DOWN, Direction.UP}) {
            BlockState adjacent = ctx.getWorld().getBlockState(ctx.getBlockPos().offset(d));
            if (adjacent.isOf(this)) return getDefaultState().with(FACING, adjacent.get(FACING));
        }
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }
    @Override protected VoxelShape getOutlineShape(BlockState s, BlockView w, BlockPos p, ShapeContext c) {
        return Block.createCuboidShape(5, 0, 5, 11, 16, 11);
    }
}
