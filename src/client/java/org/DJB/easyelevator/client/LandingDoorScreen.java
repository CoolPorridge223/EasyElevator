package org.DJB.easyelevator.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.DJB.easyelevator.network.ElevatorNetworking;

/**
 * 楼层门上的<b>厅外呼叫面板</b>：三个 20×20 方形按钮竖排成一列——向上三角、向下三角、关闭（×）。
 *
 * <p>为什么不直接呼叫（旧版"右键门 = 呼叫本层"）：真实电梯的厅外按钮分上行与下行，方向决定调度
 * （上行呼叫只由正在上行的轿厢顺路接走，见 {@code logic/ElevatorController}），
 * 因此必须先让玩家选方向。按下按钮后只发一个 {@link ElevatorNetworking.HallCallButton} 请求，
 * 服务端重新校验线路并登记呼叫，随后用 {@link ElevatorNetworking.HallPanelState} 把该站最新的点亮状态回推，
 * 按钮随即<b>变红</b>；呼叫会一直保留到轿厢真的到站开门，那一刻服务端再次回推，按钮恢复原色。
 *
 * <p>在整体架构中的位置：纯客户端 UI，与服务端之间只往返"请求 + 点亮状态"。面板内容（哪个方向还亮着）
 * 全部来自服务端下发的包，客户端不推导、不缓存线路，因此关掉再打开、或别人按过按钮之后再打开，
 * 看到的都是真实状态。面板打开时<b>不暂停游戏</b>（{@link #shouldPause()} 返回 false）。
 *
 * <p>三个按键共用同一套自绘外观（{@link SquareButton}）：与原版三段式按钮贴图无关，
 * 因此不会有"长条贴图被压进小方块"的臃肿感；关闭键与两个方向键同样大小，整列等宽等高。
 */
public class LandingDoorScreen extends Screen {
    /** 方形按钮边长（像素）、按钮间距（像素）、面板内边距（像素）。三者都用同一尺寸，整列才齐。 */
    private static final int BUTTON=20, GAP=6, PADDING=10;
    /** 面板底色与提示文字颜色：与轿厢内选站面板保持同一套深色配色。 */
    private static final int PANEL_COLOR=0xEC171E29, HINT_COLOR=0xA7B4C5;
    /** 本面板绑定的站点（楼层门根方块）；客户端收到点亮状态时据此判断"这份状态是不是本面板的"。 */
    private final BlockPos station;
    /** 上行 / 下行按钮当前是否已被登记（= 服务端仍有这条呼叫）：为 true 时按钮画成红色。 */
    private boolean upPending, downPending;
    private int panelLeft, panelTop, panelWidth, panelHeight;

    /**
     * 由服务端下发的 {@link ElevatorNetworking.OpenHallPanel} 构造面板。
     *
     * @param payload 包内容：站点坐标 + 两个方向当前的点亮状态
     */
    public LandingDoorScreen(ElevatorNetworking.OpenHallPanel payload) {
        super(Text.translatable("screen.easyelevator.hall_title"));
        this.station=payload.station(); this.upPending=payload.up(); this.downPending=payload.down();
    }

    /** @return 本面板绑定的站点（楼层门根方块）；客户端收到点亮状态时据此判断"这份状态是不是本面板的" */
    public BlockPos station() { return station; }

    /**
     * 应用服务端下发的点亮状态（{@link ElevatorNetworking.HallPanelState}）。
     *
     * <p>只改两个布尔、不重建控件：按钮外观在每帧绘制时读取最新状态，因此不会打断玩家正在进行的点击。
     *
     * @param up 上行按钮是否点亮
     * @param down 下行按钮是否点亮
     */
    public void applyState(boolean up,boolean down) { upPending=up; downPending=down; }

    /**
     * 构建控件：上行、下行、关闭三个同尺寸方块，自上而下排成一列。
     *
     * <p>副作用：向屏幕添加控件；不发包、不改世界。窗口尺寸变化时由原版重新调用。
     */
    @Override
    protected void init() {
        panelWidth=BUTTON+2*PADDING;
        panelHeight=3*BUTTON+2*GAP+2*PADDING;
        panelLeft=width/2-panelWidth/2;
        panelTop=height/2-panelHeight/2;
        int left=width/2-BUTTON/2, top=panelTop+PADDING;
        addDrawableChild(new SquareButton(left,top,"▲",() -> upPending,
                Text.translatable("screen.easyelevator.hall_up"),
                b->ClientPlayNetworking.send(new ElevatorNetworking.HallCallButton(station,true))));
        addDrawableChild(new SquareButton(left,top+BUTTON+GAP,"▼",() -> downPending,
                Text.translatable("screen.easyelevator.hall_down"),
                b->ClientPlayNetworking.send(new ElevatorNetworking.HallCallButton(station,false))));
        // 关闭键：与上面两个方向键完全同款同尺寸（只有一个 × 字符，不再用原版长条按钮）。
        addDrawableChild(new SquareButton(left,top+2*(BUTTON+GAP),"×",() -> false,
                Text.translatable("screen.easyelevator.close"),b->close()));
    }

