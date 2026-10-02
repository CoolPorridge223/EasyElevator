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
