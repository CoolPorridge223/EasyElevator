package org.DJB.easyelevator;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
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
import org.DJB.easyelevator.block.LandingDoorBlockEntity;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.item.CabinItem;
import org.DJB.easyelevator.network.ElevatorNetworking;

/**
 * 模组主入口：集中完成全部注册，并持有全局单例。
 *
 * <p>在整体架构中的位置：本类只负责“把对象登记进原版注册表并暴露全局单例”，不含任何电梯逻辑；
 * 运行逻辑由 {@link org.DJB.easyelevator.logic.ElevatorController}（纯 Java 状态机）与
 * {@link org.DJB.easyelevator.entity.CabinEntity}（世界适配层）承担。</p>
 *
 * <p>三个游戏内组件：电梯轨道 {@link #RAIL}、楼层电梯门 {@link #LANDING_DOOR}（注册 ID 仍为
 * {@code easyelevator:call_button}）、电梯轿厢 {@link #CABIN}（实体）与 {@link #CABIN_ITEM}（生成用物品）。</p>
 *
 * <p>注册顺序约束：所有注册都必须在 {@link #onInitialize()} 内、且晚于类加载时创建的静态单例字段，
 * 否则可能出现“注册了未初始化的实例”或 Fabric API 未就绪的问题。注册 ID 一经发布不可更改，
 * 否则旧存档（方块状态、实体、物品 NBT）与旧物品会全部失效。</p>
 */
