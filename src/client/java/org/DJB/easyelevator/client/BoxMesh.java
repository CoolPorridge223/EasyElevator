package org.DJB.easyelevator.client;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;

/**
 * 长方体网格的共享绘制工具：轿厢（{@link CabinRenderer}）与楼层门门扇
 * （{@link LandingDoorRenderer}）共用同一套顶点顺序与纹理坐标约定，两处外观才不会各画一套。
 *
 * <p>约定：每个面按"从外侧看逆时针"的顺序提交 4 个顶点，法线显式给出并交给矩阵变换；
 * 纹理坐标默认铺满整张图（第 1、2 个顶点 u=1，第 3、4 个顶点 v=0）。
 *
 * <h2>UV 矩形（材质图集）</h2>
 * 需要"一张贴图里放多种材质"时，传一个 {@code {u0,v0,u1,v1}}（归一化 0..1）的 UV 矩形，
 * 每个面就只取这一小块：u0→u1 对应面的横向（外侧看从左到右），v0 在面的上沿、v1 在下沿
 * （原版贴图 v=0 在图像顶部，所以"v1 在下"与"v0 在上"是一致的）。
 * 传 {@link #FULL_UV}（或不传）就是原来的行为：整面铺满整张贴图。
 *
 * <p>每个面都用同一个 UV 矩形（不做逐面 UV 表），因为两类调用方都不需要更细的映射：
 * 门扇是"一整张深色钢板贴图铺满一扇门"，轿厢内饰是"每件几何取图集里的一格"。
 * 各面的长宽比不同，纹理会按面拉伸——金属拉丝、点状地板、格窗这类图案拉伸后仍然成立。
 */
public final class BoxMesh {
    private BoxMesh() { }

    /** Per-face lighting sampled in local coordinates, before rotation/translation. */
    @FunctionalInterface
    public interface FaceLighting {
        int sample(int worldLight,float x,float y,float z,float nx,float ny,float nz);
    }

    public static final FaceLighting AMBIENT=(light,x,y,z,nx,ny,nz)->light;

    /** 铺满整张贴图（0,0,1,1）；默认 UV，等价于 1.5.x 之前的固定纹理坐标。 */
    public static final float[] FULL_UV={0,0,1,1};

    /**
     * 以两个对角点（单位：格）生成一个只有外表面的长方体，六个面共用一个颜色，整面铺满贴图。
     *
     * <p>不做剔除/合并：调用方决定用带背面剔除还是 NoCull 的 {@link net.minecraft.client.render.RenderLayer}。
     * 轿厢外壳由有厚度的长方体组成，其朝厢内的表面也有独立几何；可使用带剔除的层。
     *
     * @param m 渲染矩阵栈
     * @param v 顶点消费者
     * @param x 最小 X（格）
     * @param y 最小 Y（格）
     * @param z 最小 Z（格）
     * @param X 最大 X（格）
     * @param Y 最大 Y（格）
     * @param Z 最大 Z（格）
     * @param light 打包后的光照值
     * @param color 顶点颜色（ARGB）
     */
    public static void cuboid(MatrixStack m,VertexConsumer v,float x,float y,float z,float X,float Y,float Z,int light,int color) {
        cuboid(m,v,x,y,z,X,Y,Z,light,color,FULL_UV);
    }

    /**
     * 同上，但六个面都只取 {@code uv} 指定的那一小块贴图。
     *
     * @param uv UV 矩形 {u0,v0,u1,v1}，归一化 0..1；v0 在面的上沿
     * @see #cuboid(MatrixStack, VertexConsumer, float, float, float, float, float, float, int, int)
     */
    public static void cuboid(MatrixStack m,VertexConsumer v,float x,float y,float z,float X,float Y,float Z,int light,int color,float[] uv) {
        cuboid(m,v,x,y,z,X,Y,Z,light,color,new float[][]{uv,uv,uv,uv,uv,uv});
    }

