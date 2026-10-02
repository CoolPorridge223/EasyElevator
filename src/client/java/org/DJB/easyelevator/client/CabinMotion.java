package org.DJB.easyelevator.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.logic.MotionTimeline;
import org.DJB.easyelevator.network.ElevatorNetworking;
import java.util.Map;
import java.util.WeakHashMap;

/** Rendering only. Authoritative positions, collision checks and teleport confirmations stay intact.
 * 纯客户端渲染辅助：只读服务端同步过来的绝对位置并做本地插值。
 * 设计意图：双精度运动由服务端权威下发（自定义包 MotionFrame，绕过原版相对位置包的定点量化），
 * 本类把样本喂给 logic/MotionTimeline，仅在已收到的样本之间插值、绝不外推；
 * 因此实体的真实位置、碰撞、传送确认与逻辑判定完全不经过这里，客户端平滑不会改变服务端状态。
 */
public final class CabinMotion {
    // 弱键映射：轿厢实体被移除或区块卸载后 Track 可被 GC 回收，避免客户端长时间积累无效时间线。
    private static final Map<CabinEntity, Track> TRACKS = new WeakHashMap<>();
    // 本机玩家当前乘坐的轿厢：仅当收到带有效 riderOffset 的 MotionFrame 时记录，供相机平滑使用；不参与任何逻辑判定。
    private static CabinEntity localRiderCabin;
    /** 单个轿厢的客户端运动状态：时间线 + 最近一次同步的快照。 */
    private static final class Track {
        final MotionTimeline timeline = new MotionTimeline();
        long receivedAt; // 最近一次收到 MotionFrame 时的客户端世界时刻（刻，world.getTime()），用于过期判定
        double lastY, riderOffset = Double.NaN; // lastY：最近同步的服务端绝对 Y（格）；riderOffset：乘客相对轿厢底部的偏移（格），NaN=本机玩家不是该轿厢乘客
        boolean initialized; // 是否已收过样本；首个样本没有"上一次位置"，故用实体当前 Y 作锚点
    }
    // 纯静态工具类，禁止实例化。
    private CabinMotion() { }
    /** 接收一帧服务端运动同步并写入该轿厢的时间线。
     * 只在客户端主线程执行（由 ClientPlayNetworking 回调用 context.client().execute 派发）。
     * @param frame 服务端心跳包：entityId 轿厢实体 id、tick 服务端世界刻、y 轿厢绝对 Y（格）、
     *              riderOffset 观察者相对轿厢底部的偏移（格），非乘客为 NaN
     * 副作用：更新 TRACKS 中的时间线与 localRiderCabin；不发包、不改世界方块、不播放音效。
     */
    public static void receive(ElevatorNetworking.MotionFrame frame) {
        var client = MinecraftClient.getInstance();
        // 世界未就绪、Y 非有限值、或 id 已不对应轿厢（实体被移除/复用）时直接丢弃，防止脏样本污染时间线。
        if (client.world == null || !Double.isFinite(frame.y())
                || !(client.world.getEntityById(frame.entityId()) instanceof CabinEntity cabin)) return;
        Track track = TRACKS.computeIfAbsent(cabin, c -> new Track());
        long now = client.world.getTime();
        // MotionTimeline.add 内部用 (本地刻 - 服务端刻) 对齐时钟；首个样本用实体当前 Y 作为"前一刻"锚点。
        track.timeline.add(frame.tick(), frame.y(), now, track.initialized ? track.lastY : cabin.getY());
        track.receivedAt = now; track.lastY = frame.y(); track.initialized = true;
        track.riderOffset = frame.riderOffset();
        // riderOffset 为 NaN 表示本机玩家不在该轿厢内；只有当前记录仍指向它时才清空，避免被别的轿厢的无乘客包误清。
        if (Double.isFinite(frame.riderOffset())) localRiderCabin = cabin;
        else if (localRiderCabin == cabin) localRiderCabin = null;
    }
    /** 计算该轿厢本帧用于渲染的 Y 坐标（格，绝对世界坐标）。
     * @param cabin 目标轿厢
     * @param delta 渲染插值系数（0..1，表示当前帧位于两个游戏刻之间的位置）
     * @return 同步数据新鲜时返回时间线在"本地刻 - INTERPOLATION_DELAY_TICKS"处的插值值，否则回退原版插值
     * 副作用：无（不写实体位置、不发包），服务端权威位置保持原样。
     */
    public static double renderY(CabinEntity cabin, float delta) {
        double vanilla = MathHelper.lerp(delta, cabin.lastRenderY, cabin.getY());
        Track track = TRACKS.get(cabin);
        // 超过 MOTION_STALE_TICKS 未收到包（掉线、卡顿、超出追踪范围）即放弃时间线，退回原版相对位置渲染；
        // 宁可"看起来卡在原地"也不外推，避免穿过障碍或越过站点。
        if (track == null || cabin.getWorld().getTime() - track.receivedAt > ElevatorParameters.MOTION_STALE_TICKS) return vanilla;
        return track.timeline.sample(cabin.getWorld().getTime() + delta, vanilla);
    }
    /** 计算本地乘客相机的 Y 偏移量（格），供 CameraMixin 在 setPos 前叠加，让第一人称镜头随轿厢平滑升降。
     * @param focused 相机跟随的实体（一般即 client.player）
     * @param delta 渲染插值系数（0..1）
     * @return Y 方向偏移（格）；非本机乘客或任一前提失效时返回 0
     * 副作用：前提失效时清空 localRiderCabin（相机只跟随当前轿厢，避免记住已过期的乘客关系）。
     */
    public static double cameraOffset(Entity focused, float delta) {
        var client = MinecraftClient.getInstance();
        CabinEntity cabin = localRiderCabin;
        // 只处理本机第一人称主体；其它实体视角（副相机、回放）不介入，防止污染旁观视角。
        if (focused != client.player || cabin == null) return 0;
        Track track = TRACKS.get(cabin);
        // 下列条件任一不成立（轿厢已移除、跨世界、旁观模式、骑乘载具、无乘客偏移、同步过期、
        // 玩家离开轿厢水平 1.5 格以上、与记录高度差超过 0.5 格）都表示"本机乘客"关系已失效：
        // 立即放弃平滑让原版相机接管，避免把玩家镜头锁在错误的轿厢上。
        if (cabin.isRemoved() || client.world != cabin.getWorld() || focused.isSpectator() || focused.hasVehicle()
                || track == null || !Double.isFinite(track.riderOffset)
                || cabin.getWorld().getTime() - track.receivedAt > ElevatorParameters.MOTION_STALE_TICKS
                || Math.abs(focused.getX() - cabin.getX()) > 1.5 || Math.abs(focused.getZ() - cabin.getZ()) > 1.5
                || Math.abs(focused.getY() - track.lastY - track.riderOffset) > .5) {
            localRiderCabin = null;
            return 0;
        }
        double visualY = renderY(cabin, delta);
        // 静止且视觉位置与服务端位置一致（误差 <= POSITION_EPSILON = 1e-7 格）时不加偏移：
        // 否则相机插值与实体插值两条独立曲线会产生亚像素级抖动。
        if (cabin.phase() != org.DJB.easyelevator.logic.ElevatorController.Phase.MOVING
                && Math.abs(visualY - track.lastY) <= ElevatorParameters.POSITION_EPSILON) return 0;
        // 视觉轿厢底 Y + 乘客相对底部偏移（格）- 玩家原版插值 Y（格）= 需要补偿的高度差；
        // 锚点仍是"脚下"，因此原版随后的眼高、视角摆动与第三人称贴墙检测照常工作。
        return visualY + track.riderOffset - MathHelper.lerp(delta, focused.prevY, focused.getY());
    }
    /** 清空全部客户端运动状态。副作用：丢弃插值历史与本地乘客标记，之后渲染回到原版位置。断线与切换世界时调用，防止跨世界复用旧样本。 */
    public static void clear() { TRACKS.clear(); localRiderCabin = null; }
}
