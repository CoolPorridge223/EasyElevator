package org.DJB.easyelevator.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.logic.FloorIndicator;

/** Replace this renderer/model only: simulation and animation timing live in AbstractCabinEntity.
 * 轿厢渲染器：只负责画，不参与模拟，三种轿厢共用。
 * 设计意图：轿厢没有实体模型（无 EntityModel/纹理 UV 表），这里直接以世界坐标系画出 3x3x3 的空心白模：
 * 底板、顶板、两块侧壁、后壁各一个长方体，正面留门洞并由两扇滑门（按 doorProgress 位移）遮挡。
 * 所有坐标以轿厢中心为原点、单位为格；正面朝向由 FACING（= 轨道/轿厢门朝向）决定，
 * 数值来自 ElevatorParameters 的 CABIN_FRONT_Z / CABIN_DOOR_BACK_Z，使轿厢正面整体内缩，
 * 与楼层门框留出缝隙，避免门与门框共面闪烁。模拟、门动画时序都在 AbstractCabinEntity 与 logic/ElevatorController 中。
 *
 * <p>两种外观（几何与碰撞完全一致，只有材质与哪几块面透明不同）：
 * <ul>
 *   <li>普通 / 高速：整舱不透明白模。高速型号刻意与普通型号外观完全相同——速度不是外观差异。</li>
 *   <li>观光（{@link AbstractCabinEntity#glassWalls()} 为 true）：地板、顶板、四根角柱（支撑边）与操作面板
 *       保持不透明，左右侧墙 / 后墙 / 两扇门换成半透明玻璃（每处一张零厚度单面，正反都可见），
 *       因此厢内外互相可见。玻璃走专用的 {@link #GLASS_LAYER}：只写颜色不写深度，
 *       否则会把之后才绘制的楼层门整片剔掉（见该常量注释）。</li>
 * </ul>
 *
 * <p><b>绘制顺序的硬性约束（改动本类前必读）</b>：实体渲染拿到的 {@link VertexConsumerProvider} 是
 * 原版的 {@code VertexConsumerProvider.Immediate}，它<b>每层只有一个正在构建的缓冲</b>；一旦通过
 * {@code getBuffer} 切换到另一层，上一层就会被结束（end）并提交，之前拿到的 {@link VertexConsumer}
 * 立刻失效。此后继续往那个旧引用写顶点会直接抛
 * {@code IllegalStateException: Not building!} —— 客户端崩溃报告里就是这一条。
 * 因此本类固定按"层"分组绘制，且画完一组就不再回头：
 * <ol>
 *   <li>不透明层 {@code getEntityCutoutNoCull}：舱体外壳（观光型号只画地板 / 顶板 / 四根角柱）＋ 舱内操作面板；</li>
 *   <li>文字层（由 {@link TextRenderer#draw} 内部取用）：面板上的楼层号与运行状态；</li>
 *   <li>半透明层 {@code getEntityTranslucent}（仅观光型号）：玻璃墙与玻璃门，必须最后画，画完不再写任何顶点。</li>
 * </ol>
 * 楼层门渲染器 {@link LandingDoorRenderer} 遵守同一条约定（先画文字、再画门扇层）。
 *
 * @param <T> 轿厢实体类型；三种型号共用一个渲染器实例类型，故取共同父类 {@link AbstractCabinEntity}
 */
