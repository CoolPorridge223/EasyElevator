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

/** Replace this renderer/model only: simulation and animation timing live in CabinEntity. */
public class CabinRenderer extends EntityRenderer<CabinEntity> {
    private static final Identifier TEXTURE=Easyelevator.id("textures/entity/cabin.png");
    public CabinRenderer(EntityRendererFactory.Context context) { super(context); }
    @Override public Identifier getTexture(CabinEntity entity) { return TEXTURE; }
    @Override public void render(CabinEntity cabin,float yaw,float delta,MatrixStack matrices,VertexConsumerProvider buffers,int light) {
        matrices.push();
        matrices.translate(0, CabinMotion.renderY(cabin,delta) - net.minecraft.util.math.MathHelper.lerp(delta,cabin.lastRenderY,cabin.getY()), 0);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(switch(cabin.facing()) {case NORTH->180;case EAST->90;case WEST->-90;default->0;}));
        VertexConsumer out=buffers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));
        box(matrices,out,-1.5f,0,-1.5f,1.5f,.2f,1.5f,light,0xFFE6E6E6);
        box(matrices,out,-1.5f,2.8f,-1.5f,1.5f,3,1.5f,light,0xFFF5F5F5);
        box(matrices,out,-1.5f,.2f,-1.5f,-1.3f,2.8f,1.5f,light,0xFFFFFFFF);
        box(matrices,out,1.3f,.2f,-1.5f,1.5f,2.8f,1.5f,light,0xFFFFFFFF);
        box(matrices,out,-1.3f,.2f,-1.5f,1.3f,2.8f,-1.3f,light,0xFFFFFFFF);
        float open=cabin.doorProgress(delta);
        // Two sliding leaves retract into the side walls. 0=closed, 1=open.
        if(open<.999f) {
            box(matrices,out,-1.3f,.2f,1.3f,-1.3f*open,2.8f,1.5f,light,0xFFCCCCCC);
            box(matrices,out,1.3f*open,.2f,1.3f,1.3f,2.8f,1.5f,light,0xFFCCCCCC);
        }
        // Blank interior panel marker.
        box(matrices,out,1.25f,1.2f,.3f,1.30f,1.8f,.8f,light,0xFFBBBBBB);
        matrices.pop(); super.render(cabin,yaw,delta,matrices,buffers,light);
    }
    private static void box(MatrixStack m,VertexConsumer v,float x,float y,float z,float X,float Y,float Z,int light,int color) {
        face(m,v,light,color,0,0,-1,new float[]{X,y,z,x,y,z,x,Y,z,X,Y,z});
        face(m,v,light,color,0,0,1,new float[]{x,y,Z,X,y,Z,X,Y,Z,x,Y,Z});
        face(m,v,light,color,-1,0,0,new float[]{x,y,z,x,y,Z,x,Y,Z,x,Y,z});
        face(m,v,light,color,1,0,0,new float[]{X,y,Z,X,y,z,X,Y,z,X,Y,Z});
        face(m,v,light,color,0,1,0,new float[]{x,Y,Z,X,Y,Z,X,Y,z,x,Y,z});
        face(m,v,light,color,0,-1,0,new float[]{x,y,z,X,y,z,X,y,Z,x,y,Z});
    }
    private static void face(MatrixStack m,VertexConsumer v,int light,int color,float nx,float ny,float nz,float[] p) {
        for(int i=0;i<4;i++) v.vertex(m.peek(),p[i*3],p[i*3+1],p[i*3+2]).color(color)
                .texture(i==1||i==2?1:0,i>=2?0:1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(m.peek(),nx,ny,nz);
    }
}
