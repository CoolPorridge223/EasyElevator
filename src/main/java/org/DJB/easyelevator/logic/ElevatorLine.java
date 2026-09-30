package org.DJB.easyelevator.logic;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.entity.CabinEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One unbroken, consistently oriented vertical column. Adjacent shafts stay separate. */
public record ElevatorLine(int x, int z, int bottom, int top, Direction facing, List<BlockPos> stops) {
    public static ElevatorLine scan(World world, BlockPos seed) {
        if (!world.isChunkLoaded(seed)) return null;
        BlockState state = world.getBlockState(seed);
        if (!state.isOf(Easyelevator.RAIL)) return null;
        Direction direction = state.get(ElevatorRailBlock.FACING);
        int low = seed.getY(), high = low;
        while (low > world.getBottomY() && matches(world, new BlockPos(seed.getX(), low - 1, seed.getZ()), direction)) low--;
        while (high < world.getTopY() - 1 && matches(world, new BlockPos(seed.getX(), high + 1, seed.getZ()), direction)) high++;
        List<BlockPos> stops = new ArrayList<>();
        for (int y = low; y <= high; y++) {
            BlockPos rail = new BlockPos(seed.getX(), y, seed.getZ());
            BlockPos door = rail.offset(direction,LandingDoorBlock.RAIL_DISTANCE);
            if (!world.isChunkLoaded(door)) continue;
            BlockState b = world.getBlockState(door);
            if (LandingDoorBlock.isRoot(b) && b.get(LandingDoorBlock.FACING)==direction
                    && LandingDoorBlock.complete(world,door)) stops.add(door.toImmutable());
        }
        stops.sort(Comparator.comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return new ElevatorLine(seed.getX(), seed.getZ(), low, high, direction, List.copyOf(stops));
    }
    public static boolean matches(World w, BlockPos p, Direction d) {
        if (!w.isChunkLoaded(p)) return false;
        BlockState s = w.getBlockState(p);
        return s.isOf(Easyelevator.RAIL) && s.get(ElevatorRailBlock.FACING) == d;
    }
    public boolean containsRail(BlockPos p) { return p.getX() == x && p.getZ() == z && p.getY() >= bottom && p.getY() <= top; }
    public double centerX() { return x + .5 + facing.getOffsetX() * 2; }
    public double centerZ() { return z + .5 + facing.getOffsetZ() * 2; }
    public List<CabinEntity> cabins(World world) {
        return world.getEntitiesByClass(CabinEntity.class, new Box(centerX()-2, bottom-1, centerZ()-2, centerX()+2, top+4, centerZ()+2),
                c -> !c.isRemoved() && c.railX() == x && c.railZ() == z && c.getY() >= bottom - .01 && c.getY() <= top + .01);
    }
}
