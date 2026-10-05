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
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.entity.HighSpeedCabinEntity;
import org.DJB.easyelevator.entity.ObservationCabinEntity;
import org.DJB.easyelevator.item.CabinItem;
import org.DJB.easyelevator.network.ElevatorNetworking;

/**
 * 模组主入口：集中完成全部注册，并持有全局单例。
 *
 * <p>在整体架构中的位置：本类只负责“把对象登记进原版注册表并暴露全局单例”，不含任何电梯逻辑；
 * 运行逻辑由 {@link org.DJB.easyelevator.logic.ElevatorController}（纯 Java 状态机）与
 * {@link org.DJB.easyelevator.entity.AbstractCabinEntity}（世界适配层，三个型号的父类）承担。</p>
 *
 * <p>游戏内组件：电梯轨道 {@link #RAIL}、楼层电梯门 {@link #LANDING_DOOR}（注册 ID 仍为
 * {@code easyelevator:call_button}），以及三种共用父类 {@link AbstractCabinEntity} 的轿厢——
 * 普通 {@link #CABIN} / {@link #CABIN_ITEM}、高速 {@link #HIGH_SPEED_CABIN} / {@link #HIGH_SPEED_CABIN_ITEM}
 * （速度 2.5 倍，外观不变）、观光 {@link #OBSERVATION_CABIN} / {@link #OBSERVATION_CABIN_ITEM}
 * （四面玻璃，性能不变）。三种轿厢的实体 ID 与物品 ID 一一对应。</p>
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
    /**
     * 普通电梯轿厢实体类型单例（注册 ID {@code easyelevator:cabin}）。
     *
     * <p>碰撞箱固定 3x3（宽 3.0 格、高 3.0 格）；{@code maxTrackingRange(10)} 单位为区块，
     * 即 10 * 16 = 160 格；{@code trackingTickInterval(1)} 表示每刻都向追踪者同步位置，
     * 这是必需的——轿厢以 0.20 格/刻运行，间隔追踪会明显抖动。</p>
     */
    public static final EntityType<CabinEntity> CABIN = Registry.register(Registries.ENTITY_TYPE, id("cabin"),
            EntityType.Builder.<CabinEntity>create(CabinEntity::new, SpawnGroup.MISC)
                    .dimensions(3.0f, 3.0f).maxTrackingRange(10).trackingTickInterval(1).build("easyelevator:cabin"));
    /**
     * 高速电梯轿厢实体类型单例（注册 ID {@code easyelevator:high_speed_cabin}）。
     *
     * <p>与 {@link #CABIN} 逐项相同，只是实体类在构造时把速度设为 {@link
     * org.DJB.easyelevator.logic.ElevatorParameters#HIGH_SPEED}（2.5 倍 = 10 格/秒）；
     * 尺寸、追踪范围、渲染外观与普通轿厢完全一致，因此旧建筑与井道无需任何改动。</p>
     */
    public static final EntityType<HighSpeedCabinEntity> HIGH_SPEED_CABIN = Registry.register(Registries.ENTITY_TYPE, id("high_speed_cabin"),
            EntityType.Builder.<HighSpeedCabinEntity>create(HighSpeedCabinEntity::new, SpawnGroup.MISC)
                    .dimensions(3.0f, 3.0f).maxTrackingRange(10).trackingTickInterval(1).build("easyelevator:high_speed_cabin"));
    /**
     * 观光电梯轿厢实体类型单例（注册 ID {@code easyelevator:observation_cabin}）。
     *
     * <p>碰撞与追踪参数与 {@link #CABIN} 相同（性能一致），差别只在客户端渲染：四面墙与门扇
     * 用半透明玻璃材质绘制，保留四个角柱、地板与顶板。</p>
     */
    public static final EntityType<ObservationCabinEntity> OBSERVATION_CABIN = Registry.register(Registries.ENTITY_TYPE, id("observation_cabin"),
            EntityType.Builder.<ObservationCabinEntity>create(ObservationCabinEntity::new, SpawnGroup.MISC)
                    .dimensions(3.0f, 3.0f).maxTrackingRange(10).trackingTickInterval(1).build("easyelevator:observation_cabin"));
    /**
     * 电梯轿厢生成物品单例；maxCount(1) 限制为一格一个，避免一次放置多台轿厢。
     *
     * <p>三个物品只在"生成哪一种轿厢 / 回收哪一件"上不同，逻辑共用 {@link CabinItem}；
     * 实体类型用 Supplier 延迟读取，避免与上方静态字段的初始化顺序耦合。</p>
     */
    public static final Item CABIN_ITEM = new CabinItem(new Item.Settings().maxCount(1), () -> CABIN);
    /** 高速轿厢生成物品单例：右键轨道生成高速轿厢（外观与普通一致，速度 2.5 倍）。 */
    public static final Item HIGH_SPEED_CABIN_ITEM = new CabinItem(new Item.Settings().maxCount(1), () -> HIGH_SPEED_CABIN);
    /** 观光轿厢生成物品单例：右键轨道生成观光轿厢（四面玻璃，性能与普通一致）。 */
    public static final Item OBSERVATION_CABIN_ITEM = new CabinItem(new Item.Settings().maxCount(1), () -> OBSERVATION_CABIN);
    /** 运行音效单例：轿厢移动时播放，音量 RUNNING_VOLUME = 0.6f。 */
    public static final SoundEvent RUNNING = sound("elevator_running");
    /**
     * 到站提示音单例：轿厢精确到站（误差 &lt;= 1e-7 格）时播放，音量使用 EVENT_VOLUME = 0.8f。
     *
     * <p>这也是每扇门的设置面板里"默认音效"那一项的来源，并且<b>是全模组唯一的门相关音效</b>——
     * 开关门本身不再发声（曾经有 {@code door_open} / {@code door_close} 两个 ID，已随"每扇门只管到站音效"
     * 的改动一并移除）。
     */
    public static final SoundEvent ARRIVAL = sound("elevator_arrival");

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
        Registry.register(Registries.ITEM, id("high_speed_cabin"), HIGH_SPEED_CABIN_ITEM);
        Registry.register(Registries.ITEM, id("observation_cabin"), OBSERVATION_CABIN_ITEM);
        // 自定义物品栏分组：图标固定用普通轿厢物品；entries 回调在分组内容被构建时执行，
        // 因此这里只放入“可被玩家直接获得”的物品（轨道、楼层门、三种轿厢），
        // 避免依赖注册顺序或每次打开物品栏都重建列表。
        Registry.register(Registries.ITEM_GROUP, id("main"), FabricItemGroup.builder()
                .displayName(Text.translatable("itemGroup.easyelevator"))
                .icon(() -> new ItemStack(CABIN_ITEM))
                .entries((context, entries) -> {
                    entries.add(RAIL); entries.add(LANDING_DOOR);
                    entries.add(CABIN_ITEM); entries.add(HIGH_SPEED_CABIN_ITEM); entries.add(OBSERVATION_CABIN_ITEM);
                }).build());
        // 网络注册必须晚于实体注册：payload 与处理逻辑会按实体 ID 查找已登记的轿厢。
        ElevatorNetworking.register();
    }
}