public class Easyelevator implements ModInitializer {
    /** 模组 ID，同时是全部注册 ID 的命名空间；与 fabric.mod.json 中的 id 必须一致。 */
    public static final String MOD_ID = "easyelevator";
    /** 电梯轨道方块单例：垂直放置的一列连续轨道，朝向即轿厢所在方向。 */
    public static final Block RAIL = new ElevatorRailBlock(AbstractBlock.Settings.create().strength(3.0f).nonOpaque());
    /** 楼层电梯门方块单例：唯一根方块（COLUMN=1, LEVEL=0）是站点与控制器，注册 ID 复用旧的 call_button。 */
    public static final Block LANDING_DOOR = new LandingDoorBlock(AbstractBlock.Settings.create().strength(3.0f).nonOpaque());
    /**
     * 楼层电梯门的方块实体类型单例（注册 ID {@code easyelevator:landing_door}）。
     *
     * <p>整扇 3x3 门只有根方块会创建它（其余部件 {@code createBlockEntity} 返回 null），
     * 里面存的是门扇滑动的连续进度样本，供渲染插值与碰撞形状使用，详见 {@link LandingDoorBlockEntity}。
     * 用 Fabric 的 builder 而不是原版 {@code BlockEntityType.Builder}，因为原版 {@code build()}
     * 需要额外的 datafixer 类型参数，而本方块实体没有需要数据迁移的旧存档字段。
     */
    public static final BlockEntityType<LandingDoorBlockEntity> LANDING_DOOR_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
            id("landing_door"), FabricBlockEntityTypeBuilder.create(LandingDoorBlockEntity::new, LANDING_DOOR).build());
    /** 电梯轿厢生成物品单例；maxCount(1) 限制为一格一个，避免一次放置多台轿厢。 */
    public static final Item CABIN_ITEM = new CabinItem(new Item.Settings().maxCount(1));
    /**
     * 电梯轿厢实体类型单例。
     *
     * <p>碰撞箱固定 3x3（宽 3.0 格、高 3.0 格）；{@code maxTrackingRange(10)} 单位为区块，
     * 即 10 * 16 = 160 格；{@code trackingTickInterval(1)} 表示每刻都向追踪者同步位置，
     * 这是必需的——轿厢以 0.20 格/刻运行，间隔追踪会明显抖动。</p>
     */
    public static final EntityType<CabinEntity> CABIN = Registry.register(Registries.ENTITY_TYPE, id("cabin"),
            EntityType.Builder.<CabinEntity>create(CabinEntity::new, SpawnGroup.MISC)
                    .dimensions(3.0f, 3.0f).maxTrackingRange(10).trackingTickInterval(1).build("easyelevator:cabin"));
    /** 运行音效单例：轿厢移动时播放，音量 RUNNING_VOLUME = 0.6f。 */
    public static final SoundEvent RUNNING = sound("elevator_running");
    /** 到站音效单例：轿厢精确到站（误差 <= 1e-7 格）时播放，音量使用 EVENT_VOLUME = 0.8f。 */
    public static final SoundEvent ARRIVAL = sound("elevator_arrival");
    /** 开门音效单例：楼层门与轿厢门进入 OPENING 时播放。 */
    public static final SoundEvent DOOR_OPEN = sound("door_open");
    /** 关门音效单例：楼层门与轿厢门进入 CLOSING 时播放。 */
    public static final SoundEvent DOOR_CLOSE = sound("door_close");

    /**
     * 构造本模组命名空间下的 {@link Identifier}。
     *
     * @param path 注册路径，不含命名空间（例如 {@code "cabin"}）
     * @return {@code easyelevator:<path>} 形式的标识符
     */
    public static Identifier id(String path) { return Identifier.of(MOD_ID, path); }

    /**
     * 注册一个音效事件。
     *
     * <p>副作用：写入 {@link Registries#SOUND_EVENT} 注册表；音效的实际音频文件由
     * {@code assets/easyelevator/sounds.json} 按同一 ID 提供，两边 ID 必须保持一致。</p>
     *
     * @param name 音效注册路径（不含命名空间）
     * @return 已注册的音效事件，供实体在服务端播放
     */
    private static SoundEvent sound(String name) {
        return Registry.register(Registries.SOUND_EVENT, id(name), SoundEvent.of(id(name)));
    }

    /**
     * 以同一个名字同时注册方块与其对应的方块物品（BlockItem）。
     *
     * <p>为什么必须成对注册：方块物品栏中的条目是 ITEM，而世界中的方块是 BLOCK；
     * 二者共用一个 ID，缺任意一半都会导致方块存在但无法被玩家获得（或反之）。
     * 物品栏可见性由 {@code assets/easyelevator/lang} 中以该 ID 派生的翻译键决定。</p>
     *
     * @param name 方块与物品共用的注册路径（不含命名空间）
     * @param block 要注册的方块单例
     */
    private static void block(String name, Block block) {
        Registry.register(Registries.BLOCK, id(name), block);
        Registry.register(Registries.ITEM, id(name), new BlockItem(block, new Item.Settings()));
    }

    /**
     * Fabric 模组入口：按“方块 -> 物品 -> 实体 -> 物品栏分组 -> 网络”的顺序完成全部注册。
     *
     * <p>顺序不能随意调整：{@link #CABIN_ITEM} 等静态字段在类加载时创建，
     * 而入表动作必须发生在此方法内；网络通道最后注册，因为其 payload 类型会引用前面已注册的实体类型。</p>
     *
     * <p>副作用：写入 BLOCK / ITEM / ENTITY_TYPE / ITEM_GROUP / SOUND_EVENT 注册表，
     * 并注册 S2C 与 C2S 自定义数据包。</p>
     */
    @Override
    public void onInitialize() {
        block("elevator_rail", RAIL);
        // Keep the old registry ID so existing inventory items are not lost on upgrade.
        // 兼容性 hack：楼层电梯门沿用旧的 call_button ID（现在显示为“电梯门”），
        // 使旧存档中的方块状态、旧物品堆以及旧配方/掉落表在升级后继续有效。
        block("call_button", LANDING_DOOR);
        Registry.register(Registries.ITEM, id("cabin"), CABIN_ITEM);
        // 自定义物品栏分组：图标固定用轿厢物品；entries 回调在分组内容被构建时执行，
        // 因此这里只放入“可被玩家直接获得”的三件物品，避免依赖注册顺序或每次打开物品栏都重建列表。
        Registry.register(Registries.ITEM_GROUP, id("main"), FabricItemGroup.builder()
                .displayName(Text.translatable("itemGroup.easyelevator"))
                .icon(() -> new ItemStack(CABIN_ITEM))
                .entries((context, entries) -> { entries.add(RAIL); entries.add(LANDING_DOOR); entries.add(CABIN_ITEM); }).build());
        // 网络注册必须晚于实体注册：payload 与处理逻辑会按实体 ID 查找已登记的轿厢。
        ElevatorNetworking.register();
    }
}
