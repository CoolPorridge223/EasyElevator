package org.DJB.easyelevator.mixin;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.network.PlatformMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版服务端连接处理器（{@code ServerPlayNetworkHandler}）的混入：让"随厢移动"与"踩在轿厢地板上"
 * 这两件事被原版校验接受。
 *
 * <p>本类做了两件互不重叠的事：
 * <ul>
 *   <li><b>适配</b>（实现 {@link PlatformMovement}，无注入）：{@code easyelevator$canCarry} 暴露
 *       "当前有无待确认的强制传送"，{@code easyelevator$carried} 平移 {@code lastTickY}/{@code updatedY}
 *       这两个原版移动基准。没有这一步，随厢平移会被速度校验当成玩家自己在飞。</li>
 *   <li><b>注入</b> {@code onPlayerMove} 的 <b>RETURN</b>：原版校验全部做完之后，把"真实的轿厢地板"
 *       也算作支撑，清掉悬浮计数并复位坠落距离。</li>
 * </ul>
 *
 * <p>为什么悬浮判定必须补这一刀：原版 {@code onPlayerMove} 的浮动检测只看<b>方块</b>支撑
 * （脚下是否有碰撞方块），而轿厢地板是实体碰撞箱——玩家站在轿厢里、脚下是空气时，
 * 原版会认为他在悬空，累计若干刻后触发拉回。若为此整体放宽速度/飞行判定，就会同时放过真正作弊的移动；
 * 因此只在"确有本厢地板托着"的当刻补支撑，其余移动路径一律不动。
 *
 * <p>关键不变量/约束：
 * <ul>
 *   <li>注入点在 {@code RETURN}：先让原版完成全部速度、碰撞、飞行与传送校验，本方法只<b>追加</b>结论，
 *       既不取消也不改写原版判定，因此非乘客、旁观者、骑乘者与所有其它移动路径的行为保持原样。</li>
 *   <li>{@code @Shadow} 的字段名与类型必须与目标类逐字一致（写错会让 Mixin 在 APPLY 阶段直接报错）；
 *       本类登记于 {@code easyelevator.mixins.json} 的必装列表，注入失败即模组启动失败（required）。</li>
 *   <li>只在服务端主线程执行：{@code onPlayerMove} 的调用线程即服务端主线程，两个 {@code @Shadow}
 *       字段也没有同步保护。</li>
 * </ul>
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin implements PlatformMovement {
    /** 本条连接正在处理的玩家；由 {@code onPlayerMove} 的注入回调读取。 */
    @Shadow public ServerPlayerEntity player;
    /** 原版移动校验用的"上一刻 Y"基准（格）：平台位移必须计入它，否则会被当成玩家自己上升。 */
    @Shadow private double lastTickY;
    /** 原版移动校验用的"本刻已更新 Y"（格）：与 {@link #lastTickY} 成对平移。 */
    @Shadow private double updatedY;
    /** 待确认的强制传送目标；非 null 期间任何位置平移都会被传送确认判成作弊，故此时禁止随厢移动。 */
    @Shadow private Vec3d requestedTeleportPos;
    /** 原版悬浮判定标志：为 true 时服务端认为玩家悬空，累计 {@link #floatingTicks} 刻后会拉回。 */
    @Shadow private boolean floating;
    /** 悬浮累计刻数（刻）；轿厢地板托住玩家时归零。 */
    @Shadow private int floatingTicks;

    /**
     * {@inheritDoc}
     *
     * <p>判据就是"没有待确认传送"：原版把传送目标暂存在 {@link #requestedTeleportPos}，
     * 非空期间平移玩家会让随后的确认包与预期位置不符（见 {@code genuineTeleportIsNotCarried} 用例）。
     */
    @Override public boolean easyelevator$canCarry() { return requestedTeleportPos == null; }

    /**
     * {@inheritDoc}
     *
     * <p>只平移这两个原版基准：它们就是下一包要比较的"上一刻 Y / 已更新 Y"，平台抬升多少就跟着抬多少，
     * 于是玩家自身的水平与垂直位移仍旧按原值参与速度校验，不会凭空获得或丢失速度。
     */
    @Override public void easyelevator$carried(double dy) {
        lastTickY += dy;
        updatedY += dy;
    }

    /**
     * 原版移动处理返回之后：若玩家脚下站的是本厢地板，就撤回悬浮计数并复位坠落距离。
     *
     * <p>为什么注入这里：{@code onPlayerMove} 是服务端唯一消费移动包的地方，而它的悬浮判定只看方块；
     * 在 RETURN 追加"实体地板也算支撑"，既不影响原版任何校验结论，也不必在实体 tick 里重复判定，
     * 更不会让半空中的非乘客获得支撑。
     *
     * @param packet 刚被处理的原版移动包；本方法不使用它，位置一律以 {@link #player} 的当前状态为准
     * @param ci 原版回调信息；本方法不取消调用，只可能修改 {@link #floating}/{@link #floatingTicks}
     *           与玩家的 {@code fallDistance}
     */
    // 只补"实体地板也算支撑"这一条：原版的速度、碰撞、飞行与传送校验一概不碰。
    @Inject(method = "onPlayerMove", at = @At("RETURN"))
    private void easyelevator$floorSupport(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        // 只处理"自称踩在地上"的正常玩家：旁观者与骑乘者本来就不走这套站立判定，直接放行。
        if (!player.isOnGround() || player.isSpectator() || player.hasVehicle()) return;
        // 外扩 0.01 格再搜索：玩家站在地板边缘时包围盒可能因浮点误差与轿厢刚好不相交，漏判就会白挨一次拉回。
        for (var cabin : player.getWorld().getEntitiesByClass(AbstractCabinEntity.class,
                player.getBoundingBox().expand(.01), c -> !c.isRemoved())) {
            // supportsPassenger = 在厢内 且 脚底恰好贴在地板面（容差 0.025 格）；站在厢内半空中跳起的玩家不算，悬浮判定照旧生效。
            if (cabin.supportsPassenger(player)) {
                floating = false;
                floatingTicks = 0;
                player.fallDistance = 0;
                break;
            }
        }
    }
}
