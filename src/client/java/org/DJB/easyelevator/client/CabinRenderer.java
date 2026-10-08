package org.DJB.easyelevator.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Frustum;
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
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.entity.PowerfulCabinEntity;
import org.DJB.easyelevator.logic.*;

/** Replace this renderer/model only: simulation and animation timing live in AbstractCabinEntity.
 * 轿厢渲染器：只负责画，不参与模拟，四种轿厢共用。
 *
 * <p>轿厢没有实体模型（无 EntityModel/纹理 UV 表），这里直接以轿厢局部坐标画出 3x3x3 的<b>空心车体</b>：
 * 底板、顶板、两块侧壁、后壁各一个长方体，正面就是<b>两扇对开滑门</b>（{@link SlidingDoor}：门洞 = 整个内净宽，两扇外缘固定在侧壁内侧、内缘向两侧移开，
 * 全开时门洞全通），与楼层门同一套做法。
 * 在此之上再叠一层<b>内饰</b>：踢脚线、不锈钢扶手、顶棚灯槽与顶灯、门口金属门槛与门楣轨道，
 * 以及右侧壁上的操纵面板（深色边框 / 亮色面板 / 下沉显示窗 / 三颗按钮）。
 * 所有坐标以轿厢中心为原点、单位为格；正面朝向由 FACING（= 轨道/轿厢门朝向）决定，
 * 数值来自 ElevatorParameters 的 CABIN_FRONT_Z / CABIN_DOOR_BACK_Z，使轿厢正面整体内缩，
 * 与楼层门框留出缝隙，避免门与门框共面闪烁。模拟、门动画时序都在 AbstractCabinEntity 与 logic/ElevatorController 中。
 *
 * <h2>材质图集</h2>
 * 不透明件使用 {@link #TEXTURE}：一张 4x4 共 16 格的<b>材质图集</b>，每格占 1/4 贴图。
 * {@link Mat} 的枚举顺序就是图集格号，{@link #MATERIAL_UV} 把它换算成 UV 矩形，
 * 每块几何通过 {@link BoxMesh#cuboid} 的 UV 重载只取自己那一格，于是"亮钢舱壁 / 深色踢脚 /
 * 拉丝地板 / 灯罩"都来自同一张贴图；玻璃由 GlassLayers 单独绑定原版无色玻璃贴图。
 * 顶点色一律留白（{@code 0xFFFFFFFF}）：颜色与纹理细节全部由贴图给出，改配色只需换 PNG。
 *
 * <h2>几何数据表</h2>
 * 不随门进度变化的内饰件集中写在 {@link #STANDARD_PARTS} / {@link #POWERFUL_PARTS} /
 * {@link #OBSERVATION_PARTS} 三张表里
 * （每行 = {x,y,z,X,Y,Z,材质格号,自发光}，单位格，轿厢局部坐标）。
 * 表里的坐标必须满足三条不变量；改完表跑 {@code python tools/generate_art.py}，
 * 它会解析这三张表并逐条校验（{@code check_cabin_parts}），不合格直接报错：
 * <ol>
 *   <li><b>不越界</b>：内饰件都待在净空 X ±1.3、Y 0.2..2.8、Z -1.3..门背面之内
 *       （允许向外壳里嵌 0.002 格，见下一条），不会从外壳穿出去；</li>
 *   <li><b>不留共面</b>：与外壳或彼此相接时，相接面要错开 0.002 格以上（一方嵌进另一方体内），
 *       不允许两个面的平面方程完全重合——即使采用背面剔除，同向共面片仍会互相抢深度；</li>
 *   <li><b>材质格号</b>合法（0..15），自发光只能是 0 或 1。</li>
 * </ol>
 * 自发光为 1 的格子（顶灯灯罩）仅朝下使用 15 级方块光，其余面保持环境光。
 *
 * <h2>厢内照明</h2>
 * {@link CabinLighting} 按面中心、法线与灯位计算距离衰减：只给朝向灯具的舱内表面补光，
 * 外壳、玻璃和导靴保持环境光。舱内补光上限 13 级，灯罩仅朝下的面使用 15 级方块光。
 * 这只改变模型的光照坐标，保留天光，不修改世界光照、存档或联机逻辑。
 *
 * <p>三种外观（几何外壳与碰撞完全一致，只有材质、内饰件与哪几块面透明不同）：
 * <ul>
 *   <li>普通 / 高速：整舱不透明。高速型号刻意与普通型号外观完全相同——速度不是外观差异。</li>
 *   <li>强力（{@link AbstractCabinEntity#heavyDuty()} 为 true）：外壳与门与普通型号<b>逐件相同</b>，
 *       只在 {@link #STANDARD_PARTS} 之上再叠一层"重载"内饰（{@link #POWERFUL_PARTS} =
 *       普通内饰 + 强化件 + 同一块操纵面板）：上下<b>两道</b>不锈钢扶手围成的双扶手环、后壁两根
 *       立柱把两道扶手连成整体、顶棚<b>两块</b>顶灯（门口再补一盏）、后壁的载重铭牌
 *       （深色边框 + 亮面板，上面用红字写出 {@link AbstractCabinEntity#passengerNumLimit()}），
 *       以及门槛内侧的一条防滑钢踏板。载客量是"能不能装下更多人"，因此改的是<b>装潢与灯具</b>，
 *       不是外壳尺寸：井道预留、碰撞、门时序与普通型号逐位相同。</li>
 *   <li>观光（{@link AbstractCabinEntity#glassWalls()} 为 true）：地板、顶板、四根角柱（支撑边）
 *       保持不透明，左右侧墙 / 后墙换成无色透明玻璃（每处一张零厚度双面，正反都可见）；门换成
 *       <b>铁框玻璃门</b>（周围钢框、中间玻璃，见 {@link FramedGlassDoor}），于是从厢内朝外看仍然通透；
 *       玻璃上另加不透明的上下压条、一道横向中梃与每面两道竖向分格，把整面玻璃分成 2x3 格窗，
 *       因此观光舱看上去是"分段幕墙"而不是一整片蓝雾，厢内外仍然互相可见。
 *       玻璃走 {@link GlassLayers#CABIN} 的原版 cutout 管线，透明像素丢弃，
 *       正反两面各有正确法线并剔除背面，不会整片遮挡之后绘制的楼层门。</li>
 * </ul>
 *
 * <p><b>绘制顺序的硬性约束（改动本类前必读）</b>：实体渲染拿到的 {@link VertexConsumerProvider} 是
 * 原版的 {@code VertexConsumerProvider.Immediate}，它<b>每层只有一个正在构建的缓冲</b>；一旦通过
 * {@code getBuffer} 切换到另一层，上一层就会被结束（end）并提交，之前拿到的 {@link VertexConsumer}
 * 立刻失效。此后继续往那个旧引用写顶点会直接抛
 * {@code IllegalStateException: Not building!} —— 客户端崩溃报告里就是这一条。
 * 因此本类固定按"层"分组绘制，且画完一组就不再回头：
 * <ol>
 *   <li>不透明层 {@code getEntityCutout}：舱体外壳、全部内饰、门口门槛与门楣、两扇滑门、
 *       背面的抱轨导靴（观光型号只画地板 / 顶板 / 四根角柱与内饰、压条、分格）；</li>
 *   <li>文字层（由 {@link TextRenderer#draw} 内部取用）：面板上的楼层号与运行状态，
 *       以及强力型号后壁载重铭牌上的限载人数；</li>
 *   <li>玻璃层 {@code getEntityCutout}（仅观光型号）：玻璃墙与玻璃门，必须最后画，画完不再写任何顶点。</li>
 * </ol>
 * 楼层门渲染器 {@link LandingDoorRenderer} 遵守同一条约定（先画文字、再画门扇层）。
 *
 * @param <T> 轿厢实体类型；四种型号共用一个渲染器实例类型，故取共同父类 {@link AbstractCabinEntity}
 */
