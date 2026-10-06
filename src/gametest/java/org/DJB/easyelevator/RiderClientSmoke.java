package org.DJB.easyelevator;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityPose;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.slf4j.LoggerFactory;

/** Opt-in, disposable-world integration smoke. Never included in the released mod. */
public class RiderClientSmoke implements ClientModInitializer {
    private int ticks;
    private int startupTicks;
    private volatile int cabinId = -1;
    private boolean setup, requested, jumped, returned, exiting, reentering, exitChecked;
    private int arrivalTicks;
    private double maxJump, maxHorizontal, startX;

    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("easyelevator.riderSmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null || client.getServer() == null) {
                if (++startupTicks % 100 == 0) LoggerFactory.getLogger("rider-smoke").info("Waiting for test world: {}", client.currentScreen);
                if (startupTicks > 1200) {
                    LoggerFactory.getLogger("rider-smoke").error("RIDER_CLIENT_SMOKE_FAILED: startup timeout");
                    client.scheduleStop();
                }
                return;
            }
            try { tick(client); }
            catch (Throwable error) {
                LoggerFactory.getLogger("rider-smoke").error("RIDER_CLIENT_SMOKE_FAILED", error);
                client.options.rightKey.setPressed(false);
                client.options.jumpKey.setPressed(false);
                client.options.forwardKey.setPressed(false);
                client.options.backKey.setPressed(false);
                client.scheduleStop();
            }
        });
    }

    private void tick(MinecraftClient client) {
        ticks++;
        if (!setup) {
            setup = true;
            client.getServer().execute(() -> {
                var world = client.getServer().getOverworld();
                world.getEntitiesByClass(AbstractCabinEntity.class,
                        new net.minecraft.util.math.Box(-3, 78, -2, 5, 110, 8), c -> true)
                        .forEach(AbstractCabinEntity::discard);
                BlockPos rail = new BlockPos(0, 80, 0);
                for (BlockPos p : BlockPos.iterate(new BlockPos(-3, 79, -2), new BlockPos(4, 108, 6)))
                    world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                for (int y = 0; y <= 23; y++) world.setBlockState(rail.up(y),
                        Easyelevator.RAIL.getDefaultState().with(ElevatorRailBlock.FACING, Direction.SOUTH), 2);
                for (int y : new int[]{0, 20}) {
                    for (int x = -1; x <= 1; x++) for (int z = 4; z <= 7; z++)
                        world.setBlockState(new BlockPos(x, 79 + y, z), Blocks.STONE.getDefaultState(), 2);
                    BlockPos root = rail.up(y).south(3);
                    for (int column = 0; column < 3; column++) for (int level = 0; level < 3; level++)
                        world.setBlockState(root.offset(Direction.WEST, column - 1).up(level),
                                Easyelevator.LANDING_DOOR.getDefaultState().with(LandingDoorBlock.FACING, Direction.SOUTH)
                                        .with(LandingDoorBlock.COLUMN, column).with(LandingDoorBlock.LEVEL, level), 2);
                }
                var cabin = Easyelevator.HIGH_SPEED_CABIN.create(world);
                cabin.initialize(rail, Direction.SOUTH);
                world.spawnEntity(cabin);
                cabinId = cabin.getId();
                ServerPlayerEntity player = client.getServer().getPlayerManager().getPlayer(client.player.getUuid());
                player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                player.networkHandler.requestTeleport(cabin.getX(), 80.2, cabin.getZ(), 0, 0);
            });
        }
        if (ticks > 900) throw new AssertionError("client round trip timed out");
        if (!(client.world.getEntityById(cabinId) instanceof AbstractCabinEntity cabin)) return;
        if (!requested && ticks > 40 && cabin.containsPassenger(client.player)) {
            requested = true;
            startX = client.player.getX();
            client.getServer().execute(() -> ((AbstractCabinEntity) client.getServer().getOverworld()
                    .getEntityById(cabinId)).requestStop(new BlockPos(0, 100, 3)));
        }
        if (requested && cabin.getY() > 83 && cabin.getY() < 88 && !jumped) {
            jumped = true;
            client.options.jumpKey.setPressed(true);
            client.options.rightKey.setPressed(true);
        } else {
            client.options.jumpKey.setPressed(false);
            client.options.rightKey.setPressed(false);
        }
        if (requested) {
            maxJump = Math.max(maxJump, client.player.getY() - cabin.getY() - .2);
            maxHorizontal = Math.max(maxHorizontal, Math.abs(client.player.getX() - startX));
            if (client.player.getPose() == EntityPose.SWIMMING) throw new AssertionError("rider forced crawling");
            if (!exiting && !reentering && client.player.getY() < cabin.getY() + .14)
                throw new AssertionError("rider fell through floor");
        }
        if (!returned && !exiting && !reentering && !exitChecked && cabin.getY() == 100 && cabin.phase() == ElevatorController.Phase.OPEN) {
            exiting = true;
            if (maxJump < .2 || maxHorizontal < .005) throw new AssertionError("input not preserved: jump=" + maxJump + " walk=" + maxHorizontal);
        }
        if (exiting || reentering) {
            client.getServer().execute(() -> ((AbstractCabinEntity) client.getServer().getOverworld()
                    .getEntityById(cabinId)).doorCommand(true));
            client.options.forwardKey.setPressed(exiting);
            client.options.backKey.setPressed(reentering);
            if (exiting && client.player.getZ() > 5.2) {
                exiting = false; reentering = true; exitChecked = true;
                client.options.forwardKey.setPressed(false);
            } else if (reentering && client.player.getZ() <= 2.6 && cabin.containsPassenger(client.player)) {
                reentering = false; returned = true;
                client.options.backKey.setPressed(false);
                LoggerFactory.getLogger("rider-smoke").info("RIDER_EXIT_REENTRY_PASSED: no crawling after upward arrival");
                client.getServer().execute(() -> ((AbstractCabinEntity) client.getServer().getOverworld()
                        .getEntityById(cabinId)).requestStop(new BlockPos(0, 80, 3)));
            }
        }
        if (returned && cabin.getY() == 80 && cabin.phase() == ElevatorController.Phase.OPEN) {
            if (++arrivalTicks < 10) return;
            LoggerFactory.getLogger("rider-smoke").info("RIDER_CLIENT_SMOKE_PASSED: round trip, jump={}, walk={}, standing={}",
                    maxJump, maxHorizontal, client.player.getPose());
            client.scheduleStop();
        }
    }
}
