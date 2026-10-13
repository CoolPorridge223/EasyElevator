package org.DJB.easyelevator.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.block.LandingDoorBlockEntity;
import org.DJB.easyelevator.block.LandingDoorGeometry;
import org.DJB.easyelevator.logic.ElevatorStatus;
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
 * <h2>26.3 的两段式渲染</h2>
 * 方块实体渲染不再有 {@code render(entity, delta, poseStack, buffers, light, overlay)}，而是
 * {@link #extractRenderState}（把方块实体上的数据拷进 {@link LandingDoorRenderState}）+
 * {@link #submit}（只读状态、把几何与文字交给 {@link SubmitNodeCollector}）两步。
 * 门进度、朝向、玻璃门标志、层号与运行状态全部在抽状态时取一次，提交阶段不再触碰方块实体；
 * 顶点也不再自己取 {@code MultiBufferSource}，而是用
 * {@code submitCustomGeometry(poseStack, 图层, (pose, buffer) -> …)} 提交，文字走 {@code submitText}。
 */
public class LandingDoorRenderer implements BlockEntityRenderer<LandingDoorBlockEntity,LandingDoorRenderState> {
    /** 门扇贴图：深色阳极氧化钢板，比门框的亮钢暗一档，形成层次。
     *  贴图纵轴映射到门扇高度（v=0 在上、v=1 在下），因此图里的踢脚板正好落在门扇底部。 */
    private static final Identifier TEXTURE=Easyelevator.id("textures/block/blank_door.png");
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
    public LandingDoorRenderer(BlockEntityRendererProvider.Context context) { }

    /** 新建一份渲染状态；每帧由管线调用一次，本渲染器不在其中保存任何跨帧数据。 */
    @Override
    public LandingDoorRenderState createRenderState() { return new LandingDoorRenderState(); }

    /**
     * 抽取渲染状态：把方块实体上这一帧要用到的数据拷进状态对象。
     *
     * <p>为什么在这里取而不是在 {@link #submit} 里现算：26.3 的提交阶段只有状态对象，
     * 拿不到方块实体，也读不到方块状态（基类把 {@code blockState} 藏成了私有字段）。
     * 采样时刻比 1.21.1 提前了一个阶段，但仍是同一个渲染帧、同一个 tickDelta，数值完全相同。
     *
     * <p>副作用：只读方块实体与方块状态，不修改世界、不发包；仅客户端调用。
     *
     * @param door 根方块的方块实体；进度样本、楼层号与门朝向都来自它
     * @param state 待填充的渲染状态
     * @param tickDelta 渲染插值系数 0..1，与轿厢门使用同一个值
     * @param cameraPosition 摄像机位置（本渲染器不用，交给基类/默认剔除）
     * @param breakProgress 破坏进度覆盖层（未使用，交给基类填默认值）
     */
    @Override
    public void extractRenderState(LandingDoorBlockEntity door,LandingDoorRenderState state,float tickDelta,
                                   Vec3 cameraPosition,ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(door,state,tickDelta,cameraPosition,breakProgress);
        BlockState blockState=door.getBlockState();
        // 方块实体只在根方块创建，这里再挡一次：万一其它部件也带上实体，也不会把整扇门画 9 遍
        state.root=LandingDoorBlock.isRoot(blockState);
        if(!state.root) return;
        state.facing=blockState.getValue(LandingDoorBlock.FACING);
        state.progress=door.openProgress(tickDelta);
        state.glassDoors=door.glassDoors();
        state.cabinFloor=door.cabinFloor();
        state.cabinStatus=door.cabinStatus();
    }

    /**
     * 绘制门框顶部的楼层数字与两扇门扇（渲染原点 = 根方块原点，矩阵已由管线平移到方块位置）。
     *
     * <p>副作用：只向提交收集器提交几何与文字，不改世界状态、不发包。仅客户端调用。
     *
     * @param state 本帧的渲染状态（进度、朝向、层号、光照都来自它）
     * @param matrices 渲染矩阵栈
     * @param collector 提交收集器：几何走 {@code submitCustomGeometry}，文字走 {@code submitText}
     * @param camera 摄像机渲染状态（本渲染器不用，只透传给父类）
     */
    @Override
    public void submit(LandingDoorRenderState state,PoseStack matrices,SubmitNodeCollector collector,CameraRenderState camera) {
        if(!state.root) return;
        // 楼层数字与门扇无关（门全开时没有门扇几何），因此先画层号再判断门扇是否还需要绘制
        drawFloorDisplay(state,matrices,collector);
        float progress=state.progress;
        // 全开时两扇门扇宽度归零、完全躲进门框立柱后面，没有可见几何，直接跳过
        if(progress>=.999f) return;
        Direction facing=state.facing;
        AABB left=LandingDoorGeometry.leafBox(facing,progress,false);
        AABB right=LandingDoorGeometry.leafBox(facing,progress,true);
        if(left==null&&right==null) return;
        // 用带背面剔除的实体 cutout 图层：门扇是实心长方体，背向的面本来看不见；
        // 顺带让"门扇外缘与门框内缘贴合"处的共面三角形不再互相闪烁。
        int light=state.lightCoords;
        // 观光轿厢所在的线路：所有楼层门的门扇都换成"铁框 + 中间玻璃"（判据来自门方块实体，见 glassDoors()）。
        // 铁框与玻璃用各自的图层分两次提交：图层不同，混在一起就没法各自控制剔除与混合。
        boolean glass=state.glassDoors;
        // 门扇是"盒子越收越窄"画出来的：照旧把整张贴图铺上去，贴图会随开门被横向挤压。
        // 大面只取"此刻还露在外面"的那一段（靠门框那一端固定、移动端被门框挡住），断面另给一小段贴图，
        // 换算集中在 logic/LeafUv 里（纯算术 + 单测）。
        // 注意这里**没有**朝向参数：BoxMesh 把 UV 的 u0 交给每个面的第一个顶点，而四种朝向下
        // "朝走廊那一面"的第一个顶点分别落在左扇的先导端、右扇的门框端（doorBox 的镜像正好抵消），
        // 因此贴图方向只由"哪一扇"决定。之前按 SOUTH/WEST 翻端，导致四个朝向里只有两个是对的。
        boolean plateAlongZ=facing==Direction.NORTH||facing==Direction.SOUTH;
        if(glass) {
            // 铁框玻璃门：先提交铁框（不透明层），再提交玻璃（玻璃层）
            collector.submitCustomGeometry(matrices,RenderTypes.entityCutout(TEXTURE),(pose,buffer)->{
                if(left!=null) FramedGlassDoor.frame(pose,buffer,left,plateAlongZ,BoxMesh.FULL_UV,light);
                if(right!=null) FramedGlassDoor.frame(pose,buffer,right,plateAlongZ,BoxMesh.FULL_UV,light);
            });
            collector.submitCustomGeometry(matrices,GlassLayers.DOOR,(pose,buffer)->{
                if(left!=null) FramedGlassDoor.glass(pose,buffer,left,plateAlongZ,BoxMesh.FULL_UV,light,GLASS_COLOR);
                if(right!=null) FramedGlassDoor.glass(pose,buffer,right,plateAlongZ,BoxMesh.FULL_UV,light,GLASS_COLOR);
            });
            return;
        }
        collector.submitCustomGeometry(matrices,RenderTypes.entityCutout(TEXTURE),(pose,buffer)->{
            if(left!=null)
                BoxMesh.cuboid(pose,buffer,left,light,0xFFFFFFFF,LeafUv.slabUv(
                        LeafUv.toUv(LeafUv.leafRange(progress,false),BoxMesh.FULL_UV),
                        LeafUv.toUv(LeafUv.edgeRange(false,LeafUv.EDGE_WIDTH),BoxMesh.FULL_UV),plateAlongZ));
            if(right!=null)
                BoxMesh.cuboid(pose,buffer,right,light,0xFFFFFFFF,LeafUv.slabUv(
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
     * （字体坐标的 y 是这一行的**顶边**）。方块实体提交时矩阵<b>已经平移到方块原点</b>，
     * 因此只能用方块内局部坐标。
     *
     * <p>副作用：只向提交收集器提交文字（由文字特性渲染器统一建批次），不改世界状态、不发包。
     *
     * @param state 渲染状态（提供每刻缓存的楼层号与运行状态、方块朝向）
     * @param matrices 渲染矩阵栈
     * @param collector 提交收集器：与原版告示牌一样直接用管线给的这个
     */
    private static void drawFloorDisplay(LandingDoorRenderState state,PoseStack matrices,SubmitNodeCollector collector) {
        int floor=state.cabinFloor;
        if(floor==0) return; // 本线路没有轿厢：什么都不画，避免显示成"0 层"（负数 = 地下 B1、B2…，要显示）
        Direction facing=state.facing;
        Font textRenderer=Minecraft.getInstance().font;
        // 运行方向用闪烁箭头表示（▲ 上行 / ▼ 下行 / 停靠留空），不再写"电梯上行"这类文字。
        ElevatorStatus status=state.cabinStatus;
        boolean moving=StatusArrow.moving(status);
        Component arrowText=Component.literal(StatusArrow.glyph(status));
        // 楼层号统一走 FloorIndicator.format：基准层 1、其上 2,3…、其下 B1,B2…，与选站面板、轿厢内面板同一口径。
        Component floorText=Component.literal(FloorIndicator.format(floor));
        // 箭头宽度始终按字符本身预留（停靠时不占位），这样闪烁的半个周期里楼层号不会左右跳动。
        int statusWidth=moving?StatusArrow.width(textRenderer):0, floorWidth=textRenderer.width(floorText);
        float start=-(statusWidth+STATUS_GAP+floorWidth)/2f; // 整组居中的起点
        // 位置：从方块中心沿朝向推出"半格（到方块面）− 屏幕凹陷 + 一点空隙"，因此红字正好贴在
        // 凹进去的显示屏面上（而不是浮在方块面之外）。四种朝向的偏移量同一个公式，因为
        // "沿朝向往外"在正向与反向朝向上刚好互为镜像。
        double offset=.5-SCREEN_INSET+TEXT_STANDOFF;
        matrices.pushPose();
        // 高度取显示屏中线：字体坐标的 y 是这一行的**顶边**，所以再下移半个字高，两行才会被屏幕装住。
        matrices.translate(.5+facing.getStepX()*offset,SCREEN_CENTRE_Y,.5+facing.getStepZ()*offset);
        // 26.3 起旋转用 rotateDegrees：mulPose 只剩矩阵 / Transformation 两个重载。
        matrices.rotateDegrees(Axis.YP,switch(facing) {case NORTH->180;case EAST->90;case WEST->-90;default->0;});
        // Y 轴取负：字体内部坐标是 Y 向下，与告示牌一致；不取负文字会上下颠倒。
        matrices.scale(FLOOR_SCALE,-FLOOR_SCALE,FLOOR_SCALE);
        float textY=-textRenderer.lineHeight/2f; // 行心 → 顶边（见上）
        // 最高亮度：井道或走廊再暗也能看清红字。POLYGON_OFFSET 与原版告示牌一致，避免与屏幕面片闪烁。
        // 只在"正在运行 + 闪烁的亮相"画箭头；灭相与停靠都不画，位置由上面的 statusWidth 固定住。
        // submitText 的形参顺序与旧的 drawInBatch 不同：光照在颜色之前（旧版把 packedLight 放在最后）。
        if(!arrowText.getString().isEmpty())
            collector.submitText(matrices,start,textY,arrowText.getVisualOrderText(),true,
                    Font.DisplayMode.POLYGON_OFFSET,LightCoordsUtil.FULL_BRIGHT,FLOOR_COLOR,0,0);
        collector.submitText(matrices,start+statusWidth+STATUS_GAP,textY,floorText.getVisualOrderText(),true,
                Font.DisplayMode.POLYGON_OFFSET,LightCoordsUtil.FULL_BRIGHT,FLOOR_COLOR,0,0);
        matrices.popPose();
    }

    /**
     * 整扇门始终渲染，不做"轮廓包围盒"可见性剔除。
     *
     * <p>原因：方块实体渲染器的默认剔除用的是<b>根方块自己那一格</b>的轮廓形状，而门扇会滑到左右两列
     * （进度过半后两扇都离开了中列），此时中列的轮廓已经为空，若沿用默认剔除，门扇会在还看得见的时候
     * 突然消失。返回 true 后本渲染器被登记进"全局渲染"集合（26.3 的
     * {@code ClientLevel.onBlockEntityAdded} → {@code getGloballyRenderedBlockEntities()}），
     * 由不依赖视锥的那一趟遍历处理；距离判定仍由默认的
     * {@link #shouldRender(LandingDoorBlockEntity, Vec3)}（{@link #getViewDistance()} = 64 格）负责，
     * 与 1.21.1 的 {@code shouldRenderOffScreen(entity)} 行为一致。
     *
     * @return 恒为 true
     */
    @Override
    public boolean shouldRenderOffScreen() { return true; }
}