public class CabinRenderer<T extends AbstractCabinEntity> extends EntityRenderer<T> {
    private static final Identifier TEXTURE=Easyelevator.id("textures/entity/cabin.png");
    /**
     * 材质图集的分格。<b>枚举顺序 = 图集格号</b>：四行依次是"舱壁系 / 顶棚系 / 面板系 / 饰面系"，
     * 与 {@code tools/generate_art.py} 里 {@code ATLAS_TILES} 的顺序一一对应——那边换了顺序，
     * 这边必须同步，生成脚本的 {@code check_cabin_parts} 会直接报错。
     *
     * <p>为什么用图集：{@link BoxMesh} 每画一块几何都要指定 UV 矩形；如果每种材质一张贴图，
     * 就只能在渲染中途反复切缓冲（切一次上一层就被提交、旧引用作废），而实体渲染每帧只有一次机会。
     * 一张 4x4 图集 + UV 分格让整舱在同一个缓冲里一次画完。
     */
    private enum Mat {
        WALL,TRIM,DARK,FLOOR,       // 亮钢舱壁、中性饰条、深色阳极氧化、拉丝地板
        CEIL,LAMP,RAIL,SILL,        // 顶板、灯罩（自发光）、不锈钢扶手、防滑门槛
        PANEL,BEZEL,BUTTON,GLASS,   // 操纵面板、面板边框、按钮、旧玻璃格（世界玻璃已改用原版贴图）
        ACCENT,DOOR,SPARE2,SPARE3;  // 暖色饰板、门扇（带会滑动的折边）、两个备用格
    }

    /** 每格贴图的 UV 矩形，下标 = {@link Mat} 的序号；{u0,v0,u1,v1}，取值 0..1。
     *  4x4 分格、每格 0.25：格子越靠右 u0 越大，越靠下行 v0 越大（原版贴图 v=0 在图像顶部）。 */
    private static final float[][] MATERIAL_UV=new float[Mat.values().length][];
    static {
        for(Mat mat:Mat.values()) {
            int col=mat.ordinal()%4,row=mat.ordinal()/4;
            MATERIAL_UV[mat.ordinal()]=new float[]{col*.25f,row*.25f,col*.25f+.25f,row*.25f+.25f};
        }
    }

    /** 舱壁内缘、地板面与顶板内缘：内饰件一律限制在这个空腔里。 */
    private static final float INNER=1.3f, FLOOR_TOP=.2f, CEIL_INNER=2.8f;
    /**
     * 门扇的<b>渲染</b>下沿（格）：比地板面 {@link #FLOOR_TOP} 高 0.5 毫米。
     *
     * <p>为什么不能直接用 {@code FLOOR_TOP}：门扇下沿与地板面相同时，两者在门口那一段（Z 1.1..1.29）
     * 会是<b>共面</b>的一对矩形，共面片可能互相抢深度——实机看到的就是"轿厢门底部模型一闪一闪"。抬高半毫米即彻底分开，
     * 肉眼不可见（0.0005 格 ≈ 0.5 毫米），却不会再闪烁。
     *
     * <p>碰撞仍用 {@code FLOOR_TOP}（见 {@code collisionBoxes()}）：这半毫米只影响外观，不影响走路与防夹。
     */
    private static final float DOOR_RENDER_BOTTOM=FLOOR_TOP+.0005f;
    /** 与外壳相接的内饰件嵌进外壳的深度（格）：绝不与外壳面共面，否则两面互相抢深度。 */
    private static final float BITE=.002f;
    /**
     * 普通 / 高速轿厢的内饰件：{x,y,z,X,Y,Z,材质格号,自发光}，单位格，轿厢局部坐标。
     *
     * <p>Z 一律不超过 1.09：门口那一段（门槛、门楣）随 {@link ElevatorParameters#CABIN_FRONT_Z}
     * 变化，所以画在 {@link #drawDoorway} 里而不在这张表里。
     */
    private static final float[][] STANDARD_PARTS={
        // 踢脚线：三面围一圈，转角处两块互相嵌进去 0.01 格（端面互相垂直，因此不会共面）
        {-1.302f,.198f,-1.25f,-1.24f,.34f,1.02f,2,0},
        {1.24f,.198f,-1.25f,1.302f,.34f,1.02f,2,0},
        {-1.25f,.198f,-1.302f,1.25f,.34f,-1.24f,2,0},
        // 不锈钢扶手：0.95 格高、出墙 0.08 格，同样是"三面围一圈"，转角处互嵌
        {-1.302f,.95f,-1.25f,-1.22f,1.02f,1.02f,6,0},
        {1.22f,.95f,-1.25f,1.302f,1.02f,1.02f,6,0},
        {-1.25f,.95f,-1.302f,1.25f,1.02f,-1.22f,6,0},
        // 顶棚灯槽：贴顶一圈 0.08 格宽的凹边，把顶板与舱壁的交线收干净
        {-1.302f,2.72f,-1.25f,-1.22f,2.802f,1.03f,1,0},
        {1.22f,2.72f,-1.25f,1.302f,2.802f,1.03f,1,0},
        {-1.25f,2.72f,-1.302f,1.25f,2.802f,-1.22f,1,0},
        {-1.25f,2.72f,1.02f,1.25f,2.802f,1.09f,1,0},
        // 顶灯：灯槽框 + 中间灯罩；灯罩自发光，井道再暗也亮着
        {-.92f,2.786f,-1.02f,.92f,2.812f,.52f,1,0},
        {-.85f,2.78f,-.95f,.85f,2.804f,.45f,5,1},
        // 操纵面板：贴右侧壁的深色边框 + 亮色面板 + 下沉显示窗 + 一排三颗呼梯键。
        // 这六行与 OBSERVATION_PARTS 的最后六行必须逐字相同（观光舱同样要有面板），
        // generate_art.py 的 check_cabin_parts 会断言。
        {1.26f,1.10f,.20f,1.302f,1.90f,.90f,9,0},
        {1.25f,1.14f,.24f,1.28f,1.86f,.86f,8,0},
        {1.246f,1.31f,.30f,1.252f,1.73f,.80f,2,0},
        {1.221f,1.21f,.32f,1.251f,1.29f,.40f,10,0},
        {1.221f,1.21f,.46f,1.251f,1.29f,.54f,10,0},
        {1.221f,1.21f,.60f,1.251f,1.29f,.68f,10,0},
    };

