package org.DJB.easyelevator;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.GameMode;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.entity.PowerfulCabinEntity;
import org.DJB.easyelevator.logic.CargoLoad;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.logic.ElevatorController.Phase;
import org.DJB.easyelevator.screen.CargoScreenHandler;

import java.util.ArrayList;
import java.util.List;

public class CargoTests implements FabricGameTest {
    private PowerfulCabinEntity cabin(TestContext ctx) {
        BlockPos rail = ctx.getAbsolutePos(new BlockPos(2, 4, 1));
        for (BlockPos pos : BlockPos.iterate(rail.add(-1,0,1), rail.add(1,10,3)))
            ctx.getWorld().setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
        for (int y = 0; y < 11; y++) ctx.getWorld().setBlockState(rail.up(y),
                Easyelevator.RAIL.getDefaultState().with(ElevatorRailBlock.FACING, Direction.SOUTH));
        for (int y : new int[]{0, 6}) {
            BlockPos stop = rail.up(y).south(3);
            for (int column=0; column<3; column++) for (int level=0; level<3; level++)
                ctx.getWorld().setBlockState(stop.offset(Direction.WEST, column-1).up(level),
                        Easyelevator.LANDING_DOOR.getDefaultState().with(LandingDoorBlock.FACING, Direction.SOUTH)
                                .with(LandingDoorBlock.COLUMN, column).with(LandingDoorBlock.LEVEL, level), 2);
        }
        PowerfulCabinEntity cabin = Easyelevator.POWERFUL_CABIN.create(ctx.getWorld());
        cabin.initialize(rail, Direction.SOUTH);
        ctx.getWorld().spawnEntity(cabin);
        return cabin;
    }

    private PlayerEntity viewer(TestContext ctx, PowerfulCabinEntity cabin) {
        PlayerEntity player = ctx.createMockPlayer(GameMode.SURVIVAL);
        player.setPosition(cabin.getX(), cabin.getY() + .2, cabin.getZ() + .2);
        return player;
    }

