package org.DJB.easyelevator.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.logic.FloorIndicator;
import org.DJB.easyelevator.logic.PanelLayout;
import org.DJB.easyelevator.network.ElevatorNetworking;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 轿厢内的选站面板：把当前线路的站点排成一整块"电梯按键面板"，点方形数字按钮发送 SelectStop 请求，
 * 底部另有开门 / 关门键（发送 DoorCommand）。
 *
 * <p>在整体架构中的位置：纯客户端 UI。站点列表与"停靠计划"都由服务端下发（服务端已校验乘客身份），
 * 面板自身不查询线路、不做权限判断；点击只发"请求"，最终由服务端状态机决断。面板打开时不暂停游戏
 * （{@link #shouldPause()} 返回 false），因为轿厢仍需继续运行。
 *
 * <p>按键排布（模拟真实电梯面板）：站点按高度升序编号，<b>最底层 = 1 层</b>；按钮从<b>右下角</b>开始，
 * 先<b>从右往左</b>排满一行，再换到<b>上一行</b>继续（即从下往上）。于是每一列的数字自下而上连续递增，
 * 右侧列编号最小，和真实面板"号码往上长"的观感一致。列数由 {@link PanelLayout#chooseColumns} 选成
 * 尽量长方形，并按窗口宽高限制单页容量；站点超过一页时用底部的上一页/下一页翻页。
 *
 * <p><b>外观分层</b>（自上而下）：面板外框 → 表头条（标题 + 左上角楼层指示牌 + 右侧"高度 / 页数"两行）
 * → 按键区（比面板更暗的凹底）→ 页脚条（翻页 / 开门 / 关门 / 完成）。三条横带之间用 1 像素分隔线断开，
 * 与真实电梯面板"显示区 / 按键区"的划分一致，也让视线有落点。
 *
 * <p><b>面板不透明</b>：底色用不透明色（{@link #PANEL_COLOR}）。早先用 92% 不透明时，井道与轿厢内面板的
 * 红色层号会从面板底下隐约透出来，看起来像"按钮上叠了脏数字"。
 *
 * <p>按键配色：普通为灰色描边；<b>轿厢当前停靠层</b>用绿色描边 + 冷绿底面；<b>已加入停靠计划</b>（正在执行
 * 的目的站或排队中的站点）整体偏红；悬停/键盘聚焦时点亮为白色。计划由服务端在变化时用
 * {@link ElevatorNetworking.PanelState} 推送（见 {@link #applyPlanned}）。
 */
public class ElevatorScreen extends Screen {
    /** 方形按钮边长（像素）与按钮间距（像素）。边长 20 与原版按钮同高，间距 4 不至于挤在一起。 */
    private static final int BUTTON=20, GAP=4;
    /** 单页最多列数与行数：再多就翻页，避免按钮小到点不准（8×8 = 单页 64 个站点）。 */
    private static final int MAX_COLUMNS=8, MAX_ROWS=8;
    /** 面板内边距，以及头部（标题/楼层牌/状态/页码）与底部（翻页/开门/关门/完成）占用的高度（像素）。 */
    private static final int PADDING=16, HEADER=64, FOOTER=40;
    /** 配色：面板底（不透明）、表头/页脚条、按键区凹底、分隔线与外框。 */
    private static final int PANEL_COLOR=0xFF161D27, BAR_COLOR=0xFF1E2733, WELL_COLOR=0xFF0F141B, LINE_COLOR=0xFF39485A, BORDER_COLOR=0xFF4A5A6D;
    /** 楼层指示牌（仿七段数码管）：最深底 + 红色数字，显示与门框顶部、轿厢内面板相同的楼层号。 */
    private static final int FLOOR_PANEL_COLOR=0xFF0A0F15, FLOOR_TEXT_COLOR=0xFFFF4A4A;
    /** 楼层指示牌（表头左侧）：与右侧"高度 / 页数"两行的整体垂直居中，只显示层号本身（3 / B1 / --）。 */
    private static final int PLAQUE_W=40, PLAQUE_H=30, PLAQUE_TOP=20;
    /** 指示牌里的层号放大倍数：数字本身只有 5~6 像素高，放大 2 倍才醒目（颜色与门框、轿厢内面板同源）。 */
    private static final float FLOOR_TEXT_SCALE=2f;
    /** 表头两行文字的 Y（相对面板顶，像素）：状态行、页数行；两行整体与左侧指示牌垂直居中。 */
    private static final int STATUS_Y=24, COUNT_Y=38;
    /** 按键区凹底在按键块四周外扩的宽度（像素）：只比按键块稍宽一点，不再横贯整块面板。 */
    private static final int WELL_PAD=6;
    /** 面板最小宽度：底部一行要放下 `<` `>`、开门、关门与"完成"五个控件。 */
    private static final int MIN_PANEL_WIDTH=260;
    /** UI 用的"还在轿厢里"判定范围（格）：水平半宽、相对轿厢底的高度下限与上限，见 {@link #staysInside}。 */
    private static final double KEEP_HORIZONTAL=1.5, KEEP_BELOW=0.6, KEEP_ABOVE=2.9;

    private final int entityId; // 面板绑定的轿厢实体 id，用于回发 SelectStop / DoorCommand
    private final List<BlockPos> stops; // 按高度升序的站点（根方块）列表：下标 0 就是"1 层"；只读
    /** 停靠计划里的站点：{@code BlockPos.asLong()} 集合，命中的按钮画红色描边。 */
    private final Set<Long> planned=new HashSet<>();
    /** 本线路基准层（1 层）的高度（格）：来自服务端下发的 baseFloorY；没有基准层时为 Integer.MIN_VALUE。 */
    private int baseFloorY=Integer.MIN_VALUE;
    /** 基准层在 stops 里的下标（0 基）：该下标处显示 1，其上 2,3…，其下 B1,B2…；找不到基准时为 0。 */
    private int baseIndex;
    private int page; // 当前页（0 基）；翻页时保留，越界会在 init() 里被夹回合法范围
    private PanelLayout.Grid grid=PanelLayout.grid(0,1,1); // 网格与分页参数（列数/行数/页数），init() 时按站点数与窗口尺寸重算
    private int panelLeft, panelTop, panelWidth, panelHeight; // 面板矩形（像素）：init() 计算，render() 复用
    private int gridLeft, gridRight, gridTop, gridBottom; // 按键区矩形（像素）：凹底按它四周各外扩 WELL_PAD，编号 1 的按钮落在右下角
    private ButtonWidget doorOpen, doorClose; // 底部"开门/关门"键；可用状态每帧按轿厢实时状态刷新
    private ButtonWidget pagePrev, pageNext; // 底部翻页键（`<` `>`）；首页/末页禁用而不是隐藏，布局保持稳定

    /**
     * 从服务端下发的 OpenPanel 包构造面板；标题等文案由翻译键决定。
     *
     * @param payload 服务端包：entityId 轿厢实体 id、stops 站点（根方块）列表、planned 停靠计划
     */
    public ElevatorScreen(ElevatorNetworking.OpenPanel payload) {
        super(Text.translatable("screen.easyelevator.title")); entityId=payload.entityId();
        // 自己再排一次序：面板上的"1 层"永远是最低站点，不依赖下发顺序（服务端也是按高度排序的），
        // 同高度时按 X/Z 排序保证编号稳定，不会因为扫描顺序变化而跳号。
        List<BlockPos> sorted=new ArrayList<>(payload.stops());
        sorted.sort(Comparator.comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        stops=List.copyOf(sorted);
        setBaseFloor(payload.baseFloorY());
        applyPlanned(payload.planned());
    }

    /** @return 面板绑定的轿厢实体 id；客户端网络回调据此判断"这份状态是不是本面板的" */
    public int entityId() { return entityId; }

    /**
     * 应用服务端下发的停靠计划（目的站在前、排队站点随后）：命中的按钮标红。
     *
     * <p>由 {@link ElevatorNetworking.PanelState} 在计划变化时推送（到达、取消、新请求）。只更新高亮集合，
     * 不重建控件，因此不会打断玩家正在进行的点击。
     *
     * @param plan 计划中的站点根方块坐标；空列表表示当前没有计划
     */
    public void applyPlanned(List<BlockPos> plan) {
        planned.clear();
        for(BlockPos pos:plan) planned.add(pos.asLong());
    }

    /**
     * 应用服务端下发的"计划 + 基准层"（{@code PanelState}）：计划只改高亮；
     * 基准层若变化则重建控件——按钮上的编号（1 / 2 / B1 / B2…）是按下标算出来的，必须重新铺一遍。
     *
     * @param plan 停靠计划中的站点根方块坐标（空列表 = 当前没有计划）
     * @param baseFloorY 本线路基准层高度（格）；{@code Integer.MIN_VALUE} 表示没有基准层
     */
    public void applyState(List<BlockPos> plan,int baseFloorY) {
        applyPlanned(plan);
        if(setBaseFloor(baseFloorY)) clearAndInit(); // 编号口径变了：重铺按钮（保留当前页）
    }

    /**
     * 记录基准层并换算成站点下标。
     *
     * @param baseFloorY 基准层高度（格）
     * @return 是否发生了变化（true 时调用方需要重铺按钮）
     */
    private boolean setBaseFloor(int baseFloorY) {
        int index=FloorIndicator.baseIndex(stops.stream().map(BlockPos::getY).toList(),baseFloorY);
        boolean changed=index!=baseIndex||baseFloorY!=this.baseFloorY;
        this.baseFloorY=baseFloorY; this.baseIndex=index;
        return changed;
    }

    /**
     * 构建控件：按窗口尺寸算出网格与面板矩形，再铺方形楼层按钮、翻页/开门/关门与"完成"。
     * 窗口尺寸变化或翻页（clearAndInit）时会被重新调用。
     *
     * <p>副作用：向屏幕添加控件；不发包、不改世界。翻页按钮只改 page 并重建控件。
     */
    @Override
    protected void init() {
        // 列数上限由窗口宽度给出、行数上限由窗口高度给出，具体排布交给 PanelLayout（纯算术，有单测）。
        int maxColumns=Math.clamp((width-2*PADDING)/(BUTTON+GAP),1,MAX_COLUMNS);
        int maxRows=Math.clamp((height-HEADER-FOOTER-2*PADDING-2*WELL_PAD)/(BUTTON+GAP),1,MAX_ROWS);
        grid=PanelLayout.grid(stops.size(),maxColumns,maxRows);
        // 窗口缩小或站点减少后可能停在空页上，这里把 page 夹回合法范围。
        page=Math.clamp(page,0,grid.pageCount()-1);

        int gridWidth=grid.columns()*BUTTON+(grid.columns()-1)*GAP, gridHeight=grid.rowsPerPage()*BUTTON+(grid.rowsPerPage()-1)*GAP;
        panelWidth=Math.max(gridWidth+2*PADDING,MIN_PANEL_WIDTH);
        // 高度 = 表头 + 表头分隔线 + 凹底上留白 + 按键区 + 凹底下留白 + 页脚分隔线 + 页脚。
        panelHeight=HEADER+1+WELL_PAD+gridHeight+WELL_PAD+1+FOOTER;
        panelLeft=width/2-panelWidth/2;
        panelTop=height/2-panelHeight/2;
        gridLeft=width/2-gridWidth/2;
        gridRight=width/2+gridWidth/2;

        gridTop=panelTop+HEADER+1+WELL_PAD; // 让开表头那条分隔线，并给凹底留出上边距
        gridBottom=gridTop+gridHeight;

        // 铺按钮：k = 本页内序号，0 是右下角；行列位置由 PanelLayout 给出（从右往左、从下往上）。
        int first=PanelLayout.pageStart(grid,page), last=Math.min(stops.size(),first+PanelLayout.capacity(grid));
        for(int i=first;i<last;i++) {
            int k=i-first, fromRight=PanelLayout.columnFromRight(k,grid.columns()), fromBottom=PanelLayout.rowFromBottom(k,grid.columns());
            int x=gridRight-(fromRight+1)*BUTTON-fromRight*GAP;
            int y=gridBottom-(fromBottom+1)*BUTTON-fromBottom*GAP;
            BlockPos stop=stops.get(i);
            // 按钮上只印楼层编号（i+1）；具体高度与方块坐标放进悬停提示，避免方块里塞满文字。
            String label=FloorIndicator.label(i,baseIndex); // 编号：基准层 = 1，其上 2,3…，其下 B1,B2…
            addDrawableChild(new StationButton(x,y,label,stop.getY(),stop.asLong(),
                    Text.translatable("screen.easyelevator.station",label,stop.getY()),
                    button->ClientPlayNetworking.send(new ElevatorNetworking.SelectStop(entityId,stop))));
        }
        // 页脚一行：翻页（< >）、开门、关门、完成。文案与可用状态都由原版控件机制负责，
        // 绘制统一走 FlatButton（深色扁平键），免得原版灰色长条贴图和深色面板打架。
        int footerY=gridBottom+WELL_PAD+1+(FOOTER-BUTTON)/2, left=panelLeft+PADDING;
        pagePrev=addDrawableChild(new FlatButton(left,footerY,BUTTON,BUTTON,Text.literal("<"),b->{page--;clearAndInit();},
                Text.translatable("screen.easyelevator.prev_page")));
        pageNext=addDrawableChild(new FlatButton(left+BUTTON+GAP,footerY,BUTTON,BUTTON,Text.literal(">"),b->{page++;clearAndInit();},
                Text.translatable("screen.easyelevator.next_page")));
        doorOpen=addDrawableChild(new FlatButton(left+56,footerY,44,BUTTON,Text.translatable("screen.easyelevator.open_door"),
                b->ClientPlayNetworking.send(new ElevatorNetworking.DoorCommand(entityId,true))));
        doorClose=addDrawableChild(new FlatButton(left+104,footerY,44,BUTTON,Text.translatable("screen.easyelevator.close_door"),
                b->ClientPlayNetworking.send(new ElevatorNetworking.DoorCommand(entityId,false))));
        addDrawableChild(new FlatButton(panelLeft+panelWidth-PADDING-60,footerY,60,BUTTON,Text.translatable("gui.done"),b->close()));
    }

    /** 查找面板绑定的轿厢。@return 世界/实体未就绪或 id 已不对应轿厢时返回 null，调用方需判空。 */
    private AbstractCabinEntity cabin() {
        return client!=null && client.world!=null && client.world.getEntityById(entityId) instanceof AbstractCabinEntity c ? c : null;
    }

    /**
     * 每刻检查面板是否仍然有效。副作用：轿厢消失、玩家离开世界或玩家已经不在轿厢里时自动关闭界面。
     *
     * <p>为什么不用 {@link AbstractCabinEntity#containsPassenger}：那是服务端权威的严格包围盒（下沿只留 0.14 格），
     * 而客户端上轿厢与乘客的位置来自不同时刻的网络包——电梯<b>下行</b>时乘客会比同步到的轿厢地板多落一点，
     * 相对高度瞬时跌到 0.14 格以下，于是面板刚打开就被判成"已离开轿厢"而立刻关闭（上行时只会拉高，不会触发）。
     * 这里改用面向 UI 的宽松判定 {@link #staysInside}：只在真正走出轿厢范围时才关闭。
     * 权限校验始终在服务端，本地判定宽松不会带来越权操作。
     */
    @Override
    public void tick() {
        if(client==null) return;
        var cabin=cabin();
        if(cabin==null || client.player==null || !staysInside(cabin,client.player)) close();
    }

    /**
     * UI 用的宽松"还在轿厢里"判定：水平 ±1.5 格、相对轿厢底部 -0.6..+2.9 格（单位：格）。
     *
     * <p>下界取 -0.6 是为了容忍下行时乘客比轿厢地板低一点（同步延迟 + 客户端重力）；
     * 上界 2.9 与轿厢净高一致，站在轿厢顶上或相邻楼层都会落在范围外。
     *
     * @param cabin 轿厢
     * @param player 本机玩家
     * @return 判定为"仍可作为面板操作者"时为 true
     */
    private static boolean staysInside(AbstractCabinEntity cabin,PlayerEntity player) {
        return !player.isSpectator() && !player.hasVehicle()
                && Math.abs(player.getX()-cabin.getX())<=KEEP_HORIZONTAL
                && Math.abs(player.getZ()-cabin.getZ())<=KEEP_HORIZONTAL
                && player.getY()>=cabin.getY()-KEEP_BELOW
                && player.getY()<=cabin.getY()+KEEP_ABOVE;
    }

    /**
     * 按轿厢实时状态刷新"开门/关门"键与翻页键的可用性。
     *
     * <p>纯客户端提示，只为避免点了必然失败；服务端仍会重新判定并可能回一条提示消息。
     * <b>开门键的判据与服务端共用 {@link ElevatorController#canOpenDoor}</b>，因此按钮亮着点了就一定会被受理：
     * <ul>
     *   <li>正常停靠（停在某一层）时可用——已经全开时按下只是续满停留时间，相当于按住开门键；</li>
     *   <li>故障脱困（{@link ElevatorStatus#faulted}，例如断轨或被卡在两层之间）时也可用，
     *       让被困的乘客能自己开门走出来；</li>
     *   <li>本线路一扇完整的门都没有（{@code stops} 为空，例如轿厢刚放到轨道上还没建门）时也可用——
     *       这辆车永远不会动，门关着就必须能开出来；</li>
     *   <li>运行途中一律不可用——运行时门会重新变灰，防止半空开门。</li>
     * </ul>
     * 关门键在门处于打开或开门过程中可用。
     *
     * <p><b>两个事实都由这里算好，不能改让 {@code ElevatorController} 去查世界</b>：
     * 客户端的 {@code AbstractCabinEntity.railX/railZ} 不进 DataTracker（恒 0），
     * {@code cabin.line()} 会去扫 (0, y, 0) 并返回 null，于是"没有线路"那一支会永远成立，
     * 开门键从此常亮、点下去却被服务端拒绝。站点列表用服务端在 {@code OpenPanel} 里下发的 {@code stops}
     * 即可——它本来就是"这条线路上现在有哪些站点"的权威答案，还省掉了每帧扫一次世界。
     *
     * @param cabin 面板绑定的轿厢；为 null（实体暂时未同步）时两个键都禁用
     */
    private void updateDoorButtons(AbstractCabinEntity cabin) {
        boolean atStation=false;
        if(cabin!=null) for(BlockPos stop:stops) if(parkedAt(cabin,stop.getY())) { atStation=true; break; }
        // 开门：停稳在某一层、处于故障、或这条线路上一扇门都没有（与服务端的 canOpenDoor 是同一条判据）
        if(doorOpen!=null) doorOpen.active=cabin!=null && ElevatorController.canOpenDoor(
                cabin.phase(),cabin.hasTarget()?cabin.targetY():Integer.MIN_VALUE,atStation,!stops.isEmpty());
        // 关门：门处于打开或开门过程中（此时必然已经停稳）
        if(doorClose!=null) doorClose.active=cabin!=null && stopped(cabin)
                && (cabin.phase()==ElevatorController.Phase.OPEN || cabin.phase()==ElevatorController.Phase.OPENING);
        if(pagePrev!=null) pagePrev.active=page>0;
        if(pageNext!=null) pageNext.active=page+1<grid.pageCount();
    }

    /**
     * 轿厢是否已经"停稳"（不再处于运行途中）。
     *
     * <p>为什么必须加这个条件：轿厢以 0.20 格/刻运行，站点高度是整数，因此运行时每一刻的高度都可能
     * 精确落在某个整数楼层上；只按"当前高度等于站点高度"判断，会让选站按钮在运行时经过每一层闪绿、
     * 开门键也闪一下可用。这里要求相位不是 MOVING，或者处于 MOVING 但没有目的站
     * （= 已经关好门停在本层等待下一次呼叫）。
     *
     * @param cabin 轿厢
     * @return 已经停稳时为 true
     */
    private static boolean stopped(AbstractCabinEntity cabin) {
        return cabin.phase()!=ElevatorController.Phase.MOVING || !cabin.hasTarget();
    }

    /**
     * 轿厢是否正停在这个站点上：既要高度吻合，也必须已经停稳。
     *
     * @param cabin 轿厢
     * @param stationY 站点高度（方块 Y，格）
     * @return 正停在该站点时为 true
     */
    private static boolean parkedAt(AbstractCabinEntity cabin,int stationY) {
        return stopped(cabin) && Math.abs(cabin.getY()-stationY)<=ElevatorParameters.SYNC_POSITION_EPSILON;
    }

    /**
     * 渲染面板：全屏浅遮罩 → 面板底与外框 → 表头条（标题、楼层指示牌、状态/页数）→ 按键区凹底
     * → 页脚条与分隔线 → 最后交给 super 画按钮。
     *
     * @param mouseX 鼠标 X（像素，屏幕坐标）
     * @param mouseY 鼠标 Y（像素，屏幕坐标）
     * @param delta 渲染插值系数（0..1）
     * 副作用：仅绘制；状态行每帧读取轿厢的 Y（格）与 Phase，因此会随服务端同步刷新。
     */
    @Override
    public void render(DrawContext context,int mouseX,int mouseY,float delta) {
        var cabin=cabin();
        updateDoorButtons(cabin); // 可用性跟着轿厢状态走，因此放在 render 里每帧刷新
        renderBackground(context,mouseX,mouseY,delta);
        // 面板底：不透明深色（早先用半透明时，井道与轿厢内面板的红字会从底下透出来）+ 1 像素外框。
        context.fill(panelLeft,panelTop,panelLeft+panelWidth,panelTop+panelHeight,PANEL_COLOR);
        drawFrame(context,panelLeft,panelTop,panelWidth,panelHeight,BORDER_COLOR);
        // 表头条：比面板底略亮，让"显示区"和按键区分开；标题居中放最上，其下是状态与页数两行。
        context.fill(panelLeft+1,panelTop+1,panelLeft+panelWidth-1,panelTop+HEADER,BAR_COLOR);
        context.drawCenteredTextWithShadow(textRenderer,title,panelLeft+panelWidth/2,panelTop+6,0xFFEAF2FA);
        context.fill(panelLeft+1,panelTop+HEADER,panelLeft+panelWidth-1,panelTop+HEADER+1,LINE_COLOR);
        // 楼层指示牌：内凹的"数码管"（外框 → 面板 → 最深底），层号放大 2 倍、只显示数字本身。
        int floor=cabin==null?0:cabin.floorNumber();
        int plaqueLeft=panelLeft+PADDING, plaqueTop=panelTop+PLAQUE_TOP;
        context.fill(plaqueLeft,plaqueTop,plaqueLeft+PLAQUE_W,plaqueTop+PLAQUE_H,BORDER_COLOR);
        context.fill(plaqueLeft+1,plaqueTop+1,plaqueLeft+PLAQUE_W-1,plaqueTop+PLAQUE_H-1,0xFF2B3542);
        context.fill(plaqueLeft+2,plaqueTop+2,plaqueLeft+PLAQUE_W-2,plaqueTop+PLAQUE_H-2,FLOOR_PANEL_COLOR);
        context.getMatrices().push();
        // 以指示牌中心为原点放大；缩放后仍按"文字宽度的一半"定位，保证严格居中。
        context.getMatrices().translate(plaqueLeft+PLAQUE_W/2f,plaqueTop+(PLAQUE_H-textRenderer.fontHeight*FLOOR_TEXT_SCALE)/2f,0);
        context.getMatrices().scale(FLOOR_TEXT_SCALE,FLOOR_TEXT_SCALE,1f);
        // 不带阴影：带阴影时字会整体偏右下 1 像素，放大 2 倍后看起来就是"没居中"。
        // 深底红字本身对比度足够，这里用手动居中的无阴影绘制。
        Text floorLabel=Text.literal(FloorIndicator.format(floor));
        context.drawText(textRenderer,floorLabel,-textRenderer.getWidth(floorLabel)/2,0,FLOOR_TEXT_COLOR,false);
        context.getMatrices().pop();
        // 右侧剩余区域的水平中心：状态行与页数行都对齐到这里，避免和左边放大的指示牌挤在一起。
        int textCenter=plaqueLeft+PLAQUE_W+(panelLeft+panelWidth-PADDING-plaqueLeft-PLAQUE_W)/2;
        if(cabin!=null) {
            // 状态行："Y 坐标（格，保留 1 位小数）+ Phase 翻译键 phase.easyelevator.<小写阶段名>"。
            // Locale.ROOT 保证小数点是 '.'、阶段键不随语言环境变形（翻译键必须与语言文件一致）。
            Text status=Text.translatable("screen.easyelevator.status",String.format(java.util.Locale.ROOT,"%.1f",cabin.getY()),
                    Text.translatable("phase.easyelevator."+cabin.phase().name().toLowerCase(java.util.Locale.ROOT)));
            context.drawCenteredTextWithShadow(textRenderer,status,textCenter,panelTop+STATUS_Y,0xC9DAEA);
        }
        // 页数行：总站点数、当前页（1 基）、总页数。
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("screen.easyelevator.count",stops.size(),page+1,grid.pageCount()),textCenter,panelTop+COUNT_Y,0x8FA0B3);
        // 按键区凹底：只比按键块四周各宽 WELL_PAD 像素，把数字键"托"起来——横贯整块面板会多出一条突兀的黑带。
        // 只填充、不再给凹底描边：描边会和上下两条分隔线贴在一起，看起来像叠了三层线，显得臃肿。
        int wellLeft=gridLeft-WELL_PAD, wellTop=gridTop-WELL_PAD, wellRight=gridRight+WELL_PAD, wellBottom=gridBottom+WELL_PAD;
        context.fill(wellLeft,wellTop,wellRight,wellBottom,WELL_COLOR);
        // 页脚条：与表头同色，放翻页/开门/关门/完成。
        context.fill(panelLeft+1,wellBottom,panelLeft+panelWidth-1,wellBottom+1,LINE_COLOR);
        context.fill(panelLeft+1,wellBottom+1,panelLeft+panelWidth-1,panelTop+panelHeight-1,BAR_COLOR);
        if(stops.isEmpty()) context.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.easyelevator.empty"),
                panelLeft+panelWidth/2,gridTop+BUTTON/2,0xFFC27A);
        super.render(context,mouseX,mouseY,delta);
    }

    /**
     * 画 1 像素矩形外框（四条 fill 拼成）：比整块 fill 再挖空更直观，也避免多一次全屏填充。
     *
     * @param context 绘制上下文
     * @param x 左上角 X
     * @param y 左上角 Y
     * @param w 宽（像素）
     * @param h 高（像素）
     * @param color 颜色（ARGB）
     */
    private static void drawFrame(DrawContext context,int x,int y,int w,int h,int color) {
        context.fill(x,y,x+w,y+1,color);
        context.fill(x,y+h-1,x+w,y+h,color);
        context.fill(x,y,x+1,y+h,color);
        context.fill(x+w-1,y,x+w,y+h,color);
    }

    /** 覆盖原版背景绘制。副作用：仅用一次全屏浅色叠加代替原版模糊着色器。
     * 为什么这样做：Screen.renderBackground 会应用原版模糊（blur）后处理，会把世界连同文字一起糊掉；
     * 这里只做 0x18000000 的淡黑叠加，既压暗背景又保持世界与文字清晰，也不会二次模糊已经画好的标题/状态文字。
     */
    @Override
    public void renderBackground(DrawContext context,int mouseX,int mouseY,float delta) {
        // Screen.renderBackground applies the vanilla blur shader. A light tint keeps the world
        // and its text sharp, and cannot blur title/status text already drawn by another pass.
        context.fill(0,0,width,height,0x18000000);
    }

    /** @return false，电梯运行期间不暂停单人游戏世界，否则轿厢会在面板打开时停住。 */
    @Override
    public boolean shouldPause() { return false; }

    /**
     * 面板上的扁平按钮（翻页 / 开门 / 关门 / 完成共用）：1 像素描边 + 深色底面 + 顶部一道高光。
     *
     * <p>为什么不用原版 ButtonWidget 的默认外观：原版是 200×20 的三段式灰色贴图，压在深色面板上像贴了
     * 一张灰纸；这里统一画成与楼层键同族的扁平键，禁用时压暗并变灰，悬停/聚焦时点亮。
     * 点击音效、悬停提示、键盘朗读与禁用判定仍由 {@link ButtonWidget} 提供。
     */
    private final class FlatButton extends ButtonWidget {
        /**
         * @param x 左上角 X（像素）
         * @param y 左上角 Y（像素）
         * @param w 宽（像素）
         * @param h 高（像素）
         * @param message 按钮文字（翻页为 `&lt;` `&gt;`，其余为翻译键）
         * @param onPress 点击回调
         * @param tooltip 悬停提示
         */
        FlatButton(int x,int y,int w,int h,Text message,PressAction onPress,Text tooltip) {
            super(x,y,w,h,message,onPress,DEFAULT_NARRATION_SUPPLIER);
            setTooltip(Tooltip.of(tooltip));
        }

        /**
         * 无提示的扁平按钮（"完成"用）。
         *
         * @param x 左上角 X（像素）
         * @param y 左上角 Y（像素）
         * @param w 宽（像素）
         * @param h 高（像素）
         * @param message 按钮文字
         * @param onPress 点击回调
         */
        FlatButton(int x,int y,int w,int h,Text message,PressAction onPress) {
            super(x,y,w,h,message,onPress,DEFAULT_NARRATION_SUPPLIER);
        }

        /**
         * 画按钮本体（不调用父类，避免再叠一层原版长条贴图）。
         *
         * @param ctx 绘制上下文
         * @param mouseX 鼠标 X（像素）
         * @param mouseY 鼠标 Y（像素）
         * @param delta 渲染插值系数
         */
        @Override
        protected void renderWidget(DrawContext ctx,int mouseX,int mouseY,float delta) {
            boolean lit=active&&(isHovered()||isFocused());
            int x=getX(), y=getY(), w=getWidth(), h=getHeight();
            ctx.fill(x,y,x+w,y+h, !active?0xFF2A3441:lit?0xFFE8F1F8:0xFF5A6A7B);
            ctx.fill(x+1,y+1,x+w-1,y+h-1, !active?0xFF1A212B:lit?0xFF41566C:0xFF2B3542);
            if(h>6) ctx.fill(x+2,y+2,x+w-2,y+3, lit?0x30FFFFFF:0x14FFFFFF); // 顶部高光：一像素就够，别抢楼层键的层次
            ctx.drawCenteredTextWithShadow(textRenderer,getMessage(),x+w/2,y+(h-8)/2,
                    !active?0xFF7C8896:lit?0xFFFFFFFF:0xD5E2EE);
        }
    }

    /**
     * 电梯面板样式的方形楼层按键：一个方块 + 中间的数字。
     *
     * <p>配色优先级：悬停/键盘聚焦（点亮为白色）&gt; 已加入停靠计划（红色）&gt; 轿厢当前停靠层（绿色）&gt; 普通灰色。
     * 为什么不用原版 ButtonWidget 的默认外观：原版按钮是 200×20 的三段式贴图，压成 20×20 后左右边框挤在一起，
     * 看起来像被压扁的长条；这里直接画描边 + 底色 + 数字，更接近真实面板上的按键，以后想换成贴图也只需要
     * 改本类的 {@link #renderWidget}。点击音效、悬停提示、键盘朗读等行为仍由 {@link ButtonWidget} 提供。
     */
    private final class StationButton extends ButtonWidget {
        private final String label;  // 楼层编号文本（基准层 1、其上 2,3…、其下 B1,B2…），同时是按钮上的字
        private final int stationY; // 该站点高度（方块 Y，格），用于判断"轿厢当前停靠层"
        private final long packed;  // 站点根方块的打包坐标，用于与停靠计划比对

        /**
         * @param x 按钮左上角 X（像素）
         * @param y 按钮左上角 Y（像素）
         * @param label 楼层编号文本（例如 "1"、"B2"），印在按钮上
         * @param stationY 站点高度（方块 Y，格），用于当前层高亮与提示
         * @param packed 站点根方块的打包坐标（{@code BlockPos.asLong()}），用于停靠计划高亮
         * @param tooltip 悬停提示（楼层编号 + 具体高度）
         * @param onPress 点击回调：发送 SelectStop 请求
         */
        StationButton(int x,int y,String label,int stationY,long packed,Text tooltip,PressAction onPress) {
            super(x,y,BUTTON,BUTTON,Text.literal(label),onPress,DEFAULT_NARRATION_SUPPLIER);
            this.label=label; this.stationY=stationY; this.packed=packed;
            setTooltip(Tooltip.of(tooltip));
        }

        /** @return 轿厢是否正停在这一层（必须已停稳：运行途中经过某一层不算，也不会闪一下绿色）。 */
        private boolean isCurrentFloor() {
            AbstractCabinEntity cabin=cabin();
            return cabin!=null && parkedAt(cabin,stationY);
        }

        /** @return 这一站是否已经在停靠计划里（目的站或排队中），服务端每次变化都会推送。 */
        private boolean isPlanned() { return planned.contains(packed); }

        /**
         * 画方形按键本体（不调用父类，避免再叠一层原版长条贴图）。
         *
         * @param ctx 绘制上下文
         * @param mouseX 鼠标 X（像素）
         * @param mouseY 鼠标 Y（像素）
         * @param delta 渲染插值系数
         */
        @Override
        protected void renderWidget(DrawContext ctx,int mouseX,int mouseY,float delta) {
            boolean lit=isHovered()||isFocused(), queued=isPlanned(), current=isCurrentFloor();
            int x=getX(), y=getY(), s=getWidth();
            // 三层嵌套色块 = 描边 + 底面 + 内凹面，形成按键的立体感；配色与面板深色底协调。
            // 计划中的站点整体偏红（含底色与文字）；当前层用绿色描边 + 冷绿底面，两种高亮互不遮盖。
            ctx.fill(x,y,x+s,y+s, lit?0xFFE8F1F8:queued?0xFFE08585:current?0xFF7FCB93:0xFF5A6A7B);
            ctx.fill(x+1,y+1,x+s-1,y+s-1, lit?0xFF54697F:queued?0xFF3E2A2E:current?0xFF23392D:0xFF2B3542);
            ctx.fill(x+2,y+2,x+s-2,y+s-2, lit?0xFF41566C:queued?0xFF33222A:current?0xFF1B2C23:0xFF212A36);
            ctx.drawCenteredTextWithShadow(textRenderer,getMessage(),x+s/2,y+(s-8)/2,
                    lit?0xFFFFFF:queued?0xFFFFD9D9:current?0xFFCFF5DA:0xC3D5E8);
        }

        /** @return 键盘朗读用的说明：楼层编号 + 该站高度，和悬停提示一致。 */
        @Override
        public void appendClickableNarrations(net.minecraft.client.gui.screen.narration.NarrationMessageBuilder builder) {
            builder.put(net.minecraft.client.gui.screen.narration.NarrationPart.TITLE,
                    Text.translatable("screen.easyelevator.station",label,stationY));
        }
    }
}
