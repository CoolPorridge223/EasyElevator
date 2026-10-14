package org.DJB.easyelevator.client;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.block.LandingDoorBlockEntity;
import org.DJB.easyelevator.block.LandingDoorGeometry;
import org.DJB.easyelevator.logic.FloorIndicator;
import org.DJB.easyelevator.logic.LeafUv;

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
 *
 * <h2>1.21.9 之后的渲染架构</h2>
 * 方块实体渲染也改成了"提取 → 提交 → 绘制"：{@link #updateRenderState} 把方块实体里那份
 * <b>每刻缓存</b>（门进度、楼层号、运行状态、是否玻璃门、两扇门扇的几何盒）抄进
 * {@link LandingDoorRenderState}，{@link #render} 只读 state 并往
 * {@link OrderedRenderCommandQueue} 提交命令，顶点由原版在本帧稍后写入。
 * 三条必须遵守的约束：
 * <ol>
 *   <li>{@code render} 里<b>没有</b>方块实体引用：所有"读实体"的调用都必须在
 *       {@link #updateRenderState} 里完成（那里还能拿到 {@link LandingDoorBlockEntity}）；</li>
 *   <li>几何回调的第一个参数是"提交时快照的" {@link MatrixStack.Entry}，<b>不能</b>回头读外层
 *       {@link MatrixStack}（回调可能在 {@code render} 返回之后才执行，那时矩阵栈已经不保证）；</li>
 *   <li>旧版"换层会作废上一个缓冲（{@code Not building!}）"的约束已不成立：铁框与玻璃是两条独立的
 *       {@code submitCustom} 命令。</li>
 * </ol>
 */
public class LandingDoorRenderer implements BlockEntityRenderer<LandingDoorBlockEntity, LandingDoorRenderer.LandingDoorRenderState> {
    /** 门扇贴图：深色阳极氧化钢板，比门框的亮钢暗一档，形成层次。
     *  贴图纵轴映射到门扇高度（v=0 在上、v=1 在下），因此图里的踢脚板正好落在门扇底部。 */
    private static final Identifier TEXTURE=Easyelevator.id("textures/block/blank_door.png");
    /** 门扇层：1.21.9 起工厂搬到了 {@link RenderLayers}（{@code RenderLayers.entityCutout}）。 */
    private static final RenderLayer LAYER_LEAF=RenderLayers.entityCutout(TEXTURE);
    /** 与轿厢相同，原版无色玻璃不使用整面染色或 alpha 混合。 */
    private static final int GLASS_COLOR=0xFFFFFFFF;

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
     * 渲染状态：{@link #render} 能看到的<b>全部</b>数据，全部来自 {@link #updateRenderState}。
     *
     * <p>为什么连两扇门扇的盒子都要先算好：几何完全由 {@code progress} 与朝向决定，而
     * {@code render} 里已经读不到方块实体，也没有 {@code tickDelta} 可以再插值。
     */
    public static class LandingDoorRenderState extends BlockEntityRenderState {
        /** 根方块：为 false 时整扇门什么都不画（门被拆/其它部件带上方块实体）。 */
        boolean root;
        /** 门扇朝向（决定厚度方向是 X 还是 Z，以及文字面的朝向）。 */
        Direction facing=Direction.NORTH;
        /** 插值后的门进度 0..1。 */
        float progress;
        /** 是否观光线路：门扇换成"铁框 + 中间玻璃"。 */
        boolean glass;
        /** 门框顶部的楼层号（0 = 不知道，不显示）。 */
        int floor;
        /** 是否正在运行（决定是否为箭头预留宽度）。 */
        boolean moving;
        /** 运行方向的闪烁箭头字符（空串 = 停靠或灭相）。 */
        String arrow="";
        /** 两扇门扇的长方体（格，方块局部坐标）；某一扇不可见时为 null。 */
        Box left,right;
    }

    /** @return 一份新的楼层门渲染状态（原版每帧取一次，见 {@link #updateRenderState}）。 */
    @Override public LandingDoorRenderState createRenderState() { return new LandingDoorRenderState(); }

    /**
     * 把方块实体数据"提取"进渲染状态。
     *
     * <p>先调原版助手 {@link BlockEntityRenderState#updateBlockEntityRenderState} 填好基础字段
     * （pos / blockState / type / 光照，其中光照来自 {@code WorldRenderer.getLightmapCoordinates}，
     * 与旧版渲染方法收到的 {@code light} 参数同源）。
     *
     * <p>副作用：只读方块实体与方块状态（{@code cabinFloor()} / {@code cabinStatus()} /
     * {@code glassDoors()} 内部有每刻缓存，逐帧调用不会重复扫世界），不改世界状态、不发包。仅客户端调用。
     *
     * @param door 根方块的方块实体；进度样本、楼层号与门朝向都来自它
     * @param state 待填充的渲染状态
     * @param tickDelta 渲染插值系数 0..1，与轿厢门使用同一个值
     * @param cameraPos 摄像机位置（本渲染器不使用：可见性由 {@link #rendersOutsideBoundingBox()} 与渲染距离控制）
     * @param crumblingOverlay 方块破坏覆盖层（交给原版助手存进 state，本渲染器不使用）
     */
    @Override
    public void updateRenderState(LandingDoorBlockEntity door,LandingDoorRenderState state,float tickDelta,Vec3d cameraPos,ModelCommandRenderer.CrumblingOverlayCommand crumblingOverlay) {
        BlockEntityRenderState.updateBlockEntityRenderState(door,state,crumblingOverlay);
        BlockState block=state.blockState;
        // 方块实体只在根方块创建，这里再挡一次：万一其它部件也带上实体，也不会把整扇门画 9 遍
        state.root=block!=null&&LandingDoorBlock.isRoot(block);
        state.floor=door.cabinFloor();
        // 运行方向用闪烁箭头表示（▲ 上行 / ▼ 下行 / 停靠留空），不再写"电梯上行"这类文字。
        state.moving=StatusArrow.moving(door.cabinStatus());
        state.arrow=StatusArrow.glyph(door.cabinStatus());
        state.progress=door.openProgress(tickDelta);
        // 观光轿厢所在的线路：所有楼层门的门扇都换成"铁框 + 中间玻璃"（判据来自门方块实体，见 glassDoors()）。
        state.glass=door.glassDoors();
        // 只有真的是楼层门根方块时才去读 FACING：换过方块的旧方块实体没有这个属性，读它会抛异常。
        if(state.root) {
            state.facing=block.get(LandingDoorBlock.FACING);
            state.left=LandingDoorGeometry.leafBox(state.facing,state.progress,false);
            state.right=LandingDoorGeometry.leafBox(state.facing,state.progress,true);
        }
    }

    /**
     * 提交门框顶部的楼层数字与两扇门扇的绘制命令。
     *
     * <p>副作用：只往命令队列提交几何与文字，不改世界状态、不发包。仅客户端调用。
     *
     * @param state 渲染状态（本帧提取好的全部数据）
     * @param matrices 渲染矩阵栈（已平移到根方块原点；本方法只在其上叠加局部变换）
     * @param queue 有序渲染命令队列
     * @param cameraState 摄像机渲染状态（本渲染器不需要）
     */
    @Override
    public void render(LandingDoorRenderState state,MatrixStack matrices,OrderedRenderCommandQueue queue,CameraRenderState cameraState) {
        if(!state.root) return;
        // 楼层数字与门扇无关（门全开时没有门扇几何），因此先提交层号再判断门扇是否还需要绘制
        drawFloorDisplay(state,matrices,queue);
        float progress=state.progress;
        // 全开时两扇门扇宽度归零、完全躲进门框立柱后面，没有可见几何，直接跳过
        if(progress>=.999f) return;
        Box left=state.left, right=state.right;
        if(left==null&&right==null) return;
        // 门扇是实心长方体，背向的面本来看不见；用带背面剔除的 entityCutout 顺带让
        // "门扇外缘与门框内缘贴合"处的共面三角形不再互相闪烁。
        // 观光线路：不透明层只画铁框，玻璃另提交一条命令（见下面的玻璃层）。
        boolean plateAlongZ=state.facing==Direction.NORTH||state.facing==Direction.SOUTH;
        // 门扇是"盒子越收越窄"画出来的：照旧把整张贴图铺上去，贴图会随开门被横向挤压。
        // 大面只取"此刻还露在外面"的那一段（靠门框那一端固定、移动端被门框挡住），断面另给一小段贴图，
        // 换算集中在 logic/LeafUv 里（纯算术 + 单测）。
        // 注意这里**没有**朝向参数：BoxMesh 把 UV 的 u0 交给每个面的第一个顶点，而四种朝向下
        // "朝走廊那一面"的第一个顶点分别落在左扇的先导端、右扇的门框端（doorBox 的镜像正好抵消），
        // 因此贴图方向只由"哪一扇"决定。之前按 SOUTH/WEST 翻端，导致四个朝向里只有两个是对的。
        if(state.glass) {
            queue.submitCustom(matrices,LAYER_LEAF,(entry,out)->{
                if(left!=null) FramedGlassDoor.frame(entry,out,left,plateAlongZ,BoxMesh.FULL_UV,state.lightmapCoordinates);
                if(right!=null) FramedGlassDoor.frame(entry,out,right,plateAlongZ,BoxMesh.FULL_UV,state.lightmapCoordinates);
            });
            // 玻璃：零厚度双面，画在门扇厚度中线上（与铁框同层，但同层内后提交、后绘制）
            queue.submitCustom(matrices,GlassLayers.DOOR,(entry,panes)->{
                if(left!=null) FramedGlassDoor.glass(entry,panes,left,plateAlongZ,BoxMesh.FULL_UV,state.lightmapCoordinates,GLASS_COLOR);
                if(right!=null) FramedGlassDoor.glass(entry,panes,right,plateAlongZ,BoxMesh.FULL_UV,state.lightmapCoordinates,GLASS_COLOR);
            });
            return;
        }
        queue.submitCustom(matrices,LAYER_LEAF,(entry,out)->{
            if(left!=null)
                BoxMesh.cuboid(entry,out,left,state.lightmapCoordinates,0xFFFFFFFF,LeafUv.slabUv(
                        LeafUv.toUv(LeafUv.leafRange(progress,false),BoxMesh.FULL_UV),
                        LeafUv.toUv(LeafUv.edgeRange(false,LeafUv.EDGE_WIDTH),BoxMesh.FULL_UV),plateAlongZ));
            if(right!=null)
                BoxMesh.cuboid(entry,out,right,state.lightmapCoordinates,0xFFFFFFFF,LeafUv.slabUv(
                        LeafUv.toUv(LeafUv.leafRange(progress,true),BoxMesh.FULL_UV),
                        LeafUv.toUv(LeafUv.edgeRange(true,LeafUv.EDGE_WIDTH),BoxMesh.FULL_UV),plateAlongZ));
        });
    }

    /**
     * 在门框顶部横向显示"[方向箭头] [楼层号]"（例如"▲ 3"），整组水平居中；箭头会闪烁，停靠时不显示。
     *
     * <p>排版：先量出方向箭头与楼层号的像素宽度，起点取"负的半个总宽"（含中间留白），于是箭头在左、
     * 楼层号在右、整组在门宽中点居中。箭头宽度固定按字符本身预留（停靠时不占位，见 {@code StatusArrow.width}），
     * 因此闪烁的半个周期里楼层号不会左右跳动。
     *
     * <p>位置：从方块中心沿朝向推出"半格（到方块面）− 屏幕凹陷 + 0.008 格空隙"，因此红字正好贴在
     * 凹进去的显示屏面上（而不是浮在方块面之外）；高度取屏幕中线，字体坐标再下移半个字高
     * （{@code submitText} 的 y 也是这一行的**顶边**）。方块实体渲染器传进来的矩阵
     * <b>已经平移到方块原点</b>，因此只能用方块内局部坐标。
     *
     * <p>副作用：只向命令队列提交文字（原版统一在所有自定义几何之前绘制并 flush），
     * 不改世界状态、不发包。
     *
     * @param state 渲染状态（楼层号与运行方向都已在提取阶段抄好）
     * @param matrices 渲染矩阵栈（submitText 会自行快照矩阵）
     * @param queue 有序渲染命令队列
     */
    private static void drawFloorDisplay(LandingDoorRenderState state,MatrixStack matrices,OrderedRenderCommandQueue queue) {
        int floor=state.floor;
        if(floor==0) return; // 本线路没有轿厢：什么都不画，避免显示成"0 层"（负数 = 地下 B1、B2…，要显示）
        Direction facing=state.facing;
        TextRenderer textRenderer=MinecraftClient.getInstance().textRenderer;
        Text arrowText=Text.literal(state.arrow);
        // 楼层号统一走 FloorIndicator.format：基准层 1、其上 2,3…、其下 B1,B2…，与选站面板、轿厢内面板同一口径。
        Text floorText=Text.literal(FloorIndicator.format(floor));
        // 箭头宽度始终按字符本身预留（停靠时不占位），这样闪烁的半个周期里楼层号不会左右跳动。
        int statusWidth=state.moving?StatusArrow.width(textRenderer):0, floorWidth=textRenderer.getWidth(floorText);
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
        float textY=-textRenderer.fontHeight/2f; // 行心 → 顶边（见上）
        // 最高亮度：井道或走廊再暗也能看清红字。POLYGON_OFFSET 与原版告示牌一致，避免与屏幕面片闪烁。
        // 只在"正在运行 + 闪烁的亮相"画箭头；灭相与停靠都不画，位置由上面的 statusWidth 固定住。
        if(!state.arrow.isEmpty())
            queue.submitText(matrices,start,textY,arrowText.asOrderedText(),true,
                    TextRenderer.TextLayerType.POLYGON_OFFSET,LightmapTextureManager.MAX_LIGHT_COORDINATE,FLOOR_COLOR,0,0);
        queue.submitText(matrices,start+statusWidth+STATUS_GAP,textY,floorText.asOrderedText(),true,
                TextRenderer.TextLayerType.POLYGON_OFFSET,LightmapTextureManager.MAX_LIGHT_COORDINATE,FLOOR_COLOR,0,0);
        matrices.pop();
    }

    /**
     * 始终按渲染距离判定，不做"轮廓包围盒"可见性剔除。
     *
     * <p>原因：方块实体渲染器的默认剔除用的是<b>根方块自己那一格</b>的轮廓形状，而门扇会滑到左右两列
     * （进度过半后两扇都离开了中列），此时中列的轮廓已经为空，若沿用默认剔除，门扇会在还看得见的时候
     * 突然消失。这里改为按渲染距离显示，整扇门由 {@link #getRenderDistance()} 与摄像机裁剪控制。
     *
     * <p>1.21.9 起这个方法<b>没有参数</b>（判定发生在"提取"之前，与具体方块实体无关）。
     *
     * @return 恒为 true
     */
    @Override
    public boolean rendersOutsideBoundingBox() { return true; }
}