    /**
     * 强力轿厢的内饰件：{@link #STANDARD_PARTS} 的<b>全部</b>普通内饰（踢脚线、扶手、灯槽、顶灯）
     * + 一组<b>重载件</b> + 与普通轿厢逐字相同的操纵面板。
     *
     * <p>为什么写成"整表复制 + 追加"而不是让代码把两张表拼起来：{@code tools/generate_art.py} 直接解析
     * Java 源里的表，正是靠这一点断言"强力舱确实保留了普通舱的每一件内饰"
     * （{@code POWERFUL_PARTS[:12] == STANDARD_PARTS[:12]} 且两者最后 6 行都是同一块面板）。
     * 于是"保留原来基本样貌、另外加料"这条需求变成了构建期就守得住的约束，而不是一句口头约定。
     *
     * <p>为什么要"加料"：强力型号的卖点是<b>载客量</b>（{@link AbstractCabinEntity#passengerNumLimit()}
     * 块 20 人，普通型号不限），因此多出来的一律是"人多时用得着"的东西——
     * <ol>
     *   <li><b>第二道扶手</b>（Y=1.55..1.62）：站着的乘客也有地方扶，是"大轿厢"最直观的特征；
     *       右侧壁那一段在操纵面板前<b>收头</b>（Z 止于 0.18，面板从 0.20 开始），
     *       与真实电梯"扶手让开操纵盘"一致；</li>
     *   <li><b>两根立柱</b>把上下两道扶手连成整体（后壁 X=±0.9 处），看上去是承力件而不只是两根管子；</li>
     *   <li><b>第二块顶灯</b>（门口一侧 Z=0.56..0.98）：双灯照明，满载时舱内不暗；</li>
     *   <li><b>载重铭牌</b>（后壁中央，深色边框 + 亮色板）：红字的限载人数由
     *       {@link #drawCapacityPlate} 画在这块板上（文字层），因此"能拉多少人"是<b>写出来</b>的；</li>
     *   <li><b>防滑钢踏板</b>（门槛内侧一条 0.01 格厚的 {@link Mat#SILL} 板）：高客流车门口的标配。</li>
     * </ol>
     *
     * <p>刻意的<b>不变</b>：外壳、地板、门扇、门槛与门楣全部沿用普通型号的件
     * （外壳画在 {@link #drawStandardShell}，门与门口画在 {@link #drawDoors} / {@link #drawDoorway}），
     * 因此井道预留、碰撞盒、门口防夹、门时序与普通型号逐位相同——换乘强力型号不需要改任何建筑。
     */
    private static final float[][] POWERFUL_PARTS={
        // ↓↓↓ 以下 12 行与 STANDARD_PARTS 的前 12 行逐字相同（普通内饰原样保留）↓↓↓
        // 踢脚线：三面围一圈，转角处两块互相嵌进去 0.01 格（端面互相垂直，因此不会共面）
        {-1.302f,.198f,-1.25f,-1.24f,.34f,1.02f,2,0},
        {1.24f,.198f,-1.25f,1.302f,.34f,1.02f,2,0},
        {-1.25f,.198f,-1.302f,1.25f,.34f,-1.24f,2,0},
        // 不锈钢扶手：0.95 格高、出墙 0.08 格，同样是"三面围一圈"，转角处互嵌
        {-1.302f,.95f,-1.25f,-1.22f,1.02f,1.02f,6,0},
        {1.22f,.95f,-1.25f,1.302f,1.02f,1.02f,6,0},
        {-1.25f,.95f,-1.302f,1.25f,1.02f,-1.22f,6,0},
        // 顶棚灯槽：贴顶一圈 0.08 格宽的凹边，把顶板与舱壁的交线收干净
        {-1.302f,2.72f,-1.25f,-1.22f,2.802f,1.03f,1,0},
        {1.22f,2.72f,-1.25f,1.302f,2.802f,1.03f,1,0},
        {-1.25f,2.72f,-1.302f,1.25f,2.802f,-1.22f,1,0},
        {-1.25f,2.72f,1.02f,1.25f,2.802f,1.09f,1,0},
        // 顶灯（后侧一块）：灯槽框 + 中间灯罩；灯罩自发光，井道再暗也亮着
        {-.92f,2.786f,-1.02f,.92f,2.812f,.52f,1,0},
        {-.85f,2.78f,-.95f,.85f,2.804f,.45f,5,1},
        // ↓↓↓ 以下为强力型号独有的"重载件"（普通型号没有）↓↓↓
        // 第二道扶手（Y=1.55..1.62）：与下面那道 0.95 的扶手同规格，右侧壁一段在操纵面板前收头
        {-1.302f,1.55f,-1.25f,-1.22f,1.62f,1.02f,6,0},
        {1.22f,1.55f,-1.25f,1.302f,1.62f,.18f,6,0},
        {-1.25f,1.55f,-1.302f,1.25f,1.62f,-1.22f,6,0},
        // 两根立柱：后壁左右各一根，把上下两道扶手连成整体（两端各嵌进扶手 0.02 格）
        {-.96f,1f,-1.28f,-.88f,1.57f,-1.2f,6,0},
        {.88f,1f,-1.28f,.96f,1.57f,-1.2f,6,0},
        // 载重铭牌：后壁中央的深色边框 + 亮色板（红字由 drawCapacityPlate 画在板上）
        {-.68f,1.1f,-1.302f,.68f,1.5f,-1.25f,9,0},
        {-.62f,1.15f,-1.302f,.62f,1.45f,-1.242f,8,0},
        // 第二块顶灯（门口一侧）：与后侧那块同一套做法，门口也照亮
        {-.92f,2.786f,.56f,.92f,2.812f,.98f,1,0},
        {-.85f,2.78f,.62f,.85f,2.804f,.92f,5,1},
        // 防滑钢踏板：门槛内侧一条 0.01 格厚的防滑板（底面与踢脚线同高 0.198、左右两端各嵌进
        // 侧壁踢脚线 0.002 格，因此端面藏在踢脚线里、踏板看起来是"从左墙铺到右墙"的；顶面比
        // 地板铺面高约 4.5 毫米，是一条约 1 厘米宽的钢板，不是浮在地上的一块铁）
        {-1.298f,.198f,.86f,1.298f,.204f,.98f,7,0},
        // 操纵面板：与 STANDARD_PARTS 的最后六行逐字相同（generate_art.py 会断言）
        {1.26f,1.10f,.20f,1.302f,1.90f,.90f,9,0},
        {1.25f,1.14f,.24f,1.28f,1.86f,.86f,8,0},
        {1.246f,1.31f,.30f,1.252f,1.73f,.80f,2,0},
        {1.221f,1.21f,.32f,1.251f,1.29f,.40f,10,0},
        {1.221f,1.21f,.46f,1.251f,1.29f,.54f,10,0},
        {1.221f,1.21f,.60f,1.251f,1.29f,.68f,10,0},
    };

    /**
     * 观光轿厢的不透明结构件与玻璃压条：地板、顶板、四根角柱在 {@link #drawObservationShell} 里
     * 随门洞尺寸绘制，这里放的是玻璃幕墙的分格——上下压条、一道横向中梃、每面两道竖向分格，
     * 以及贴玻璃内侧的扶手、顶棚灯槽与顶灯、以及操纵面板（面板与普通轿厢逐行相同，只在
     * 前面多一块"安装座"把面板接到玻璃上）。
     *
     * <p>压条与分格都<b>横跨玻璃平面</b>（玻璃在 X=±1.4 / Z=-1.4），因此玻璃片段会被它们正确遮挡，
     * 看上去就是"分格窗"；这也是本表允许伸到 ±1.41 的原因。
     *
     * <p>本表<b>最后六行必须与 {@link #STANDARD_PARTS} / {@link #POWERFUL_PARTS} 完全一致</b>（操纵面板六件）：
     * 观光舱同样要显示层号与呼梯键，漏掉它就会出现"红字浮在空中"。
     * {@code tools/generate_art.py} 的 {@code check_cabin_parts} 会断言这一点。
     */
    private static final float[][] OBSERVATION_PARTS={
        // 上下压条（兼作玻璃收边与底托）：横跨 X=±1.4 / Z=-1.4 的玻璃平面
        {-1.41f,.198f,-1.40f,-1.39f,.36f,1.09f,2,0},
        {1.39f,.198f,-1.40f,1.41f,.36f,1.09f,2,0},
        {-1.40f,.198f,-1.41f,1.40f,.36f,-1.39f,2,0},
        {-1.41f,2.64f,-1.40f,-1.39f,2.802f,1.09f,1,0},
        {1.39f,2.64f,-1.40f,1.41f,2.802f,1.09f,1,0},
        {-1.40f,2.64f,-1.41f,1.40f,2.802f,-1.39f,1,0},
        // 横向中梃：把每面玻璃分成上下两排
        {-1.41f,1.42f,-1.40f,-1.39f,1.48f,1.09f,1,0},
        {1.39f,1.42f,-1.40f,1.41f,1.48f,1.09f,1,0},
        {-1.40f,1.42f,-1.41f,1.40f,1.48f,-1.39f,1,0},
        // 竖向分格：每面两道，把每排再分成三格（两端嵌进上下压条 0.01 格）
        {-1.41f,.35f,-.85f,-1.39f,2.66f,-.79f,1,0},
        {-1.41f,.35f,.35f,-1.39f,2.66f,.41f,1,0},
        {1.39f,.35f,-.85f,1.41f,2.66f,-.79f,1,0},
        {1.39f,.35f,.35f,1.41f,2.66f,.41f,1,0},
        {-.85f,.35f,-1.41f,-.79f,2.66f,-1.39f,1,0},
        {.35f,.35f,-1.41f,.41f,2.66f,-1.39f,1,0},
        // 扶手：贴着玻璃内侧，0.95 格高
        {-1.399f,.95f,-1.25f,-1.27f,1.02f,1.02f,6,0},
        {1.27f,.95f,-1.25f,1.399f,1.02f,1.02f,6,0},
        {-1.25f,.95f,-1.399f,1.25f,1.02f,-1.27f,6,0},
        // 顶棚灯槽 + 顶灯（与普通轿厢同一套）
        {-1.399f,2.72f,-1.25f,-1.27f,2.802f,1.03f,1,0},
        {1.27f,2.72f,-1.25f,1.399f,2.802f,1.03f,1,0},
        {-1.25f,2.72f,-1.399f,1.25f,2.802f,-1.27f,1,0},
        {-1.25f,2.72f,1.02f,1.25f,2.802f,1.09f,1,0},
        {-.92f,2.786f,-1.02f,.92f,2.812f,.52f,1,0},
        {-.85f,2.78f,-.95f,.85f,2.804f,.45f,5,1},
        // 面板安装座：观光舱的侧壁是玻璃，面板悬在玻璃内侧 0.1 格；这块座把面板接到玻璃上
        {1.300f,1.12f,.22f,1.399f,1.88f,.88f,1,0},
        // ↓↓↓ 以下六行与 STANDARD_PARTS 的最后六行逐字相同（操纵面板）↓↓↓
        {1.26f,1.10f,.20f,1.302f,1.90f,.90f,9,0},
        {1.25f,1.14f,.24f,1.28f,1.86f,.86f,8,0},
        {1.246f,1.31f,.30f,1.252f,1.73f,.80f,2,0},
        {1.221f,1.21f,.32f,1.251f,1.29f,.40f,10,0},
        {1.221f,1.21f,.46f,1.251f,1.29f,.54f,10,0},
        {1.221f,1.21f,.60f,1.251f,1.29f,.68f,10,0},
    };

