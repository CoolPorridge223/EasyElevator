package org.DJB.easyelevator.item;

import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.util.Formatting;
import java.util.List;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorLine;
import java.util.function.Supplier;

/**
 * 电梯轿厢生成物品：对电梯轨道使用后，在轨道前方生成一台轿厢。
 *
 * <p><b>四个</b>轿厢（普通 / 高速 / 观光 / 重载）共用这一个物品类：它们唯一的行为差别是
 * "生成哪一种实体"，因此实体类型由构造参数注入，放置校验、扣物品、失败提示与消息键完全共用一份实现，
 * 将来新增型号也只需要再注册一个实体类型与一个物品。</p>
 *
 * <p>在整体架构中的位置：属于“服务端权威”链路的入口动作之一。世界查询与实体生成只在服务端执行，
 * 客户端仅做预测性返回，真正的成败判定由 {@link ActionResult} 回传给客户端——因此
 * 服务端拒绝时不会产生“客户端已播放放置动画、服务端却没生成实体”的错觉。</p>
 *
 * <p>关键约束：每条线路最多一台轿厢（同一世界高度也只能有一扇正面门），
 * 因此放置前必须校验线路合法且该线路尚无轿厢；不满足条件时只发消息、不消耗物品。</p>
 */
public class CabinItem extends Item {

    /**
     * 本物品生成的轿厢实体类型。
     *
     * <p>用 {@link Supplier} 而不是直接持有 {@link EntityType}：物品与实体类型都是
     * {@link Easyelevator} 的静态字段，Supplier 把"读哪个字段"推迟到真正放置的那一刻，
     * 因此不必依赖两个静态初始化块之间的先后顺序。</p>
     */
    private final Supplier<EntityType<? extends AbstractCabinEntity>> type;

    /**
     * @param s 物品设置，由 {@link org.DJB.easyelevator.Easyelevator} 传入 maxCount(1)
     * @param type 本物品生成的轿厢实体类型（例如 {@code () -> Easyelevator.CABIN}）
     */
    public CabinItem(Settings s, Supplier<EntityType<? extends AbstractCabinEntity>> type) { super(s); this.type = type; }

    /**
     * 物品提示：<b>只有重载轿厢</b>多一行"怎么打开货舱"的说明，其余三种保持原版观感（不额外加行）。
     *
     * <p>为什么按实体类型判断而不是给每个物品加一个"提示键"字段：全模组只有这一条型号专属提示，
     * 为此往构造参数里塞一个可空字符串，会让四个物品的注册都多一个读不出意图的参数；
     * 直接比一次 {@link Easyelevator#POWERFUL_CABIN} 更直白，而且新增型号时不会被漏掉——
     * 只有需要提示时才在这里加一个分支。
     *
     * <p>用灰色（{@link Formatting#GRAY}）：与物品名区分开、读起来像说明文字，
     * 而不是像附魔（浅紫）或警告（红）那样的"属性"。
     *
     * @param stack 本物品的物品堆
     * @param context 提示上下文（当前未使用）
     * @param tooltip 提示行列表，直接追加
     * @param tooltipType 提示类型（当前未使用）
     */
    @Override public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType tooltipType) {
        super.appendTooltip(stack, context, tooltip, tooltipType);
        if (type.get() == Easyelevator.POWERFUL_CABIN)
            tooltip.add(Text.translatable("item.easyelevator.cargo_hint").formatted(Formatting.GRAY));
    }

    /**
     * 对电梯轨道使用本物品：校验线路后生成一台站在被点击那一格轨道上的轿厢。
     *
     * <p>执行流程与副作用：</p>
     * <ol>
     *   <li>目标方块不是电梯轨道，或未处于已加载区块 -> {@link ActionResult#PASS}，交还给原版处理，
     *       不产生任何副作用。</li>
     *   <li>客户端 -> {@link ActionResult#SUCCESS}（仅做预测，不生成实体、不改物品数量）；
     *       真实生成、扣物品与消息全部在服务端完成，避免双端各生成一台。</li>
     *   <li>服务端 -> 扫描线路，失败时记录对应的失败消息键；成功后按本物品绑定的实体类型生成轿厢，
     *       轿厢中心位于轨道朝向前方 2 格、底部 Y 与被点击轨道相同，朝向沿用轨道朝向。</li>
     *   <li>生成前用 {@link AbstractCabinEntity#spaceClear} 检查 3x3 井道空间；被占据则判定为受阻。</li>
     *   <li>生成成功且玩家非创造模式时扣减 1 个物品，返回 {@link ActionResult#CONSUME}；
     *       失败时向玩家发送 actionbar 消息（{@code overlay = true}，即不刷屏聊天栏）并返回
     *       {@link ActionResult#FAIL}，物品保留。</li>
     * </ol>
     *
     * @param ctx 使用上下文，提供世界、被点击方块坐标、使用者与手中物品堆
     * @return 服务端为 CONSUME（已生成）或 FAIL（未生成，物品保留），客户端为 SUCCESS 或 PASS
     */
    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        if (!ctx.getWorld().getBlockState(ctx.getBlockPos()).isOf(Easyelevator.RAIL)) return ActionResult.PASS;
        // 客户端提前返回：只放行预测，让服务端独占生成与扣物品逻辑，防止双端不一致。
        if (ctx.getWorld().isClient) return ActionResult.SUCCESS;
        // scan 要求种子方块所在区块已加载，否则返回 null（等同于“这不是一条合法线路”）。
        var line = ElevatorLine.scan(ctx.getWorld(), ctx.getBlockPos());
        String error;
        if (line == null) error = "message.easyelevator.invalid_stop";
        else if (!line.cabins(ctx.getWorld()).isEmpty()) error = "message.easyelevator.existing_cabin";
        else {
            // 用注册好的实体类型创建实体：三种型号各自的构造器负责写入自己的速度与外观，物品无需分支。
            AbstractCabinEntity cabin = type.get().create(ctx.getWorld());
            if (cabin == null) error = "message.easyelevator.obstructed"; // 理论上不会发生；宁可按受阻处理也不空指针
            else {
                cabin.initialize(ctx.getBlockPos(), line.facing());
                // 位置先按轨道算好再查空间：spaceClear 依赖 initialize 已设定的坐标与朝向。
                if (!cabin.spaceClear(cabin.getBoundingBox())) error = "message.easyelevator.obstructed";
                else if (ctx.getWorld().spawnEntity(cabin)) {
                    // 创造模式不扣物品，方便搭建时反复调整；其余模式每次放置消耗 1 个。
                    if (ctx.getPlayer() == null || !ctx.getPlayer().isCreative()) ctx.getStack().decrement(1);
                    return ActionResult.CONSUME;
                } else error = "message.easyelevator.obstructed";
            }
        }
        // overlay = true：提示显示在快捷栏上方，不写入聊天栏，避免连续失败时刷屏。
        if (ctx.getPlayer() != null) ctx.getPlayer().sendMessage(Text.translatable(error), true);
        return ActionResult.FAIL;
    }
}
