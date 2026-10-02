package org.DJB.easyelevator.client;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorParameters;

/** Replace this renderer/model only: simulation and animation timing live in CabinEntity.
 * 轿厢渲染器：只负责画，不参与模拟。
 * 设计意图：轿厢没有实体模型（无 EntityModel/纹理 UV 表），这里直接以世界坐标系画出 3x3x3 的空心白模：
 * 底板、顶板、两块侧壁、后壁各一个长方体，正面留门洞并由两扇滑门（按 doorProgress 位移）遮挡。
 * 所有坐标以轿厢中心为原点、单位为格；正面朝向由 FACING（= 轨道/轿厢门朝向）决定，
 * 数值来自 ElevatorParameters 的 CABIN_FRONT_Z / CABIN_DOOR_BACK_Z，使轿厢正面整体内收，
 * 与楼层门框留出缝隙，避免门与门框共面闪烁。模拟、门动画时序都在 CabinEntity 与 logic/ElevatorController 中。
 */
public class CabinRenderer extends EntityRenderer<CabinEntity> {
    private static final Identifier TEXTURE=Easyelevator.id("textures/entity/cabin.png");
    /** 构造渲染器。副作用：仅保存 EntityRenderer 上下文（光源、模型加载器等）。 */
    public CabinRenderer(EntityRendererFactory.Context context) { super(context); }
    /** 返回轿厢整张白模使用的纹理。 */
    @Override public Identifier getTexture(CabinEntity entity) { return TEXTURE; }
    /** 绘制轿厢。只读服务端同步的状态（FACING、DOOR 的插值门进度），不修改任何游戏状态。
     * @param cabin 目标轿厢实体
     * @param yaw 实体朝向角（未使用，朝向由 FACING 决定）
     * @param delta 渲染插值系数（0..1）
     * @param matrices 渲染矩阵栈
     * @param buffers 顶点缓冲提供者
     * @param light 打包后的光照值
     */
    @Override public void render(CabinEntity cabin,float yaw,float delta,MatrixStack matrices,VertexConsumerProvider buffers,int light) {
        matrices.push();
        // 只叠加"视觉 Y - 原版插值 Y"的差值（单位：格）：实体自身坐标仍由原版渲染管线提供，
        // 因此碰撞箱、判定与服务端位置完全不受影响，这里只做外观平滑。
        matrices.translate(0, CabinMotion.renderY(cabin,delta) - net.minecraft.util.math.MathHelper.lerp(delta,cabin.lastRenderY,cabin.getY()), 0);
        // 把 +Z 面转到 FACING 指定的轿厢门方向（NORTH=+Z，EAST=+X，WEST=-X，SOUTH=+Z 默认 0 度）。
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(switch(cabin.facing()) {case NORTH->180;case EAST->90;case WEST->-90;default->0;}));
        VertexConsumer out=buffers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));
        // 正面 Z（格）与门板背面 Z（格），均取自 ElevatorParameters，保证与楼层门框的间隙一致。
        float front=(float)ElevatorParameters.CABIN_FRONT_Z;
        float doorBack=(float)ElevatorParameters.CABIN_DOOR_BACK_Z;
        box(matrices,out,-1.5f,0,-1.5f,1.5f,.2f,front,light,0xFFE6E6E6); // 底板：Y=0..0.2 格，略暗以区分地面
        box(matrices,out,-1.5f,2.8f,-1.5f,1.5f,3,front,light,0xFFF5F5F5); // 顶板：Y=2.8..3.0 格，最高亮度
        box(matrices,out,-1.5f,.2f,-1.5f,-1.3f,2.8f,front,light,0xFFFFFFFF); // 左侧壁：厚 0.2 格
        box(matrices,out,1.3f,.2f,-1.5f,1.5f,2.8f,front,light,0xFFFFFFFF); // 右侧壁：厚 0.2 格
        box(matrices,out,-1.3f,.2f,-1.5f,1.3f,2.8f,-1.3f,light,0xFFFFFFFF); // 后壁：Z=-1.5..-1.3 格，正面留空形成门洞
        float open=cabin.doorProgress(delta);
        // Two sliding leaves retract into the side walls. 0=closed, 1=open.
        // 两扇滑门，0 = 完全关闭、1 = 完全开启：门宽按 1.3 格 * open 内缩，收到侧壁里（不做缩放，避免纹理拉伸）。
        if(open<.999f) {
            // 门板 Z 范围 doorBack..front（格），即夹在门洞内侧与轿厢正面之间，厚 0.2 格。
            box(matrices,out,-1.3f,.2f,doorBack,-1.3f*open,2.8f,front,light,0xFFCCCCCC);
            box(matrices,out,1.3f*open,.2f,doorBack,1.3f,2.8f,front,light,0xFFCCCCCC);
        }
        // Blank interior panel marker.
        // 轿厢内壁的空白操作面板（占位标记，无交互）：贴在右侧壁内侧 0.05 格厚、1.2..1.8 格高处。
        box(matrices,out,1.25f,1.2f,.3f,1.30f,1.8f,.8f,light,0xFFBBBBBB);
        matrices.pop(); super.render(cabin,yaw,delta,matrices,buffers,light);
    }
    /** 以两个对角点 (x,y,z)-(X,Y,Z)（单位：格）生成一个只有外表面的长方体，六个面共用一个颜色。
     * 不做任何剔除/合并：轿厢是空心结构，内部表面也需要可见（RenderLayer 已关闭背面剔除）。
     */
    private static void box(MatrixStack m,VertexConsumer v,float x,float y,float z,float X,float Y,float Z,int light,int color) {
        face(m,v,light,color,0,0,-1,new float[]{X,y,z,x,y,z,x,Y,z,X,Y,z}); // -Z 面
        face(m,v,light,color,0,0,1,new float[]{x,y,Z,X,y,Z,X,Y,Z,x,Y,Z}); // +Z 面
        face(m,v,light,color,-1,0,0,new float[]{x,y,z,x,y,Z,x,Y,Z,x,Y,z}); // -X 面
        face(m,v,light,color,1,0,0,new float[]{X,y,Z,X,y,z,X,Y,z,X,Y,Z}); // +X 面
        face(m,v,light,color,0,1,0,new float[]{x,Y,Z,X,Y,Z,X,Y,z,x,Y,z}); // +Y 面（顶）
        face(m,v,light,color,0,-1,0,new float[]{x,y,z,X,y,z,X,y,Z,x,y,Z}); // -Y 面（底）
    }
    /** 画一个四边形面。顶点按 p 中每 3 个 float 一组（单位：格）顺序提交；
     * 纹理坐标按"第 1、2 个顶点 u=1，其余 u=0；第 3、4 个顶点 v=0，其余 v=1"的固定规则给出，
     * 使白模六面完整铺满整张纹理；法线由 nx,ny,nz 直接给定并交给矩阵变换。
     */
    private static void face(MatrixStack m,VertexConsumer v,int light,int color,float nx,float ny,float nz,float[] p) {
        for(int i=0;i<4;i++) v.vertex(m.peek(),p[i*3],p[i*3+1],p[i*3+2]).color(color)
                .texture(i==1||i==2?1:0,i>=2?0:1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(m.peek(),nx,ny,nz);
    }
}