    /**
     * 同上，但**每个面可以各用一块贴图**。
     *
     * <p>用途是滑门：门板的两个大面要按进度显示"还露在外面"的那一段（贴图随门滑动），
     * 而四周的断面应该是一小段固定的门板边缘——照旧让六个面共用同一个 UV 的话，
     * 0.2 格厚的断面会把整块门板贴图挤进去，看起来像"贴图被截断/掐了一下"。
     *
     * @param faceUv 六个面的 UV 矩形，顺序与下面提交面的顺序一致：{-Z, +Z, -X, +X, +Y, -Y}
     * @see #cuboid(MatrixStack, VertexConsumer, float, float, float, float, float, float, int, int, float[])
     */
    public static void cuboid(MatrixStack m,VertexConsumer v,float x,float y,float z,float X,float Y,float Z,int light,int color,float[][] faceUv) {
        cuboid(m,v,x,y,z,X,Y,Z,light,color,faceUv,AMBIENT);
    }

    public static void cuboid(MatrixStack m,VertexConsumer v,float x,float y,float z,float X,float Y,float Z,int light,int color,float[] uv,FaceLighting lighting) {
        cuboid(m,v,x,y,z,X,Y,Z,light,color,new float[][]{uv,uv,uv,uv,uv,uv},lighting);
    }

    public static void cuboid(MatrixStack m,VertexConsumer v,Box box,int light,int color,float[][] uv,FaceLighting lighting) {
        cuboid(m,v,(float)box.minX,(float)box.minY,(float)box.minZ,(float)box.maxX,(float)box.maxY,(float)box.maxZ,light,color,uv,lighting);
    }

    public static void cuboid(MatrixStack m,VertexConsumer v,float x,float y,float z,float X,float Y,float Z,int light,int color,float[][] faceUv,FaceLighting lighting) {
        float cx=(x+X)/2,cy=(y+Y)/2,cz=(z+Z)/2;
        quad(m,v,lighting.sample(light,cx,cy,z,0,0,-1),color,0,0,-1,new float[]{X,y,z,x,y,z,x,Y,z,X,Y,z},faceUv[0]);
        quad(m,v,lighting.sample(light,cx,cy,Z,0,0,1),color,0,0,1,new float[]{x,y,Z,X,y,Z,X,Y,Z,x,Y,Z},faceUv[1]);
        quad(m,v,lighting.sample(light,x,cy,cz,-1,0,0),color,-1,0,0,new float[]{x,y,z,x,y,Z,x,Y,Z,x,Y,z},faceUv[2]);
        quad(m,v,lighting.sample(light,X,cy,cz,1,0,0),color,1,0,0,new float[]{X,y,Z,X,y,z,X,Y,z,X,Y,Z},faceUv[3]);
        quad(m,v,lighting.sample(light,cx,Y,cz,0,1,0),color,0,1,0,new float[]{x,Y,Z,X,Y,Z,X,Y,z,x,Y,z},faceUv[4]);
        quad(m,v,lighting.sample(light,cx,y,cz,0,-1,0),color,0,-1,0,new float[]{x,y,z,X,y,z,X,y,Z,x,y,Z},faceUv[5]);
    }

    /**
     * 以 {@link Box}（单位：格）绘制长方体，整面铺满贴图。
     *
     * @param m 渲染矩阵栈
     * @param v 顶点消费者
     * @param box 长方体，坐标单位格
     * @param light 打包后的光照值
     * @param color 顶点颜色（ARGB）
     */
    public static void cuboid(MatrixStack m,VertexConsumer v,Box box,int light,int color) {
        cuboid(m,v,box,light,color,FULL_UV);
    }

    /**
     * 以 {@link Box}（单位：格）绘制长方体，只取 {@code uv} 指定的贴图分格。
     *
     * @param uv UV 矩形 {u0,v0,u1,v1}
     * @see #cuboid(MatrixStack, VertexConsumer, float, float, float, float, float, float, int, int, float[])
     */
    public static void cuboid(MatrixStack m,VertexConsumer v,Box box,int light,int color,float[] uv) {
        cuboid(m,v,(float)box.minX,(float)box.minY,(float)box.minZ,(float)box.maxX,(float)box.maxY,(float)box.maxZ,light,color,uv);
    }

    /**
     * 以 {@link Box}（单位：格）绘制长方体，逐面指定贴图分格。
     *
     * @param faceUv 六个面的 UV 矩形，顺序为 {-Z, +Z, -X, +X, +Y, -Y}
     * @see #cuboid(MatrixStack, VertexConsumer, float, float, float, float, float, float, int, int, float[][])
     */
    public static void cuboid(MatrixStack m,VertexConsumer v,Box box,int light,int color,float[][] faceUv) {
        cuboid(m,v,(float)box.minX,(float)box.minY,(float)box.minZ,(float)box.maxX,(float)box.maxY,(float)box.maxZ,light,color,faceUv);
    }

