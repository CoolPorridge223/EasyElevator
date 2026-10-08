package org.DJB.easyelevator.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.logic.CargoLoad;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.screen.CargoScreenHandler;

import java.util.List;

/** 重载轿厢：空载 20 人；持久化货舱、货箱碰撞和动态载客上限只作用于本型号。 */
public class PowerfulCabinEntity extends AbstractCabinEntity {
    private static final TrackedData<Integer> CARGO_ITEMS = DataTracker.registerData(
            PowerfulCabinEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private final SimpleInventory cargo = new SimpleInventory(CargoLoad.SLOTS);

    public PowerfulCabinEntity(EntityType<?> type, World world) {
        super(type, world, ElevatorParameters.SPEED, ElevatorParameters.HIGH_PASSENGER_NUM_LIMIT);
        cargo.addListener(inventory -> syncCargo());
    }

    @Override protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(CARGO_ITEMS, 0);
    }

    public SimpleInventory cargo() { return cargo; }
    public int cargoItems() { return dataTracker.get(CARGO_ITEMS); }
    public int cargoCrates() { return CargoLoad.crates(cargoItems()); }

    private void syncCargo() {
        if (!getWorld().isClient) {
            int count = 0;
            for (int i = 0; i < cargo.size(); i++) count += cargo.getStack(i).getCount();
            dataTracker.set(CARGO_ITEMS, count);
        }
    }

    @Override public int passengerNumLimit() { return CargoLoad.passengers(cargoItems()); }
    @Override protected Item cabinItem() { return Easyelevator.POWERFUL_CABIN_ITEM; }
    @Override public boolean heavyDuty() { return true; }

    /** 装卸只在开门停靠时进行；离厢、跨世界、死亡或实体移除后容器立即失效。 */
    public boolean canUseCargo(PlayerEntity player) {
        return isAlive() && player.isAlive() && !player.isSpectator() && player.getWorld() == getWorld()
                && containsPassenger(player) && doorProgress(1) >= .999f
                && (phase() == ElevatorController.Phase.OPEN || phase() == ElevatorController.Phase.OPENING
                    || phase() == ElevatorController.Phase.OVERLOAD);
    }

    private boolean cargoInUse() {
        return getWorld().getPlayers().stream().anyMatch(player ->
                player.currentScreenHandler instanceof CargoScreenHandler handler
                        && handler.cabin() == this && canUseCargo(player));
    }

    @Override public void tick() {
        if (!getWorld().isClient && cargoInUse()) super.doorCommand(true);
        super.tick();
    }

    @Override public boolean doorCommand(boolean open) {
        if (!open && cargoInUse()) return false;
        return super.doorCommand(open);
    }

    @Override public ActionResult interact(PlayerEntity player, Hand hand) {
        if (hand == Hand.MAIN_HAND && player.isSneaking() && containsPassenger(player)) {
            if (!getWorld().isClient) {
                if (canUseCargo(player)) {
                    player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                            (syncId, inventory, viewer) -> new CargoScreenHandler(syncId, inventory, this),
                            Text.translatable("screen.easyelevator.cargo")));
                } else player.sendMessage(Text.translatable("message.easyelevator.cargo_stopped"), true);
            }
            return ActionResult.SUCCESS;
        }
        return super.interact(player, hand);
    }

    public Box cargoBox(int index) {
        Box box = CargoLoad.box(index);
        return localBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /** 不允许新出现的实体货箱与人/动物重叠；已存在的货箱不影响继续填装。 */
    public int cargoSlotLimit(int slot) {
        return Math.max(0, Math.min(64, cargoSpaceLimit() - cargoItems() + cargo.getStack(slot).getCount()));
    }

    public boolean cargoSpaceBlocked() {
        int limit = cargoSpaceLimit();
        return limit < CargoLoad.MAX_ITEMS && limit <= cargoItems();
    }

    private int cargoSpaceLimit() {
        for (int i = cargoCrates(); i < CargoLoad.MAX_CRATES; i++) {
            if (!getWorld().getOtherEntities(this, cargoBox(i), e -> e instanceof LivingEntity && !e.isSpectator()).isEmpty()) {
                return i * CargoLoad.ITEMS_PER_CRATE;
            }
        }
        return CargoLoad.MAX_ITEMS;
    }

    @Override public List<Box> collisionBoxesStatic() {
        List<Box> boxes = super.collisionBoxesStatic();
        for (int i = 0; i < cargoCrates(); i++) boxes.add(cargoBox(i));
        return boxes;
    }

    /** 站在货箱顶部也属于实体支撑，避免把移动中的箱顶乘客判为浮空。 */
    @Override public boolean supportsPassenger(Entity entity) {
        if (super.supportsPassenger(entity)) return true;
        if (!containsPassenger(entity)) return false;
        Box feet = entity.getBoundingBox();
        for (int i=0; i<cargoCrates(); i++) {
            Box box = cargoBox(i);
            if (Math.abs(entity.getY()-box.maxY) < .025 && feet.maxX > box.minX && feet.minX < box.maxX
                    && feet.maxZ > box.minZ && feet.minZ < box.maxZ) return true;
        }
        return false;
    }

    @Override protected void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.put("Cargo", Inventories.writeNbt(new NbtCompound(), cargo.getHeldStacks(), getRegistryManager()));
    }

    @Override protected void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        cargo.clear();
        Inventories.readNbt(nbt.getCompound("Cargo"), cargo.getHeldStacks(), getRegistryManager());
        syncCargo();
    }

    /** 回收和 /kill 掉货一次；区块卸载、停服和跨维度移除必须保留库存供保存/转移。 */
    @Override public void remove(RemovalReason reason) {
        if (!getWorld().isClient && !isRemoved()
                && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED)) {
            for (int i = 0; i < cargo.size(); i++) {
                var stack = cargo.removeStack(i);
                if (!stack.isEmpty()) dropStack(stack);
            }
            syncCargo();
        }
        super.remove(reason);
    }
}
