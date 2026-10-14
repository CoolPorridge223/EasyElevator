package org.DJB.easyelevator.client;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import org.DJB.easyelevator.logic.CargoLoad;
import org.DJB.easyelevator.screen.CargoScreenHandler;

/**
 * 重载轿厢货舱的客户端界面：27 格货舱 + 玩家背包，沿用电梯面板的深色配色，且**不调用原版模糊**。
 *
 * <p>职责边界：本类<b>只画</b>，不含任何规则。件数、限载人数、每格上限都由 {@link CargoScreenHandler}
 * （即服务端）给出，这里只是把它们显示出来；因此面板上的数字永远等于服务端真正在用的数字。
 *
 * <p>布局常量与原版大箱子同源，且必须与 {@code CargoScreenHandler} 的槽位坐标配套：
 * 背景 176×210（比大箱子高 44 像素，用来放表头那三行），背包标题画在 y=116，
 * 槽位本身由原版 {@code HandledScreen} 按 {@code handler.slots} 的坐标绘制，
 * 本类只负责在它们背后铺一层凹槽底（连玩家背包的格子一起铺，观感才统一）。
 *
 * <p>表头三行（左上角起）分别是：标题、`货物：n / 1728 件`、`当前限载：m / 20 人`（橙色）；
 * 表头下沿还有一条**载重进度条**（宽度按件数占 {@link CargoLoad#MAX_ITEMS} 的比例）。
 * 鼠标移到表头文字上会弹出完整规则（可以让玩家不去翻文档就知道"多少货少载几个人"）。
 *
 * <p><b>1.19.2 差异</b>：原版还没有 {@code DrawContext}（1.20 才引入），绘制入口是
 * {@link MatrixStack}，所有填充 / 居中文案都走 {@code DrawableHelper} 的静态方法；
 * {@code Screen.renderBackground()} 由 {@code Screen.render} 自动调用，不需要自己先铺一次。
 */
public class CargoScreen extends HandledScreen<CargoScreenHandler> {
    /** 面板底色、边框、表头条、槽位凹底、普通文字与载重文字用的六个颜色常量（与选站面板同一套深色配色）。 */
    private static final int PANEL_COLOR = 0xFF161D27, BORDER_COLOR = 0xFF4A5A6D, BAR_COLOR = 0xFF1E2733,
            SLOT_BG_COLOR = 0xFF0F141B, COUNT_COLOR = 0xFFB7C7D9, CAPACITY_COLOR = 0xFFFFBC62;

    /**
     * 绑定菜单。
     *
     * @param handler 货舱菜单（客户端的空壳实例，槽位内容由原版容器同步填写）
     * @param inventory 查看者背包
     * @param title 面板标题（服务端下发时用的 `screen.easyelevator.cargo` 翻译键）
     * 副作用：设置三个布局常量——176×210 的背景与"背包标题画在 y=116"，
     * 它们必须与 {@code CargoScreenHandler} 的槽位坐标一致，改一处就要改另一处。
     */
    public CargoScreen(CargoScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        backgroundWidth = 176;
        backgroundHeight = 210;
        playerInventoryTitleY = 116;
    }

    /**
     * 画面板底：不透明深色底 + 1 像素外框 → 表头条 → 每个槽位的凹槽 → 表头分隔线 → 载重进度条。
     *
     * <p>槽位凹槽遍历的是 {@code handler.slots}（含玩家背包那 36 格），因此货舱与背包的格子观感一致；
     * 槽位本身由原版随后绘制，这里只铺底。
     *
     * <p>进度条：长度 = {@code min(160, 件数 × 160 / MAX_ITEMS)}，即满舱 1728 件时铺满整条；
     * 用整数乘除而不是浮点，是为了"同一件数在任何客户端上画出同一长度"（面板截图与验收可比对）。
     *
     * @param matrices 渲染矩阵栈（坐标原点在屏幕左上角，槽位坐标要加上 {@code x}/{@code y}）
     * @param delta 帧插值系数（未使用；本面板没有动画）
     * @param mouseX 鼠标 X（未使用）
     * @param mouseY 鼠标 Y（未使用）
     */
    @Override protected void drawBackground(MatrixStack matrices, float delta, int mouseX, int mouseY) {
        fill(matrices, x, y, x + backgroundWidth, y + backgroundHeight, PANEL_COLOR);
        drawBorder(matrices, x, y, backgroundWidth, backgroundHeight, BORDER_COLOR);
        fill(matrices, x + 1, y + 1, x + 175, y + 46, BAR_COLOR);
        for (var slot : handler.slots) {
            fill(matrices, x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, BORDER_COLOR);
            fill(matrices, x + slot.x, y + slot.y, x + slot.x + 16, y + slot.y + 16, SLOT_BG_COLOR);
        }
        fill(matrices, x + 8, y + 43, x + 168, y + 45, SLOT_BG_COLOR);
        int used = Math.min(160, handler.cargoItems() * 160 / CargoLoad.MAX_ITEMS);
        fill(matrices, x + 8, y + 43, x + 8 + used, y + 45, CAPACITY_COLOR);
    }

