package org.DJB.easyelevator;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.client.CargoScreen;
import org.DJB.easyelevator.entity.PowerfulCabinEntity;
import org.DJB.easyelevator.screen.CargoScreenHandler;
import org.slf4j.LoggerFactory;

/** 独立 CargoSmoke 测试世界：真实开容器、点击同步、卸货和模型截图，不接触用户存档。 */
public class CargoClientSmoke implements ClientModInitializer {
    private int ticks, startup;
    private volatile int cabinId = -1;

    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("easyelevator.cargoSmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null || client.getServer() == null) {
                if (++startup > 1800) {
                    LoggerFactory.getLogger("cargo-smoke").error("CARGO_CLIENT_SMOKE_FAILED: startup {}", client.currentScreen);
                    client.scheduleStop();
                }
                return;
            }
            try { tick(client); }
            catch (Throwable error) {
                LoggerFactory.getLogger("cargo-smoke").error("CARGO_CLIENT_SMOKE_FAILED", error);
                client.options.sneakKey.setPressed(false);
                client.scheduleStop();
            }
        });
    }

    private void tick(MinecraftClient client) {
        ticks++;
        if (ticks == 1) client.getServer().execute(() -> {
            var world = client.getServer().getOverworld();
            world.getEntitiesByClass(PowerfulCabinEntity.class,
                    new net.minecraft.util.math.Box(-3,79,-2,4,92,7), e -> true).forEach(PowerfulCabinEntity::discard);
            for (BlockPos pos : BlockPos.iterate(new BlockPos(-3,79,-2), new BlockPos(4,92,7)))
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
            BlockPos rail = new BlockPos(0,80,0);
            for (int y=0; y<11; y++) world.setBlockState(rail.up(y),
                    Easyelevator.RAIL.getDefaultState().with(ElevatorRailBlock.FACING,Direction.SOUTH), 2);
            for (int y:new int[]{0,6}) {
                BlockPos root = rail.up(y).south(3);
                for (int column=0; column<3; column++) for (int level=0; level<3; level++)
                    world.setBlockState(root.offset(Direction.WEST,column-1).up(level),
                            Easyelevator.LANDING_DOOR.getDefaultState().with(LandingDoorBlock.FACING,Direction.SOUTH)
                                    .with(LandingDoorBlock.COLUMN,column).with(LandingDoorBlock.LEVEL,level),2);
            }
            var cabin = Easyelevator.POWERFUL_CABIN.create(world);
            cabin.initialize(rail,Direction.SOUTH);
            world.spawnEntity(cabin);
            cabinId = cabin.getId();
            var player = client.getServer().getPlayerManager().getPlayer(client.player.getUuid());
            player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
            player.networkHandler.requestTeleport(cabin.getX(),80.2,cabin.getZ()+.8,180,24);
            for(int slot=0;slot<27;slot++) cabin.cargo().setStack(slot,new ItemStack(Items.IRON_INGOT,64));
        });
        if (!(client.world.getEntityById(cabinId) instanceof PowerfulCabinEntity cabin)) {
            if(ticks>200) throw new AssertionError("cabin not tracked");
            return;
        }
        // Hold open until the screenshot/interaction sequence has finished.
        client.getServer().execute(() -> {
            var serverCabin = (PowerfulCabinEntity)client.getServer().getOverworld().getEntityById(cabinId);
            if(serverCabin!=null) serverCabin.doorCommand(true);
        });
        if(ticks==60) {
            if(cabin.cargoItems()!=1728 || cabin.passengerNumLimit()!=6 || cabin.cargoCrates()!=6)
                throw new AssertionError("client cargo tracking mismatch");
            shot(client,"cargo-full-crates.png");
            client.options.sneakKey.setPressed(true);
        }
        if(ticks==70) client.interactionManager.interactEntity(client.player,cabin,Hand.MAIN_HAND);
        if(ticks==90) {
            client.options.sneakKey.setPressed(false);
            if(!(client.currentScreen instanceof CargoScreen)
                    || !(client.player.currentScreenHandler instanceof CargoScreenHandler handler)
                    || handler.cargoItems()!=1728 || handler.passengerLimit()!=6)
                throw new AssertionError("cargo screen did not open/sync");
            shot(client,"cargo-full-panel.png");
            client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId,0,0,SlotActionType.QUICK_MOVE,client.player);
        }
        if(ticks==110) {
            var handler=(CargoScreenHandler)client.player.currentScreenHandler;
            if(handler.cargoItems()!=1664 || client.player.getInventory().count(Items.IRON_INGOT)!=64)
                throw new AssertionError("network shift transfer mismatch");
            // Close the container, then clear the isolated test cargo for empty-state QA.
            client.player.closeHandledScreen();
            client.getServer().execute(() -> ((PowerfulCabinEntity)client.getServer().getOverworld()
                    .getEntityById(cabinId)).cargo().clear());
        }
        if(ticks==135) {
            if(cabin.cargoItems()!=0 || cabin.passengerNumLimit()!=20 || cabin.cargoCrates()!=0)
                throw new AssertionError("unloading did not restore client capacity/geometry");
            shot(client,"cargo-empty-crates.png");
            client.options.sneakKey.setPressed(true);
        }
        if(ticks==145) client.interactionManager.interactEntity(client.player,cabin,Hand.MAIN_HAND);
        if(ticks==165) {
            client.options.sneakKey.setPressed(false);
            if(!(client.currentScreen instanceof CargoScreen)) throw new AssertionError("empty cargo screen");
            shot(client,"cargo-empty-panel.png");
            LoggerFactory.getLogger("cargo-smoke").info("CARGO_CLIENT_SMOKE_PASSED: tracked cargo, sneak interaction, menu sync, network transfer, empty restore");
        }
        if(ticks==185) client.scheduleStop();
    }

    private void shot(MinecraftClient client,String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory,name,client.getFramebuffer(),
                message -> LoggerFactory.getLogger("cargo-smoke").info("{}",message.getString()));
    }
}
