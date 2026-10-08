package org.DJB.easyelevator;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.ScreenHandlerType;
import org.DJB.easyelevator.screen.CargoScreenHandler;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.block.LandingDoorBlockEntity;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.entity.*;
import org.DJB.easyelevator.item.CabinItem;
import org.DJB.easyelevator.network.ElevatorNetworking;

/**
 * 模组主入口：集中完成全部注册，并持有全局单例。
 *
 * <p>在整体架构中的位置：本类只负责“把对象登记进原版注册表并暴露全局单例”，不含任何电梯逻辑；
 * 运行逻辑由 {@link org.DJB.easyelevator.logic.ElevatorController}（纯 Java 状态机）与
 * {@link org.DJB.easyelevator.entity.AbstractCabinEntity}（世界适配层，四个型号的父类）承担。</p>
 *
 * <p>游戏内组件：电梯轨道 {@link #RAIL}、楼层电梯门 {@link #LANDING_DOOR}（注册 ID 仍为
 * {@code easyelevator:call_button}），以及四种共用父类 {@link AbstractCabinEntity} 的轿厢——
 * 普通 {@link #CABIN} / {@link #CABIN_ITEM}（限载 {@link org.DJB.easyelevator.logic.ElevatorParameters#PASSENGER_NUM_LIMIT} 人）、
 * 高速 {@link #HIGH_SPEED_CABIN} / {@link #HIGH_SPEED_CABIN_ITEM}（速度 2.5 倍，外观与普通逐面相同）、
 * 观光 {@link #OBSERVATION_CABIN} / {@link #OBSERVATION_CABIN_ITEM}（四面玻璃，性能不变）、
 * 重载 {@link #POWERFUL_CABIN} / {@link #POWERFUL_CABIN_ITEM}（速度 2/3、27 格货舱、限载随货量在 1..20 之间变化）。
 * 四种轿厢的实体 ID 与物品 ID 一一对应；只有重载型号额外注册一个容器菜单 {@link #CARGO_SCREEN}。</p>
 *
 * <p>注册顺序约束：所有注册都必须在 {@link #onInitialize()} 内、且晚于类加载时创建的静态单例字段，
 * 否则可能出现“注册了未初始化的实例”或 Fabric API 未就绪的问题。注册 ID 一经发布不可更改，
 * 否则旧存档（方块状态、实体、物品 NBT）与旧物品会全部失效。</p>
 */
