package org.DJB.easyelevator.logic;

/**
 * 轿厢门的开合布局：<b>两扇对开滑门</b>，把整个轿厢正面分成左右两半，开门时两扇一起向两侧收拢、
 * 完全让开门洞——与楼层门（{@code LandingDoorGeometry}）同一套做法，因此里外两道门看起来是一致的。
 *
 * <h2>为什么是这个形状</h2>
 * 实机反馈的诉求是"像外面的电梯门一样完全打开"：轿厢正面整个 2.6 格（内净宽 |X| ≤ 1.3）都是门，
 * 门一开就全通，而不是只在中间开一条三分之一宽的缝、两侧还留着固定的墙。所以这里不再做"门袋"：
 * 门扇的<b>外缘固定在侧壁内侧</b>（|X| = {@link #OUTER_EDGE}），内缘（先导端）随进度向外移动，
 * 全开时两扇宽度归零、完全躲进侧壁后面。门扇贴图按"还露在外面"的那一段取
 * （{@link LeafUv#cabinPanelRange}——注意不是楼层门用的 {@code leafRange}，原因见该函数注释），
 * 于是折边那条线会跟着内缘一起往外走——这就是"门在往两边滑"的视觉线索。
 *
 * <p>代价说明：门扇可见宽度确实会随开门变小（"盒子越收越窄"）。这是 3x3x3 轿厢里的必然结果——
 * 门扇要整块平移出去、又不许捅出车外，就需要 1.3 格深的门袋，而侧壁只有 0.2 格。
 * 楼层门（3 格宽门洞 2.8）用的也是这套机制，画面上读起来就是"门滑进门框"，因此这里保持一致。
 *
 * <h2>可脱离游戏验证</h2>
 * 纯算术（只有 float/double），因此 {@code SlidingDoorTest} 能直接断言：关门时两扇拼满整个正面只留中缝、
 * 两扇始终镜像对称、外缘固定不动、内缘随进度线性外移、全开时宽度归零（门洞全通）、
 * 门扇始终不越出轿厢外表面、与楼层门框保 0.0125 格间隙。
 */
public final class SlidingDoor {
    private SlidingDoor() { }

    /** 轿厢内净宽的一半：门洞就是整个正面，|X| ≤ 1.3。 */
    public static final double DOORWAY_HALF=1.3;
    /** 门扇外缘比内净宽再内收一点（格）：避免与侧壁内表面共面（共面片在禁止剔除时会抢深度）。 */
    public static final double OUTER_INSET=.001;
    /** 门扇外缘的 |X|（格）：整扇门就是从这里往中线铺的。 */
    public static final double OUTER_EDGE=DOORWAY_HALF-OUTER_INSET;
    /** 关门时中缝的半宽（格）。 */
    public static final double SEAM=.005;
    /** 门区背面 Z（= 轿厢正面内收 0.2 格）：门扇占满这段厚度，前表面与轿厢正面齐平。 */
    public static final double DOOR_Z_BACK=1.1;
    /** 门区前面 Z（= 轿厢正面前缘）：楼层门后缘在 1.3125，因此留 0.0125 格间隙。 */
    public static final double DOOR_Z_FRONT=1.3;

    /**
     * 一扇门扇此刻覆盖的横向区间（轿厢局部 X，格）。外缘固定在 ±{@link #OUTER_EDGE}，
     * 内缘（先导端）从 {@link #SEAM} 线性移到 {@link #OUTER_EDGE}（全开时宽度归零）。
     *
     * @param right true 取右侧（+X），false 取左侧
     * @param progress 门进度 0..1（0 = 全关，1 = 全开）；超范围夹取，非有限值按 0
     * @return {x0, x1}，x0 &lt; x1；全开时两者相等（宽度为 0）
     */
    public static double[] panelX(boolean right,float progress) {
        double inner=SEAM+(OUTER_EDGE-SEAM)*sanitize(progress);
        return right?new double[]{inner,OUTER_EDGE}:new double[]{-OUTER_EDGE,-inner};
    }

    /**
     * 门扇占用的 Z 区间（轿厢局部 Z，格）：整个门区厚度，门扇前表面与轿厢正面齐平。
     *
     * @return {z0, z1}
     */
    public static double[] doorZ() { return new double[]{DOOR_Z_BACK,DOOR_Z_FRONT}; }

    /**
     * 进度安全化：渲染与碰撞每刻都调用，绝不能让 NaN 进到坐标里。
     *
     * @param progress 原始进度
     * @return 0..1 之间的有限值；非有限值当 0（全关，门关着比留个洞安全）
     */
    public static float sanitize(float progress) {
        return Float.isFinite(progress)?Math.max(0,Math.min(1,progress)):0f;
    }

    /**
     * 净开度的半宽（格）：门洞中线到此刻仍被门扇挡住的位置。
     *
     * @param progress 门进度 0..1
     * @return 半宽；全关时等于 {@link #SEAM}，全开时等于 {@link #OUTER_EDGE}（几乎整个门洞）
     */
    public static double clearHalfWidth(float progress) {
        return SEAM+(OUTER_EDGE-SEAM)*sanitize(progress);
    }

    /**
     * 门扇是否还有可见几何：全开时宽度归零，渲染与碰撞都可以跳过它。
     *
     * @param progress 门进度 0..1
     * @return 还有可见宽度时 true
     */
    public static boolean visible(float progress) {
        return clearHalfWidth(progress)<OUTER_EDGE-1e-6;
    }
}
