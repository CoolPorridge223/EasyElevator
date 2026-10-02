package org.DJB.easyelevator.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
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
    /** 轿厢内面板的字号（格/像素）：楼层行大一号、运行状态行小一点；两行各自水平居中。 */
    private static final float FLOOR_SCALE=.018f, STATUS_SCALE=.011f;
    /** 两行的行锚点（像素，相对面板中心）：负 Y 缩放后局部 +Y 是世界向下，因此楼层行取负（在上）、状态行取正（在下）。 */
    private static final float FLOOR_LINE_Y=-10f, STATUS_LINE_Y=5f;
    /** 面板上文字的颜色（红色）；与门框顶部、选站面板显示同一个楼层号与状态。 */
    private static final int FLOOR_COLOR=0xFFFF4040;
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
        BoxMesh.cuboid(matrices,out,-1.5f,0,-1.5f,1.5f,.2f,front,light,0xFFE6E6E6); // 底板：Y=0..0.2 格，略暗以区分地面
        BoxMesh.cuboid(matrices,out,-1.5f,2.8f,-1.5f,1.5f,3,front,light,0xFFF5F5F5); // 顶板：Y=2.8..3.0 格，最高亮度
        BoxMesh.cuboid(matrices,out,-1.5f,.2f,-1.5f,-1.3f,2.8f,front,light,0xFFFFFFFF); // 左侧壁：厚 0.2 格
        BoxMesh.cuboid(matrices,out,1.3f,.2f,-1.5f,1.5f,2.8f,front,light,0xFFFFFFFF); // 右侧壁：厚 0.2 格
        BoxMesh.cuboid(matrices,out,-1.3f,.2f,-1.5f,1.3f,2.8f,-1.3f,light,0xFFFFFFFF); // 后壁：Z=-1.5..-1.3 格，正面留空形成门洞
        float open=cabin.doorProgress(delta);
        // Two sliding leaves retract into the side walls. 0=closed, 1=open.
        // 两扇滑门，0 = 完全关闭、1 = 完全开启：门宽按 1.3 格 * open 内缩，收到侧壁里（不做缩放，避免纹理拉伸）。
        if(open<.999f) {
            // 门板 Z 范围 doorBack..front（格），即夹在门洞内侧与轿厢正面之间，厚 0.2 格。
            BoxMesh.cuboid(matrices,out,-1.3f,.2f,doorBack,-1.3f*open,2.8f,front,light,0xFFCCCCCC);
            BoxMesh.cuboid(matrices,out,1.3f*open,.2f,doorBack,1.3f,2.8f,front,light,0xFFCCCCCC);
        }
        // Blank interior panel marker.
        // 轿厢内壁的空白操作面板（占位标记，无交互）：贴在右侧壁内侧 0.05 格厚、1.2..1.8 格高处。
        BoxMesh.cuboid(matrices,out,1.25f,1.2f,.3f,1.30f,1.8f,.8f,light,0xFFBBBBBB);
        // 面板上的楼层号（红色），与选站面板、楼层门框顶部显示的是同一个由服务端同步的楼层号。
        drawFloorDisplay(cabin,matrices,buffers,light);
        matrices.pop(); super.render(cabin,yaw,delta,matrices,buffers,light);
    }

    /**
     * 在轿厢内右侧壁的模拟操作面板上画两行红字：第一行是当前到达层数，第二行是运行状态
     * （"电梯上行" / "电梯下行" / "停靠"）。
     *
     * <p>面板是 1.25..1.30（X）× 1.2..1.8（Y）× 0.3..0.8（Z）的占位方块，内侧朝 -X：文字先绕 Y 轴
     * 转 -90° 让正面朝 -X（轿厢内部），再按面板中心定位，因此乘客在轿厢里读到的是正向文字。
     * 楼层那行字号 0.018 格/像素（两位数也放得下），状态那行 0.011（四个汉字约 0.4 格，正好在面板内）。
     *
     * <p>副作用：只向顶点缓冲写入文字（复用管线传入的缓冲，与原版告示牌一致），不改实体状态、不发包。
     *
     * @param cabin 轿厢（提供同步过来的楼层号与运行状态）
     * @param matrices 渲染矩阵栈（已包含轿厢朝向与位置）
     * @param buffers 顶点缓冲提供者：直接用管线给的这一个，由管线统一 flush
     * @param light 打包后的光照值
     */
    private static void drawFloorDisplay(CabinEntity cabin,MatrixStack matrices,VertexConsumerProvider buffers,int light) {
        int floor=cabin.floorNumber();
        if(floor<=0) return; // 还没经过任何站点（或线路无效）：不显示，避免出现"0 层"
        TextRenderer textRenderer=MinecraftClient.getInstance().textRenderer;
        // 负 Y 缩放之后，局部 +Y 对应世界里的"向下"，所以取负偏移的那行显示在上面：
        // 第一行楼层号、第二行运行状态。
        drawPanelLine(textRenderer,matrices,buffers,Text.literal(Integer.toString(floor)),FLOOR_LINE_Y,FLOOR_SCALE);
        drawPanelLine(textRenderer,matrices,buffers,Text.translatable("status.easyelevator."+cabin.status().key()),STATUS_LINE_Y,STATUS_SCALE);
    }

    /**
     * 在轿厢内面板上画一行水平居中的红字。
     *
     * @param textRenderer 字体渲染器
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param buffers 顶点缓冲提供者
     * @param text 这一行的文本
     * @param yOffset 行锚点（像素，相对面板中心；负 Y 缩放后局部 +Y 是世界向下）
     * @param scale 这一行的字号（格/像素）
     *
     * <p>副作用：只写顶点缓冲。用最高亮度是因为轿厢内部往往很暗，按局部光照画出来会是一团黑；
     * POLYGON_OFFSET 给文字一点深度偏移，贴在面板上不会与面板面片闪烁。
     */
    private static void drawPanelLine(TextRenderer textRenderer,MatrixStack matrices,VertexConsumerProvider buffers,Text text,float yOffset,float scale) {
        matrices.push();
        // 实体渲染器传进来的矩阵已经平移到实体位置，因此这里全部用轿厢局部坐标（面板在右侧壁内侧）。
        matrices.translate(1.245f,1.5f,.55f); // 面板中心，沿 -X 略微让开面片
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-90)); // 正面（局部 +Z）转到局部 -X，朝轿厢内部
        matrices.scale(scale,-scale,scale); // Y 取负：字体内部坐标是 Y 向下，与告示牌一致；不取负文字会上下颠倒
        textRenderer.draw(text,-textRenderer.getWidth(text)/2f,yOffset,FLOOR_COLOR,true,
                matrices.peek().getPositionMatrix(),buffers,TextRenderer.TextLayerType.POLYGON_OFFSET,0,LightmapTextureManager.MAX_LIGHT_COORDINATE);
        matrices.pop();
    }
}