public class CabinRenderer<T extends AbstractCabinEntity> extends EntityRenderer<T> {
    private static final Identifier TEXTURE=Easyelevator.id("textures/entity/cabin.png");
    /**
     * 观光玻璃专用渲染层：逐项照抄原版 {@code RenderLayer.getEntityTranslucent(TEXTURE)}
     * （同一个着色器、同一张贴图、同样的半透明混合与禁止剔除），唯一区别是把写掩码从默认的
     * "颜色 + 深度"改成 {@link RenderPhase#COLOR_MASK}（<b>只写颜色、不写深度</b>）。
     *
     * <p>为什么必须自己建一层：原版 {@code entity_translucent} 会写深度缓冲，而世界渲染的固定顺序是
     * <b>实体 → 方块实体</b>（{@code WorldRenderer.render}：先 "entities" 再 "blockentities"）。
     * 观光轿厢的玻璃是离观察者更近的那一面，它一旦写入深度，之后才绘制的楼层门（由方块实体渲染器绘制）
     * 就会被深度测试整体剔除，现象就是"坐在观光轿厢里看不见每层的电梯门"。
     * 只写颜色后，玻璃不再遮挡任何后画的东西，而它自己仍受深度测试约束（会被舱体不透明件正确遮挡）。
     * 这与原版对方块半透明层（玻璃、水）的处理方式一致：半透明面只写颜色。
     *
     * <p>层名必须唯一：{@code RenderLayer} 用名字做相等性判定与缓冲分组，重名会与原版层共用缓冲。
     */
    private static final RenderLayer GLASS_LAYER=RenderLayer.of("easyelevator_cabin_glass",
            VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,VertexFormat.DrawMode.QUADS,1536,true,true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.ENTITY_TRANSLUCENT_PROGRAM)
                    .texture(new RenderPhase.Texture(TEXTURE,false,false))
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .lightmap(RenderPhase.ENABLE_LIGHTMAP)
                    .overlay(RenderPhase.ENABLE_OVERLAY_COLOR)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .build(true));
    /** 轿厢内面板的字号（格/像素）：楼层行大一号、运行状态行小一点；两行各自水平居中。 */
    private static final float FLOOR_SCALE=.018f, STATUS_SCALE=.011f;
    /** 两行的行锚点（像素，相对面板中心）：负 Y 缩放后局部 +Y 是世界向下，因此楼层行取负（在上）、状态行取正（在下）。 */
    private static final float FLOOR_LINE_Y=-10f, STATUS_LINE_Y=5f;
    /** 面板上文字的颜色（红色）；与门框顶部、选站面板显示同一个楼层号与状态。 */
    private static final int FLOOR_COLOR=0xFFFF4040;
    /**
     * 观光轿厢墙面的玻璃色（ARGB）：淡蓝白 + 25% 不透明度。
     *
     * <p>为什么是"每面一次"的透明度：玻璃用<b>零厚度单面</b>绘制（见 {@link BoxMesh#planeX}），
     * 每层玻璃在视线里只叠一次 25%；隔着轿厢看穿两面玻璃约 44%，透过玻璃看外面的景物仍然清楚。
     * 若改用 0.12 格厚的薄板（正反两面都画），同一面墙就会叠两次、透明度翻倍而发灰。
     * 贴图本身是全白，透明度全部来自顶点色，因此只改这一个常量即可调通透度。
     */
    private static final int GLASS_COLOR=0x40BFE4F5;
    /** 观光轿厢门扇的玻璃色：比墙面略深一点，透明状态下仍能看出两扇门与中缝。 */
    private static final int GLASS_DOOR_COLOR=0x59A8D2EC;

    /** 构造渲染器。副作用：仅保存 EntityRenderer 上下文（光源、模型加载器等）。 */
    public CabinRenderer(EntityRendererFactory.Context context) { super(context); }
    /** 返回轿厢整张白模使用的纹理；玻璃与不透明面共用同一张贴图，通透度来自顶点色的 alpha。 */
    @Override public Identifier getTexture(T entity) { return TEXTURE; }
    /** 绘制轿厢。只读服务端同步的状态（FACING、DOOR 的插值门进度），不修改任何游戏状态。
     * @param cabin 目标轿厢实体（普通 / 高速 / 观光）
     * @param yaw 实体朝向角（未使用，朝向由 FACING 决定）
     * @param delta 渲染插值系数（0..1）
     * @param matrices 渲染矩阵栈
     * @param buffers 顶点缓冲提供者
     * @param light 打包后的光照值
     */
    @Override public void render(T cabin,float yaw,float delta,MatrixStack matrices,VertexConsumerProvider buffers,int light) {
        matrices.push();
        // 只叠加"视觉 Y - 原版插值 Y"的差值（单位：格）：实体自身坐标仍由原版渲染管线提供，
        // 因此碰撞箱、判定与服务端位置完全不受影响，这里只做外观平滑。
        matrices.translate(0, CabinMotion.renderY(cabin,delta) - net.minecraft.util.math.MathHelper.lerp(delta,cabin.lastRenderY,cabin.getY()), 0);
        // 把 +Z 面转到 FACING 指定的轿厢门方向（NORTH=+Z，EAST=+X，WEST=-X，SOUTH=+Z 默认 0 度）。
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(switch(cabin.facing()) {case NORTH->180;case EAST->90;case WEST->-90;default->0;}));
        // 正面 Z（格）与门板背面 Z（格），均取自 ElevatorParameters，保证与楼层门框的间隙一致。
        float front=(float)ElevatorParameters.CABIN_FRONT_Z;
        float doorBack=(float)ElevatorParameters.CABIN_DOOR_BACK_Z;
        float open=cabin.doorProgress(delta);
        // 第 1 组（不透明层）：外壳 + 舱内操作面板。面板必须和外壳同在这一次取缓冲里写完，
        // 否则切到文字层之后再回头写它就会触发 Not building!（见类注释）
        VertexConsumer out=buffers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));
        if(cabin.glassWalls()) drawObservationShell(matrices,out,front,doorBack,light);
        else drawStandard(matrices,out,front,doorBack,open,light);
        // 轿厢内壁的空白操作面板（占位标记，无交互）：贴在右侧壁内侧 0.05 格厚、1.2..1.8 格高处。
        // 观光型号同样保留它——玻璃墙是"看得见"，不是"没有墙"，操作面板仍贴着右侧玻璃内侧。
        BoxMesh.cuboid(matrices,out,1.25f,1.2f,.3f,1.30f,1.8f,.8f,light,0xFFBBBBBB);
        // 第 2 组（文字层）：面板上的楼层号（红色），与选站面板、楼层门框顶部显示的是同一个由服务端同步的楼层号。
        drawFloorDisplay(cabin,matrices,buffers,light);
        // 第 3 组（半透明层，仅观光型号）：玻璃墙与玻璃门。必须是最后一组，返回前不再写任何顶点。
        if(cabin.glassWalls()) drawObservationGlass(matrices,buffers,front,doorBack,open,light);
        matrices.pop(); super.render(cabin,yaw,delta,matrices,buffers,light);
    }

    /**
     * 画普通 / 高速轿厢：整舱不透明白模，与 1.3.0 的外观逐面一致（高速型号刻意不做任何区别）。
     *
     * <p>只使用不透明层的一个 {@link VertexConsumer}，调用方负责在切换到别的层之前画完。
     *
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param out 不透明顶点缓冲
     * @param front 轿厢正面前缘 Z（格）
     * @param doorBack 门扇背面 Z（格）
     * @param open 门进度 0..1
     * @param light 打包后的光照值
     */
    private static void drawStandard(MatrixStack matrices,VertexConsumer out,float front,float doorBack,float open,int light) {
        BoxMesh.cuboid(matrices,out,-1.5f,0,-1.5f,1.5f,.2f,front,light,0xFFE6E6E6); // 底板：Y=0..0.2 格，略暗以区分地面
        BoxMesh.cuboid(matrices,out,-1.5f,2.8f,-1.5f,1.5f,3,front,light,0xFFF5F5F5); // 顶板：Y=2.8..3.0 格，最高亮度
        BoxMesh.cuboid(matrices,out,-1.5f,.2f,-1.5f,-1.3f,2.8f,front,light,0xFFFFFFFF); // 左侧壁：厚 0.2 格
        BoxMesh.cuboid(matrices,out,1.3f,.2f,-1.5f,1.5f,2.8f,front,light,0xFFFFFFFF); // 右侧壁：厚 0.2 格
        BoxMesh.cuboid(matrices,out,-1.3f,.2f,-1.5f,1.3f,2.8f,-1.3f,light,0xFFFFFFFF); // 后壁：Z=-1.5..-1.3 格，正面留空形成门洞
        // Two sliding leaves retract into the side walls. 0=closed, 1=open.
        // 两扇滑门，0 = 完全关闭、1 = 完全开启：门宽按 1.3 格 * open 内缩，收到侧壁里（不做缩放，避免纹理拉伸）。
        if(open<.999f) {
            // 门板 Z 范围 doorBack..front（格），即夹在门洞内侧与轿厢正面之间，厚 0.2 格。
            BoxMesh.cuboid(matrices,out,-1.3f,.2f,doorBack,-1.3f*open,2.8f,front,light,0xFFCCCCCC);
            BoxMesh.cuboid(matrices,out,1.3f*open,.2f,doorBack,1.3f,2.8f,front,light,0xFFCCCCCC);
        }
    }

    /**
     * 画观光轿厢的<b>不透明结构件</b>：地板、顶板与四个支撑边（角柱）。
     *
     * <p>为什么角柱是 0.2×0.2、并且前两根与门扇同厚：四根柱子就是"轿厢的四个支撑边"，
     * 观光车保留它们既是结构轮廓，也是玻璃板的收边；前两根跨 Z=门背面..正面，
     * 这样玻璃门开到头时正好藏进柱子后面。
     *
     * <p>本方法只写不透明层；玻璃部分在 {@link #drawObservationGlass}，两组之间隔着文字层，
     * 因此绝不能合并成一个方法（见类注释的层约束）。
     *
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param out 不透明顶点缓冲
     * @param front 轿厢正面前缘 Z（格）
     * @param doorBack 门扇背面 Z（格）
     * @param light 打包后的光照值
     */
    private static void drawObservationShell(MatrixStack matrices,VertexConsumer out,float front,float doorBack,int light) {
        BoxMesh.cuboid(matrices,out,-1.5f,0,-1.5f,1.5f,.2f,front,light,0xFFE6E6E6); // 底板（观光舱同样保留，乘客站在上面）
        BoxMesh.cuboid(matrices,out,-1.5f,2.8f,-1.5f,1.5f,3,front,light,0xFFF5F5F5); // 顶板
        BoxMesh.cuboid(matrices,out,-1.5f,.2f,-1.5f,-1.3f,2.8f,-1.3f,light,0xFFFFFFFF); // 后左角柱
        BoxMesh.cuboid(matrices,out,1.3f,.2f,-1.5f,1.5f,2.8f,-1.3f,light,0xFFFFFFFF); // 后右角柱
        // 前两根角柱与门扇同厚（Z 从门背面到轿厢正面）
        BoxMesh.cuboid(matrices,out,-1.5f,.2f,doorBack,-1.3f,2.8f,front,light,0xFFFFFFFF); // 前左角柱
        BoxMesh.cuboid(matrices,out,1.3f,.2f,doorBack,1.5f,2.8f,front,light,0xFFFFFFFF); // 前右角柱
    }

    /**
     * 画观光轿厢的<b>半透明玻璃</b>：左右侧墙、后墙与两扇门，每处都是一张零厚度单面玻璃。
     *
     * <p>为什么用单面而不是 0.12 格厚的薄板：薄板的正反两面都会被绘制（半透明层不剔除背面），
     * 同一面墙的透明度会叠两次、整舱发灰；单面每层只叠一次，观感与真实玻璃窗一致。
     * 单面必须画在<b>禁止剔除</b>的层上（{@link #GLASS_LAYER}），否则从轿厢内侧看会整片消失。
     *
     * <p>为什么每个尺寸都留 0.01 格缝、而不是贴着角柱/地板/顶板：
     * 两个面恰好共面时，浮点误差会让它们的深度值差一点点，镜头一动就来回抢先，
     * 看起来就是"玻璃门与不透明框架衔接处轻微闪烁"。玻璃面因此内缩在结构件之间：
     * 侧墙取墙心 X=∓1.4 且 Z=-1.29..门背面-0.01、后墙取 Z=-1.4 且 X=∓1.29，
     * 上下都取 Y=0.21..2.79，四个方向都与不透明件脱开 0.01 格以上（视觉上仍是连续的整面玻璃）。
     *
     * <p>调用时机：必须在不透明层与文字层都画完之后（本方法是整个 render 的最后一步）。
     * 取半透明层会结束上一层缓冲，之后再往旧引用写顶点会抛 {@code Not building!}。
     *
     * <p>碰撞不受影响：外壳仍由 {@link AbstractCabinEntity#collisionBoxes()} 按原尺寸（0.2 格厚实心墙）生成，
     * 因此玻璃墙照样挡住乘客，井道尺寸、门口防夹与门联锁与普通轿厢逐位相同。
     *
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param buffers 顶点缓冲提供者，用于取玻璃层
     * @param front 轿厢正面前缘 Z（格）
     * @param doorBack 门扇背面 Z（格）
     * @param open 门进度 0..1
     * @param light 打包后的光照值
     */
    private static void drawObservationGlass(MatrixStack matrices,VertexConsumerProvider buffers,float front,float doorBack,float open,int light) {
        VertexConsumer glass=buffers.getBuffer(GLASS_LAYER);
        // 四面玻璃与不透明件一律留 0.01 格缝（见方法注释），因此这里的边界都写成"结构面 ∓0.01"。
        float y0=.21f, y1=2.79f;                  // 地板面 0.2、顶板内侧 2.8
        float z0=-1.29f, z1=doorBack-.01f;        // 后角柱内缘 -1.3、前角柱背面 1.1
        float paneX=1.4f, paneZ=-1.4f;            // 侧壁厚 0.2 → 玻璃贴在墙心 X=±1.4；后壁同理 Z=-1.4
        BoxMesh.planeX(matrices,glass,-paneX,y0,z0,y1,z1,light,GLASS_COLOR); // 左侧玻璃（单面，正反都可见）
        BoxMesh.planeX(matrices,glass,paneX,y0,z0,y1,z1,light,GLASS_COLOR);  // 右侧玻璃
        BoxMesh.planeZ(matrices,glass,paneZ,-1.29f,y0,1.29f,y1,light,GLASS_COLOR); // 背面玻璃（X 留 0.01 缝）
        // 两扇玻璃滑门：与普通车门同样的内缩规律（0 = 全关、1 = 收进两侧），只是材质换成更深的玻璃色。
        // 内缘用 max/min 夹到 ∓1.29，保证门开到尽头时矩形不会翻转（宽度归零而不是变成负值）。
        if(open<.999f) {
            float leftInner=Math.max(-1.3f*open,-1.29f), rightInner=Math.min(1.3f*open,1.29f);
            float paneDoorZ=front-.01f; // 贴在轿厢正面内侧 0.01 格：与楼层门后缘（1.3125）留 0.0225 格
            BoxMesh.planeZ(matrices,glass,paneDoorZ,-1.29f,y0,leftInner,y1,light,GLASS_DOOR_COLOR);  // 左扇
            BoxMesh.planeZ(matrices,glass,paneDoorZ,rightInner,y0,1.29f,y1,light,GLASS_DOOR_COLOR);  // 右扇
        }
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
     * 本方法会切到文字层，因此调用方之后不能再往之前取到的不透明缓冲写顶点（见类注释）。
     *
     * @param cabin 轿厢（提供同步过来的楼层号与运行状态）
     * @param matrices 渲染矩阵栈（已包含轿厢朝向与位置）
     * @param buffers 顶点缓冲提供者：直接用管线给的这一个，由管线统一 flush
     * @param light 打包后的光照值
     */
    private static void drawFloorDisplay(AbstractCabinEntity cabin,MatrixStack matrices,VertexConsumerProvider buffers,int light) {
        int floor=cabin.floorNumber();
        if(floor==0) return; // 还没经过任何站点（或线路无效）：不显示，避免出现"0 层"（负数 = 地下 B1、B2…，要显示）
        TextRenderer textRenderer=MinecraftClient.getInstance().textRenderer;
        // 负 Y 缩放之后，局部 +Y 对应世界里的"向下"，所以取负偏移的那行显示在上面：
        // 第一行楼层号、第二行运行状态。楼层号统一走 FloorIndicator.format：基准层 1、其上 2,3…、其下 B1,B2…
        drawPanelLine(textRenderer,matrices,buffers,Text.literal(FloorIndicator.format(floor)),FLOOR_LINE_Y,FLOOR_SCALE);
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
     * POLYGON_OFFSET 给文字一点深度偏移，贴在面板上不会与面板面片闪烁。两行都取同一种文字层，
     * 因此两次调用之间不会发生层切换（否则第二次写的就是已失效的缓冲）。
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