    /** 轿厢内面板的字号（格/像素）：方向箭头一行、楼层号一行（都是单个字符，字号大些才醒目）；两行各自水平居中。 */
    private static final float FLOOR_SCALE=.018f, ARROW_SCALE=.022f;
    /**
     * 两行的<b>行心</b>（格，相对面板中心，正 = 向上）：箭头在上（+0.08）、楼层号在下（-0.10）。
     *
     * <p>为什么不直接写字体坐标：{@link TextRenderer#draw} 的 y 参数是这一行的<b>顶边</b>而不是中心，
     * 而 {@code scale(scale,-scale,scale)} 之后局部 +Y 朝世界下方，于是顶边 = 面板中心 - 行心 - 半个字高
     * （字高 = {@link TextRenderer#fontHeight} 像素）。这里由 {@link #drawCabinLine} 换算，
     * 因此改字号不会再把字推出显示窗——1.5.6 之前楼层号就是照抄像素偏移，结果掉到窗外压在按钮上。
     */
    private static final float ARROW_LINE_CENTRE=.08f, FLOOR_LINE_CENTRE=-.10f;
    /**
     * 强力型号后壁载重铭牌上那行限载人数的字号（格/像素）与落点。
     *
     * <p>为什么比面板上的字号小：铭牌只有 1.24 x 0.30 格，而这一行是<b>一句带数字的话</b>
     * （中英文字宽差得很多，见 {@code text.easyelevator.capacity}），字号稍大英文就会溢出板面。
     * 落点取板面中心（X=0、Y={@link #CAPACITY_PLATE_CENTRE_Y}），文字再向前让开 0.0035 格，
     * 与 {@link #drawFloorDisplay} 在显示窗里的做法一致。
     */
    private static final float CAPACITY_SCALE=.014f, CAPACITY_PLATE_CENTRE_Y=1.30f, CAPACITY_PLATE_FRONT_Z=-1.2385f;
    /** 面板上文字的颜色（红色）；与门框顶部、选站面板、后壁载重铭牌显示同一类信息。 */
    private static final int FLOOR_COLOR=0xFFFF4040;
    /** 白色顶点，不叠加整面颜色或 alpha；通透区域由原版玻璃贴图决定。 */
    private static final int GLASS_COLOR=0xFFFFFFFF;
    private static final int GLASS_DOOR_COLOR=0xFFFFFFFF;

    /** 构造渲染器。副作用：仅保存 EntityRenderer 上下文（光源、模型加载器等）。 */
    public CabinRenderer(EntityRendererFactory.Context context) { super(context); }
    /** 返回轿厢整张图集贴图；用于外壳与内饰；玻璃单独使用原版贴图。 */
    @Override public Identifier getTexture(T entity) { return TEXTURE; }

    /**
     * 视锥剔除：用"向后加宽一格"的包围盒判定，容纳背面的抱轨导靴（见 {@link #drawGuideShoes}）。
     *
     * <p>导靴为了夹住轨道必须伸出背板约 0.94 格，超出了实体注册尺寸的包围盒；默认剔除用的是
     * {@code entity.getBoundingBox()}，镜头贴近背板时那半格视锥就会判定"轿厢不可见"，
     * 于是整组导靴（连同整个轿厢）突然消失。这里把判定盒向后扩一格。
     * 只影响剔除，不参与碰撞（碰撞仍取 {@code AbstractCabinEntity.collisionBoxes()}）。
     *
     * @param entity 轿厢实体
     * @param frustum 视锥
     * @param x 摄像机 X
     * @param y 摄像机 Y
     * @param z 摄像机 Z
     * @return 需要绘制时 true
     */
    @Override
    public boolean shouldRender(T entity,Frustum frustum,double x,double y,double z) {
        return entity.shouldRender(x,y,z)&&frustum.isVisible(entity.getBoundingBox().expand(0,0,1));
    }

    /** 绘制轿厢。只读服务端同步的状态（FACING、DOOR 的插值门进度），不修改任何游戏状态。
     * @param cabin 目标轿厢实体（普通 / 高速 / 强力 / 观光）
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
        // 强力型号：外壳、门与门口构件全部与普通型号共用，只在内饰表上换成"重载"那一张（见 POWERFUL_PARTS）。
        // 之所以在这里取一次而不是在每个绘制方法里各判一次，是为了让"同一帧只认一个型号"这件事只写一遍。
        boolean heavyDuty=cabin.heavyDuty();
        // 光照保留世界采样值，BoxMesh 逐面调用 CabinLighting：
        // 外表面不补光，舱内朝向灯具的表面获得衰减补光，灯罩仅朝下发亮。
        // 舱内补光的采样器按型号选：普通 / 高速 / 观光按舱顶后侧那一盏灯补光；强力舱门口还有第二盏灯，
        // 因此它的外壳内面与内饰一起改走双灯采样（`CabinLighting.surfaceTwoLamps`，取两盏里更亮的一盏）。
        // 这一步刻意只对强力型号生效：把第二盏灯并进通用采样会顺手改掉另外三种型号的舱内观感。
        BoxMesh.FaceLighting surface=heavyDuty?CabinLighting::surfaceTwoLamps:CabinLighting::surface;
        // 第 1 组（不透明层）：外壳 + 内饰 + 门口构件 + 门扇。所有不透明几何必须在这一组里画完，
        // 否则切到文字层之后再回头写它就会触发 Not building!（见类注释）
        VertexConsumer out=buffers.getBuffer(RenderLayer.getEntityCutout(TEXTURE));
        if(cabin.glassWalls()) {
            // 观光舱：结构与内饰 + 玻璃双面（门板同样是不透明的钢框门，见 drawDoors）
            drawObservationShell(matrices,out,front,doorBack,light);
            drawParts(matrices,out,OBSERVATION_PARTS,light,CabinLighting::surface);
        } else {
            drawStandardShell(matrices,out,front,light,surface);
            drawParts(matrices,out,heavyDuty?POWERFUL_PARTS:STANDARD_PARTS,light,surface);
        }
        drawGuideShoes(matrices,out,light);   // 背面的抱轨导靴：让轿厢看起来骑在轨道上（三种外观共用，且不进碰撞）
        drawDoors(matrices,out,light,open,cabin.glassWalls()); // 两扇对开滑门（观光型号画铁框，玻璃在后面一组）
        drawDoorway(matrices,out,front,doorBack,light); // 门槛 + 门楣轨道
        if(cabin instanceof PowerfulCabinEntity powerful) drawCargo(powerful,matrices,out,light);
        // 第 2 组（文字层）：面板上的楼层号（红色），与选站面板、楼层门框顶部显示的是同一个由服务端同步的楼层号；
        // 强力型号再多一行后壁铭牌上的限载人数。两次都是同一类文字层，之间不会发生层切换（见类注释）。
        drawFloorDisplay(cabin,matrices,buffers);
        if(heavyDuty) drawCapacityPlate(cabin,matrices,buffers);
        // 第 3 组（玻璃 cutout 层，仅观光型号）：玻璃墙与玻璃门。必须是最后一组，返回前不再写任何顶点。
        if(cabin.glassWalls()) {
            drawObservationGlass(matrices,buffers,front,doorBack,light);
            drawGlassDoorPanes(matrices,buffers,light,open); // 门扇中间的玻璃（与玻璃墙同一层）
        }
        matrices.pop(); super.render(cabin,yaw,delta,matrices,buffers,light);
    }

    /** 钢带木色货箱：完全位于共用碰撞几何之内，不占门口、不遮住后壁铭牌。 */
    private static void drawCargo(PowerfulCabinEntity cabin, MatrixStack matrices, VertexConsumer out, int light) {
        float[] woodCell = MATERIAL_UV[Mat.WALL.ordinal()], bandCell = MATERIAL_UV[Mat.TRIM.ordinal()];
        float[][] wood = {woodCell,woodCell,woodCell,woodCell,woodCell,woodCell};
        float[][] band = {bandCell,bandCell,bandCell,bandCell,bandCell,bandCell};
        for(int i=0;i<cabin.cargoCrates();i++) {
            var b=CargoLoad.box(i);
            BoxMesh.cuboid(matrices,out,new net.minecraft.util.math.Box(b.minX,b.minY,b.minZ+.006,
                    b.maxX,b.maxY-.003,b.maxZ-.006),light,0xFFC28C50,wood,CabinLighting::surfaceTwoLamps);
            // 钢带套在木箱上，略内嵌，外表面留出深度差，避免共面闪烁。
            for(double fraction:new double[]{.22,.78}) {
                double sx=b.minX+(b.maxX-b.minX)*fraction;
                BoxMesh.cuboid(matrices,out,new net.minecraft.util.math.Box(sx-.025,b.minY,b.minZ,
                        sx+.025,b.maxY,b.maxZ),light,0xFF536477,band,CabinLighting::surfaceTwoLamps);
            }
            BoxMesh.cuboid(matrices,out,new net.minecraft.util.math.Box(b.minX+.27,b.minY+.14,b.maxZ-.008,
                    b.minX+.37,b.minY+.25,b.maxZ),light,0xFFE7CF8A,band,CabinLighting::surfaceTwoLamps);
        }
    }

