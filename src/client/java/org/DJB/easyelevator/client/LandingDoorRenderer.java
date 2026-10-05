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
import org.DJB.easyelevator.logic.ElevatorStatus;
import org.DJB.easyelevator.logic.FloorIndicator;
import org.DJB.easyelevator.logic.LeafUv;
import org.joml.Matrix4f;

/**
 * 楼层门渲染器：按连续进度把两扇门扇画出来，并在门框顶部显示轿厢当前楼层。
 *
 * <p>分工：门框（左右立柱 3/16 格宽、门楣 3/16 格高）是常驻几何，由方块模型
 * {@code landing_door_frame_*.json} 绘制（亮钢 {@code blank} + 底座/门槛 {@code blank_plate}
 * + 门楣显示屏 {@code blank_screen}）；两扇可动门扇位于门洞内、会随进度收拢，
 * 方块模型无法在渲染时插值，因此交给方块实体渲染器逐帧画。
 * 扇用深色阳极氧化 {@code blank_door}，那一张图里自带面板压边、中缝与踢脚板，
 * 于是"门框"与"门"在视觉上分得开、门扇本身也有细节。
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
    /** 门扇贴图：深色阳极氧化钢板，比门框的亮钢暗一档，形成层次。
     *  贴图纵轴映射到门扇高度（v=0 在上、v=1 在下），因此图里的踢脚板正好落在门扇底部。 */
    private static final Identifier TEXTURE=Easyelevator.id("textures/block/blank_door.png");
    /** 铁框玻璃门扇的玻璃色（ARGB）：比观光舱壁略不透一点，隔着玻璃仍能看清井道与轿厢。 */
    private static final int GLASS_COLOR=0x66A8D2EC;

    /**
     * 门楣显示屏上的字号（格/像素）、颜色与排版参数。
     *
     * <p>显示屏是门楣中间那条**凹进去**的深色玻璃（方块模型 {@code landing_door_frame_top*}
     * 里 Y=13.25..15.75/16、Z=0.75/16 起的那一件），屏幕净高 2.5/16 = 0.15625 格。
     * 字号取 {@code .016} 时：楼层号字模约 7 像素 = 0.112 格、方向箭头约 8 像素 = 0.128 格，
     * 都留有余量；之前用 {@code .02} 时字模 0.18 格、比整条门楣还高，数字会溢出屏幕压在钢框上
     * （1.5.6 实机反馈的"数字有点突兀"就是这个）。
     */
    private static final float FLOOR_SCALE=.016f;
    private static final int FLOOR_COLOR=0xFFFF4040;
    /** 方向箭头与楼层号之间的留白（像素）。 */
    private static final float STATUS_GAP=6f;
    /**
     * 显示屏中线的高度（根方块局部 Y，格）：顶行 13.25..15.75/16 的中点是 <b>14.5/16</b>，再加 2 格层高。
     *
     * <p>注意中点不是 13.5：13.5 是屏幕下沿往上 0.25/16 处，写成 13.5 会让整行字下移 1/16 格，
     * 字模下半截跑到屏幕之外——被压边挡住的部分看不见，露在门洞里的那一截就成了"穿透"的横杠
     * （1.5.6 第二轮实机反馈）。改这个常量请连同上下的压边一起量。
     */
    private static final float SCREEN_CENTRE_Y=2+14.5f/16f;
    /** 显示屏面（凹进面）相对方块面的深度（格），必须与模型里的 {@code SCREEN_INSET} 一致（0.75/16）。 */
    private static final double SCREEN_INSET=.75/16;
    /** 文字离屏面留的空隙（格）：太小会与屏幕面抢深度，太大会看成浮在空中。 */
    private static final double TEXT_STANDOFF=.008;

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
        Direction facing=state.get(LandingDoorBlock.FACING);
        Box left=LandingDoorGeometry.leafBox(facing,progress,false);
        Box right=LandingDoorGeometry.leafBox(facing,progress,true);
        if(left==null&&right==null) return;
        // getEntityCutout 会剔除背面：门扇是实心长方体，背向的面本来看不见；
        // 顺带让"门扇外缘与门框内缘贴合"处的共面三角形不再互相闪烁。
        VertexConsumer out=buffers.getBuffer(RenderLayer.getEntityCutout(TEXTURE));
        // 观光轿厢所在的线路：所有楼层门的门扇都换成"铁框 + 中间玻璃"（判据来自门方块实体，见 glassDoors()）。
        // 玻璃必须画在不透明层之后、而且必须在返回前只写玻璃层（换层会结束上一层缓冲，见 BoxMesh 类注释）。
        boolean glass=door.glassDoors();
        // 门扇是"盒子越收越窄"画出来的：照旧把整张贴图铺上去，贴图会随开门被横向挤压。
        // 大面只取"此刻还露在外面"的那一段（靠门框那一端固定、移动端被门框挡住），断面另给一小段贴图，
        // 换算集中在 logic/LeafUv 里（纯算术 + 单测）。
        // 注意这里**没有**朝向参数：BoxMesh 把 UV 的 u0 交给每个面的第一个顶点，而四种朝向下
        // "朝走廊那一面"的第一个顶点分别落在左扇的先导端、右扇的门框端（doorBox 的镜像正好抵消），
        // 因此贴图方向只由"哪一扇"决定。之前按 SOUTH/WEST 翻端，导致四个朝向里只有两个是对的。
        boolean plateAlongZ=facing==Direction.NORTH||facing==Direction.SOUTH;
        if(glass) {
            // 铁框玻璃门：不透明层只画铁框，玻璃在同一层的末尾单独画（见下面 glassPanes）
            if(left!=null) FramedGlassDoor.frame(matrices,out,left,plateAlongZ,BoxMesh.FULL_UV,light);
            if(right!=null) FramedGlassDoor.frame(matrices,out,right,plateAlongZ,BoxMesh.FULL_UV,light);
            VertexConsumer panes=buffers.getBuffer(GlassLayers.DOOR);
            if(left!=null) FramedGlassDoor.glass(matrices,panes,left,plateAlongZ,BoxMesh.FULL_UV,light,GLASS_COLOR);
            if(right!=null) FramedGlassDoor.glass(matrices,panes,right,plateAlongZ,BoxMesh.FULL_UV,light,GLASS_COLOR);
            return; // 已经切到玻璃层，不能再往 out 写顶点
        }
        if(left!=null)
            BoxMesh.cuboid(matrices,out,left,light,0xFFFFFFFF,LeafUv.slabUv(
                    LeafUv.toUv(LeafUv.leafRange(progress,false),BoxMesh.FULL_UV),
                    LeafUv.toUv(LeafUv.edgeRange(false,LeafUv.EDGE_WIDTH),BoxMesh.FULL_UV),plateAlongZ));
        if(right!=null)
            BoxMesh.cuboid(matrices,out,right,light,0xFFFFFFFF,LeafUv.slabUv(
                    LeafUv.toUv(LeafUv.leafRange(progress,true),BoxMesh.FULL_UV),
                    LeafUv.toUv(LeafUv.edgeRange(true,LeafUv.EDGE_WIDTH),BoxMesh.FULL_UV),plateAlongZ));
    }

    /**
     * 在门框顶部横向显示"[方向箭头] [楼层号]"（例如"▲ 3"），整组水平居中；箭头会闪烁，停靠时不显示。
     *
     * <p>排版：先量出状态文本与楼层号的像素宽度，起点取"负的半个总宽"（含中间留白），于是状态文本在左、
     * 楼层号在右、整组在门宽中点居中。状态文本宽度随内容变化（"停靠"比"电梯上行"窄），所以每帧重算，
     * 不会出现固定左边距导致的偏心。
     *
     * <p>位置：从方块中心沿朝向推出"半格（到方块面）− 屏幕凹陷 + 0.008 格空隙"，因此红字正好贴在
     * 凹进去的显示屏面上（而不是浮在方块面之外）；高度取屏幕中线，字体坐标再下移半个字高
     * （{@link TextRenderer#draw} 的 y 是这一行的**顶边**）。方块实体渲染器传进来的矩阵
     * <b>已经平移到方块原点</b>，因此只能用方块内局部坐标。
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
        if(floor==0) return; // 本线路没有轿厢：什么都不画，避免显示成"0 层"（负数 = 地下 B1、B2…，要显示）
        Direction facing=state.get(LandingDoorBlock.FACING);
        TextRenderer textRenderer=MinecraftClient.getInstance().textRenderer;
        // 运行方向用闪烁箭头表示（▲ 上行 / ▼ 下行 / 停靠留空），不再写"电梯上行"这类文字。
        ElevatorStatus status=door.cabinStatus();
        boolean moving=StatusArrow.moving(status);
        Text arrowText=Text.literal(StatusArrow.glyph(status));
        // 楼层号统一走 FloorIndicator.format：基准层 1、其上 2,3…、其下 B1,B2…，与选站面板、轿厢内面板同一口径。
        Text floorText=Text.literal(FloorIndicator.format(floor));
        // 箭头宽度始终按字符本身预留（停靠时不占位），这样闪烁的半个周期里楼层号不会左右跳动。
        int statusWidth=moving?StatusArrow.width(textRenderer):0, floorWidth=textRenderer.getWidth(floorText);
        float start=-(statusWidth+STATUS_GAP+floorWidth)/2f; // 整组居中的起点
        // 位置：从方块中心沿朝向推出"半格（到方块面）− 屏幕凹陷 + 一点空隙"，因此红字正好贴在
        // 凹进去的显示屏面上（而不是浮在方块面之外）。四种朝向的偏移量同一个公式，因为
        // "沿朝向往外"在正向与反向朝向上刚好互为镜像。
        double offset=.5-SCREEN_INSET+TEXT_STANDOFF;
        matrices.push();
        // 高度取显示屏中线：字体坐标的 y 是这一行的**顶边**，所以再下移半个字高，两行才会被屏幕装住。
        matrices.translate(.5+facing.getOffsetX()*offset,SCREEN_CENTRE_Y,.5+facing.getOffsetZ()*offset);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(switch(facing) {case NORTH->180;case EAST->90;case WEST->-90;default->0;}));
        // Y 轴取负：字体内部坐标是 Y 向下，与告示牌一致；不取负文字会上下颠倒。
        matrices.scale(FLOOR_SCALE,-FLOOR_SCALE,FLOOR_SCALE);
        Matrix4f matrix=matrices.peek().getPositionMatrix();
        float textY=-textRenderer.fontHeight/2f; // 行心 → 顶边（见上）
        // 最高亮度：井道或走廊再暗也能看清红字。POLYGON_OFFSET 与原版告示牌一致，避免与屏幕面片闪烁。
        // 只在"正在运行 + 闪烁的亮相"画箭头；灭相与停靠都不画，位置由上面的 statusWidth 固定住。
        if(!arrowText.getString().isEmpty())
            textRenderer.draw(arrowText,start,textY,FLOOR_COLOR,true,matrix,buffers,TextRenderer.TextLayerType.POLYGON_OFFSET,0,LightmapTextureManager.MAX_LIGHT_COORDINATE);
        textRenderer.draw(floorText,start+statusWidth+STATUS_GAP,textY,FLOOR_COLOR,true,matrix,buffers,TextRenderer.TextLayerType.POLYGON_OFFSET,0,LightmapTextureManager.MAX_LIGHT_COORDINATE);
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
