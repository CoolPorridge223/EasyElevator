package org.DJB.easyelevator.client;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import org.DJB.easyelevator.logic.FramedLeaf;
import org.DJB.easyelevator.logic.LeafUv;

/**
 * 画"铁框 + 中间玻璃"的门扇（观光轿厢的门、以及观光线路上的楼层门）。
 *
 * <p>为什么不是一个贴图搞定：中间那块玻璃要<b>真的透光</b>（能看见轿厢/井道），所以不能靠贴图画出玻璃，
 * 必须分成"不透明铁框"和"半透明玻璃面"两层画。铁框由四块小长方体组成（上下横框 + 两侧竖框），
 * 玻璃是<b>零厚度单面</b>，画在门扇厚度中线上，走 {@link GlassLayers} 的"只写颜色"层，
 * 因此正反两面都看得见、也不会把后画的方块实体剔掉。
 *
 * <p>布局（边框多宽、玻璃多大）全部来自纯算术类 {@link FramedLeaf}：门扇越开越窄时边框会按比例缩，
 * 玻璃不会变成负宽度，也不会在快开完时闪一下。
 *
 * <p>调用顺序（与 {@code VertexConsumerProvider.Immediate} 的约束一致）：
 * 先在不透明层调 {@link #frame}，把不透明缓冲画完之后再取玻璃层调 {@link #glass}——
 * 拿到新层会结束上一层，之后就绝不能再往旧引用写顶点（会抛 {@code Not building!}）。
 */
public final class FramedGlassDoor {
    private FramedGlassDoor() { }

    /**
     * 画铁框（不透明层）：上下横框各一块、两侧竖框各一块。
     *
     * <p><b>每块边框的贴图都按它自己的尺寸取</b>（{@link FramedLeaf#stileUv} / {@link FramedLeaf#railUv}），
     * 与普通电梯门门扇用 {@code LeafUv.leafRange} 让贴图窗口跟着门板收窄是同一套思路：
     * 照旧把整张贴图铺到 2/16 格宽的竖框上，整块门板贴图会被压成一条"条形码"——
     * 实机反馈的"贴图拉伸"就是这个。四周断面再各取一小段（{@link LeafUv#slabUv}），
     * 与门扇断面的处理完全一致。
     *
     * @param matrices 渲染矩阵栈
     * @param out 不透明顶点缓冲
     * @param leaf 门扇长方体（格；局部坐标系）
     * @param alongZ true 表示门扇厚度方向是 Z（门宽沿 X），false 表示厚度方向是 X（门宽沿 Z）
     * @param window 门扇大面的 UV 矩形（与门扇本体用的是同一个窗口）
     * @param light 打包后的光照值
     */
    public static void frame(MatrixStack matrices,VertexConsumer out,Box leaf,boolean alongZ,float[] window,int light) {
        double w0=alongZ?leaf.minX:leaf.minZ, w1=alongZ?leaf.maxX:leaf.maxZ;
        double y0=leaf.minY, y1=leaf.maxY;
        double[] l=FramedLeaf.layout(w0,w1,y0,y1);
        double t0=alongZ?leaf.minZ:leaf.minX, t1=alongZ?leaf.maxZ:leaf.maxX;
        double width=w1-w0, height=y1-y0;
        // 竖框：横向只取"边框宽度占门扇宽度"那一段；横框：纵向只取"边框高度占门扇高度"那一段
        float[] stileUv=FramedLeaf.stileUv(window,width<=1e-9?1:l[5]-l[4]<=1e-9?1:(l[5]-l[4])/width);
        float[] railUv=FramedLeaf.railUv(window,height<=1e-9?1:(l[1]-l[0])/height);
        // 底框、顶框：满宽
        bar(matrices,out,alongZ,w0,w1,l[0],l[1],t0,t1,railUv,light);
        bar(matrices,out,alongZ,w0,w1,l[2],l[3],t0,t1,railUv,light);
        // 两侧竖框：满高
        bar(matrices,out,alongZ,l[4],l[5],y0,y1,t0,t1,stileUv,light);
        bar(matrices,out,alongZ,l[6],l[7],y0,y1,t0,t1,stileUv,light);
    }

