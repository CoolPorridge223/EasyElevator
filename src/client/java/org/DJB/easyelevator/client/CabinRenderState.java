package org.DJB.easyelevator.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.Direction;
import org.DJB.easyelevator.logic.ElevatorStatus;

/**
 * 轿厢的<b>渲染状态</b>：26.3 的渲染分成"抽状态（extract）→ 提交（submit）"两段，
 * 提交阶段只看得到这个状态对象、看不到实体，因此凡是绘制要用到的实体数据都必须在这里备一份。
 *
 * <p>为什么不能像 1.21.1 那样在 render 里现算：{@link CabinRenderer#extractRenderState} 每帧跑一次，
 * 实体上的门进度、型号标志、层号、面板文字与视觉 Y 偏移都在那一刻<b>定格</b>；
 * 真正的 {@code submit} 只读这里，不再触碰实体。这样渲染状态可以脱离实体被缓存/排序，
 * 也不会在提交阶段读到已经变化的实体数据。
 *
 * <p>字段与原来的 {@code render(...)} 参数一一对应：
 * <ul>
 *   <li>{@link #lightCoords} 来自基类（原 {@code light} 参数）；</li>
 *   <li>{@link #facing} 原 {@code cabin.facing()}；{@link #doorProgress} 原 {@code cabin.doorProgress(delta)}；</li>
 *   <li>{@link #heavyDuty} / {@link #glassWalls} 决定内饰表与外壳走哪条分支；</li>
 *   <li>{@link #yOffset} 原 {@code CabinMotion.renderY(...) - 原版插值 Y} 的差值（纯视觉位移）；</li>
 *   <li>{@link #cargoCrates} 原 {@code PowerfulCabinEntity.cargoCrates()}（非重载型号恒为 0）；</li>
 *   <li>{@link #floorNumber} / {@link #status} / {@link #overload} / {@link #passengerLimit}
 *       是面板两行红字与后壁铭牌的文字来源。</li>
 * </ul>
 */
public final class CabinRenderState extends EntityRenderState {
    /** 轿厢正面朝向（= 轨道 / 门朝向）：submit 里用它把局部 +Z 面转到门的方向。 */
    public Direction facing=Direction.NORTH;
    /** 门进度 0..1（已按帧内进度插值）：两扇滑门的宽度与贴图窗口都由它决定。 */
    public float doorProgress;
    /** 视觉 Y 偏移（格）：只叠加"视觉 Y − 原版插值 Y"的差值，碰撞与服务端位置完全不受影响。 */
    public float yOffset;
    /** 重载型号：内饰换成 {@code POWERFUL_PARTS}，并走双顶灯补光。 */
    public boolean heavyDuty;
    /** 观光型号：侧墙 / 后墙换成玻璃，门扇换成铁框玻璃门。 */
    public boolean glassWalls;
    /** 后端货物箱数量（0..6）：只有重载型号可能非 0，0 表示不画货箱。 */
    public int cargoCrates;
    /** 面板上显示的楼层号；0 表示"还没经过任何站点"，面板上什么都不画。 */
    public int floorNumber;
    /** 运行状态：决定面板第一行画 ▲ / ▼ 还是留空（闪烁由 {@link StatusArrow} 按墙钟判定）。 */
    public ElevatorStatus status=ElevatorStatus.IDLE;
    /** 超载相位：面板第二行改显示"超载"文案，而不是楼层号。 */
    public boolean overload;
    /** 本型号限载人数（非正数 = 不限载）：重载型号后壁铭牌上的红字。 */
    public int passengerLimit;
}
