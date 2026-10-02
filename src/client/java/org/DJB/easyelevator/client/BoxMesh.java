package org.DJB.easyelevator.client;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;

/**
 * 白模长方体的共享绘制工具：轿厢（{@link CabinRenderer}）与楼层门门扇
 * （{@link LandingDoorRenderer}）共用同一套顶点顺序与纹理坐标约定，两处外观才不会各画一套。
 *
 * <p>约定：每个面按"从外侧看逆时针"的顺序提交 4 个顶点，法线显式给出并交给矩阵变换；
 * 纹理坐标固定铺满整张图（第 1、2 个顶点 u=1，第 3、4 个顶点 v=0），
 * 因此白模在换贴图后仍然整面铺满，不需要 UV 表。
 */
public final class BoxMesh {
    private BoxMesh() { }

    /**
     * 以两个对角点（单位：格）生成一个只有外表面的长方体，六个面共用一个颜色。
     *
     * <p>不做剔除/合并：调用方决定用带背面剔除还是 NoCull 的 {@link net.minecraft.client.render.RenderLayer}。
     * 轿厢是空心结构、需要看到内表面，所以用 NoCull；门扇是实心盒子，用带剔除的层更省也避免共面闪烁。
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
        quad(m,v,light,color,0,0,-1,new float[]{X,y,z,x,y,z,x,Y,z,X,Y,z}); // -Z 面
        quad(m,v,light,color,0,0,1,new float[]{x,y,Z,X,y,Z,X,Y,Z,x,Y,Z}); // +Z 面
        quad(m,v,light,color,-1,0,0,new float[]{x,y,z,x,y,Z,x,Y,Z,x,Y,z}); // -X 面
        quad(m,v,light,color,1,0,0,new float[]{X,y,Z,X,y,z,X,Y,z,X,Y,Z}); // +X 面
        quad(m,v,light,color,0,1,0,new float[]{x,Y,Z,X,Y,Z,X,Y,z,x,Y,z}); // +Y 面（顶）
        quad(m,v,light,color,0,-1,0,new float[]{x,y,z,X,y,z,X,y,Z,x,y,Z}); // -Y 面（底）
    }

    /**
     * 以 {@link Box}（单位：格）绘制长方体。
     *
     * @param m 渲染矩阵栈
     * @param v 顶点消费者
     * @param box 长方体，坐标单位格
     * @param light 打包后的光照值
     * @param color 顶点颜色（ARGB）
     */
    public static void cuboid(MatrixStack m,VertexConsumer v,Box box,int light,int color) {
        cuboid(m,v,(float)box.minX,(float)box.minY,(float)box.minZ,(float)box.maxX,(float)box.maxY,(float)box.maxZ,light,color);
    }

    /**
     * 画一个<b>零厚度的矩形面</b>（单位：格）：与 YZ 平面平行（X 固定），由 Y、Z 两个方向的范围定义。
     *
     * <p>为什么需要单面：玻璃这类"只有一层可见表面"的几何如果做成薄板长方体，正反两面都会被画一遍，
     * 半透明叠加后透明度翻倍、画面发灰；做成单面则每层玻璃只叠一次，与真实玻璃窗一致。
     * 代价是单面在<b>剔除背面</b>的层上从背面看不见，因此调用方必须配合禁止剔除的层
     * （本模组的观光玻璃层即 {@code DISABLE_CULLING}），否则从轿厢内侧看玻璃会整片消失。
     *
     * <p>法线固定取该轴的负方向：实体半透明着色用的是光照贴图，法线不参与着色，
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
        float lo=Math.min(y1,y2),hi=Math.max(y1,y2),a=Math.min(z1,z2),b=Math.max(z1,z2);
        quad(m,v,light,color,-1,0,0,new float[]{x,lo,a,x,lo,b,x,hi,b,x,hi,a});
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
        float lo=Math.min(x1,x2),hi=Math.max(x1,x2),a=Math.min(z1,z2),b=Math.max(z1,z2);
        quad(m,v,light,color,0,-1,0,new float[]{lo,y,a,hi,y,a,hi,y,b,lo,y,b});
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
        float lo=Math.min(x1,x2),hi=Math.max(x1,x2),a=Math.min(y1,y2),b=Math.max(y1,y2);
        quad(m,v,light,color,0,0,-1,new float[]{lo,a,z,lo,b,z,hi,b,z,hi,a,z});
    }

    /**
     * 画一个四边形面。
     *
     * @param m 渲染矩阵栈
     * @param v 顶点消费者
     * @param light 打包后的光照值
     * @param color 顶点颜色（ARGB）
     * @param nx 法线 X
     * @param ny 法线 Y
     * @param nz 法线 Z
     * @param p 顶点数组：每 3 个 float 一组（单位：格），按外侧逆时针顺序给出
     */
    private static void quad(MatrixStack m,VertexConsumer v,int light,int color,float nx,float ny,float nz,float[] p) {
        for(int i=0;i<4;i++) v.vertex(m.peek(),p[i*3],p[i*3+1],p[i*3+2]).color(color)
                .texture(i==1||i==2?1:0,i>=2?0:1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(m.peek(),nx,ny,nz);
    }
}
