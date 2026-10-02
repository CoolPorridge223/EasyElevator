package org.DJB.easyelevator.client;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.block.LandingDoorBlockEntity;
import org.DJB.easyelevator.block.LandingDoorGeometry;
import org.joml.Matrix4f;

/**
 * 楼层门渲染器：按连续进度把两扇门扇画出来，并在门框顶部显示轿厢当前楼层。
 *
 * <p>分工：门框（左右立柱 3/16 格宽、门楣 3/16 格高）是常驻几何，由方块模型
 * {@code landing_door_frame_*.json} 绘制；两扇可动门扇位于门洞内、会随进度收拢，
 * 方块模型无法在渲染时插值，因此交给方块实体渲染器逐帧画。框用亮白贴图、扇用暗白贴图，
 * 于是"门框"与"门"在视觉上分得开。
 *
 * <p>动画来源：{@link LandingDoorBlockEntity#openProgress(float)}，它逐刻采样的就是
 * 在站轿厢的门进度，因此楼层门门扇与 {@link CabinRenderer} 画的轿厢门扇
 * （{@code cabin.doorProgress(tickDelta)}）同刻同值、用同一个 tickDelta 插值，两层门完全同步，
 * 不会再出现"门面瞬间变一堵墙"。
 *
 * <p>几何全部来自 {@link LandingDoorGeometry}（{@link LandingDoorBlock} 的碰撞形状也取同一份），
 * 因此画面与碰撞不会脱节。
 */
public class LandingDoorRenderer implements BlockEntityRenderer<LandingDoorBlockEntity> {
    /** 门扇贴图：暗白占位贴图，与门框模型的亮白形成层次。换正式素材时替换这一张即可。 */
    private static final Identifier TEXTURE=Easyelevator.id("textures/block/blank_dark.png");

    /** 门框顶部文字的字号（格/像素：0.02 × 字体 9 像素高 ≈ 0.18 格，正好落在 3/16 格高的门楣上）与颜色（红色）。 */
    private static final float FLOOR_SCALE=.02f;
    private static final int FLOOR_COLOR=0xFFFF4040;
    /** 状态文本与楼层号之间的留白（像素），以及整行的纵向锚点（像素；实测取 -4 时正落在门楣中部）。 */
    private static final float STATUS_GAP=6f, TEXT_Y=-4f;

    /** 门扇不做顶点着色（保持贴图原色，由光照决定明暗）。
     * @param context 渲染器上下文（本渲染器不需要额外资源） */
    public LandingDoorRenderer(BlockEntityRendererFactory.Context context) { }

    /**
     * 绘制门框顶部的楼层数字与两扇门扇（渲染原点 = 根方块原点）。
     *
     * <p>副作用：只往顶点缓冲写入几何与文字，不改世界状态、不发包。仅客户端调用。
     *
     * @param door 根方块的方块实体；进度样本、楼层号与门朝向都来自它
     * @param tickDelta 渲染插值系数 0..1，与轿厢门使用同一个值
     * @param matrices 渲染矩阵栈
     * @param buffers 顶点缓冲提供者
     * @param light 打包后的光照值
     * @param overlay 覆盖层（未使用，交给 {@link BoxMesh} 填默认值）
     */
    @Override
    public void render(LandingDoorBlockEntity door,float tickDelta,MatrixStack matrices,VertexConsumerProvider buffers,int light,int overlay) {
        BlockState state=door.getCachedState();
        // 方块实体只在根方块创建，这里再挡一次：万一其它部件也带上实体，也不会把整扇门画 9 遍
        if(!LandingDoorBlock.isRoot(state)) return;
        // 楼层数字与门扇无关（门全开时没有门扇几何），因此先画层号再判断门扇是否还需要绘制
        drawFloorDisplay(door,state,matrices,buffers,light);
        float progress=door.openProgress(tickDelta);
        // 全开时两扇门扇宽度归零、完全躲进门框立柱后面，没有可见几何，直接跳过
        if(progress>=.999f) return;
        Box left=LandingDoorGeometry.leafBox(state.get(LandingDoorBlock.FACING),progress,false);
        Box right=LandingDoorGeometry.leafBox(state.get(LandingDoorBlock.FACING),progress,true);
        if(left==null&&right==null) return;
        // getEntityCutout 会剔除背面：门扇是实心长方体，背向的面本来看不见；
        // 顺带让"门扇外缘与门框内缘贴合"处的共面三角形不再互相闪烁。
        VertexConsumer out=buffers.getBuffer(RenderLayer.getEntityCutout(TEXTURE));
        if(left!=null) BoxMesh.cuboid(matrices,out,left,light,0xFFFFFFFF);
        if(right!=null) BoxMesh.cuboid(matrices,out,right,light,0xFFFFFFFF);
    }