    /**
     * 绘制面板：深色底 + 标题 + 站点高度提示，再交给 super 画三个按钮。
     *
     * @param context 绘制上下文
     * @param mouseX 鼠标 X（像素）
     * @param mouseY 鼠标 Y（像素）
     * @param delta 渲染插值系数
     * 副作用：仅绘制；不读世界、不发包。
     */
    @Override
    public void render(DrawContext context,int mouseX,int mouseY,float delta) {
        renderBackground(context,mouseX,mouseY,delta);
        context.fill(panelLeft,panelTop,panelLeft+panelWidth,panelTop+panelHeight,PANEL_COLOR);
        context.drawCenteredTextWithShadow(textRenderer,title,width/2,panelTop-PADDING-10,0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("screen.easyelevator.hall_station",station.getY()),
                width/2,panelTop+panelHeight+PADDING-4,HINT_COLOR);
        super.render(context,mouseX,mouseY,delta);
    }

    /** 覆盖原版背景绘制：只用一次淡黑叠加代替模糊着色器，保持世界画面清晰（与选站面板一致）。
     * @param context 绘制上下文
     * @param mouseX 鼠标 X（像素）
     * @param mouseY 鼠标 Y（像素）
     * @param delta 渲染插值系数
     */
    @Override
    public void renderBackground(DrawContext context,int mouseX,int mouseY,float delta) {
        context.fill(0,0,width,height,0x18000000);
    }

    /** @return false：等车期间不暂停单人游戏世界，否则轿厢会停在半路。 */
    @Override
    public boolean shouldPause() { return false; }

    /**
     * 面板上的方形按键（三个键共用）：一个方块 + 中间的字符（▲ / ▼ / ×）。
     *
     * <p>配色与轿厢内选站面板的楼层键完全一致：普通灰、悬停点亮为白、<b>已被登记的呼叫为红</b>
     * （红 = "服务端还留着这条呼叫"，由服务端推送，因此反映的是真实调度状态而不是本地点击）。
     * 关闭键的 {@code pending} 恒为 false，因此永远是灰/悬停白两种状态。
     */
    private final class SquareButton extends ButtonWidget {
        /** 该键"点亮/变红"的判据：方向键读服务端推送的状态，关闭键恒为 false。 */
        private final java.util.function.BooleanSupplier pending;
        /** 键盘朗读与悬停提示用的文本（"上行呼叫 / 下行呼叫 / 关闭"）。 */
        private final Text narration;

        /**
         * @param x 按钮左上角 X（像素）
         * @param y 按钮左上角 Y（像素）
         * @param glyph 按钮上的字符（▲ / ▼ / ×）
         * @param pending 该键是否处于"已登记"状态（决定是否画成红色）
         * @param tooltip 悬停提示（"上行呼叫 / 下行呼叫 / 关闭"）
         * @param onPress 点击回调：方向键发 HallCallButton，关闭键关闭界面
         */
        SquareButton(int x,int y,String glyph,java.util.function.BooleanSupplier pending,Text tooltip,PressAction onPress) {
            super(x,y,BUTTON,BUTTON,Text.literal(glyph),onPress,DEFAULT_NARRATION_SUPPLIER);
            this.pending=pending; this.narration=tooltip;
            setTooltip(Tooltip.of(tooltip));
        }

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
            boolean lit=isHovered()||isFocused(), queued=pending.getAsBoolean();
            int x=getX(), y=getY(), s=getWidth();
            // 三层嵌套色块 = 描边 + 底面 + 内凹面；配色与选站面板的楼层键同源。
            ctx.fill(x,y,x+s,y+s, lit?0xFFE8F1F8:queued?0xFFE08585:0xFF6B7A88);
            ctx.fill(x+1,y+1,x+s-1,y+s-1, lit?0xFF54697F:queued?0xFF3E2A2E:0xFF2B3542);
            ctx.fill(x+2,y+2,x+s-2,y+s-2, lit?0xFF41566C:queued?0xFF33222A:0xFF212A36);
            ctx.drawCenteredTextWithShadow(textRenderer,getMessage(),x+s/2,y+(s-8)/2, lit?0xFFFFFF:queued?0xFFFFD9D9:0xC3D5E8);
        }

        /** @return 键盘朗读用的说明：按钮当前的提示文本（方向或"关闭"）。 */
        @Override
        public void appendClickableNarrations(net.minecraft.client.gui.screen.narration.NarrationMessageBuilder builder) {
            builder.put(net.minecraft.client.gui.screen.narration.NarrationPart.TITLE,narration);
        }
    }
}
