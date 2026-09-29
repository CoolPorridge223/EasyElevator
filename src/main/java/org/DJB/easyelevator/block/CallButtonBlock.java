package org.DJB.easyelevator.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.text.Text;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.logic.ElevatorLine;

public class CallButtonBlock extends HorizontalFacingBlock {
    public static final MapCodec<CallButtonBlock> CODEC = createCodec(CallButtonBlock::new);
    public CallButtonBlock(Settings settings) { super(settings); setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH)); }
    @Override public MapCodec<CallButtonBlock> getCodec() { return CODEC; }
    @Override protected void appendProperties(StateManager.Builder<Block, BlockState> b) { b.add(FACING); }
    public static BlockPos railPos(BlockState state, BlockPos pos) { return pos.offset(state.get(FACING).getOpposite()); }
    @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction side = ctx.getSide();
        if (side.getAxis().isHorizontal() && ctx.getWorld().getBlockState(ctx.getBlockPos().offset(side.getOpposite())).isOf(Easyelevator.RAIL))
            return getDefaultState().with(FACING, side);
        for (Direction d : Direction.Type.HORIZONTAL)
            if (ctx.getWorld().getBlockState(ctx.getBlockPos().offset(d.getOpposite())).isOf(Easyelevator.RAIL))
                return getDefaultState().with(FACING, d);
        return null;
    }
    @Override protected VoxelShape getOutlineShape(BlockState s, BlockView w, BlockPos p, ShapeContext c) {
        return switch (s.get(FACING)) {
            case NORTH -> Block.createCuboidShape(5, 5, 13, 11, 11, 16);
            case SOUTH -> Block.createCuboidShape(5, 5, 0, 11, 11, 3);
            case EAST -> Block.createCuboidShape(0, 5, 5, 3, 11, 11);
            default -> Block.createCuboidShape(13, 5, 5, 16, 11, 11);
        };
    }
    @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!world.isClient) {
            ElevatorLine line = ElevatorLine.scan(world, railPos(state, pos));
            var cabins = line == null ? java.util.List.<org.DJB.easyelevator.entity.CabinEntity>of() : line.cabins(world);
            if (cabins.size() != 1) player.sendMessage(Text.translatable(cabins.isEmpty() ? "message.easyelevator.no_cabin" : "message.easyelevator.multiple_cabins"), true);
            else player.sendMessage(Text.translatable(cabins.getFirst().requestStop(pos) ? "message.easyelevator.called" : "message.easyelevator.invalid_stop"), true);
        }
        return ActionResult.SUCCESS;
    }
}