    // ----------------------------------------------------------------------------------
    // 外壳与内饰
    // ----------------------------------------------------------------------------------

    /** 按钮格贴图里"没有圆点图案"的那一段宽度占比：`tile_button` 的圆点居中（约占格子的 56%），
     * 左缘约 15% 是纯拉丝底板，正好拿来当按钮五个侧面的金属侧壁。 */
    private static final float BUTTON_PLAIN_U=.15f;

    /** 按内饰清单逐块绘制：材质格号与自发光标志都来自表；顶点色一律白色，颜色由贴图决定。
     *
     * <p>呼梯按钮（{@link Mat#BUTTON}）是唯一"图案只在一个面上成立"的件：它的贴图是一个居中的
     * 圆点按钮面，若像其余件那样六面共用同一个 UV 矩形，六个面都会画上圆点——实机反馈
     * "面板下面这几个模拟的按钮，怎么每个面画了个圆圈"就是这个。按钮因此改走 {@link #buttonFaceUv}。
     * 其余件的贴图（墙板、饰条、灯罩、显示屏…）都是均匀或方向无关的，六面共用一张格反而是对的。
     *
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param out 不透明顶点缓冲
     * @param parts 内饰清单，每行 {x,y,z,X,Y,Z,材质格号,自发光}
     * @param light 环境光；按面区分外表面、舱内受光面与灯罩朝下的发光面。
     * @param surface 舱内表面的补光采样器：普通 / 高速 / 观光传 {@link CabinLighting#surface}，
     *                强力舱传 {@link CabinLighting#surfaceTwoLamps}（门口还有第二盏顶灯）。灯罩行不受它影响，
     *                自发光行一律走 {@link CabinLighting#lamp}。
     */
    private static void drawParts(MatrixStack matrices,VertexConsumer out,float[][] parts,int light,BoxMesh.FaceLighting surface) {
        for(float[] p:parts) {
            BoxMesh.FaceLighting lighting=p[7]!=0?CabinLighting::lamp:surface;
            int mat=(int)p[6];
            if(mat==Mat.BUTTON.ordinal())
                BoxMesh.cuboid(matrices,out,p[0],p[1],p[2],p[3],p[4],p[5],light,0xFFFFFFFF,buttonFaceUv(MATERIAL_UV[mat],p),lighting);
            else
                BoxMesh.cuboid(matrices,out,p[0],p[1],p[2],p[3],p[4],p[5],light,0xFFFFFFFF,MATERIAL_UV[mat],lighting);
        }
    }

    /**
     * 呼梯按钮的逐面 UV：圆点徽标只贴<b>朝向乘客</b>的那一面，其余五面取格子左缘那条没有图案的
     * 窄条（{@link #BUTTON_PLAIN_U}），于是侧面读起来就是按钮的金属侧壁。
     *
     * <p>"哪一面朝乘客"由数据表推导而不是写死：面板挂在 +X 侧壁上、按钮向舱内突出，
     * 所以 +X 侧壁上的按钮朝乘客的是 <b>-X 面</b>；将来若把面板挪到 -X 侧壁，这里会自动取 +X 面。
     *
     * @param cell 按钮格的图集 UV 矩形 {u0,v0,u1,v1}
     * @param part 该按钮的几何行 {x,y,z,X,Y,Z,…}，用于判断它挂在哪一面侧壁上
     * @return 六个面的 UV，顺序与 {@code BoxMesh.cuboid} 提交面的顺序一致：{-Z,+Z,-X,+X,+Y,-Y}
     */
    private static float[][] buttonFaceUv(float[] cell,float[] part) {
        float plainU=cell[0]+(cell[2]-cell[0])*BUTTON_PLAIN_U;
        float[] plain={cell[0],cell[1],plainU,cell[3]};
        float[][] uv={plain,plain,plain,plain,plain,plain};
        uv[(part[0]+part[3])*.5f>0?2:3]=cell; // +X 侧壁 → -X 面（下标 2）；-X 侧壁 → +X 面（下标 3）
        return uv;
    }

    /**
     * 画普通 / 高速 / 强力轿厢的外壳：整舱不透明（高速型号刻意不做任何区别，强力型号只换内饰表）。
     *
     * <p>五块几何的坐标与 {@code AbstractCabinEntity.collisionBoxes()} 的前五项逐一对应，
     * 因此"看得见的墙"就是"挡得住人的墙"。只有材质从纯白顶点色换成了图集里的分格：
     * 地板与顶板取各自的格子，三面墙里后壁取略暖的饰面板（乘客背对的一面），侧壁取亮钢。
     *
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param out 不透明顶点缓冲
     * @param front 轿厢正面前缘 Z（格）
     * @param light 打包后的光照值
     * @param surface 舱内表面的补光采样器：普通 / 高速传 {@link CabinLighting#surface}，
     *                强力舱传 {@link CabinLighting#surfaceTwoLamps}（外壳内面与内饰必须用同一盏/一组灯，
     *                否则舱壁与扶手会被两套灯位分别照亮，接缝上看得出来）
     */
    private static void drawStandardShell(MatrixStack matrices,VertexConsumer out,float front,int light,BoxMesh.FaceLighting surface) {
        drawFloor(matrices,out,front,light,surface);
        BoxMesh.cuboid(matrices,out,-1.5f,CEIL_INNER,-1.5f,1.5f,3,front,light,0xFFFFFFFF,MATERIAL_UV[Mat.CEIL.ordinal()],surface); // 顶板：Y=2.8..3.0 格
        BoxMesh.cuboid(matrices,out,-1.5f,FLOOR_TOP,-1.5f,-INNER,CEIL_INNER,front,light,0xFFFFFFFF,MATERIAL_UV[Mat.WALL.ordinal()],surface); // 左侧壁：厚 0.2 格
        BoxMesh.cuboid(matrices,out,INNER,FLOOR_TOP,-1.5f,1.5f,CEIL_INNER,front,light,0xFFFFFFFF,MATERIAL_UV[Mat.WALL.ordinal()],surface);  // 右侧壁：厚 0.2 格
        BoxMesh.cuboid(matrices,out,-INNER,FLOOR_TOP,-1.5f,INNER,CEIL_INNER,-INNER,light,0xFFFFFFFF,MATERIAL_UV[Mat.ACCENT.ordinal()],surface); // 后壁：正面留空形成门洞
    }

