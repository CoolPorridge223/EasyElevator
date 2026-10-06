package org.DJB.easyelevator;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.RiderMotionHistory;
import org.DJB.easyelevator.network.PlatformMovement;
import org.DJB.easyelevator.network.RiderMove;

public class RiderMovementTests implements FabricGameTest {
    private static void near(TestContext ctx, double expected, double actual, String message) {
        ctx.assertTrue(Math.abs(expected - actual) < 1e-7, message + ": " + expected + " != " + actual);
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void historyRejectsStaleAndUnknownFrames(TestContext ctx) {
        RiderMotionHistory history = new RiderMotionHistory();
        for (long tick = 0; tick <= 200; tick++) history.record(tick, 64 + tick * .5);
        near(ctx, 164, history.height(200, 200), "latest sample");
        near(ctx, 159, history.height(190, 200), "delayed sample");
        ctx.assertTrue(Double.isNaN(history.height(201, 200)), "future rejected");
        ctx.assertTrue(Double.isNaN(history.height(139, 200)), "expired rejected");
        history.record(200, 900);
        near(ctx, 164, history.height(200, 200), "duplicate cannot replace authority");
        for (double speed : new double[]{-.5, -.2, .2, .5}) {
            for (int delay = 0; delay <= 40; delay++) {
                double seen = 100 - speed * delay;
                for (double relative : new double[]{.2, .62, 1.0})
                    near(ctx, 100 + relative, RiderMotionHistory.rebase(seen + relative, seen, 100),
                            "delay must preserve relative jump height");
            }
        }
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void carryPreservesWalkJumpAndGroundState(TestContext ctx) {
        PlayerEntity player = ctx.createMockPlayer(GameMode.SURVIVAL);
        player.setPosition(2, 80.2, 2);
        Vec3d velocity = new Vec3d(.13, .42, -.09);
        player.setVelocity(velocity);
        player.setOnGround(false);
        for (double dy : new double[]{.2, .5, -.2, -.5, .000001}) {
            double y = player.getY();
            AbstractCabinEntity.carryPassenger(player, dy);
            near(ctx, y + dy, player.getY(), "platform translation");
            ctx.assertEquals(velocity, player.getVelocity(), "walk/jump velocity preserved");
            ctx.assertFalse(player.isOnGround(), "airborne player must not be forced grounded");
        }
        ctx.complete();
    }

    private static AbstractCabinEntity cabin(TestContext ctx, EntityType<? extends AbstractCabinEntity> type) {
        var cabin = type.create(ctx.getWorld());
        cabin.initialize(ctx.getAbsolutePos(new BlockPos(2, 4, 1)), Direction.SOUTH);
        ctx.getWorld().spawnEntity(cabin);
        return cabin;
    }

    private static ServerPlayerEntity player(TestContext ctx, AbstractCabinEntity cabin) {
        ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
        try {
            var id = ServerPlayNetworkHandler.class.getDeclaredField("requestedTeleportId");
            id.setAccessible(true);
            player.networkHandler.onTeleportConfirm(new TeleportConfirmC2SPacket(id.getInt(player.networkHandler)));
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
        player.changeGameMode(GameMode.SURVIVAL);
        player.setPosition(cabin.getX(), cabin.getY() + .2, cabin.getZ());
        player.setOnGround(true);
        player.networkHandler.syncWithPlayerPosition();
        return player;
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void delayedPacketsKeepWalkingAndJumping(TestContext ctx) {
        var cabin = cabin(ctx, Easyelevator.HIGH_SPEED_CABIN);
        var player = player(ctx, cabin);
        cabin.tick(); // Record the actual client frame before the platform moves ahead.
        long frame = ctx.getWorld().getTime();
        double seenY = cabin.getY();
        cabin.setPosition(cabin.getX(), seenY + 2, cabin.getZ());
        AbstractCabinEntity.carryPassenger(player, 2);
        var move = new RiderMove(cabin.getId(), frame, player.getX() + .1, seenY + .2,
                player.getZ(), 32, 8, true, true, true);
        move.apply(player);
        near(ctx, cabin.getX() + .1, player.getX(), "horizontal movement accepted");
        near(ctx, cabin.getY() + .2, player.getY(), "delayed packet rebased");
        ctx.assertTrue(((PlatformMovement) player.networkHandler).easyelevator$canCarry(), "no correction teleport");
        new RiderMove(cabin.getId(), frame, player.getX() + .1, seenY + .62,
                player.getZ(), 32, 8, false, true, true).apply(player);
        near(ctx, cabin.getY() + .62, player.getY(), "jump preserved in cabin frame");
        ctx.assertFalse(player.isOnGround(), "jump remains airborne");
        ctx.assertTrue(((PlatformMovement) player.networkHandler).easyelevator$canCarry(), "jump needs no teleport");
        player.discard(); cabin.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void genuineTeleportIsNotCarried(TestContext ctx) {
        var cabin = cabin(ctx, Easyelevator.CABIN);
        var player = player(ctx, cabin);
        player.networkHandler.requestTeleport(player.getX(), player.getY() + 10, player.getZ(), 0, 0);
        double y = player.getY();
        AbstractCabinEntity.carryPassenger(player, .5);
        near(ctx, y, player.getY(), "pending teleport must retain its destination");
        player.discard(); cabin.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void actualFloorClearsFloatingButAirDoesNot(TestContext ctx) throws Exception {
        var cabin = cabin(ctx, Easyelevator.CABIN);
        var player = player(ctx, cabin);
        var floating = ServerPlayNetworkHandler.class.getDeclaredField("floating");
        floating.setAccessible(true);
        player.networkHandler.onPlayerMove(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.OnGroundOnly(true));
        ctx.assertFalse(floating.getBoolean(player.networkHandler), "entity floor counts as real support");
        player.setPosition(cabin.getX() + 5, cabin.getY() + 1, cabin.getZ());
        player.networkHandler.syncWithPlayerPosition();
        player.networkHandler.onPlayerMove(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.OnGroundOnly(false));
        ctx.assertTrue(floating.getBoolean(player.networkHandler), "nearby air must retain vanilla floating check");
        player.discard(); cabin.discard(); ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void forgedSampleCannotAddPlatformDisplacement(TestContext ctx) {
        var cabin = cabin(ctx, Easyelevator.CABIN);
        var player = player(ctx, cabin);
        cabin.tick();
        double y = player.getY();
        new RiderMove(cabin.getId(), Long.MAX_VALUE, player.getX() + .1, y, player.getZ(),
                0, 0, true, true, false).apply(player);
        near(ctx, y, player.getY(), "unknown sample has no transport credit");
        near(ctx, cabin.getX() + .1, player.getX(), "fallback still uses vanilla walking");
        player.discard(); cabin.discard(); ctx.complete();
    }

    private static void door(TestContext ctx, BlockPos root) {
        for (int column = 0; column < 3; column++) for (int level = 0; level < 3; level++)
            ctx.getWorld().setBlockState(root.offset(Direction.WEST, column - 1).up(level),
                    Easyelevator.LANDING_DOOR.getDefaultState().with(LandingDoorBlock.FACING, Direction.SOUTH)
                            .with(LandingDoorBlock.COLUMN, column).with(LandingDoorBlock.LEVEL, level), 2);
    }

    private static void trip(TestContext ctx, EntityType<? extends AbstractCabinEntity> type, boolean up) {
        var cabin = cabin(ctx, type);
        BlockPos rail = new BlockPos(cabin.railX(), (int) cabin.getY(), cabin.railZ());
        // The built-in empty structure has boundary barriers; open the entire test shaft explicitly.
        for (BlockPos pos : BlockPos.iterate(rail.add(-1, 0, 1), rail.add(1, 9, 3)))
            ctx.getWorld().setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
        for (int y = 0; y <= 8; y++) ctx.getWorld().setBlockState(rail.up(y),
                Easyelevator.RAIL.getDefaultState().with(ElevatorRailBlock.FACING, Direction.SOUTH), 2);
        door(ctx, rail.south(3)); door(ctx, rail.up(6).south(3));
        if (!up) cabin.setPosition(cabin.getX(), rail.getY() + 6, cabin.getZ());
        var player = player(ctx, cabin);
        double target = rail.getY() + (up ? 6 : 0);
        ctx.assertTrue(cabin.requestStop(rail.up(up ? 6 : 0).south(3)), "trip accepted");
        // Run the actual entity/controller synchronously so vanilla fake-player ticking adds no unrelated motion.
        boolean arrived = false;
        for (int i = 0; i < 400; i++) {
            cabin.tick();
            near(ctx, cabin.getY() + .2, player.getY(), "floor/rider alignment including arrival step");
            ctx.assertTrue(((PlatformMovement) player.networkHandler).easyelevator$canCarry(), "ride must not teleport");
            if (Math.abs(cabin.getY() - target) < 1e-7 && cabin.phase() == ElevatorController.Phase.OPENING) {
                arrived = true; break;
            }
        }
        ctx.assertTrue(arrived, "must actually arrive: phase=" + cabin.phase() + " y=" + cabin.getY()
                + " target=" + target + " line=" + cabin.line() + " clear="
                + cabin.spaceClear(cabin.getBoundingBox().union(cabin.getBoundingBox().offset(0, target-cabin.getY(), 0)).contract(.001)));
        ctx.assertTrue(ctx.getWorld().isSpaceEmpty(player, player.getBoundingBox().contract(1e-7)),
                "standing body must fit after arrival");
        // Leave during OPENING: the online roster must not teleport the rider back.
        player.setPosition(player.getX(), target + .2, cabin.getZ() + 1.2);
        cabin.tick();
        near(ctx, cabin.getZ() + 1.2, player.getZ(), "exit retained");
        player.discard(); cabin.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE) public void normalUpArrival(TestContext ctx) { trip(ctx, Easyelevator.CABIN, true); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void normalDownArrival(TestContext ctx) { trip(ctx, Easyelevator.CABIN, false); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void expressUpArrival(TestContext ctx) { trip(ctx, Easyelevator.HIGH_SPEED_CABIN, true); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void expressDownArrival(TestContext ctx) { trip(ctx, Easyelevator.HIGH_SPEED_CABIN, false); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void glassUpArrival(TestContext ctx) { trip(ctx, Easyelevator.OBSERVATION_CABIN, true); }
    @GameTest(templateName = EMPTY_STRUCTURE) public void glassDownArrival(TestContext ctx) { trip(ctx, Easyelevator.OBSERVATION_CABIN, false); }
}