    /**
     * 画一个<b>零厚度的矩形面</b>（单位：格）：与 YZ 平面平行（X 固定），由 Y、Z 两个方向的范围定义。
     *
     * <p>为什么需要单面：玻璃这类"只有一层可见表面"的几何如果做成薄板长方体，正反两面都会被画一遍，
     * 半透明叠加后透明度翻倍、画面发灰；做成单面则每层玻璃只叠一次，与真实玻璃窗一致。
     * 代价是单面在<b>剔除背面</b>的层上从背面看不见，因此调用方必须配合禁止剔除的层
     * ；玻璃专用的 glassX/glassZ 则提交正反两面，配合背面剔除。
     *
     * <p>法线固定取该轴的负方向：单面方法的法线与绕序一致，
     * 这里给出一个确定值只是为了顶点格式完整、且与 {@link #cuboid} 的写法保持一致。
     *
     * @param m 渲染矩阵栈
     * @param v 顶点消费者
     * @param x 该面所在的 X 平面（格）
     * @param y1 矩形在 Y 方向的一角（内部自动取 min/max，调用方不必保证顺序）
     * @param z1 矩形在 Z 方向的一角
     * @param y2 矩形在 Y 方向的另一角
     * @param z2 矩形在 Z 方向的另一角
     * @param light 打包后的光照值
     * @param color 顶点颜色（ARGB）
     */
    public static void planeX(MatrixStack m,VertexConsumer v,float x,float y1,float z1,float y2,float z2,int light,int color) {
        planeX(m,v,x,y1,z1,y2,z2,light,color,FULL_UV);
    }

    /**
     * 同上，只取 {@code uv} 指定的贴图分格。
     *
     * @param uv UV 矩形 {u0,v0,u1,v1}
     * @see #planeX(MatrixStack, VertexConsumer, float, float, float, float, float, int, int)
     */
    public static void planeX(MatrixStack m,VertexConsumer v,float x,float y1,float z1,float y2,float z2,int light,int color,float[] uv) {
        float lo=Math.min(y1,y2),hi=Math.max(y1,y2),a=Math.min(z1,z2),b=Math.max(z1,z2);
        quad(m,v,light,color,-1,0,0,new float[]{x,lo,a,x,lo,b,x,hi,b,x,hi,a},uv);
    }

    /** Two opposite faces for a culling layer: each visible side has its own correct normal. */
    public static void glassX(MatrixStack m,VertexConsumer v,float x,float y1,float z1,float y2,float z2,int light,int color,float[] uv) {
        planeX(m,v,x,y1,z1,y2,z2,light,color,uv);
        float lo=Math.min(y1,y2),hi=Math.max(y1,y2),a=Math.min(z1,z2),b=Math.max(z1,z2);
        quad(m,v,light,color,1,0,0,new float[]{x,lo,b,x,lo,a,x,hi,a,x,hi,b},new float[]{uv[2],uv[1],uv[0],uv[3]});
    }

    /**
     * 画一个零厚度的矩形面（单位：格）：与 XZ 平面平行（Y 固定，即水平面），由 X、Z 的范围定义。
     *
     * @param m 渲染矩阵栈
     * @param v 顶点消费者
     * @param y 该面所在的 Y 平面（格）
     * @param x1 矩形在 X 方向的一角
     * @param z1 矩形在 Z 方向的一角
     * @param x2 矩形在 X 方向的另一角
     * @param z2 矩形在 Z 方向的另一角
     * @param light 打包后的光照值
     * @param color 顶点颜色（ARGB）
     * @see #planeX(MatrixStack, VertexConsumer, float, float, float, float, float, int, int)
     */
    public static void planeY(MatrixStack m,VertexConsumer v,float y,float x1,float z1,float x2,float z2,int light,int color) {
        planeY(m,v,y,x1,z1,x2,z2,light,color,FULL_UV);
    }