    /**
     * 画门扇中间的玻璃（玻璃层：零厚度单面，位于门扇厚度中线）。
     *
     * <p>玻璃的 UV 按玻璃面的宽高比取（{@link FramedLeaf#paneUv}），使贴图横竖像素密度一致，
     * 玻璃上的花纹不会被纵向拉长。
     *
     * @param matrices 渲染矩阵栈
     * @param glass 玻璃顶点缓冲（必须由 {@link GlassLayers} 的层取得）
     * @param leaf 门扇长方体（格；局部坐标系）
     * @param alongZ true 表示门扇厚度方向是 Z（门宽沿 X），false 表示厚度方向是 X（门宽沿 Z）
     * @param cell 玻璃贴图/图集格的 UV 矩形
     * @param light 打包后的光照值
     * @param color 顶点颜色（含 alpha，决定通透度）
     */
    public static void glass(MatrixStack matrices,VertexConsumer glass,Box leaf,boolean alongZ,float[] cell,int light,int color) {
        double w0=alongZ?leaf.minX:leaf.minZ, w1=alongZ?leaf.maxX:leaf.maxZ;
        double[] l=FramedLeaf.layout(w0,w1,leaf.minY,leaf.maxY);
        if(!FramedLeaf.glassVisible(l)) return; // 全开时门扇宽度归零，没有玻璃可画
        float[] uv=FramedLeaf.paneUv(cell,l[9]-l[8],l[11]-l[10]);
        double mid=alongZ?(leaf.minZ+leaf.maxZ)/2:(leaf.minX+leaf.maxX)/2;
        if(alongZ) BoxMesh.planeZ(matrices,glass,(float)mid,(float)l[8],(float)l[10],(float)l[9],(float)l[11],light,color,uv);
        else BoxMesh.planeX(matrices,glass,(float)mid,(float)l[10],(float)l[8],(float)l[11],(float)l[9],light,color,uv);
    }

    /**
     * 一块"门宽方向 × 高度方向"的长方体，厚度方向用调用方给的范围。
     *
     * <p>UV 用 {@link LeafUv#slabUv}：两个大面用这块边框自己的矩形，四周断面取它的一小段——
     * 与普通电梯门门扇（大面用滑动窗口、断面用 {@code edgeRange}）完全同一套处理，
     * 断面就不会把整块贴图挤进 2/16 格的窄边里。
     *
     * @param matrices 渲染矩阵栈
     * @param out 顶点缓冲
     * @param alongZ 厚度方向是否为 Z
     * @param a0 门宽方向起点
     * @param a1 门宽方向终点
     * @param y0 底部
     * @param y1 顶部
     * @param t0 厚度方向起点
     * @param t1 厚度方向终点
     * @param uv 这块边框大面的 UV 矩形
     * @param light 打包后的光照值
     */
    private static void bar(MatrixStack matrices,VertexConsumer out,boolean alongZ,double a0,double a1,
                            double y0,double y1,double t0,double t1,float[] uv,int light) {
        if(a1-a0<=1e-6||y1-y0<=1e-6||t1-t0<=1e-6) return; // 退化件直接跳过，避免反向长方体
        float[][] faceUv=LeafUv.slabUv(uv,LeafUv.centredThinSlice(uv),alongZ);
        if(alongZ) BoxMesh.cuboid(matrices,out,(float)a0,(float)y0,(float)t0,(float)a1,(float)y1,(float)t1,light,0xFFFFFFFF,faceUv);
        else BoxMesh.cuboid(matrices,out,(float)t0,(float)y0,(float)a0,(float)t1,(float)y1,(float)a1,light,0xFFFFFFFF,faceUv);
    }
}
