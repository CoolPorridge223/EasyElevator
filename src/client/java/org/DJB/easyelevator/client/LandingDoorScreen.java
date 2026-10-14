package org.DJB.easyelevator.client;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.DJB.easyelevator.network.ElevatorNetworking;

import java.util.function.BooleanSupplier;

/**
 * 楼层门上的<b>厅外呼叫面板</b>：20×20 的方形按钮竖排成一列——有效的方向键（▲ / ▼）与关闭键（×）。
 *
 * <p><b>端站只有一个方向</b>：最底层下面没有站点，面板只显示 ▲；最顶层上面没有站点，只显示 ▼；
 * 中间层两个方向都显示（由服务端在 {@link ElevatorNetworking.OpenHallPanel} 里给出）。
 * 这样玩家不会按到"注定没意义"的方向键。
 *
 * <p>为什么不直接呼叫（旧版"右键门 = 呼叫本层"）：真实电梯的厅外按钮分上行与下行，方向决定调度
 * （上行呼叫只由正在上行的轿厢顺路接走，见 {@code logic/ElevatorController}），
 * 因此必须先让玩家选方向。按下按钮后只发一个 {@link ElevatorNetworking.HallCallButton} 请求，
 * 服务端重新校验线路并登记呼叫，随后用 {@link ElevatorNetworking.HallPanelState} 把该站最新的点亮状态回推，
 * 按钮随即<b>变红</b>；呼叫会一直保留到轿厢真的到站开门，那一刻服务端再次回推，按钮恢复原色。
 *
 * <p>在整体架构中的位置：纯客户端 UI，与服务端之间只往返"请求 + 点亮状态"。面板内容（哪个方向可点、
 * 哪个方向还亮着）全部来自服务端下发的包，客户端不推导、不缓存线路，因此关掉再打开、或别人按过按钮之后
 * 再打开，看到的都是真实状态。面板打开时<b>不暂停游戏</b>（{@link #shouldPause()} 返回 false）。
 *
 * <p><b>1.19.2 差异</b>：绘制入口是 {@link MatrixStack} 而非 1.20 的 {@code DrawContext}；
 * 控件也没有 {@code Builder} 与 {@code setTooltip(Tooltip)}，悬停提示在构造时以
 * {@link ButtonWidget.TooltipSupplier} 传入（见 {@link #tooltip}）。
 */
public class LandingDoorScreen extends Screen {
    /** 方形按钮边长（像素）、按钮间距（像素）、面板内边距（像素）。三个键同尺寸，整列才齐。 */
    private static final int BUTTON=20, GAP=6, PADDING=10;
    /** 面板底色与提示文字颜色：与轿厢内选站面板保持同一套深色配色。 */
    private static final int PANEL_COLOR=0xEC171E29, HINT_COLOR=0xA7B4C5;
    /** 本面板绑定的站点（楼层门根方块）；客户端收到点亮状态时据此判断"这份状态是不是本面板的"。 */
    private final BlockPos station;
    /** 是否显示上 / 下行按钮：端站只显示有意义的那一个（服务端计算，见 OpenHallPanel）。 */
    private final boolean showUp, showDown;
    /** 上行 / 下行按钮当前是否已被登记（= 服务端仍有这条呼叫）：为 true 时按钮画成红色。 */
    private boolean upPending, downPending;
    private int panelLeft, panelTop, panelWidth, panelHeight;