    /**
     * 同上，只取 {@code uv} 指定的贴图分格。
     *
     * @param uv UV 矩形 {u0,v0,u1,v1}
     * @see #planeY(MatrixStack, VertexConsumer, float, float, float, float, float, int, int)
     */
    public static void planeY(MatrixStack m,VertexConsumer v,float y,float x1,float z1,float x2,float z2,int light,int color,float[] uv) {
        float lo=Math.min(x1,x2),hi=Math.max(x1,x2),a=Math.min(z1,z2),b=Math.max(z1,z2);
        quad(m,v,light,color,0,-1,0,new float[]{lo,y,a,hi,y,a,hi,y,b,lo,y,b},uv);
    }

    /**
     * 画一个零厚度的矩形面（单位：格）：与 XY 平面平行（Z 固定，即竖直的正面/背面），由 X、Y 的范围定义。
     *
     * @param m 渲染矩阵栈
     * @param v 顶点消费者
     * @param z 该面所在的 Z 平面（格）
     * @param x1 矩形在 X 方向的一角
     * @param y1 矩形在 Y 方向的一角
     * @param x2 矩形在 X 方向的另一角
     * @param y2 矩形在 Y 方向的另一角
     * @param light 打包后的光照值
     * @param color 顶点颜色（ARGB）
     * @see #planeX(MatrixStack, VertexConsumer, float, float, float, float, float, int, int)
     */
    public static void planeZ(MatrixStack m,VertexConsumer v,float z,float x1,float y1,float x2,float y2,int light,int color) {
        planeZ(m,v,z,x1,y1,x2,y2,light,color,FULL_UV);
    }

    /**
     * 同上，只取 {@code uv} 指定的贴图分格。
     *
     * @param uv UV 矩形 {u0,v0,u1,v1}
     * @see #planeZ(MatrixStack, VertexConsumer, float, float, float, float, float, int, int)
     */
    public static void planeZ(MatrixStack m,VertexConsumer v,float z,float x1,float y1,float x2,float y2,int light,int color,float[] uv) {
        float lo=Math.min(x1,x2),hi=Math.max(x1,x2),a=Math.min(y1,y2),b=Math.max(y1,y2);
        // 顶点从 +X 侧起步：只有这样 u 才沿 X 增长、v 沿 Y 向下，贴图在竖直面上不会转 90°。
        quad(m,v,light,color,0,0,-1,new float[]{hi,a,z,lo,a,z,lo,b,z,hi,b,z},uv);
    }

    /** As glassX, with no double blending or reversed normals when viewed from inside. */
    public static void glassZ(MatrixStack m,VertexConsumer v,float z,float x1,float y1,float x2,float y2,int light,int color,float[] uv) {
        planeZ(m,v,z,x1,y1,x2,y2,light,color,uv);
        float lo=Math.min(x1,x2),hi=Math.max(x1,x2),a=Math.min(y1,y2),b=Math.max(y1,y2);
        quad(m,v,light,color,0,0,1,new float[]{lo,a,z,hi,a,z,hi,b,z,lo,b,z},new float[]{uv[2],uv[1],uv[0],uv[3]});
    }

    /**
     * 画一个四边形面。
     *
     * <p>纹理坐标的顺序：第 0 个顶点取 (u0,v1)、第 1 个取 (u1,v1)、第 2 个取 (u1,v0)、第 3 个取 (u0,v0)。
     * 因为每个面的顶点都是"从左下开始、先横向再纵向"给出的，所以 v1 落在面的下沿、v0 落在上沿，
     * 贴图不会上下颠倒；传 {@link #FULL_UV} 时就是 1.5.x 之前写死的 (0,1)(1,1)(1,0)(0,0)。
     *
     * @param m 渲染矩阵栈
     * @param v 顶点消费者
     * @param light 打包后的光照值
     * @param color 顶点颜色（ARGB）
     * @param nx 法线 X
     * @param ny 法线 Y
     * @param nz 法线 Z
     * @param p 顶点数组：每 3 个 float 一组（单位：格），按外侧逆时针顺序给出
     * @param uv UV 矩形 {u0,v0,u1,v1}，归一化 0..1
     */
    private static void quad(MatrixStack m,VertexConsumer v,int light,int color,float nx,float ny,float nz,float[] p,float[] uv) {
        float u0=uv[0],v0=uv[1],u1=uv[2],v1=uv[3];
        for(int i=0;i<4;i++) v.vertex(m.peek(),p[i*3],p[i*3+1],p[i*3+2]).color(color)
                .texture(i==1||i==2?u1:u0,i>=2?v0:v1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(m.peek(),nx,ny,nz);
    }
}
