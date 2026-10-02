package org.DJB.easyelevator.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorParameters;
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
 * <p>按键颜色：普通为灰色描边；<b>轿厢当前停靠层</b>用绿色描边；<b>已加入停靠计划</b>（正在执行的目的站
 * 或排队中的站点）用红色描边；悬停/键盘聚焦时点亮为白色。计划由服务端在变化时用
 * {@link ElevatorNetworking.PanelState} 推送（见 {@link #applyPlanned}）。
 */
public class ElevatorScreen extends Screen {
    /** 方形按钮边长（像素）与按钮间距（像素）。边长 20 与原版按钮同高，间距 4 不至于挤在一起。 */
    private static final int BUTTON=20, GAP=4;
    /** 单页最多列数与行数：再多就翻页，避免按钮小到点不准（8×8 = 单页 64 个站点）。 */
    private static final int MAX_COLUMNS=8, MAX_ROWS=8;
    /** 面板内边距，以及头部（标题/楼层牌/状态/页码）与底部（翻页/开门/关门/完成）占用的高度（像素）。 */
    private static final int PADDING=16, HEADER=64, FOOTER=40;
    /** 楼层指示牌（仿七段数码管）：深色底 + 红色数字，显示与门框顶部、轿厢内面板相同的楼层号。 */
    private static final int FLOOR_PANEL_COLOR=0xFF101820, FLOOR_TEXT_COLOR=0xFFFF4040;
    /** 面板最小宽度：底部一行要放下 `<` `>`、开门、关门与"完成"五个控件。 */
    private static final int MIN_PANEL_WIDTH=260;
    /** 面板底色：比全屏浅遮罩更实的深色，保证文字与数字按钮都清晰。 */
    private static final int PANEL_COLOR=0xEC171E29;
    /** UI 用的"还在轿厢里"判定范围（格）：水平半宽、相对轿厢底的高度下限与上限，见 {@link #staysInside}。 */
    private static final double KEEP_HORIZONTAL=1.5, KEEP_BELOW=0.6, KEEP_ABOVE=2.9;

    private final int entityId; // 面板绑定的轿厢实体 id，用于回发 SelectStop / DoorCommand
    private final List<BlockPos> stops; // 按高度升序的站点（根方块）列表：下标 0 就是"1 层"；只读
    /** 停靠计划里的站点：{@code BlockPos.asLong()} 集合，命中的按钮画红色描边。 */
    private final Set<Long> planned=new HashSet<>();
    private int page; // 当前页（0 基）；翻页时保留，越界会在 init() 里被夹回合法范围
    private PanelLayout.Grid grid=PanelLayout.grid(0,1,1); // 网格与分页参数（列数/行数/页数），init() 时按站点数与窗口尺寸重算
    private int panelLeft, panelTop, panelWidth, panelHeight; // 面板矩形（像素）：init() 计算，render() 复用
    private int gridRight, gridBottom; // 网格右下角（像素）：楼层编号 1 的按钮就落在这里
    private ButtonWidget doorOpen, doorClose; // 底部"开门/关门"键；可用状态每帧按轿厢实时状态刷新

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
     * 构建控件：按窗口尺寸算出网格与面板矩形，再铺方形楼层按钮、翻页/开门/关门与"完成"。
     * 窗口尺寸变化或翻页（clearAndInit）时会被重新调用。
     *
     * <p>副作用：向屏幕添加控件；不发包、不改世界。翻页按钮只改 page 并重建控件。
     */
    @Override
    protected void init() {
        // 列数上限由窗口宽度给出、行数上限由窗口高度给出，具体排布交给 PanelLayout（纯算术，有单测）。
        int maxColumns=Math.clamp((width-2*PADDING)/(BUTTON+GAP),1,MAX_COLUMNS);
        int maxRows=Math.clamp((height-HEADER-FOOTER-2*PADDING)/(BUTTON+GAP),1,MAX_ROWS);
        grid=PanelLayout.grid(stops.size(),maxColumns,maxRows);
        // 窗口缩小或站点减少后可能停在空页上，这里把 page 夹回合法范围。
        page=Math.clamp(page,0,grid.pageCount()-1);

        int gridWidth=grid.columns()*BUTTON+(grid.columns()-1)*GAP, gridHeight=grid.rowsPerPage()*BUTTON+(grid.rowsPerPage()-1)*GAP;
        panelWidth=Math.max(gridWidth+2*PADDING,MIN_PANEL_WIDTH);
        panelHeight=HEADER+gridHeight+FOOTER;
        panelLeft=width/2-panelWidth/2;
        panelTop=height/2-panelHeight/2;
        gridRight=width/2+gridWidth/2;
        gridBottom=panelTop+HEADER+gridHeight;

        // 铺按钮：k = 本页内序号，0 是右下角；行列位置由 PanelLayout 给出（从右往左、从下往上）。
        int first=PanelLayout.pageStart(grid,page), last=Math.min(stops.size(),first+PanelLayout.capacity(grid));
        for(int i=first;i<last;i++) {
            int k=i-first, fromRight=PanelLayout.columnFromRight(k,grid.columns()), fromBottom=PanelLayout.rowFromBottom(k,grid.columns());
            int x=gridRight-(fromRight+1)*BUTTON-fromRight*GAP;
            int y=gridBottom-(fromBottom+1)*BUTTON-fromBottom*GAP;
            BlockPos stop=stops.get(i);
            // 按钮上只印楼层编号（i+1）；具体高度与方块坐标放进悬停提示，避免方块里塞满文字。
            addDrawableChild(new StationButton(x,y,i+1,stop.getY(),stop.asLong(),
                    Text.translatable("screen.easyelevator.station",i+1,stop.getY()),
                    button->ClientPlayNetworking.send(new ElevatorNetworking.SelectStop(entityId,stop))));
        }
        // 底部一行：翻页（< >）、开门、关门、完成。翻页用 clearAndInit() 重建控件并保留 page；
        // 首/末页禁用而不是隐藏，布局保持稳定。开门/关门只发请求，真正的判定在服务端。
        int footerY=panelTop+HEADER+gridHeight+8, left=panelLeft+PADDING;
        var previous=addDrawableChild(ButtonWidget.builder(Text.literal("<"),b->{page--;clearAndInit();})
                .dimensions(left,footerY,20,20).build());
        previous.active=page>0;
        var next=addDrawableChild(ButtonWidget.builder(Text.literal(">"),b->{page++;clearAndInit();})
                .dimensions(left+24,footerY,20,20).build());
        next.active=page+1<grid.pageCount();
        doorOpen=addDrawableChild(ButtonWidget.builder(Text.translatable("screen.easyelevator.open_door"),
                b->ClientPlayNetworking.send(new ElevatorNetworking.DoorCommand(entityId,true)))
                .dimensions(left+56,footerY,44,20).build());
        doorClose=addDrawableChild(ButtonWidget.builder(Text.translatable("screen.easyelevator.close_door"),
                b->ClientPlayNetworking.send(new ElevatorNetworking.DoorCommand(entityId,false)))
                .dimensions(left+104,footerY,44,20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"),b->close())
                .dimensions(panelLeft+panelWidth-PADDING-60,footerY,60,20).build());
    }

    /** 查找面板绑定的轿厢。@return 世界/实体未就绪或 id 已不对应轿厢时返回 null，调用方需判空。 */
    private CabinEntity cabin() {
        return client!=null && client.world!=null && client.world.getEntityById(entityId) instanceof CabinEntity c ? c : null;
    }

    /**
     * 每刻检查面板是否仍然有效。副作用：轿厢消失、玩家离开世界或玩家已经不在轿厢里时自动关闭界面。
     *
     * <p>为什么不用 {@link CabinEntity#containsPassenger}：那是服务端权威的严格包围盒（下沿只留 0.14 格），
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
    private static boolean staysInside(CabinEntity cabin,PlayerEntity player) {
        return !player.isSpectator() && !player.hasVehicle()
                && Math.abs(player.getX()-cabin.getX())<=KEEP_HORIZONTAL
                && Math.abs(player.getZ()-cabin.getZ())<=KEEP_HORIZONTAL
                && player.getY()>=cabin.getY()-KEEP_BELOW
                && player.getY()<=cabin.getY()+KEEP_ABOVE;
    }

    /**
     * 按轿厢实时状态刷新"开门/关门"键的可用性。
     *
     * <p>纯客户端提示，只为避免点了必然失败；服务端仍会重新判定并可能回一条提示消息。
     * 开门键在"停在某一层"时可用（已经全开时按下只是续满停留时间，相当于按住开门键）；
     * 关门键在门处于打开或开门过程中可用。
     *
     * @param cabin 面板绑定的轿厢；为 null（实体暂时未同步）时两个键都禁用
     */
    private void updateDoorButtons(CabinEntity cabin) {
        boolean atStation=false;
        if(cabin!=null) for(BlockPos stop:stops) if(parkedAt(cabin,stop.getY())) { atStation=true; break; }
        // 开门：只有"停稳在某一层"才可用（运行途中经过楼层时不能按，也不会闪一下可用）
        if(doorOpen!=null) doorOpen.active=cabin!=null && atStation;
        // 关门：门处于打开或开门过程中（此时必然已经停稳）
        if(doorClose!=null) doorClose.active=cabin!=null && stopped(cabin)
                && (cabin.phase()==ElevatorController.Phase.OPEN || cabin.phase()==ElevatorController.Phase.OPENING);
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
    private static boolean stopped(CabinEntity cabin) {
        return cabin.phase()!=ElevatorController.Phase.MOVING || !cabin.hasTarget();
    }

    /**
     * 轿厢是否正停在这个站点上：既要高度吻合，也必须已经停稳。
     *
     * @param cabin 轿厢
     * @param stationY 站点高度（方块 Y，格）
     * @return 正停在该站点时为 true
     */
    private static boolean parkedAt(CabinEntity cabin,int stationY) {
        return stopped(cabin) && Math.abs(cabin.getY()-stationY)<=ElevatorParameters.SYNC_POSITION_EPSILON;
    }

    /**
     * 渲染面板：先画全屏浅遮罩与面板底，再画标题、状态、页码与空列表提示，最后交给 super 画按钮。
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
        context.fill(panelLeft,panelTop,panelLeft+panelWidth,panelTop+panelHeight,PANEL_COLOR);
        context.drawCenteredTextWithShadow(textRenderer,title,width/2,panelTop+8,0xFFFFFF);
        // 楼层指示牌：显示服务端每刻算好的楼层号（只在经过或到达一层时变化），没有站点时显示 --。
        // 画成深底红字的"数码管"样式，与门框顶部、轿厢内面板上的红色层号是同一个值。
        int floor=cabin==null?0:cabin.floorNumber();
        context.fill(width/2-26,panelTop+19,width/2+26,panelTop+37,FLOOR_PANEL_COLOR);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("screen.easyelevator.floor",floor>0?Integer.toString(floor):"--"),
                width/2,panelTop+24,FLOOR_TEXT_COLOR);
        if(cabin!=null) {
            // 状态行："Y 坐标（格，保留 1 位小数）+ Phase 翻译键 phase.easyelevator.<小写阶段名>"。
            // Locale.ROOT 保证小数点是 '.'、阶段键不随语言环境变形（翻译键必须与语言文件一致）。
            Text status=Text.translatable("screen.easyelevator.status",String.format(java.util.Locale.ROOT,"%.1f",cabin.getY()),
                    Text.translatable("phase.easyelevator."+cabin.phase().name().toLowerCase(java.util.Locale.ROOT)));
            context.drawCenteredTextWithShadow(textRenderer,status,width/2,panelTop+42,0xC3D5E8);
        }
        // 页码行：总站点数、当前页（1 基）、总页数。
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("screen.easyelevator.count",stops.size(),page+1,grid.pageCount()),width/2,panelTop+53,0xA7B4C5);
        if(stops.isEmpty()) context.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.easyelevator.empty"),
                width/2,panelTop+HEADER+BUTTON/2,0xFFC27A);
        super.render(context,mouseX,mouseY,delta);
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
     * 电梯面板样式的方形楼层按键：一个方块 + 中间的数字。
     *
     * <p>配色优先级：悬停/键盘聚焦（点亮为白色）&gt; 已加入停靠计划（红色）&gt; 轿厢当前停靠层（绿色）&gt; 普通灰色。
     * 为什么不用原版 ButtonWidget 的默认外观：原版按钮是 200×20 的三段式贴图，压成 20×20 后左右边框挤在一起，
     * 看起来像被压扁的长条；这里直接画描边 + 底色 + 数字，更接近真实面板上的按键，以后想换成贴图也只需要
     * 改本类的 {@link #renderWidget}。点击音效、悬停提示、键盘朗读等行为仍由 {@link ButtonWidget} 提供。
     */
    private final class StationButton extends ButtonWidget {
        private final int floor;    // 楼层编号（1 = 最底层），同时是按钮上的数字
        private final int stationY; // 该站点高度（方块 Y，格），用于判断"轿厢当前停靠层"
        private final long packed;  // 站点根方块的打包坐标，用于与停靠计划比对

        /**
         * @param x 按钮左上角 X（像素）
         * @param y 按钮左上角 Y（像素）
         * @param floor 楼层编号（1 起），印在按钮上
         * @param stationY 站点高度（方块 Y，格），用于当前层高亮与提示
         * @param packed 站点根方块的打包坐标（{@code BlockPos.asLong()}），用于停靠计划高亮
         * @param tooltip 悬停提示（楼层编号 + 具体高度）
         * @param onPress 点击回调：发送 SelectStop 请求
         */
        StationButton(int x,int y,int floor,int stationY,long packed,Text tooltip,PressAction onPress) {
            super(x,y,BUTTON,BUTTON,Text.literal(Integer.toString(floor)),onPress,DEFAULT_NARRATION_SUPPLIER);
            this.floor=floor; this.stationY=stationY; this.packed=packed;
            setTooltip(Tooltip.of(tooltip));
        }

        /** @return 轿厢是否正停在这一层（必须已停稳：运行途中经过某一层不算，也不会闪一下绿色）。 */
        private boolean isCurrentFloor() {
            CabinEntity cabin=cabin();
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
            // 计划中的站点整体偏红（含底色与文字），当前层只换描边为绿色，避免两种高亮互相盖住。
            ctx.fill(x,y,x+s,y+s, lit?0xFFE8F1F8:queued?0xFFE08585:current?0xFF9FD8A8:0xFF6B7A88);
            ctx.fill(x+1,y+1,x+s-1,y+s-1, lit?0xFF54697F:queued?0xFF3E2A2E:0xFF2B3542);
            ctx.fill(x+2,y+2,x+s-2,y+s-2, lit?0xFF41566C:queued?0xFF33222A:0xFF212A36);
            ctx.drawCenteredTextWithShadow(textRenderer,getMessage(),x+s/2,y+(s-8)/2, lit?0xFFFFFF:queued?0xFFFFD9D9:0xC3D5E8);
        }

        /** @return 键盘朗读用的说明：楼层编号 + 该站高度，和悬停提示一致。 */
        @Override
        public void appendClickableNarrations(net.minecraft.client.gui.screen.narration.NarrationMessageBuilder builder) {
            builder.put(net.minecraft.client.gui.screen.narration.NarrationPart.TITLE,
                    Text.translatable("screen.easyelevator.station",floor,stationY));
        }
    }
}