    /**
     * 在门框顶部横向显示"[运行状态] [楼层号]"（例如"电梯上行 3"），整组水平居中。
     *
     * <p>排版：先量出状态文本与楼层号的像素宽度，起点取"负的半个总宽"（含中间留白），于是状态文本在左、
     * 楼层号在右、整组在门宽中点居中。状态文本宽度随内容变化（"停靠"比"电梯上行"窄），所以每帧重算，
     * 不会出现固定左边距导致的偏心。
     *
     * <p>位置：从方块中心沿朝向推出"半格（到门面）+ 0.02 格"的距离，正好贴在门面外侧；高度取门楣中部
     * （局部 Y=2.9）。方块实体渲染器传进来的矩阵<b>已经平移到方块原点</b>，因此只能用方块内局部坐标。
     *
     * <p>副作用：只向顶点缓冲写入文字（与门扇共用渲染管线传入的缓冲，由管线统一 flush），
     * 不改世界状态、不发包。
     *
     * @param door 根方块的方块实体（提供每刻缓存的楼层号与运行状态）
     * @param state 根方块状态（提供朝向）
     * @param matrices 渲染矩阵栈
     * @param buffers 顶点缓冲提供者：与原版告示牌一样直接复用管线给的这一个
     * @param light 打包后的光照值
     */
    private static void drawFloorDisplay(LandingDoorBlockEntity door,BlockState state,MatrixStack matrices,VertexConsumerProvider buffers,int light) {
        int floor=door.cabinFloor();
        if(floor<=0) return; // 本线路没有轿厢：什么都不画，避免显示成"0 层"
        Direction facing=state.get(LandingDoorBlock.FACING);
        TextRenderer textRenderer=MinecraftClient.getInstance().textRenderer;
        Text statusText=Text.translatable("status.easyelevator."+door.cabinStatus().key());
        Text floorText=Text.literal(Integer.toString(floor));
        int statusWidth=textRenderer.getWidth(statusText), floorWidth=textRenderer.getWidth(floorText);
        float start=-(statusWidth+STATUS_GAP+floorWidth)/2f; // 整组居中的起点
        // 位置：从方块中心沿朝向推出"半格（到门面）+ 0.02 格"；门面在朝向轴上正向为 1.0、反向为 0.0，
        // 这样四种朝向都正好贴在门面外侧，不会浮在门外半格、也不会嵌进方块内部。
        double offset=.5+.02;
        matrices.push();
        // 高度取门楣中部（门楣在局部 Y=2.8125..3.0），文字正好落在门框顶部那条横梁上
        matrices.translate(.5+facing.getOffsetX()*offset,2.9,.5+facing.getOffsetZ()*offset);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(switch(facing) {case NORTH->180;case EAST->90;case WEST->-90;default->0;}));
        // Y 轴取负：字体内部坐标是 Y 向下，与告示牌一致；不取负文字会上下颠倒。
        matrices.scale(FLOOR_SCALE,-FLOOR_SCALE,FLOOR_SCALE);
        Matrix4f matrix=matrices.peek().getPositionMatrix();
        // 最高亮度：井道或走廊再暗也能看清红字。POLYGON_OFFSET 与原版告示牌一致，避免与门框面片闪烁。
        textRenderer.draw(statusText,start,TEXT_Y,FLOOR_COLOR,true,matrix,buffers,TextRenderer.TextLayerType.POLYGON_OFFSET,0,LightmapTextureManager.MAX_LIGHT_COORDINATE);
        textRenderer.draw(floorText,start+statusWidth+STATUS_GAP,TEXT_Y,FLOOR_COLOR,true,matrix,buffers,TextRenderer.TextLayerType.POLYGON_OFFSET,0,LightmapTextureManager.MAX_LIGHT_COORDINATE);
        matrices.pop();
    }

    /**
     * 始终按渲染距离判定，不做"轮廓包围盒"可见性剔除。
     *
     * <p>原因：方块实体渲染器的默认剔除用的是<b>根方块自己那一格</b>的轮廓形状，而门扇会滑到左右两列
     * （进度过半后两扇都离开了中列），此时中列的轮廓已经为空，若沿用默认剔除，门扇会在还看得见的时候
     * 突然消失。这里改为按渲染距离显示，整扇门由 {@link #getRenderDistance()} 与摄像机裁剪控制。
     *
     * @param door 根方块的方块实体
     * @return 恒为 true
     */
    @Override
    public boolean rendersOutsideBoundingBox(LandingDoorBlockEntity door) { return true; }
}