    /**
     * 画表头三行与背包标题（原版在画完槽位后调用）。
     *
     * <p>三行的 y 坐标（6 / 19 / 31）与 {@link #drawBackground} 的表头条（1..46）配套；
     * 件数与限载都现问 {@link CargoScreenHandler}，因此与后壁铭牌、超载判定永远一致。
     *
     * @param matrices 渲染矩阵栈（此处坐标相对面板左上角）
     * @param mouseX 鼠标 X（未使用）
     * @param mouseY 鼠标 Y（未使用）
     */
    @Override protected void drawForeground(MatrixStack matrices, int mouseX, int mouseY) {
        textRenderer.draw(matrices, title, 8, 6, 0xFFEAF2FA);
        textRenderer.draw(matrices, Text.translatable("screen.easyelevator.cargo_count", handler.cargoItems()),
                8, 19, COUNT_COLOR);
        textRenderer.draw(matrices, Text.translatable("screen.easyelevator.cargo_capacity", handler.passengerLimit()),
                8, 31, CAPACITY_COLOR);
        textRenderer.draw(matrices, playerInventoryTitle, 8, playerInventoryTitleY, COUNT_COLOR);
    }

    /**
     * 每帧绘制：先走原版管线（背景 + 槽位 + 前景），再补光标物品的提示，
     * 最后在鼠标悬停表头文字区（x 8..168、y 19..46）时弹出完整规则。
     *
     * <p>规则文本按 220 像素宽度自动折行（中英文字宽差异很大，固定折行会串行），
     * 因此新增文案不需要在这里调坐标。
     *
     * <p>1.19.2 的 {@code HandledScreen.render} 已经会调用 {@code Screen.renderBackground}
     * （模糊 / 遮罩由原版负责），因此这里不再自己铺遮罩，否则会画两遍。
     *
     * @param matrices 渲染矩阵栈
     * @param mouseX 鼠标 X（屏幕坐标，用于悬停判定）
     * @param mouseY 鼠标 Y（屏幕坐标）
     * @param delta 帧插值系数
     */
    @Override public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);
        drawMouseoverTooltip(matrices, mouseX, mouseY);
        if (mouseX >= x + 8 && mouseX < x + 168 && mouseY >= y + 19 && mouseY < y + 46)
            renderOrderedTooltip(matrices, textRenderer.wrapLines(
                    Text.translatable("screen.easyelevator.cargo_rule"), 220), mouseX, mouseY);
    }

    /**
     * 面板背景：只铺一层很淡的暗色遮罩，**不调用原版模糊**——与选站面板、厅外呼叫面板一致。
     *
     * <p>1.19.2 的调用链与 1.20.1 不同：{@code Screen.render} 会调用
     * {@code renderBackground(MatrixStack)}，而 {@code HandledScreen.render} 又会调用
     * {@code drawBackground} 画面板底与槽位凹槽。因此这里只负责画遮罩，
     * <b>不要再手动调 {@link #drawBackground}</b>，否则面板底会被画两遍。
     *
     * @param matrices 渲染矩阵栈
     */
    @Override public void renderBackground(MatrixStack matrices) {
        fill(matrices, 0, 0, width, height, 0x18000000);
    }

    /**
     * 画 1 像素矩形外框（四条 fill 拼成）。1.19.2 的 {@code MatrixStack} 版本没有
     * {@code DrawContext.drawBorder}，因此在本类补一个小工具方法。
     *
     * @param matrices 渲染矩阵栈
     * @param bx 左上角 X
     * @param by 左上角 Y
     * @param bw 宽（像素）
     * @param bh 高（像素）
     * @param color 颜色（ARGB）
     */
    private static void drawBorder(MatrixStack matrices, int bx, int by, int bw, int bh, int color) {
        fill(matrices, bx, by, bx + bw, by + 1, color);
        fill(matrices, bx, by + bh - 1, bx + bw, by + bh, color);
        fill(matrices, bx, by, bx + 1, by + bh, color);
        fill(matrices, bx + bw - 1, by, bx + bw, by + bh, color);
    }
}