    /**
     * 画轿厢地板：分成"深色基座 + 略小的铺面"两层。
     *
     * <p>为什么要拆两层：地板盒子的顶面是 3×2.8 格（接近正方形，铺装贴图正好），而四个侧面只有
     * 0.2 格高、3 格宽——比例 1:15，同一张铺装贴图铺上去会被纵向压成一堆横条纹（1.5.6 实机反馈的
     * "地板侧面贴图挤在一起"）。所以侧面改用横向无细节的深色阳极氧化材质（拉丝是纵向的，压扁也看不出来），
     * 铺面单独做成略小的一层贴在顶上：四周留一圈 0.01 格的阴影缝，看起来就是"地板砖嵌在底座里"。
     *
     * <p>两层互相嵌进去 0.002 格、且铺面四周缩进 0.01..0.002 格，避免任何两个面共面
     * （同向共面片会互相抢深度）。净高与碰撞完全不受影响：
     * 铺面顶面仍在 Y={@link #FLOOR_TOP}，玩家脚下与 {@code collisionBoxes()} 的地板面一致。
     *
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param out 不透明顶点缓冲
     * @param front 轿厢正面前缘 Z（格）
     * @param light 打包后的光照值
     * @param surface 舱内表面的补光采样器（由 {@link #drawStandardShell} 按型号传入）
     */
    private static void drawFloor(MatrixStack matrices,VertexConsumer out,float front,int light,BoxMesh.FaceLighting surface) {
        // 基座顶面用 FLOOR_TOP-.0125 而不是 -.01：关门时门扇内缘正好在 |X| = 0.010 格（{@link SlidingDoor#SEAM}），
        // 基座 / 铺面的侧面若也落在 ±0.010 就会与门扇侧面共面（门底部那一段会闪）。缩到 ±0.0125 彻底错开。
        // 铺面顶面同样从 FLOOR_TOP 压到 -.0005，与门扇渲染下沿 DOOR_RENDER_BOTTOM 分开半毫米。
        BoxMesh.cuboid(matrices,out,-1.5f,0,-1.5f,1.5f,FLOOR_TOP-.0125f,front,light,0xFFFFFFFF,MATERIAL_UV[Mat.DARK.ordinal()],surface);        // 基座与四周立面
        BoxMesh.cuboid(matrices,out,-1.4875f,FLOOR_TOP-.0125f,-1.4875f,1.4875f,FLOOR_TOP-.0005f,front-.01f,light,0xFFFFFFFF,MATERIAL_UV[Mat.FLOOR.ordinal()],surface); // 略小的铺面
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
        drawFloor(matrices,out,front,light,CabinLighting::surface); // 观光舱同样保留地板，乘客站在上面（单灯：观光舱只有后侧那一盏顶灯）
        BoxMesh.cuboid(matrices,out,-1.5f,CEIL_INNER,-1.5f,1.5f,3,front,light,0xFFFFFFFF,MATERIAL_UV[Mat.CEIL.ordinal()],CabinLighting::surface);  // 顶板
        BoxMesh.cuboid(matrices,out,-1.5f,FLOOR_TOP,-1.5f,-INNER,CEIL_INNER,-INNER,light,0xFFFFFFFF,MATERIAL_UV[Mat.TRIM.ordinal()],CabinLighting::surface); // 后左角柱
        BoxMesh.cuboid(matrices,out,INNER,FLOOR_TOP,-1.5f,1.5f,CEIL_INNER,-INNER,light,0xFFFFFFFF,MATERIAL_UV[Mat.TRIM.ordinal()],CabinLighting::surface);  // 后右角柱
        // 前两根角柱与门扇同厚（Z 从门背面到轿厢正面）
        BoxMesh.cuboid(matrices,out,-1.5f,FLOOR_TOP,doorBack,-INNER,CEIL_INNER,front,light,0xFFFFFFFF,MATERIAL_UV[Mat.TRIM.ordinal()],CabinLighting::surface); // 前左角柱
        BoxMesh.cuboid(matrices,out,INNER,FLOOR_TOP,doorBack,1.5f,CEIL_INNER,front,light,0xFFFFFFFF,MATERIAL_UV[Mat.TRIM.ordinal()],CabinLighting::surface);  // 前右角柱
    }

    /**
     * 画门口的常驻构件：金属门槛与门楣轨道，两种外观共用。
     *
     * <p>门槛（贴地 0.03 格高）与门楣（2.72 格以上 0.08 格高的导轨箱）都夹在门扇背面与轿厢正面之间，
     * 并且四周各缩进或外嵌 0.002..0.01 格：门关着时完全藏在门扇后面（所以不会和门扇抢深度），
     * 门一开就露出门槛与导轨，与真实电梯车厢一致。
     *
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param out 不透明顶点缓冲
     * @param front 轿厢正面前缘 Z（格）
     * @param doorBack 门扇背面 Z（格）
     * @param light 打包后的光照值
     */
    private static void drawDoorway(MatrixStack matrices,VertexConsumer out,float front,float doorBack,int light) {
        // 门槛：左右嵌进侧壁 0.002 格、底面嵌进地板 0.002 格，前后各缩 0.01 格，顶面 0.23 格（比地板面高 0.03 格）
        BoxMesh.cuboid(matrices,out,-INNER-BITE,FLOOR_TOP-BITE,doorBack+.01f,INNER+BITE,.23f,front-.01f,light,0xFFFFFFFF,MATERIAL_UV[Mat.SILL.ordinal()],CabinLighting::surface);
        // 门楣：贴顶的导轨箱，顶面嵌进顶板 0.002 格，背面缩进门扇背面 0.002 格
        BoxMesh.cuboid(matrices,out,-INNER-BITE,2.72f,doorBack-.002f,INNER+BITE,CEIL_INNER+BITE,front-.01f,light,0xFFFFFFFF,MATERIAL_UV[Mat.TRIM.ordinal()],CabinLighting::surface);
    }

    /**
     * 画轿厢门：<b>两扇对开滑门</b>，门洞就是整个轿厢正面（内净宽 2.6 格）；开门时两扇一起向两侧收拢、
     * 完全让开门洞——与楼层门同一套做法（{@code LandingDoorRenderer}），所以里外两道门看起来一致。
     *
     * <p>布局（两扇的横向区间、门区 Z 厚度）来自纯算术类 {@link SlidingDoor}，与
     * {@code AbstractCabinEntity.collisionBoxes()} 用的是同一份数据，因此"看得见的门"就是"挡得住人的门"。
     * 门扇外缘固定在侧壁内侧（{@link SlidingDoor#OUTER_EDGE}），内缘（先导端）随进度向外移动，
     * 全开时宽度归零、完全收进侧壁，门洞全通。
     *
     * <p>贴图的做法与楼层门同源、但<b>不能共用同一个区间函数</b>：大面只取"还露在外面"的那一段
     * （{@link LeafUv#cabinPanelRange}——两扇都让中缝端落在纹理 t=1、门框端落在 t=进度；
     * 断面另取一小段（{@link LeafUv#edgeRange}），折边那条亮线因此跟着先导端一起往外走，
     * 看起来就是"门在往两侧滑"。
     * 楼层门用 {@link LeafUv#leafRange}，它依赖 {@code LandingDoorGeometry.doorBox} 的朝向镜像；
     * 轿厢门扇没有那层镜像，混用会让 +X 那扇整块左右翻转（厢内往外看左侧折边跑到门框端）。
     * 这里也**不能**改成"整格贴图铺满"：门扇是"盒子越收越窄"画出来的，铺满就会被横向压扁
     * （1.5.6 实机反馈过的那条）。
     *
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param out 不透明顶点缓冲
     * @param light 打包后的光照值
     * @param open 门进度 0..1
     * @param glass true 表示观光型号：门扇只画"铁框"，中间的玻璃由 {@link #drawGlassDoorPanes} 在玻璃层画
     */
    private static void drawDoors(MatrixStack matrices,VertexConsumer out,int light,float open,boolean glass) {
        if(!SlidingDoor.visible(open)) return; // 全开时两扇宽度归零，没有可见几何
        float[] cell=MATERIAL_UV[Mat.DOOR.ordinal()], trim=MATERIAL_UV[Mat.TRIM.ordinal()];
        for(boolean right:new boolean[]{false,true}) {
            double[] x=SlidingDoor.panelX(right,open);
            var leaf=leafBox(x,right);
            if(glass) {
                // 观光型号：周围铁框、中间玻璃（玻璃在最后一组里画，见类注释的层顺序约束）
                FramedGlassDoor.frame(matrices,out,leaf,true,trim,light,CabinLighting::surface);
                continue;
            }
            BoxMesh.cuboid(matrices,out,leaf,light,0xFFFFFFFF,LeafUv.slabUv(
                    // 轿厢门扇没有"按朝向镜像门宽轴"这一层，必须用 cabinPanelRange：
                    // 用 leafRange 会让 +X 那扇整块左右翻转（厢内往外看左侧折边跑到门框端）。
                    LeafUv.toUv(LeafUv.cabinPanelRange(open,right),cell),
                    LeafUv.toUv(LeafUv.edgeRange(right,LeafUv.EDGE_WIDTH),cell),true),CabinLighting::surface);
        }
    }