    private void fill(PowerfulCabinEntity cabin, int amount) {
        cabin.cargo().clear();
        for (int i = 0; amount > 0; i++) {
            int count = Math.min(64, amount);
            cabin.cargo().setStack(i, new ItemStack(Items.COBBLESTONE, count));
            amount -= count;
        }
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void quantityBoundaries(TestContext ctx) {
        var cabin = cabin(ctx);
        int[][] cases = {{0,20,0},{1,19,1},{128,19,1},{129,18,1},{288,17,1},{289,17,2},{1728,6,6}};
        for (int[] value : cases) {
            fill(cabin, value[0]);
            ctx.assertTrue(cabin.cargoItems() == value[0] && cabin.passengerNumLimit() == value[1]
                    && cabin.cargoCrates() == value[2], "quantity boundary " + value[0]);
        }
        cabin.cargo().clear();
        ctx.assertTrue(cabin.passengerNumLimit() == 20, "unloading restores all 20 seats");
        ctx.assertTrue(Easyelevator.CABIN.create(ctx.getWorld()).passengerNumLimit() == ElevatorParameters.PASSENGER_NUM_LIMIT
                && Easyelevator.HIGH_SPEED_CABIN.create(ctx.getWorld()).passengerNumLimit() == ElevatorParameters.PASSENGER_NUM_LIMIT
                && Easyelevator.OBSERVATION_CABIN.create(ctx.getWorld()).passengerNumLimit() == ElevatorParameters.PASSENGER_NUM_LIMIT,
                "other cabins retain the existing configured capacity");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void saveSlotsComponentsAndLegacy(TestContext ctx) {
        var cabin = cabin(ctx);
        ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
        named.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Cargo identity"));
        cabin.cargo().setStack(26, named);
        cabin.cargo().setStack(7, new ItemStack(Items.ENDER_PEARL, 16));
        NbtCompound nbt = new NbtCompound();
        cabin.writeNbt(nbt);
        cabin.cargo().clear();
        cabin.readNbt(nbt);
        ctx.assertTrue(ItemStack.areEqual(cabin.cargo().getStack(26), named)
                && cabin.cargo().getStack(7).getCount() == 16 && cabin.cargo().getStack(0).isEmpty()
                && cabin.cargoItems() == 17, "slot positions and stack components survive save/load");
        nbt.remove("Cargo");
        cabin.readNbt(nbt);
        ctx.assertTrue(cabin.cargo().isEmpty() && cabin.passengerNumLimit() == 20, "old saves start empty");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void transferAndStaleScreen(TestContext ctx) {
        var cabin = cabin(ctx);
        var player = viewer(ctx, cabin);
        var handler = new CargoScreenHandler(1, player.getInventory(), cabin);
        player.getInventory().setStack(9, new ItemStack(Items.IRON_INGOT, 64));
        handler.quickMove(player, 27);
        ctx.assertTrue(cabin.cargoItems() == 64 && player.getInventory().getStack(9).isEmpty(), "shift into cargo");
        handler.quickMove(player, 0);
        ctx.assertTrue(cabin.cargoItems() == 0 && player.getInventory().count(Items.IRON_INGOT) == 64, "shift out without duplication");
        handler.setCursorStack(new ItemStack(Items.DIAMOND, 12));
        handler.onSlotClick(1, 0, SlotActionType.PICKUP, player);
        ctx.assertTrue(cabin.cargoItems() == 12 && handler.getCursorStack().isEmpty(), "cursor placement");
        // Number-key swap preserves both stacks and recalculates the weight.
        player.getInventory().setStack(0, new ItemStack(Items.GOLD_INGOT, 32));
        handler.onSlotClick(1, 0, SlotActionType.SWAP, player);
        ctx.assertTrue(cabin.cargoItems() == 32 && player.getInventory().getStack(0).getCount() == 12, "hotbar swap");
        player.setPosition(cabin.getX() + 20, cabin.getY(), cabin.getZ());
        handler.onSlotClick(1, 0, SlotActionType.PICKUP, player);
        ctx.assertTrue(!handler.canUse(player) && cabin.cargoItems() == 32 && handler.getCursorStack().isEmpty(), "stale remote clicks rejected");
        cabin.cargo().clear();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void cratesCannotAppearInsideLivingEntities(TestContext ctx) {
        var cabin = cabin(ctx);
        var player = viewer(ctx, cabin);
        var handler = new CargoScreenHandler(1, player.getInventory(), cabin);
        var blocker = EntityType.ARMOR_STAND.create(ctx.getWorld());
        var box = cabin.cargoBox(0);
        blocker.setPosition(box.getCenter().x, box.minY, box.getCenter().z);
        ctx.getWorld().spawnEntity(blocker);
        player.getInventory().setStack(9, new ItemStack(Items.COBBLESTONE, 64));
        handler.quickMove(player, 27);
        ctx.assertTrue(cabin.cargoItems() == 0 && player.getInventory().getStack(9).getCount() == 64, "blocked first crate rejects items");
        blocker.discard();
        handler.quickMove(player, 27);
        ctx.assertTrue(cabin.cargoItems() == 64, "loading resumes when rear is clear");
        fill(cabin, 288);
        blocker = EntityType.ARMOR_STAND.create(ctx.getWorld());
        box = cabin.cargoBox(1);
        blocker.setPosition(box.getCenter().x, box.minY, box.getCenter().z);
        ctx.getWorld().spawnEntity(blocker);
        player.getInventory().setStack(9, new ItemStack(Items.COBBLESTONE, 64));
        handler.quickMove(player, 27);
        ctx.assertTrue(cabin.cargoItems() == 288, "second crate boundary rejects a merge and new slots");
        blocker.discard();
        cabin.cargo().clear();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void collisionGeometryAllFacings(TestContext ctx) {
        var cabin = cabin(ctx);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            cabin.initialize(ctx.getAbsolutePos(new BlockPos(1,2,1)), direction);
            fill(cabin, 1728);
            ctx.assertTrue(cabin.collisionBoxesStatic().size() == 11, "six solid crates plus five shell boxes");
            for (int i = 0; i < 6; i++) {
                var local = CargoLoad.box(i);
                ctx.assertTrue(local.maxZ < .95 && local.maxY < 1.1 && local.minY >= .2,
                        "crate stays clear of door, plaque and floor");
                ctx.assertTrue(cabin.collisionBoxes().contains(cabin.cargoBox(i)), "render geometry is also collidable");
                var player = viewer(ctx, cabin);
                var worldBox = cabin.cargoBox(i);
                player.setPosition(worldBox.getCenter().x, worldBox.maxY, worldBox.getCenter().z);
                ctx.assertTrue(cabin.supportsPassenger(player), "crate tops support riders in every facing");
                for (int j = 0; j < i; j++) ctx.assertTrue(!cabin.cargoBox(i).intersects(cabin.cargoBox(j)), "crates never intersect");
            }
        }
        cabin.cargo().clear();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void recoveryDropsOnceAndUnloadingPreserves(TestContext ctx) {
        var cabin = cabin(ctx);
        cabin.cargo().setStack(0, new ItemStack(Items.DIAMOND, 37));
        NbtCompound saved = new NbtCompound();
        cabin.writeNbt(saved);
        cabin.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        ctx.assertTrue(cabin.cargoItems() == 37, "chunk unload keeps inventory");
        var restored = Easyelevator.POWERFUL_CABIN.create(ctx.getWorld());
        restored.readNbt(saved);
        ctx.getWorld().spawnEntity(restored);
        var bounds = restored.getBoundingBox().expand(2);
        restored.discard();
        restored.discard();
        int diamonds = ctx.getWorld().getEntitiesByClass(ItemEntity.class, bounds, e -> e.getStack().isOf(Items.DIAMOND))
                .stream().mapToInt(e -> e.getStack().getCount()).sum();
        ctx.assertTrue(diamonds == 37 && restored.cargoItems() == 0, "recovery drops the cargo exactly once");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void overloadedCargoRecoversAfterUnloading(TestContext ctx) {
        var cabin = cabin(ctx);
        fill(cabin, 1728);
        List<PlayerEntity> players = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            var player = ctx.createMockCreativeServerPlayerInWorld();
            player.setPosition(cabin.getX(), cabin.getY()+.2, cabin.getZ()+.2);
            players.add(player);
        }
        cabin.tick();
        ctx.assertTrue(cabin.overloaded() && cabin.phase() == Phase.OVERLOAD, "7 people exceed the loaded 6-person limit");
        cabin.cargo().clear();
        cabin.tick();
        ctx.assertTrue(!cabin.overloaded() && cabin.phase() != Phase.OVERLOAD && cabin.passengerNumLimit() == 20,
                "unloading clears overload");
        players.forEach(Entity::discard);
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void loadingHoldsDoorAndAccessFollowsPassenger(TestContext ctx) {
        var cabin = cabin(ctx);
        var player = ctx.createMockCreativeServerPlayerInWorld();
        player.setPosition(cabin.getX(), cabin.getY()+.2, cabin.getZ()+.2);
        player.setSneaking(true);
        cabin.interact(player, Hand.MAIN_HAND);
        ctx.assertTrue(player.currentScreenHandler instanceof CargoScreenHandler, "sneak interaction opens cargo");
        for (int tick=0; tick<200; tick++) cabin.tick();
        ctx.assertTrue(cabin.doorProgress(1) == 1 && !cabin.doorCommand(false), "loading holds doors beyond dwell timer");
        player.closeHandledScreen();
        ctx.assertTrue(cabin.doorCommand(false), "closing container releases door");
        cabin.tick();
        // 货舱能不能操作只看"还是不是厢内乘客"（见 PowerfulCabinEntity.canUseCargo）：与门开没开、
        // 相位是什么无关。因此关掉面板、门开始关之后，人还在厢内就仍可再开；走出厢门则立即失效。
        ctx.assertTrue(cabin.canUseCargo(player), "still inside keeps cargo access after the container closes");
        player.setPosition(cabin.getX() + 8, cabin.getY()+.2, cabin.getZ());
        ctx.assertTrue(!cabin.canUseCargo(player), "leaving the cabin invalidates cargo access");
        player.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void dragAndSharedInventory(TestContext ctx) {
        var cabin = cabin(ctx);
        var player = viewer(ctx, cabin);
        var first = new CargoScreenHandler(1, player.getInventory(), cabin);
        var secondPlayer = viewer(ctx, cabin);
        var second = new CargoScreenHandler(2, secondPlayer.getInventory(), cabin);
        first.setCursorStack(new ItemStack(Items.EMERALD, 64));
        first.onSlotClick(-999, 0, SlotActionType.QUICK_CRAFT, player);
        first.onSlotClick(0, 1, SlotActionType.QUICK_CRAFT, player);
        first.onSlotClick(1, 1, SlotActionType.QUICK_CRAFT, player);
        first.onSlotClick(-999, 2, SlotActionType.QUICK_CRAFT, player);
        ctx.assertTrue(cabin.cargoItems() == 64 && cabin.cargo().getStack(0).getCount() == 32
                && cabin.cargo().getStack(1).getCount() == 32 && first.getCursorStack().isEmpty(), "drag split conserves count");
        second.onSlotClick(0, 0, SlotActionType.PICKUP, secondPlayer);
        first.onSlotClick(0, 0, SlotActionType.PICKUP, player);
        ctx.assertTrue(second.getCursorStack().getCount() == 32 && first.getCursorStack().isEmpty()
                && cabin.cargoItems() == 32, "two viewers cannot take the same stack twice");
        fill(cabin, 1728);
        player.getInventory().setStack(9, new ItemStack(Items.COBBLESTONE, 64));
        first.quickMove(player, 27);
        ctx.assertTrue(cabin.cargoItems() == 1728 && player.getInventory().getStack(9).getCount() == 64,
                "full cargo rejects excess without loss");
        cabin.cargo().clear();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void fullCargoTravelsBothWays(TestContext ctx) {
        var cabin = cabin(ctx);
        BlockPos lower = ctx.getAbsolutePos(new BlockPos(2,4,4));
        fill(cabin, 1728);
        for (BlockPos target : List.of(lower.up(6), lower)) {
            ctx.assertTrue(cabin.requestStop(target), "loaded trip request accepted");
            for(int tick=0;tick<600 && (Math.abs(cabin.getY()-target.getY())>.00001
                    || cabin.phase()!=Phase.OPEN);tick++) cabin.tick();
            ctx.assertTrue(Math.abs(cabin.getY()-target.getY())<.00001 && cabin.phase()==Phase.OPEN,
                    "full cargo reaches destination and opens: y="+cabin.getY()+" target="+target.getY()+" phase="+cabin.phase());
            ctx.assertTrue(cabin.cargoItems()==1728 && cabin.passengerNumLimit()==6, "trip preserves inventory and capacity");
        }
        cabin.cargo().clear();
        ctx.complete();
    }
}
