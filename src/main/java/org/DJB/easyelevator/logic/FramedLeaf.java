package org.DJB.easyelevator.logic;

/**
 * 铁框玻璃门扇的<b>纯几何</b>：把一扇门扇的矩形切成"上下左右四条铁框 + 中间一块玻璃"。
 *
 * <p>为什么单独成类：这段算术有两个容易出错的地方，而且都能脱离游戏验证——
 * <ol>
 *   <li><b>边框必须跟着门扇一起缩</b>：门扇是"盒子越开越窄"画出来的（{@link SlidingDoor} 与楼层门同理），
 *       宽度收到边框宽度以下时，如果还用固定 2/16 的边框，两条竖框就会互相穿插、玻璃宽度变成负数，
 *       画面上表现为门快开完时闪一下或者整块变黑。这里让边框宽度不超过门扇宽度的
 *       {@link #FRAME_MAX_RATIO}，于是玻璃始终是正宽度、平滑收没。</li>
 *   <li><b>退化不能出现负尺寸</b>：门扇全开时宽度为 0，玻璃矩形必须退化成一个点而不是反向的矩形。</li>
 * </ol>
 *
 * <p>坐标约定：只处理"门宽方向 + 竖直方向"两个轴（厚度方向由调用方决定），单位格。
 */
public final class FramedLeaf {
    private FramedLeaf() { }

    /** 边框宽度（格）：2/16 ≈ 12 厘米，与真实玻璃门的边框接近。 */
    public static final double FRAME=2.0/16;
    /** 边框占门扇宽度的最大比例：门扇变窄时边框按比例缩，保证玻璃不会被挤成负宽度。 */
    public static final double FRAME_MAX_RATIO=.34;
    /** 上下横框占门扇高度的最大比例（门扇通常很高，这条只在极矮的退化情况下生效）。 */
    public static final double RAIL_MAX_RATIO=.2;

    /**
     * 该宽度下应该用的边框宽度。
     *
     * @param width 门扇宽度（格）；负数按 0 处理
     * @return 边框宽度，落在 0..{@link #FRAME} 之间
     */
    public static double frameWidth(double width) {
        return Math.min(FRAME,Math.max(0,width)*FRAME_MAX_RATIO);
    }

    /**
     * 一扇门扇的布局：四条边框与中间玻璃的矩形。
     *
     * @param w0 门扇在门宽方向的起点（格）
     * @param w1 门扇在门宽方向的终点（格），必须 ≥ w0
     * @param y0 门扇底部（格）
     * @param y1 门扇顶部（格），必须 ≥ y0
     * @return 12 个 double：{底框y0,y1, 顶框y0,y1, 外侧竖框w0,w1, 先导侧竖框w0,w1, 玻璃w0,w1, 玻璃y0,y1}
     */
    public static double[] layout(double w0,double w1,double y0,double y1) {
        double width=Math.max(0,w1-w0),height=Math.max(0,y1-y0);
        double f=frameWidth(width);
        double v=Math.min(FRAME,height*RAIL_MAX_RATIO);
        double gx0=w0+f,gx1=w1-f,gy0=y0+v,gy1=y1-v;
        // 门扇收到边框以内时，玻璃退化到中线上的一个点（宽度 0），而不是出现反向矩形
        if(gx1<gx0) { gx0=gx1=(w0+w1)/2; }
        if(gy1<gy0) { gy0=gy1=(y0+y1)/2; }
        return new double[]{y0,y0+v, y1-v,y1, w0,w0+f, w1-f,w1, gx0,gx1, gy0,gy1};
    }

    /**
     * 玻璃是否还有可见面积：全开（宽度/高度归零）时调用方可以跳过这次绘制。
     *
     * @param layout {@link #layout} 的结果
     * @return 玻璃宽高都大于 1e-6 时 true
     */
    public static boolean glassVisible(double[] layout) {
        return layout[9]-layout[8]>1e-6&&layout[11]-layout[10]>1e-6;
    }

    /**
     * 在 UV 矩形里"围绕中心"取一块，宽高各占 {@code uFraction} / {@code vFraction}。
     *
     * <p>这是本类解决"贴图拉伸"的关键：UV 的取用范围要<b>跟着几何尺寸走</b>——
     * 与普通电梯门门扇用 {@code LeafUv.leafRange} 让贴图窗口随门板一起收窄是同一个思路。
     *
     * @param rect 基准 UV 矩形 {u0,v0,u1,v1}（u0/u1、v0/v1 都允许反向）
     * @param uFraction 宽度占比，夹到 0..1
     * @param vFraction 高度占比，夹到 0..1
     * @return 居中的子矩形 {u0,v0,u1,v1}
     */
    public static float[] centredSlice(float[] rect,double uFraction,double vFraction) {
        float uf=(float)Math.max(0,Math.min(1,uFraction)), vf=(float)Math.max(0,Math.min(1,vFraction));
        float cu=(rect[0]+rect[2])/2, cv=(rect[1]+rect[3])/2;
        float hu=(rect[2]-rect[0])/2*uf, hv=(rect[3]-rect[1])/2*vf;
        return new float[]{cu-hu,cv-hv,cu+hu,cv+hv};
    }

    /**
     * 竖框（门扇两侧的边框）该用的 UV：横向只取"边框占门扇宽度"那一段，纵向取整段。
     *
     * <p>这样竖框上的贴图密度与门扇大面完全一致——如果照旧把整张贴图铺到 2/16 格宽的竖框上，
     * 整块门板贴图会被压成一条"条形码"，这就是实机看到的"贴图拉伸"。
     *
     * @param window 门扇大面的 UV 矩形
     * @param widthFraction 竖框宽度占门扇宽度的比例
     * @return 竖框大面的 UV 矩形
     */
    public static float[] stileUv(float[] window,double widthFraction) {
        return centredSlice(window,widthFraction,1);
    }

    /**
     * 横框（门扇上下边框）该用的 UV：横向取整段，纵向只取"横框占门扇高度"那一段。
     *
     * @param window 门扇大面的 UV 矩形
     * @param heightFraction 横框高度占门扇高度的比例
     * @return 横框大面的 UV 矩形
     */
    public static float[] railUv(float[] window,double heightFraction) {
        return centredSlice(window,1,heightFraction);
    }

    /**
     * 玻璃面该用的 UV：按玻璃的<b>宽高比</b>取，使贴图在横向与纵向的像素密度相同。
     *
     * <p>玻璃面又高又窄（1.2 x 2.6 格），整张贴图铺上去纵向会被拉长 2 倍多，掠光会变成竖条纹；
     * 这里让 u 方向只取 {@code paneWidth/paneHeight} 那一段、v 方向取整段，
     * 于是横竖像素密度一致、玻璃上的花纹不会被拉长。
     *
     * @param cell 玻璃贴图/图集格的 UV 矩形
     * @param paneWidth 玻璃面宽度（格）
     * @param paneHeight 玻璃面高度（格）
     * @return 玻璃面的 UV 矩形
     */
    public static float[] paneUv(float[] cell,double paneWidth,double paneHeight) {
        double ratio=paneHeight<=1e-9?1:Math.max(1e-6,Math.min(1,paneWidth/paneHeight));
        return centredSlice(cell,ratio,1);
    }
}