    /**
     * 由服务端下发的 {@link ElevatorNetworking.OpenHallPanel} 构造面板。
     *
     * @param payload 包内容：站点坐标、两个方向当前的点亮状态、以及两个方向是否显示（端站只显示一个）
     */
    public LandingDoorScreen(ElevatorNetworking.OpenHallPanel payload) {
        super(Text.translatable("screen.easyelevator.hall_title"));
        this.station=payload.station();
        this.upPending=payload.up(); this.downPending=payload.down();
        this.showUp=payload.showUp(); this.showDown=payload.showDown();
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
     * 构建控件：按"这一站有哪些方向"铺 1~2 个方向键，末尾固定跟一个关闭键，全部同尺寸竖排。
     *
     * <p>副作用：向屏幕添加控件；不发包、不改世界。窗口尺寸变化时由原版重新调用。
     */
    @Override
    protected void init() {
        int buttons=(showUp?1:0)+(showDown?1:0)+1; // 方向键（1~2 个）+ 关闭键
        panelWidth=BUTTON+2*PADDING;
        panelHeight=buttons*BUTTON+(buttons-1)*GAP+2*PADDING;
        panelLeft=width/2-panelWidth/2;
        panelTop=height/2-panelHeight/2;
        int left=width/2-BUTTON/2, top=panelTop+PADDING, row=0;
        if(showUp) addDrawableChild(new SquareButton(left,top+row++*(BUTTON+GAP),"▲",() -> upPending,
                Text.translatable("screen.easyelevator.hall_up"),
                b->ClientPackets.sendClient(new ElevatorNetworking.HallCallButton(station,true))));
        if(showDown) addDrawableChild(new SquareButton(left,top+row++*(BUTTON+GAP),"▼",() -> downPending,
                Text.translatable("screen.easyelevator.hall_down"),
                b->ClientPackets.sendClient(new ElevatorNetworking.HallCallButton(station,false))));
        // 关闭键：与方向键完全同款同尺寸（只有一个 × 字符，不再用原版长条按钮）。
        addDrawableChild(new SquareButton(left,top+row*(BUTTON+GAP),"×",() -> false,
                Text.translatable("screen.easyelevator.close"),b->close()));
    }

    /**
     * 绘制面板：深色底 + 标题 + 站点高度提示，再交给 super 画按钮。
     *
     * <p>1.19.2 的 {@code Screen.render} 会自己调用 {@code renderBackground}，这里不再重复铺遮罩。
     *
     * @param matrices 渲染矩阵栈
     * @param mouseX 鼠标 X（像素）
     * @param mouseY 鼠标 Y（像素）
     * @param delta 渲染插值系数
     * 副作用：仅绘制；不读世界、不发包。
     */
    @Override
    public void render(MatrixStack matrices,int mouseX,int mouseY,float delta) {
        fill(matrices,panelLeft,panelTop,panelLeft+panelWidth,panelTop+panelHeight,PANEL_COLOR);
        centered(matrices,textRenderer,title,width/2,panelTop-PADDING-10,0xFFFFFF);
        centered(matrices,textRenderer,
                Text.translatable("screen.easyelevator.hall_station",station.getY()),
                width/2,panelTop+panelHeight+PADDING-4,HINT_COLOR);
        super.render(matrices,mouseX,mouseY,delta);
    }

    /** 覆盖原版背景绘制：只用一次淡黑叠加代替模糊着色器，保持世界画面清晰（与选站面板一致）。
     * @param matrices 渲染矩阵栈
     */
    @Override
    public void renderBackground(MatrixStack matrices) {
        fill(matrices,0,0,width,height,0x18000000);
    }

    /** @return false：等车期间不暂停单人游戏世界，否则轿厢会停在半路。 */
    @Override
    public boolean shouldPause() { return false; }

    /**
     * 画一行水平居中、带阴影的文字。
     *
     * <p>1.19.2 的 {@code DrawableHelper} 只有收 {@link net.minecraft.text.OrderedText} 的居中重载，
     * 没有 1.20 起那个直接收 {@link Text} 的版本；而 {@code TextRenderer.draw(MatrixStack, Text, ...)}
     * 两个版本都有。因此统一包一层，画法与原版 {@code drawCenteredTextWithShadow} 完全一致。
     *
     * @param matrices 渲染矩阵栈
     * @param renderer 文本渲染器
     * @param text 这一行的文本
     * @param centerX 水平中心（像素）
     * @param y 这一行的顶边（像素）
     * @param color 颜色（ARGB）
     */
    private static void centered(MatrixStack matrices, net.minecraft.client.font.TextRenderer renderer, Text text,
                                 int centerX, int y, int color) {
        renderer.draw(matrices, text, centerX - renderer.getWidth(text) / 2, y, color);
    }

    /**
     * 做成 1.19.2 控件能接受的悬停提示供应商。
     *
     * <p>1.19.2 的 {@code ClickableWidget} 没有 {@code setTooltip(Tooltip)}，提示只能在构造时以
     * {@link ButtonWidget.TooltipSupplier} 交出；而供应商要自己把提示画出来。这里按原版
     * "鼠标右下角、必要时往左 / 往上躲"的规则定位，再交给本屏幕的
     * {@link Screen#renderTooltip(MatrixStack, Text, int, int)}——刻意绕开
     * {@code ButtonWidget.renderTooltip} 的默认实现，因为它依赖内部未初始化的提示字段。
     *
     * @param tip 固定的一句提示文本
     * @return 供按钮使用的提示供应商
     */
    ButtonWidget.TooltipSupplier tooltip(Text tip) {
        return (button, matrices, mouseX, mouseY) -> {
            var lines = java.util.List.of(tip);
            int tipWidth = 0;
            for (Text line : lines) tipWidth = Math.max(tipWidth, textRenderer.getWidth(line));
            int tipX = mouseX + 12;
            if (tipX + tipWidth > width - 4) tipX = mouseX - 16 - tipWidth;
            int tipY = mouseY - 12;
            if (tipY + lines.size() * 10 > height - 4) tipY = height - 4 - lines.size() * 10;
            renderTooltip(matrices, lines, tipX, tipY);
        };
    }

    /**
     * 面板上的方形按键（方向键与关闭键共用）：一个方块 + 中间的字符（▲ / ▼ / ×）。
     *
     * <p>配色与轿厢内选站面板的楼层键完全一致：普通灰、悬停点亮为白、<b>已被登记的呼叫为红</b>
     * （红 = "服务端还留着这条呼叫"，由服务端推送，因此反映的是真实调度状态而不是本地点击）。
     * 关闭键的 {@code pending} 恒为 false，因此永远是灰/悬停白两种状态。
     */
    private final class SquareButton extends ButtonWidget {
        /** 该键"点亮/变红"的判据：方向键读服务端推送的状态，关闭键恒为 false。 */
        private final BooleanSupplier pending;
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
        SquareButton(int x,int y,String glyph,BooleanSupplier pending,Text tooltip,PressAction onPress) {
            super(x,y,BUTTON,BUTTON,Text.literal(glyph),onPress,LandingDoorScreen.this.tooltip(tooltip));
            this.pending=pending; this.narration=tooltip;
        }

        /**
         * 画方形按键本体（不调用父类，避免再叠一层原版长条贴图）。
         *
         * @param matrices 渲染矩阵栈
         * @param mouseX 鼠标 X（像素）
         * @param mouseY 鼠标 Y（像素）
         * @param delta 渲染插值系数
         */
        @Override
        public void renderButton(MatrixStack matrices,int mouseX,int mouseY,float delta) {
            boolean lit=isHovered()||isFocused(), queued=pending.getAsBoolean();
            int bx=x, by=y, s=getWidth();
            // 三层嵌套色块 = 描边 + 底面 + 内凹面；配色与选站面板的楼层键同源。
            fill(matrices,bx,by,bx+s,by+s, lit?0xFFE8F1F8:queued?0xFFE08585:0xFF6B7A88);
            fill(matrices,bx+1,by+1,bx+s-1,by+s-1, lit?0xFF54697F:queued?0xFF3E2A2E:0xFF2B3542);
            fill(matrices,bx+2,by+2,bx+s-2,by+s-2, lit?0xFF41566C:queued?0xFF33222A:0xFF212A36);
            centered(matrices,textRenderer,getMessage(),bx+s/2,by+(s-8)/2, lit?0xFFFFFF:queued?0xFFFFD9D9:0xC3D5E8);
        }

        /** @return 键盘朗读用的说明：按钮当前的提示文本（方向或"关闭"）。 */
        @Override
        public void appendNarrations(NarrationMessageBuilder builder) {
            builder.put(NarrationPart.TITLE,narration);
        }
    }
}
