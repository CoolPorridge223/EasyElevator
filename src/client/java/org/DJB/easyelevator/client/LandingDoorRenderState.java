package org.DJB.easyelevator.client;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import org.DJB.easyelevator.logic.ElevatorStatus;

/**
 * 楼层门的<b>渲染状态</b>：与 {@link CabinRenderState} 同理，26.3 的方块实体渲染也分成
 * "抽状态 → 提交"两段，提交阶段只有这份状态可用，看不到方块实体、也拿不到方块状态
 * （基类 {@link BlockEntityRenderState} 把 {@code blockState} 藏成了私有字段），
 * 所以门朝向、门进度、玻璃门标志、层号与运行状态都得在这里备一份。
 *
 * <p>原先 {@code render(...)} 里现算的东西现在全部在
 * {@link LandingDoorRenderer#extractRenderState} 里取：同一个渲染帧、同一个 tickDelta，
 * 因此门扇进度与层号显示与 1.21.1 逐位相同，只是采样时刻从"提交前"提前到"抽状态时"。
 * 基类已负责 {@link #lightCoords}（原 {@code light} 参数）与 {@link #blockPos}。
 */
public final class LandingDoorRenderState extends BlockEntityRenderState {
    /** 是否为根方块（部件方块没有方块实体，这里再挡一次，避免把整扇门画 9 遍）。 */
    public boolean root;
    /** 门朝向：门扇几何与显示屏偏移都按它算。 */
    public Direction facing=Direction.NORTH;
    /** 门进度 0..1（已插值）：两扇门扇的宽度与贴图窗口都由它决定。 */
    public float progress;
    /** 观光线路的楼层门：门扇换成"铁框 + 中间玻璃"，玻璃走 {@link GlassLayers#DOOR}。 */
    public boolean glassDoors;
    /** 本线路轿厢当前所在楼层；0 表示本线路没有轿厢，门楣显示屏什么都不画。 */
    public int cabinFloor;
    /** 轿厢运行状态：决定门楣上画 ▲ / ▼ 还是留空（闪烁由 {@link StatusArrow} 按墙钟判定）。 */
    public ElevatorStatus cabinStatus=ElevatorStatus.IDLE;
}