    /**
     * 观光轿厢门扇中间的玻璃（玻璃层，零厚度双面，位于门扇厚度中线）。
     *
     * <p>必须在不透明层与文字层都画完之后调用：取玻璃层会结束上一层缓冲，
     * 之后再往旧引用写顶点会抛 {@code Not building!}（见类注释）。这里的层是
     * {@link GlassLayers#CABIN}（透明像素直接丢弃），因此不会把之后绘制的楼层门剔掉。
     *
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param buffers 顶点缓冲提供者，用于取玻璃层
     * @param light 打包后的光照值
     * @param open 门进度 0..1
     */
    private static void drawGlassDoorPanes(MatrixStack matrices,VertexConsumerProvider buffers,int light,float open) {
        if(!SlidingDoor.visible(open)) return;
        VertexConsumer glass=buffers.getBuffer(GlassLayers.CABIN);
        float[] uv=BoxMesh.FULL_UV;
        for(boolean right:new boolean[]{false,true})
            FramedGlassDoor.glass(matrices,glass,leafBox(SlidingDoor.panelX(right,open),right),true,uv,light,GLASS_DOOR_COLOR);
    }

    /**
     * 一扇轿厢门扇的长方体（格，轿厢局部坐标）。
     *
     * @param x {@code SlidingDoor.panelX} 给出的横向区间
     * @param right true 取右扇（仅为可读性保留，两扇的 Z 范围相同）
     * @return 门扇长方体
     */
    private static net.minecraft.util.math.Box leafBox(double[] x,boolean right) {
        return new net.minecraft.util.math.Box(x[0],DOOR_RENDER_BOTTOM,SlidingDoor.DOOR_Z_BACK,x[1],CEIL_INNER,SlidingDoor.DOOR_Z_FRONT);
    }

    /**
     * 画轿厢背面的<b>导靴</b>（抱轨支架），让轿厢看起来是"骑在轨道上"而不是浮在旁边。
     *
     * <p>轿厢中心在轨道朝向前方 2 格，因此轨道就在轿厢背板外侧、本地 Z≈-1.7..-2.3 的位置，
     * 与背板之间还有约 0.19 格的空隙（见轨道模型 3..13/16）。这里在背板上装两个导靴
     * （下 y≈0.55、上 y≈2.45），每个由"背板贴板 + 两条侧臂 + 后横梁 + 两个滚轮"组成，
     * 侧臂夹住轨道的两侧导轨、后横梁从轨道背面兜住，滚轮正好触到导轨侧面——于是
     * 上下运行时能明显看出轿厢顺着轨道滑。
     *
     * <p>这两组几何<b>不进碰撞</b>（{@code collisionBoxes()} 里没有它们）：导靴本来就要贴着轨道，
     * 若进碰撞，井道扫描会把轨道方块当成障碍而永久 BLOCKED。它们也超出实体注册尺寸的包围盒，
     * 所以 {@link #shouldRender} 把包围盒向后扩了一格，避免镜头贴近背板时被剔除掉。
     *
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param out 不透明顶点缓冲
     * @param light 打包后的光照值
     */
    private static void drawGuideShoes(MatrixStack matrices,VertexConsumer out,int light) {
        float[] steel=MATERIAL_UV[Mat.TRIM.ordinal()], rubber=MATERIAL_UV[Mat.DARK.ordinal()];
        for(float centre:new float[]{.55f,2.45f}) {
            // 背板贴板：贴在背板外表面上（略嵌进去，避免与背板共面）
            BoxMesh.cuboid(matrices,out,-.5f,centre-.16f,-1.56f,.5f,centre+.16f,-1.49f,light,0xFFFFFFFF,steel);
            // 两条侧臂与后横梁：夹住轨道（轨道模型横向最外沿约 ±0.3）
            BoxMesh.cuboid(matrices,out,-.46f,centre-.13f,-2.34f,-.34f,centre+.13f,-1.5f,light,0xFFFFFFFF,steel);
            BoxMesh.cuboid(matrices,out,.34f,centre-.13f,-2.34f,.46f,centre+.13f,-1.5f,light,0xFFFFFFFF,steel);
            BoxMesh.cuboid(matrices,out,-.46f,centre-.13f,-2.44f,.46f,centre+.13f,-2.34f,light,0xFFFFFFFF,steel);
            // 两个滚轮：正好压在轨道两侧的导轨上
            BoxMesh.cuboid(matrices,out,-.33f,centre-.09f,-2.1f,-.26f,centre+.09f,-1.9f,light,0xFFFFFFFF,rubber);
            BoxMesh.cuboid(matrices,out,.26f,centre-.09f,-2.1f,.33f,centre+.09f,-1.9f,light,0xFFFFFFFF,rubber);
        }
    }

    /**
     * 画观光轿厢的<b>无色透明玻璃</b>：左右侧墙与后墙，每处都是一张零厚度双面玻璃。
     * 门扇的玻璃不在这里（它要跟着门开合一起动，见 {@link #drawGlassDoorPanes}）。
     *
     * <p>每片玻璃提交正反两个面，绕序与法线相反，使用背面剔除后每次只显示其中一面。
     * 透明像素直接丢弃，不叠加灰白底色；内外观察时均使用正确的表面法线。
     *
     * <p>为什么每个尺寸都留 0.01 格缝、而不是贴着角柱/地板/顶板：
     * 两个面恰好共面时，浮点误差会让它们的深度值差一点点，镜头一动就来回抢先，
     * 看起来就是"玻璃门与不透明框架衔接处轻微闪烁"。玻璃面因此内缩在结构件之间：
     * 侧墙取墙心 X=∓1.4 且 Z=-1.29..门背面-0.01、后墙取 Z=-1.4 且 X=∓1.29，
     * 上下都取 Y=0.21..2.79，四个方向都与不透明件脱开 0.01 格以上（视觉上仍是连续的整面玻璃）。
     * {@link #OBSERVATION_PARTS} 里的压条与中梃横跨玻璃平面，把整面玻璃分成 2x3 格窗。
     *
     * <p>调用时机：必须在不透明层与文字层都画完之后（本方法是整个 render 的最后一步）。
     * 取玻璃 cutout 层会结束上一层缓冲，之后再往旧引用写顶点会抛 {@code Not building!}。
     *
     * <p>碰撞不受影响：外壳仍由 {@link AbstractCabinEntity#collisionBoxes()} 按原尺寸（0.2 格厚实心墙）生成，
     * 因此玻璃墙照样挡住乘客，井道尺寸、门口防夹与门联锁与普通轿厢逐位相同。
     *
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param buffers 顶点缓冲提供者，用于取玻璃层
     * @param front 轿厢正面前缘 Z（格）
     * @param doorBack 门扇背面 Z（格）
     * @param light 打包后的光照值
     */
    private static void drawObservationGlass(MatrixStack matrices,VertexConsumerProvider buffers,float front,float doorBack,int light) {
        VertexConsumer glass=buffers.getBuffer(GlassLayers.CABIN);
        // 四面玻璃与不透明件一律留 0.01 格缝（见方法注释），因此这里的边界都写成"结构面 ∓0.01"。
        float y0=.21f, y1=2.79f;                  // 地板面 0.2、顶板内侧 2.8
        float z0=-1.29f, z1=doorBack-.01f;        // 后角柱内缘 -1.3、前角柱背面 1.1
        float paneX=1.4f, paneZ=-1.4f;            // 侧壁厚 0.2 → 玻璃贴在墙心 X=±1.4；后壁同理 Z=-1.4
        float[] uv=BoxMesh.FULL_UV;
        BoxMesh.glassX(matrices,glass,-paneX,y0,z0,y1,z1,light,GLASS_COLOR,uv); // 左侧玻璃（双面，按朝向剔除）
        BoxMesh.glassX(matrices,glass,paneX,y0,z0,y1,z1,light,GLASS_COLOR,uv);  // 右侧玻璃
        BoxMesh.glassZ(matrices,glass,paneZ,-1.29f,y0,1.29f,y1,light,GLASS_COLOR,uv); // 背面玻璃（X 留 0.01 缝）
        // 门扇的玻璃由 drawGlassDoorPanes 单独画（它要跟着门开合一起动）。
        // 观光舱的玻璃侧墙/后墙会在最后叠到门板上，于是从厢内朝外看仍能透过侧墙看到外面。
    }

