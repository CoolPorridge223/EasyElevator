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
 *
 * <h2>两套区间函数，别混用</h2>
 * <ul>
 *   <li>{@link #leafRange}：<b>楼层门叶</b>专用。它依赖上面那条"朝向镜像互相抵消"的前提。</li>
 *   <li>{@link #cabinPanelRange}：<b>轿厢门扇</b>专用。轿厢门扇的盒子由 {@code SlidingDoor.panelX}
 *       直接搭出、没有朝向镜像，两扇的 maxX 一头一尾，所以 u0/u1 的排法各一套。</li>
 * </ul>
 * 把 {@code leafRange} 用在轿厢门上，实机表现是"从厢内往外看，左侧那扇的折边线跑到门框端去了"
 * （两扇不再镜像对称）——这正是 2.2.0 收到的那条反馈。
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
     * <b>轿厢</b>门板此刻可见的那一段贴图，按 UV 矩形顺序给出（第 0 个数是 u0、第 1 个数是 u1）。
     *
     * <h2>为什么不能直接用 {@link #leafRange}</h2>
     * {@link #leafRange} 的前提取自<b>楼层门叶</b>：那边 {@code LandingDoorGeometry.doorBox}
     * 会按朝向把门宽轴镜像一次，正巧抵消掉 {@code BoxMesh} 的"第一个顶点"约定，于是
     * "左扇 u0 落在先导端、右扇落在门框端"两句话才成立。轿厢门扇的盒子是
     * {@code CabinRenderer.leafBox} 直接由 {@code SlidingDoor.panelX} 的两个 X 端点搭出来的，
     * <b>没有那层朝向镜像</b>（朝向由渲染矩阵负责）。而厢内乘客看到的是门扇的 <b>-Z 面</b>，
     * 该面第一个顶点固定在 <b>maxX</b>：
     * <ul>
     *   <li>{@code right=true}（+X 扇）：区间 {@code [inner, OUTER_EDGE]}，maxX 是<b>门框端</b>；</li>
     *   <li>{@code right=false}（-X 扇）：区间 {@code [-OUTER_EDGE, -inner]}，maxX 是<b>中缝端</b>。</li>
     * </ul>
     * 两扇的 maxX 一头一尾，因此 u0/u1 的排法必须<b>各用一套</b>；照抄 {@link #leafRange}
     * 会让其中一扇整块左右翻转、并且取到的是 [0,1-p] 而不是 [p,1] 那一段。实机表现是
     * "关门时两扇的折边线不在中缝两侧对称，从厢内往外看有一侧是反的"。
     *
     * <p>本函数把两扇都统一成：<b>中缝端（先导端）= 纹理 t=1</b>（{@code DOOR} 格的折边线在
     * 高 u 一侧，正好落在中缝旁）、<b>门框端 = 纹理 t=p</b>。于是 p 增大时贴图从门框端一侧被逐段
     * 盖住，折边线始终可见并跟着先导端外移，与楼层门读起来一致。
     *
     * @param progress 门进度 0..1（0 = 全关，1 = 全收进门框）；超范围夹取，非有限值当 0
     * @param right true 取 +X 扇（{@link SlidingDoor#panelX} 的 {@code right=true}）
     * @return {u0纹理坐标, u1纹理坐标}；两扇在 p 相同时互为镜像
     */
    public static float[] cabinPanelRange(float progress,boolean right) {
        float p=sanitize(progress);
        return right?new float[]{p,1f}:new float[]{1f,p};
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