public class Easyelevator implements ModInitializer {
    /** 模组 ID，同时是全部注册 ID 的命名空间；与 fabric.mod.json 中的 id 必须一致。 */
    public static final String MOD_ID = "easyelevator";
    /**
     * 重载轿厢货舱的容器菜单类型单例（注册 ID {@code easyelevator:cargo}）。
     *
     * <p>整个模组唯一的 {@code ScreenHandlerType}：普通 / 高速 / 观光型号没有容器，选中/门设置等
     * 面板都是纯客户端屏幕（不改物品栏、不与服务端做槽位事务），只有货舱需要原版的容器同步——
     * 27 格物品、拖放分配、多人同时装卸、光标物品回收全部由原版事务保证，因此这里老老实实注册一个菜单。
     *
     * <p>{@link net.minecraft.resource.featuretoggle.FeatureFlags#VANILLA_FEATURES} 表示本菜单
     * 不依赖任何实验性世界开关（它只用原版物品与槽位能力），因此在普通存档里也能开。
     * 类型工厂指向 {@code CargoScreenHandler} 的<b>两参构造器</b>（客户端空壳，见该类的类注释）：
     * 服务端实例由 {@code PowerfulCabinEntity.interact} 直接 new 出来并绑定轿厢。
     *
     * <p>注册顺序：必须在本类的静态初始化里、早于 {@code onInitialize()} 里
     * {@code PowerfulCabinEntity} 的使用（那个类按名字引用本字段），放在 {@link #MOD_ID} 之后即可。
     */
    public static final ScreenHandlerType<CargoScreenHandler> CARGO_SCREEN = Registry.register(
            Registries.SCREEN_HANDLER, id("cargo"),
            new ScreenHandlerType<>(CargoScreenHandler::new, FeatureFlags.VANILLA_FEATURES));
    /** 电梯轨道方块单例：垂直放置的一列连续轨道，朝向即轿厢所在方向。 */
    public static final Block RAIL = new ElevatorRailBlock(AbstractBlock.Settings.create().strength(3.0f).nonOpaque());
    /** 楼层电梯门方块单例：唯一根方块（COLUMN=1, LEVEL=0）是站点与控制器，注册 ID 复用旧的 call_button。 */
    public static final Block LANDING_DOOR = new LandingDoorBlock(AbstractBlock.Settings.create().strength(3.0f).nonOpaque().dynamicBounds());
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
     * 造一台轿厢的实体类型（碰撞箱、追踪参数四种型号完全一致），由调用方负责注册。
     *
     * <p><b>这里必须用 Fabric 的无参 {@code build()}，不能用原版的 {@code build(String id)}</b>——
     * 后者会在 {@code saveable}（默认 true）时先向<b>原版数据修复器</b>要这个 id 的 choice type：
     * <pre>
     *   if (this.saveable) Util.getChoiceType(TypeReferences.ENTITY_TREE, id);
     * </pre>
     * 原版 schema 里只有原版实体，模组 id 一定查不到，于是每次注册都会在日志里刷一条
     * <b>{@code No data fixer registered for easyelevator:xxx}</b>（开发环境里 {@code SharedConstants.isDevelopment}
     * 为真，还会把异常直接抛出去，模组初始化直接崩）。Fabric API 的 {@code EntityTypeBuilderMixin}
     * 提供无参 {@code build()} → 内部走 {@code build(null)}，并用 {@code WrapOperation} 拦下
     * {@code Util.getChoiceType}："id == null 就直接不查"，因此既没有那条报错，也不会有别的副作用
     * ——1.21.1 的原版 {@code build(String)} 本来就<b>只造对象、不注册</b>，注册一直是调用方的事，
     * 所以丢掉那个 id 参数不损失任何功能（mod 实体本来也不在原版数据修复器的迁移范围里）。
     *
     * @param factory 实体构造工厂，例如 {@code CabinEntity::new}
     * @param <T> 轿厢实体类型
     * @return 尚未注册的实体类型；调用方必须立刻把它 {@code Registry.register} 到
     *         {@link Registries#ENTITY_TYPE}，并用与存档一致的 ID
     */
    private static <T extends Entity> EntityType<T> cabinType(EntityType.EntityFactory<T> factory) {
        return EntityType.Builder.create(factory, SpawnGroup.MISC)
                .dimensions(3.0f, 3.0f).maxTrackingRange(10).trackingTickInterval(1).build();
    }

    /**
     * 普通电梯轿厢实体类型单例（注册 ID {@code easyelevator:cabin}）。
     *
     * <p>碰撞箱固定 3x3（宽 3.0 格、高 3.0 格）；{@code maxTrackingRange(10)} 单位为区块，
     * 即 10 * 16 = 160 格；{@code trackingTickInterval(1)} 表示每刻都向追踪者同步位置，
     * 这是必需的——轿厢以 0.20 格/刻运行，间隔追踪会明显抖动。</p>
     */
    public static final EntityType<CabinEntity> CABIN =
            Registry.register(Registries.ENTITY_TYPE, id("cabin"), cabinType(CabinEntity::new));
    /**
     * 高速电梯轿厢实体类型单例（注册 ID {@code easyelevator:high_speed_cabin}）。
     *
     * <p>与 {@link #CABIN} 逐项相同，只是实体类在构造时把速度设为 {@link
     * org.DJB.easyelevator.logic.ElevatorParameters#HIGH_SPEED}（2.5 倍 = 10 格/秒）；
     * 尺寸、追踪范围、渲染外观与普通轿厢完全一致，因此旧建筑与井道无需任何改动。</p>
     */
    public static final EntityType<HighSpeedCabinEntity> HIGH_SPEED_CABIN =
            Registry.register(Registries.ENTITY_TYPE, id("high_speed_cabin"), cabinType(HighSpeedCabinEntity::new));
    /**
     * 观光电梯轿厢实体类型单例（注册 ID {@code easyelevator:observation_cabin}）。
     *
     * <p>碰撞与追踪参数与 {@link #CABIN} 相同（性能一致），差别只在客户端渲染：四面墙与门扇
     * 用半透明玻璃材质绘制，保留四个角柱、地板与顶板。</p>
     */
    public static final EntityType<ObservationCabinEntity> OBSERVATION_CABIN =
            Registry.register(Registries.ENTITY_TYPE, id("observation_cabin"), cabinType(ObservationCabinEntity::new));
    /**
     * 重载电梯轿厢实体类型单例（注册 ID {@code easyelevator:powerful_cabin}）。
     *
     * <p>碰撞与追踪参数与 {@link #CABIN} 完全相同（井道尺寸一致，换型号不需要改建筑），差别有三处：
     * <ul>
     *   <li><b>速度</b>：{@link org.DJB.easyelevator.logic.ElevatorParameters#LOW_SPEED}（普通型号的 2/3 ≈ 2.67 格/秒）——"重载"的代价；</li>
     *   <li><b>限载随货量变化</b>：空舱 {@link org.DJB.easyelevator.logic.ElevatorParameters#HIGH_PASSENGER_NUM_LIMIT} 人，
     *       每 128 件货少载 1 人，满载（1728 件）仍可载 6 人；超过当前上限即进入
     *       {@link org.DJB.easyelevator.logic.ElevatorController.Phase#OVERLOAD}（门保持全开、不派发行程）；</li>
     *   <li><b>27 格货舱</b>：潜行右键打开（{@link #CARGO_SCREEN}），货物随实体存档，
     *       并让后部按货量长出最多 6 个可站可撞的木箱。</li>
     * </ul>
     * 渲染上另有一层"重载"内饰（双扶手、双顶灯、载重铭牌、防滑钢踏板），见
     * {@code PowerfulCabinEntity.heavyDuty()}。</p>
     */
    public static final EntityType<PowerfulCabinEntity> POWERFUL_CABIN =
            Registry.register(Registries.ENTITY_TYPE, id("powerful_cabin"), cabinType(PowerfulCabinEntity::new));
    /**
     * 电梯轿厢生成物品单例；maxCount(1) 限制为一格一个，避免一次放置多台轿厢。
     *
     * <p><b>四个</b>物品只在"生成哪一种轿厢 / 回收哪一件"上不同，逻辑共用 {@link CabinItem}；
     * 实体类型用 Supplier 延迟读取，避免与上方静态字段的初始化顺序耦合。
     * 重载轿厢的物品还多一条 Tooltip 提示（怎么打开货舱），见 {@code CabinItem.appendTooltip}。</p>
     */
    public static final Item CABIN_ITEM = new CabinItem(new Item.Settings().maxCount(1), () -> CABIN);
    /** 高速轿厢生成物品单例：右键轨道生成高速轿厢（外观与普通一致，速度 2.5 倍）。 */
    public static final Item HIGH_SPEED_CABIN_ITEM = new CabinItem(new Item.Settings().maxCount(1), () -> HIGH_SPEED_CABIN);
    /** 观光轿厢生成物品单例：右键轨道生成观光轿厢（四面玻璃，性能与普通一致）。 */
    public static final Item OBSERVATION_CABIN_ITEM = new CabinItem(new Item.Settings().maxCount(1), () -> OBSERVATION_CABIN);
    /** 重载轿厢生成物品单例：右键轨道生成重载轿厢（速度 2/3、27 格货舱、限载随货量变化） */
    public static final Item POWERFUL_CABIN_ITEM = new CabinItem(new Item.Settings().maxCount(1), () -> POWERFUL_CABIN);
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
     * Fabric 模组入口：按“方块 -> 物品 -> 物品栏分组 -> 网络”的顺序完成入表。
     *
     * <p>顺序不能随意调整：{@link #CABIN_ITEM} 等静态字段在类加载时创建（实体类型、方块实体类型、
     * 货舱菜单类型都在那一刻注册），而入表动作必须发生在此方法内；网络通道最后注册，
     * 因为其 payload 类型会引用前面已注册的实体类型。</p>
     *
     * <p>副作用：写入 BLOCK / ITEM / ITEM_GROUP 注册表，并注册 S2C 与 C2S 自定义数据包。
     * （ENTITY_TYPE / BLOCK_ENTITY_TYPE / SCREEN_HANDLER / SOUND_EVENT 都是静态字段自注册，
     * 不经过本方法。）</p>
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
        Registry.register(Registries.ITEM, id("powerful_cabin"), POWERFUL_CABIN_ITEM);
        // 自定义物品栏分组：图标固定用普通轿厢物品；entries 回调在分组内容被构建时执行，
        // 因此这里只放入“可被玩家直接获得”的物品（轨道、楼层门、四种轿厢），
        // 避免依赖注册顺序或每次打开物品栏都重建列表。
        Registry.register(Registries.ITEM_GROUP, id("main"), FabricItemGroup.builder()
                .displayName(Text.translatable("itemGroup.easyelevator"))
                .icon(() -> new ItemStack(CABIN_ITEM))
                .entries((context, entries) -> {
                    entries.add(RAIL); entries.add(LANDING_DOOR);
                    entries.add(CABIN_ITEM); entries.add(HIGH_SPEED_CABIN_ITEM); entries.add(OBSERVATION_CABIN_ITEM); entries.add(POWERFUL_CABIN_ITEM);
                }).build());
        // 网络注册必须晚于实体注册：payload 与处理逻辑会按实体 ID 查找已登记的轿厢。
        ElevatorNetworking.register();
    }
}