    /**
     * 在轿厢内右侧壁的模拟操作面板上画两行红字：第一行是运行方向箭头，第二行是当前到达层数
     * （▲ 上行 / ▼ 下行，会闪烁；停靠时留空）。
     *
     * <p>面板是 {@link #STANDARD_PARTS} 里的"深色边框 + 亮色面板 + 下沉显示窗"三件套，内侧朝 -X：
     * 文字先绕 Y 轴转 -90° 让正面朝 -X（轿厢内部），再按面板中心定位，因此乘客在轿厢里读到的是正向文字。
     * 显示窗（X=1.246..1.252）比面板面（X=1.25）靠内 0.004 格，文字再靠内一点点，
     * 于是红字落在一块深色下沉窗里，而不是浮在亮面板上——井道灯光偏暗时对比度反而更高。
     *
     * <p>副作用：只向顶点缓冲写入文字（复用管线传入的缓冲，与原版告示牌一致），不改实体状态、不发包。
     * 本方法会切到文字层，因此调用方之后不能再往之前取到的不透明缓冲写顶点（见类注释）。
     *
     * @param cabin 轿厢（提供同步过来的楼层号与运行状态）
     * @param matrices 渲染矩阵栈（已包含轿厢朝向与位置）
     * @param buffers 顶点缓冲提供者：直接用管线给的这一个，由管线统一 flush
     */
    private static void drawFloorDisplay(AbstractCabinEntity cabin,MatrixStack matrices,VertexConsumerProvider buffers) {
        int floor=cabin.floorNumber();
        if(floor==0) return; // 还没经过任何站点（或线路无效）：不显示，避免出现"0 层"（负数 = 地下 B1、B2…，要显示）
        // 两行都按"行心"定位（见 ARROW_LINE_CENTRE 的注释）：
        // 第一行运行方向箭头（▲ 上行 / ▼ 下行，闪烁），停靠时整行留空；第二行是当前到达层数
        // （FloorIndicator.format：基准层 1、其上 2,3…、其下 B1,B2…）。
        String arrow=StatusArrow.glyph(cabin.status());
        if(!arrow.isEmpty()) drawPanelLine(matrices,buffers,Text.literal(arrow),ARROW_LINE_CENTRE,ARROW_SCALE);

        // 如果电梯超载，则在面板处显示“超载”，否则显示到达楼层
        Text numText = Text.literal(FloorIndicator.format(floor));
        if (cabin.phase() == ElevatorController.Phase.OVERLOAD)
            numText = Text.translatable("phase.easyelevator.overload");
        drawPanelLine(matrices,buffers,numText,FLOOR_LINE_CENTRE,FLOOR_SCALE);
    }

    /**
     * 在强力轿厢后壁的载重铭牌上画一行红字：本型号的限载人数。
     *
     * <p>为什么要"把人数写出来"：强力型号与普通型号的外壳、门、井道尺寸<b>逐位相同</b>，差别只在
     * 内饰件与 {@link AbstractCabinEntity#passengerNumLimit()}；铭牌上的数字直接取自那个常量，
     * 因此调参或装卸货物时不需要改任何贴图或几何，
     * 数字永远和状态机里判超载用的那一个一致。
     *
     * <p>板面与落点：铭牌是 {@link #POWERFUL_PARTS} 里的"深色边框 + 亮色板"，正面朝 +Z（轿厢内部）；
     * 文字沿 X 居中，落在 {@link #CAPACITY_PLATE_CENTRE_Y} 高度、再向前让开 0.0035 格。
     * 这里<b>不</b>旋转矩阵（{@code yawDegrees=0}）：正面本来就是 +Z，与面板那面要转 -90° 不同。
     *
     * @param cabin 轿厢：限载人数取自 {@link AbstractCabinEntity#passengerNumLimit()}，
     *              非正数表示"不限载"，此时没有铭牌、什么都不画
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param buffers 顶点缓冲提供者：直接用管线给的这一个，由管线统一 flush
     */
    private static void drawCapacityPlate(AbstractCabinEntity cabin,MatrixStack matrices,VertexConsumerProvider buffers) {
        int limit=cabin.passengerNumLimit();
        if(limit<=0) return; // 不限载的型号（普通 / 高速 / 观光）没有这块牌子，别把红字画在墙上
        drawCabinLine(matrices,buffers,Text.translatable("text.easyelevator.capacity",limit),
                0,CAPACITY_PLATE_CENTRE_Y,CAPACITY_PLATE_FRONT_Z,0,0,CAPACITY_SCALE);
    }

    /**
     * 在轿厢内右侧壁的模拟操作面板上画一行水平居中的红字。
     *
     * <p>面板是 {@link #STANDARD_PARTS} 里的"深色边框 + 亮色面板 + 下沉显示窗"三件套，内侧朝 -X：
     * 文字先绕 Y 轴转 -90° 让正面朝 -X（轿厢内部），再按面板中心定位，因此乘客在轿厢里读到的是正向文字。
     * 显示窗（X=1.246..1.252）比面板面（X=1.25）靠内 0.004 格，文字再靠内一点点，
     * 于是红字落在一块深色下沉窗里，而不是浮在亮面板上——井道灯光偏暗时对比度反而更高。
     *
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param buffers 顶点缓冲提供者
     * @param text 这一行的文本
     * @param lineCentre 行心所在的高度（格，相对显示窗中心，正 = 向上）
     * @param scale 这一行的字号（格/像素）
     */
    private static void drawPanelLine(MatrixStack matrices,VertexConsumerProvider buffers,Text text,float lineCentre,float scale) {
        drawCabinLine(matrices,buffers,text,1.243f,1.5f,.55f,-90,lineCentre,scale); // 显示窗中心，再沿 -X 让开 0.003 格
    }

    /**
     * 在轿厢内的某一块板面上画一行水平居中的红字：面板显示窗（{@link #drawPanelLine}）与
     * 强力型号的后壁载重铭牌（{@link #drawCapacityPlate}）共用这一处落笔逻辑。
     *
     * <p>行心 → 字体坐标的换算：字体内部 y 轴向下、且这一行从顶边开始画，
     * 所以顶边 = 行心 + 半个字高，再除以 scale 并取负号换到"局部 +Y 朝下"的坐标系：
     * {@code yOffset = -(lineCentre + fontHeight*scale/2) / scale}。
     * 这样面板上两行始终落在显示窗（Y=1.31..1.73）里，换字号或换字体也不会跑出去。
     *
     * <p>副作用：只写顶点缓冲。用最高亮度是因为轿厢内部往往很暗，按局部光照画出来会是一团黑；
     * POLYGON_OFFSET 给文字一点深度偏移，贴在板面上不会与板面闪烁。本类所有文字都取同一种文字层，
     * 因此多次调用之间不会发生层切换（否则第二次写的就是已失效的缓冲）。
     *
     * @param matrices 渲染矩阵栈（已包含轿厢位置与朝向）
     * @param buffers 顶点缓冲提供者
     * @param text 这一行的文本
     * @param x 板面中心 X（轿厢局部坐标，格）
     * @param y 板面中心 Y（格）
     * @param z 文字所在的 Z（格）：比板面略靠轿厢内一步，避免与板面共面闪烁
     * @param yawDegrees 文字正面的偏航角（0 = 朝 +Z；右侧壁面板为 -90 = 朝 -X）
     * @param lineCentre 行心相对板面中心的竖直偏移（格，正 = 向上）
     * @param scale 这一行的字号（格/像素）
     */
    private static void drawCabinLine(MatrixStack matrices,VertexConsumerProvider buffers,Text text,
                                      float x,float y,float z,float yawDegrees,float lineCentre,float scale) {
        TextRenderer textRenderer=MinecraftClient.getInstance().textRenderer;
        float yOffset=-(lineCentre+textRenderer.fontHeight*scale/2f)/scale;
        matrices.push();
        // 实体渲染器传进来的矩阵已经平移到实体位置，因此这里全部用轿厢局部坐标。
        matrices.translate(x,y,z);
        if(yawDegrees!=0) matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yawDegrees)); // 正面（局部 +Z）转到板面朝向
        matrices.scale(scale,-scale,scale); // Y 取负：字体内部坐标是 Y 向下，与告示牌一致；不取负文字会上下颠倒
        textRenderer.draw(text,-textRenderer.getWidth(text)/2f,yOffset,FLOOR_COLOR,true,
                matrices.peek().getPositionMatrix(),buffers,TextRenderer.TextLayerType.POLYGON_OFFSET,0,LightmapTextureManager.MAX_LIGHT_COORDINATE);
        matrices.pop();
    }
}
