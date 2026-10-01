package org.DJB.easyelevator;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.item.CabinItem;
import org.DJB.easyelevator.network.ElevatorNetworking;

public class Easyelevator implements ModInitializer {
    public static final String MOD_ID = "easyelevator";
    public static final Block RAIL = new ElevatorRailBlock(AbstractBlock.Settings.create().strength(3.0f).nonOpaque());
    public static final Block LANDING_DOOR = new LandingDoorBlock(AbstractBlock.Settings.create().strength(3.0f).nonOpaque());
    public static final Item CABIN_ITEM = new CabinItem(new Item.Settings().maxCount(1));
    public static final EntityType<CabinEntity> CABIN = Registry.register(Registries.ENTITY_TYPE, id("cabin"),
            EntityType.Builder.<CabinEntity>create(CabinEntity::new, SpawnGroup.MISC)
                    .dimensions(3.0f, 3.0f).maxTrackingRange(10).trackingTickInterval(1).build("easyelevator:cabin"));
    public static final SoundEvent RUNNING = sound("elevator_running");
    public static final SoundEvent ARRIVAL = sound("elevator_arrival");
    public static final SoundEvent DOOR_OPEN = sound("door_open");
    public static final SoundEvent DOOR_CLOSE = sound("door_close");

    public static Identifier id(String path) { return Identifier.of(MOD_ID, path); }

    private static SoundEvent sound(String name) {
        return Registry.register(Registries.SOUND_EVENT, id(name), SoundEvent.of(id(name)));
    }

    private static void block(String name, Block block) {
        Registry.register(Registries.BLOCK, id(name), block);
        Registry.register(Registries.ITEM, id(name), new BlockItem(block, new Item.Settings()));
    }

    @Override
    public void onInitialize() {
        block("elevator_rail", RAIL);
        // Keep the old registry ID so existing inventory items are not lost on upgrade.
        block("call_button", LANDING_DOOR);
        Registry.register(Registries.ITEM, id("cabin"), CABIN_ITEM);
        Registry.register(Registries.ITEM_GROUP, id("main"), FabricItemGroup.builder()
                .displayName(Text.translatable("itemGroup.easyelevator"))
                .icon(() -> new ItemStack(CABIN_ITEM))
                .entries((context, entries) -> { entries.add(RAIL); entries.add(LANDING_DOOR); entries.add(CABIN_ITEM); }).build());
        ElevatorNetworking.register();
    }
}
