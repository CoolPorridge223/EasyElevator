package org.DJB.easyelevator;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorController.Phase;

import java.util.ArrayList;
import java.util.List;

/** 同层双向呼叫：八种用户场景、边界状态和真实实体/NBT 集成回归。 */
public class HallCallTests implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE) public void case1(TestContext ctx) { HallCallRegression.matrixCase(3, false, 1); ctx.complete(); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void case2(TestContext ctx) { HallCallRegression.matrixCase(3, false, 3); ctx.complete(); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void case3(TestContext ctx) { HallCallRegression.matrixCase(3, true, 1); ctx.complete(); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void case4(TestContext ctx) { HallCallRegression.matrixCase(3, true, 3); ctx.complete(); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void case5(TestContext ctx) { HallCallRegression.matrixCase(1, false, 1); ctx.complete(); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void case6(TestContext ctx) { HallCallRegression.matrixCase(1, false, 3); ctx.complete(); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void case7(TestContext ctx) { HallCallRegression.matrixCase(1, true, 1); ctx.complete(); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void case8(TestContext ctx) { HallCallRegression.matrixCase(1, true, 3); ctx.complete(); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void interruptedDeparture(TestContext ctx) { HallCallRegression.interruptedDeparture(); ctx.complete(); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void openDoorCallsAndIdle(TestContext ctx) { HallCallRegression.openDoorCallsAndIdle(); ctx.complete(); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void existingDispatchAndLegacyRestore(TestContext ctx) { HallCallRegression.existingDispatchAndLegacyRestore(); ctx.complete(); }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void entityRoutesAndNbt(TestContext ctx) {
        BlockPos rail = ctx.getAbsolutePos(new BlockPos(2, 4, 1));
        for (BlockPos pos : BlockPos.iterate(rail.add(-1, 0, 1), rail.add(1, 15, 3)))
            ctx.getWorld().setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
        for (int y = 0; y <= 14; y++) ctx.getWorld().setBlockState(rail.up(y),
                Easyelevator.RAIL.getDefaultState().with(ElevatorRailBlock.FACING, Direction.SOUTH), 2);
        for (int floor = 0; floor < 3; floor++) {
            BlockPos root = rail.up(floor * 6).south(3);
            for (int column = 0; column < 3; column++) for (int level = 0; level < 3; level++)
                ctx.getWorld().setBlockState(root.offset(Direction.WEST, column - 1).up(level),
                        Easyelevator.LANDING_DOOR.getDefaultState().with(LandingDoorBlock.FACING, Direction.SOUTH)
                                .with(LandingDoorBlock.COLUMN, column).with(LandingDoorBlock.LEVEL, level), 2);
        }
        BlockPos middle = rail.up(6).south(3);
        for (var type : List.of(Easyelevator.CABIN, Easyelevator.HIGH_SPEED_CABIN,
                Easyelevator.OBSERVATION_CABIN, Easyelevator.POWERFUL_CABIN)) {
            for (int start : new int[]{0, 2}) for (boolean firstUp : new boolean[]{false, true})
                for (int destination : new int[]{0, 2}) {
                    AbstractCabinEntity cabin = type.create(ctx.getWorld());
                    cabin.initialize(rail.up(start * 6), Direction.SOUTH);
                    ctx.getWorld().spawnEntity(cabin);
                    try {
                        ctx.assertTrue(cabin.requestHallCall(middle, firstUp), "first call accepted");
                        for (int i = 0; i < 85; i++) cabin.tick(); // 派车后才按另一方向，覆盖顺序相关缺陷。
                        ctx.assertTrue(cabin.requestHallCall(middle, !firstUp), "second call accepted");
                        List<Integer> arrivals = new ArrayList<>();
                        boolean selected = false, savedCommitted = false;
                        for (int tick = 0; tick < 2400 && arrivals.size() < 3; tick++) {
                            Phase previous = cabin.phase();
                            cabin.tick();
                            if (cabin.phase() == Phase.OPENING && previous != Phase.OPENING) {
                                arrivals.add((int) Math.round((cabin.getY() - rail.getY()) / 6));
                                if (!selected) {
                                    ctx.assertTrue(cabin.hasHallCall(middle, true) && cabin.hasHallCall(middle, false), "both arrival lights");
                                    roundTripNbt(ctx, cabin, "NONE");
                                    ctx.assertTrue(cabin.requestStop(rail.up(destination * 6).south(3)), "destination accepted");
                                    selected = true;
                                }
                            }
                            if (selected && arrivals.size() < 3) {
                                ctx.assertTrue(cabin.hasHallCall(middle, destination == 0), "residual entity light survives");
                                if (!savedCommitted && cabin.phase() == Phase.CLOSING) {
                                    ctx.assertTrue(!cabin.hasHallCall(middle, destination == 2), "departure light extinguished");
                                    roundTripNbt(ctx, cabin, destination == 2 ? "UP" : "DOWN");
                                    savedCommitted = true;
                                }
                            }
                        }
                        ctx.assertTrue(arrivals.equals(List.of(1, destination, 1)), "entity route " + arrivals);
                        ctx.assertTrue(savedCommitted && cabin.hallCalls().isEmpty(), "NBT restored route completes");
                    } finally {
                        cabin.discard();
                    }
                }
        }
        ctx.complete();
    }

    private static void roundTripNbt(TestContext ctx, AbstractCabinEntity cabin, String served) {
        NbtCompound saved = new NbtCompound();
        cabin.writeNbt(saved);
        ctx.assertTrue(saved.getCompound("StopService").getString("Served").equals(served), "persist service direction");
        var calls = cabin.hallCalls();
        cabin.readNbt(saved);
        ctx.assertTrue(cabin.hallCalls().equals(calls), "NBT keeps independent hall requests");
    }
}
