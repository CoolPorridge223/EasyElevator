package org.DJB.easyelevator.screen;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.PowerfulCabinEntity;
import org.DJB.easyelevator.logic.CargoLoad;

/** 原版容器事务提供拖放、快捷搬运、多人同步和光标物品回收。 */
public class CargoScreenHandler extends ScreenHandler {
    private final PowerfulCabinEntity cabin;
    private final Inventory cargo;
    private final PropertyDelegate properties;

    public CargoScreenHandler(int syncId, PlayerInventory playerInventory) {
        this(syncId, playerInventory, null, new SimpleInventory(CargoLoad.SLOTS), new ArrayPropertyDelegate(1));
    }

    public CargoScreenHandler(int syncId, PlayerInventory playerInventory, PowerfulCabinEntity cabin) {
        this(syncId, playerInventory, cabin, cabin.cargo(), new PropertyDelegate() {
            public int get(int index) { return cabin.cargoItems(); }
            public void set(int index, int value) {}
            public int size() { return 1; }
        });
    }

    private CargoScreenHandler(int syncId, PlayerInventory playerInventory, PowerfulCabinEntity cabin,
                               Inventory cargo, PropertyDelegate properties) {
        super(Easyelevator.CARGO_SCREEN, syncId);
        this.cabin = cabin;
        this.cargo = cargo;
        this.properties = properties;
        addProperties(properties);
        for (int row = 0; row < 3; row++) for (int column = 0; column < 9; column++) {
            final int index = column + row * 9;
            addSlot(new Slot(cargo, index, 8 + column * 18, 54 + row * 18) {
                @Override public boolean canInsert(ItemStack stack) {
                    return getMaxItemCount(stack) > 0;
                }
                @Override public int getMaxItemCount() {
                    return cabin == null ? 64 : cabin.cargoSlotLimit(index);
                }
                @Override public int getMaxItemCount(ItemStack stack) {
                    return Math.min(stack.getMaxCount(), getMaxItemCount());
                }
            });
        }
        for (int row = 0; row < 3; row++) for (int column = 0; column < 9; column++)
            addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, 128 + row * 18));
        for (int column = 0; column < 9; column++)
            addSlot(new Slot(playerInventory, column, 8 + column * 18, 186));
    }

    public PowerfulCabinEntity cabin() { return cabin; }
    public int cargoItems() { return properties.get(0); }
    public int passengerLimit() { return CargoLoad.passengers(cargoItems()); }

    @Override public boolean canUse(PlayerEntity player) { return cabin == null || cabin.canUseCargo(player); }

    @Override public void onSlotClick(int slotIndex, int button, SlotActionType action, PlayerEntity player) {
        if (!canUse(player)) return;
        int before = cabin == null ? 0 : cabin.cargoItems();
        super.onSlotClick(slotIndex, button, action, player);
        cargo.markDirty();
        if (cabin != null && cabin.cargoItems() == before && cabin.cargoSpaceBlocked())
            player.sendMessage(Text.translatable("message.easyelevator.cargo_space"), true);
    }

    @Override public ItemStack quickMove(PlayerEntity player, int index) {
        if (!canUse(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasStack()) return ItemStack.EMPTY;
        ItemStack stack = slot.getStack();
        ItemStack original = stack.copy();
        if (index < CargoLoad.SLOTS) {
            if (!insertItem(stack, CargoLoad.SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // Refresh the weight before considering the next slot's crate collision guard.
            boolean moved = false;
            for (int i = 0; i < CargoLoad.SLOTS && !stack.isEmpty(); i++) {
                moved |= insertItem(stack, i, i + 1, false);
                cargo.markDirty();
            }
            if (!moved) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setStack(ItemStack.EMPTY);
        else slot.markDirty();
        cargo.markDirty();
        return original;
    }
}
