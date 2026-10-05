package org.DJB.easyelevator.logic;

/**
 * 滑门贴图的 UV 工具：把"门板此刻还露在外面"的那一段贴图算出来，并映射进整张贴图或图集格。
 *
 * <h2>顶点顺序这个坑（1.5.6 实机反馈"四个朝向里只有一个门贴图是对的"的根因）</h2>
 * {@code BoxMesh} 把 UV 矩形的 u0 交给每个面的<b>第一个顶点</b>，而"第一个顶点"落在哪个坐标端
 * 由面的种类固定决定：
 * <ul>
 *   <li>-Z 面第一个顶点在 maxX；+Z 面在 minX；-X 面在 minZ；+X 面在 maxZ（见 {@code BoxMesh.cuboid}）。</li>
 * </ul>
 * 于是"u0 贴在长方体最小坐标那一端"这个直觉是<b>错</b>的：对楼层门叶来说，朝走廊那一面
 * 在四种朝向下分别是 -Z / +Z / +X / -X，但 {@code LandingDoorGeometry.doorBox} 同时按朝向把
 * 门宽轴镜像了，两个镜像正好抵消——结果是四种朝向下 u0 都落在同一个物理端：
 * <b>左扇落在先导端（门缝侧）、右扇落在门框端</b>。也就是说贴图方向只由"哪一扇"决定，与朝向无关。
 *
 * <p>之前的版本按"是否 SOUTH/WEST"来翻端，于是四个朝向里只有两个是对的（实机看起来只有一个明显正常），
 * 另外两个的门板贴图被反过来贴：门框端显示的是门板中段的贴图，看起来就像贴图被"截断"了。
 * 现在这里只接收"哪一扇"，朝向不再参与。
 *
 * <h2>可见区间怎么来的</h2>
 * 门叶是"盒子越开越窄"画出来的，几何上等价于<b>整块门板平移、超出部分被门框挡住</b>。
 * 以左扇为例：门板自身纹理坐标 t∈[0,1]，t=0 在门框端、t=1 在先导端；进度 p 时门板平移了整宽，
 * 于是 t&lt;p 的那一段已经滑进门框后面，<b>可见区间是 [p,1]</b>；右扇镜像，为 [0,1-p]。
 * 再按"u0 落在先导端（左扇）/ 门框端（右扇）"的事实排成 UV 矩形顺序即可。
 */
public final class LeafUv {
    private LeafUv() { }

    /** 断面取门板边缘多宽的一段贴图（占整张的比例）：0.06 ≈ 门板边缘那一条折边。 */
    public static final float EDGE_WIDTH=.06f;

    /**
     * 门叶此刻可见的那一段贴图，按 <b>UV 矩形顺序</b>给出（第 0 个数是 u0、第 1 个数是 u1）。
     *
     * <p>返回值直接交给 {@link #toUv}，再交给 {@code BoxMesh}：左扇 {1, p}（u0=1 落在先导端）、
     * 右扇 {1-p, 0}（u0=1-p 落在门框端）。进度 0 时两者都是"整段贴图"，只是左右互为镜像。
     *
     * @param progress 门进度 0..1（0 = 全关，1 = 全收进门框）；超范围夹取，非有限值当 0
     * @param right true 取右扇，false 取左扇
     * @return {u0纹理坐标, u1纹理坐标}，都在 0..1 内
     */
    public static float[] leafRange(float progress,boolean right) {
        float p=sanitize(progress);
        return right?new float[]{1-p,0}:new float[]{1,p};
    }

    /**
     * 进度安全化：渲染每帧都调用，绝不能让 NaN / 无穷跑进顶点坐标。
     *
     * @param progress 原始进度
     * @return 0..1 之间的有限值；非有限值当 0（全关）
     */
    public static float sanitize(float progress) {
        return Float.isFinite(progress)?Math.max(0,Math.min(1,progress)):0f;
    }

    /**
     * 门叶<b>断面</b>（门板厚度那一圈）应该显示的一小段贴图，同样是 UV 矩形顺序。
     *
     * <p>断面只有 3/16 格厚，若跟大面共用整张贴图，会把整块门板贴图挤进这一条窄边——
     * 实机反馈里"像被掐断"的一个来源。真实门板断面就是一条窄边，所以固定取先导端那一小段。
     * 断面是几条极窄的边，u 方向反不反在画面上看不出来，因此这里不再区分朝向。
     *
     * @param right true 取右扇（先导端在纹理 u=0 那侧），false 取左扇
     * @param width 取多宽（占整张贴图的比例），建议 {@link #EDGE_WIDTH}
     * @return {u0纹理坐标, u1纹理坐标}
     */
    public static float[] edgeRange(boolean right,float width) {
        float w=Math.max(0,Math.min(1,width));
        return right?new float[]{0,w}:new float[]{1-w,1};
    }

    /**
     * 断面（细长件的那一圈）取多小的一小段：取矩形中心的一小块。
     *
     * <p>与 {@link #EDGE_WIDTH} 同一个用途——细长件的断面如果沿用大面的整块贴图，
     * 整块贴图会被挤进一两条像素宽的边里（实机看就是贴图拉伸/掐断）。
     *
     * @param rect 大面的 UV 矩形
     * @return 居中的一小块 UV 矩形
     */
    public static float[] centredThinSlice(float[] rect) {
        return org.DJB.easyelevator.logic.FramedLeaf.centredSlice(rect,EDGE_WIDTH,EDGE_WIDTH);
    }

    /**
     * 把 {@link #leafRange} / {@link #edgeRange} 的区间映射进整张贴图或图集的某一格。
     *
     * @param range {f0,f1}，u0 在前
     * @param cell 目标格子的 UV 矩形 {u0,v0,u1,v1}；传 {@code BoxMesh.FULL_UV} 即整张贴图
     * @return 可直接交给 {@code BoxMesh} 的 UV 矩形 {u0,v0,u1,v1}
     */
    public static float[] toUv(float[] range,float[] cell) {
        float width=cell[2]-cell[0];
        return new float[]{cell[0]+range[0]*width,cell[1],cell[0]+range[1]*width,cell[3]};
    }

    /**
     * 组装"门板六面"的 UV：两个大面用门板贴图，四周断面用固定的一小段。
     *
     * @param panelUv 大面的 UV 矩形（{@link #toUv} 的结果）
     * @param edgeUv 断面的 UV 矩形
     * @param normalAlongZ true 表示门板厚度方向是 Z（轿厢门、南北向楼层门），false 表示是 X（东西向）
     * @return 六个面的 UV，顺序与 {@code BoxMesh} 提交面的顺序一致：{-Z, +Z, -X, +X, +Y, -Y}
     */
    public static float[][] slabUv(float[] panelUv,float[] edgeUv,boolean normalAlongZ) {
        return normalAlongZ
                ?new float[][]{panelUv,panelUv,edgeUv,edgeUv,edgeUv,edgeUv}
                :new float[][]{edgeUv,edgeUv,panelUv,panelUv,edgeUv,edgeUv};
    }
}
