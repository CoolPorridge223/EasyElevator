package org.DJB.easyelevator.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.DJB.easyelevator.logic.DoorSoundPersistence;
import org.DJB.easyelevator.logic.DoorSounds;
import org.DJB.easyelevator.network.ElevatorNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 单扇楼层门的<b>专属设置面板</b>：潜行右键任意一扇门打开，设置只作用于这一扇门。
 *
 * <p>面板管的是这扇门的<b>到站提示音</b>——轿厢精确停在这一站时响什么。版式是标准的"设置行"：
 * 一行到站音效（开关 + 当前音效 + 上一项/下一项 + 选择文件 + 试听），下面一条分隔线，
 * 再下面一行动作（设为基准层 + 关闭）：
 * <pre>
 *                   电梯门设置
 *  ┌──────────────────────────────────────────────────┐
 *  │         站点高度 Y = -60  ·  第 1 层              │
 *  │ [到站音效 开] [   默认音效   ] [&lt;][&gt;] [选择文件][试听]│
 *  │ ──────────────────────────────────────────────── │
 *  │ [        基准层（1 层）        ]          [关闭]  │
 *  └──────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>三个刻意的决定：
 * <ul>
 *   <li><b>行名写进开关按钮</b>（"到站音效 开"）：让"这个开关管什么"和"当前开还是关"在同一处读到。</li>
 *   <li><b>值格不画按钮外观</b>：它是只读的，做成凹陷深色条而不是凸起按钮，避免看起来能点。</li>
 *   <li><b>标题与副标题都在面板内部</b>：整块界面自成一体，窗口缩放时不会出现文字跑到面板外的错位。</li>
 * </ul>
 *
 * <p>在整体架构中的位置：纯客户端 UI。面板内容全部来自服务端下发的
 * {@link ElevatorNetworking.OpenDoorPanel}（单扇门的设置存在方块实体里，是服务端权威数据）；
 * 客户端只画界面、把每次点击原样回传成 {@link ElevatorNetworking.DoorSoundCommand} /
 * {@link ElevatorNetworking.SetBaseFloor} / {@link ElevatorNetworking.DoorSoundUpload}，
 * 从不自己改状态。因此别人改了同一扇门时，服务端推来新快照，本面板当场刷新（{@link #apply}）。
 *
 * <p>面板打开时<b>不暂停游戏</b>（{@link #shouldPause()} 返回 false），与另两个界面一致。
 */
public class DoorSoundScreen extends Screen {
    /** 诊断日志：弹窗失败时把真实原因（HeadlessException 等）写进日志，而不是只给玩家一句含糊的提示。 */
    private static final Logger LOGGER=LoggerFactory.getLogger("easyelevator/door-sound-screen");
    /** 面板底色（不透明，避免井道里的红字从底下透出来）。 */
    private static final int PANEL_COLOR=0xFF141B26;
    /** 标题、副标题、值、行名的文字颜色。 */
    private static final int TITLE_COLOR=0xFFFFFFFF, HINT_COLOR=0x9FB0C2, VALUE_COLOR=0xE6EEF7;
    /** 值格（只读凹陷条）的底与内层颜色；分隔线的颜色。 */
    private static final int VALUE_BG=0xFF0E141C, VALUE_INNER=0xFF1B2430, DIVIDER=0xFF2A3543;

    /** 控件尺寸（像素）：行高与各处间距。整块高度由这些常量静态算出。 */
    private static final int ROW=20, GAP=4, PAD=12;
    /** 行内横向尺寸：开关 | 值格 | 两个箭头 | 选择文件 | 试听。 */
    private static final int TOGGLE_W=86, VALUE_W=140, ARROW_W=20, PICK_W=58, TRY_W=40;
    /** 内容区宽度：开关 + 值 + 箭头 + 选择文件 + 试听 + 各处间距。 */
    private static final int CONTENT_W=2*PAD+TOGGLE_W+GAP+VALUE_W+GAP+2*ARROW_W+GAP+PICK_W+GAP+TRY_W;
    /** 行内固定 X 起点（相对面板左边缘）。 */
    private static final int X_TOGGLE=PAD, X_VALUE=X_TOGGLE+TOGGLE_W+GAP, X_PREV=X_VALUE+VALUE_W+GAP,
            X_NEXT=X_PREV+ARROW_W, X_PICK=X_NEXT+ARROW_W+GAP, X_TRY=X_PICK+PICK_W+GAP, X_END=X_TRY+TRY_W;
    /** 页脚两个键：基准层长键 + 关闭键，两者右边缘都对齐到 {@link #X_END}。 */
    private static final int CLOSE_W=56, BASE_W=X_END-CLOSE_W-PAD-GAP, X_CLOSE=X_END-CLOSE_W;
    /** 路径输入框与它右边"上传"键的尺寸（像素）。 */
    private static final int INPUT_W=48, INPUT_GAP=GAP;

    /**
     * 版面的纵向分段高度（像素）。
     *
     * <p>每段都给得比"刚好放下一行字"更宽：Minecraft 的字体高 9 像素，但不同 GUI 缩放下
     * 子像素取整会多占 1~2 像素；曾经把副标题塞进 10 像素的窄带里，实机就出现了副标题被第一行
     * 压掉下半截的重叠。这里给副标题留 18 像素（9 像素字 + 前后余量），标题单独占 12 像素。
     */
    private static final int TITLE_H=12, HEADER_H=18, PAD_SMALL=8, GAP_SMALL=2, DROP_H=11;
    /** 面板各段相对块顶端的偏移：设置行 -> 分隔线 -> 拖拽提示 -> 路径输入框 -> 页脚。 */
    private static final int ROW_TOP=PAD+TITLE_H+GAP+HEADER_H;
    private static final int DIVIDER_TOP=ROW_TOP+ROW+GAP_SMALL;
    private static final int DROP_TOP=DIVIDER_TOP+1+GAP_SMALL;
    private static final int INPUT_TOP=DROP_TOP+DROP_H+GAP;
    /** 整块高度：内边距 + 标题 + 副标题 + 设置行 + 分隔线 + 提示 + 输入框 + 页脚 + 内边距。 */
    private static final int BLOCK_H=INPUT_TOP+ROW+PAD_SMALL+ROW+PAD;

    /** 试听高亮的持续帧数：约 0.2 秒，够看清但不会一直亮着。 */
    private static final int FLASH_TICKS=12;

    /** 本面板绑定的站点（楼层门根方块）；用于判断"收到的最新快照是不是本面板的"。 */
    private final BlockPos station;
    /** 服务端给的层号显示文本（例如 {@code "3"} / {@code "B1"}）。 */
    private final String floorLabel;
    /** 站点高度（格），显示在副标题里。 */
    private final int stationY;
    /** 该门在运行时资源包里的音效槽位号：面板据此显示自定义音频的实际文件名。 */
    private final int soundSlot;
    /** 到站提示音的开关与音效选项（服务端权威快照，改动后由服务端回推覆盖）。 */
    private boolean enabled;
    private int choice;
    /** 这一站是否已经是整条线路的基准层（决定页脚按钮显示"设为基准层"还是"基准层（1 层）"）。 */
    private boolean baseFloor;
    /** 服务端试听提示的剩余帧数：> 0 时值格高亮并带 `▶`。 */
    private int previewFlash;
    /** 面板左上角与设置行的上边缘（渲染深色底与分隔线用）。 */
    private int panelLeft, panelTop, rowTop;
    /** 路径输入框：不依赖拖拽与系统对话框的最后一道上传入口。 */
    private TextFieldWidget pathInput;

    /**
     * 由服务端下发的 {@link ElevatorNetworking.OpenDoorPanel} 构造面板。
     *
     * @param payload 面板快照：站点、层号、开关与选项、基准层、音效槽位、试听提示
     */
    public DoorSoundScreen(ElevatorNetworking.OpenDoorPanel payload) {
        super(Text.translatable("screen.easyelevator.door_sound_title"));
        this.station=payload.station();
        this.floorLabel=payload.floorLabel();
        this.stationY=payload.station().getY();
        this.soundSlot=payload.soundSlot();
        this.enabled=payload.enabled();
        this.choice=payload.choice();
        this.baseFloor=payload.baseFloor();
        applyPreview(payload.preview());
    }

    /** @return 本面板绑定的站点（楼层门根方块）；客户端收到快照时据此判断"是不是本面板的" */
    public BlockPos station() { return station; }

    /**
     * 应用服务端推来的最新快照（自己改完服务端回推、或别人改了同一扇门时调用）。
     *
     * <p>只改字段、不重建控件：控件每帧读最新字段，因此刷新不会打断玩家正在进行的点击。
     *
     * @param enabled   到站提示音开关
     * @param choice    到站提示音选项序号
     * @param baseFloor 这一站是否为基准层
     * @param preview   本次要提示的试听状态（见 {@link ElevatorNetworking.OpenDoorPanel} 的常量）
     */
    public void apply(boolean enabled,int choice,boolean baseFloor,int preview) {
        this.enabled=enabled; this.choice=choice; this.baseFloor=baseFloor;
        applyPreview(preview);
    }

    /**
     * 记录一次试听提示，让值格闪一下。
     *
     * @param preview {@link ElevatorNetworking.OpenDoorPanel#PREVIEW_NONE} 等常量
     */
    private void applyPreview(int preview) {
        if (preview!=ElevatorNetworking.OpenDoorPanel.PREVIEW_NONE) previewFlash=FLASH_TICKS;
    }

    /**
     * 构建控件：一行到站音效设置 + 页脚（基准层 / 关闭）。
     *
     * <p>纵向位置全部由 {@link #BLOCK_H} 推出：整块在屏幕里垂直居中，因此窄窗口或大 GUI 缩放
     * 都不会把页脚挤出屏幕外。
     *
     * <p>副作用：向屏幕添加控件；不发包、不改世界。窗口尺寸变化时由原版重新调用。
     */
    @Override
    protected void init() {
        panelLeft=(width-CONTENT_W)/2;
        panelTop=(height-BLOCK_H)/2;
        rowTop=panelTop+ROW_TOP;
        // 登记为拖拽目标：把 .ogg 拖进窗口就是上传，不依赖任何文件对话框（见 FileDropHandler）
        FileDropHandler.setTarget(this);
        addRow(rowTop);
        // 路径输入框：最后一道保险——不依赖拖拽、不依赖对话框，复制粘贴路径就能上传。
        // 宽度与上面的工具键同宽，多出来的部分往右让给"上传"键。
        int inputWidth=CONTENT_W-PAD-INPUT_W-INPUT_GAP-PAD;
        pathInput=new TextFieldWidget(textRenderer,panelLeft+PAD,panelTop+INPUT_TOP,inputWidth,ROW,
                Text.translatable("screen.easyelevator.door_sound_path"));
        pathInput.setMaxLength(512); // 路径远长不到 512 字符，这里纯粹是防御性上限
        addDrawableChild(pathInput);
        addDrawableChild(new RowButton(panelLeft+X_END-INPUT_W,panelTop+INPUT_TOP,INPUT_W,() -> false,
                Text.translatable("screen.easyelevator.door_sound_upload"),b -> submitTypedPath(),
                () -> Text.translatable("screen.easyelevator.door_sound_upload")));
        int footY=panelTop+INPUT_TOP+ROW+PAD_SMALL;
        // 页脚：基准层长键 + 关闭键，右边缘与设置行的"试听"右边缘对齐
        addDrawableChild(new RowButton(panelLeft+X_TOGGLE,footY,BASE_W,
                () -> baseFloor,Text.translatable("screen.easyelevator.floor_base"),
                b -> ClientPlayNetworking.send(new ElevatorNetworking.SetBaseFloor(station,!baseFloor)),
                () -> Text.translatable(baseFloor
                        ?"screen.easyelevator.floor_base_on":"screen.easyelevator.floor_base")));
        addDrawableChild(new RowButton(panelLeft+X_CLOSE,footY,CLOSE_W,() -> false,
                Text.translatable("screen.easyelevator.close"),b -> close(),null));
    }

    /**
     * 铺唯一的一行到站音效设置。
     *
     * @param y 该行上边缘（绝对像素）
     */
    private void addRow(int y) {
        // 开关：文字 = 行名 + 当前状态（"到站音效 开"），点击请求服务端翻转
        addDrawableChild(new RowButton(panelLeft+X_TOGGLE,y,TOGGLE_W,
                () -> enabled,Text.translatable("screen.easyelevator.door_sound_row"),
                b -> sendCommand(!enabled,choice,false),
                () -> Text.translatable(enabled
                        ?"screen.easyelevator.toggle_on":"screen.easyelevator.toggle_off",
                        Text.translatable("screen.easyelevator.door_sound_row"))));
        // 当前音效：只读凹陷条（不参与键盘导航，见 RowButton#readOnly）
        addDrawableChild(new RowButton(panelLeft+X_VALUE,y,VALUE_W,() -> enabled,Text.empty(),b -> {},
                this::soundValue));
        // 上一项 / 下一项：循环切换音效选项，不改开关（只想试听别的音色时不必先打开开关）
        addDrawableChild(arrow(panelLeft+X_PREV,y,Text.translatable("screen.easyelevator.sound_prev"),-1));
        addDrawableChild(arrow(panelLeft+X_NEXT,y,Text.translatable("screen.easyelevator.sound_next"),1));
        // 选择文件：把本地 .ogg 上传到服务端（服务端存权威副本再分发）
        addDrawableChild(new RowButton(panelLeft+X_PICK,y,PICK_W,() -> false,
                Text.translatable("screen.easyelevator.sound_pick"),b -> pickFile(),
                () -> Text.translatable("screen.easyelevator.sound_pick")));
        // 试听：请服务端以权威音效当场播一次（客户端不自己播，避免与服务端的定义分叉）
        addDrawableChild(new RowButton(panelLeft+X_TRY,y,TRY_W,() -> false,
                Text.translatable("screen.easyelevator.sound_try"),b -> sendCommand(enabled,choice,true),
                () -> Text.translatable("screen.easyelevator.sound_try")));
    }

    /**
     * 铺一个方向箭头键。
     *
     * @param x 左边缘（绝对像素）
     * @param y 上边缘（绝对像素）
     * @param glyph 显示的字符（{@code <} / {@code >}）
     * @param delta +1 / -1
     * @return 按钮控件
     */
    private ButtonWidget arrow(int x,int y,Text glyph,int delta) {
        return new RowButton(x,y,ARROW_W,() -> false,glyph,
                b -> sendCommand(enabled,Math.floorMod(choice+delta,DoorSounds.CHOICE_COUNT),false),() -> glyph);
    }

    /**
     * 把一次修改发给服务端。
     *
     * <p>客户端不本地改状态：等服务端校验并回推快照，界面才更新。这样"服务端拒绝"时界面不会撒谎，
     * 也天然支持"别人同时改了同一扇门"（以服务端最终结果为准）。
     *
     * @param enabled 改动后的开关
     * @param choice  改动后的音效选项序号；{@code < 0} 表示只改开关、不动选项
     * @param preview 是否请求服务端当场试听
     */
    private void sendCommand(boolean enabled,int choice,boolean preview) {
        ClientPlayNetworking.send(new ElevatorNetworking.DoorSoundCommand(station,enabled,choice,preview));
    }

    /**
     * 处理键盘：路径输入框里按回车＝点"上传"。
     *
     * <p>为什么需要它：对话框与拖拽都可能受启动器环境影响，而"把路径粘贴进来按回车"不依赖任何
     * 系统集成，是最后一道保险。顺手支持 Ctrl+V 粘贴（原版输入框本身就处理，这里不必重复实现）。
     *
     * @param keyCode 键码
     * @param scanCode 扫描码（未使用）
     * @param modifiers 修饰键（未使用）
     * @return 已消费时为 true
     */
    @Override
    public boolean keyPressed(int keyCode,int scanCode,int modifiers) {
        if ((keyCode==org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER||keyCode==org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)
                && pathInput!=null && pathInput.isFocused()) {
            submitTypedPath();
            return true;
        }
        return super.keyPressed(keyCode,scanCode,modifiers);
    }

    /**
     * 取输入框里的路径并上传。
     *
     * <p>容忍三种常见写法：裸路径、带引号的路径、{@code file://} URI——玩家从资源管理器"复制为路径"
     * 拿到的是带引号的形式，从浏览器地址栏拿到的可能是 URI。
     */
    private void submitTypedPath() {
        if (pathInput==null) return;
        String raw=pathInput.getText().trim();
        if (raw.isEmpty()) { message(Text.translatable("message.easyelevator.door_sound_upload_failed")); return; }
        String value=raw;
        if (value.length()>=2 && value.startsWith("\"") && value.endsWith("\"")) value=value.substring(1,value.length()-1);
        Path path;
        try {
            path=value.regionMatches(true,0,"file:",0,5) ? Path.of(java.net.URI.create(value)) : Path.of(value);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not parse the typed arrival-sound path [{}]", raw, e);
            message(Text.translatable("message.easyelevator.door_sound_upload_failed"));
            return;
        }
        upload(path);
    }

    /**
     * 弹窗选一个 {@code .ogg}，读进内存后发给服务端。
     *
     * <p>取文件本身交给 {@link FilePicker}：它先试 AWT 的文件对话框，在这个进程被启动器设成 headless
     * （{@code -Djava.awt.headless=true}，且 AWT 状态已被缓存、运行时改不回来）时自动落到系统原生对话框。
     *
     * <p><b>对话框只是备选入口</b>：面板的主体操作是<b>把 .ogg 直接拖进游戏窗口</b>
     * （见 {@link FileDropHandler}）——那条路不依赖 AWT、也不依赖 PowerShell，是唯一在各种启动器环境下
     * 都可靠的做法。这里的按钮保留下来只是为了"不想拖拽"的玩家。
     *
     * <p>三种结果分开处理，绝不混成同一句提示：玩家取消 → 静默返回；弹出失败 → 提示改用拖拽；
     * 文件不合法 → "上传失败"。
     */
    private void pickFile() {
        Path picked;
        try {
            picked = FilePicker.pick(Text.translatable("screen.easyelevator.sound_pick").getString());
        } catch (FilePicker.UnavailableException e) {
            LOGGER.warn("No usable file dialog in this process", e);
            message(Text.translatable("message.easyelevator.door_sound_no_dialog"));
            return;
        }
        if (picked == null) return; // 玩家取消：不打扰
        upload(picked);
    }

    /**
     * 接收拖进窗口的文件（由 {@link FileDropHandler} 在渲染线程上调用）。
     *
     * <p>这是上传自定义音频的<b>主要入口</b>：把 {@code .ogg} 拖到游戏窗口上即可，不需要任何对话框。
     *
     * @param path 拖进来的文件路径
     */
    void acceptDroppedFile(Path path) {
        upload(path);
    }

    /**
     * 校验并上传一个本机文件，两个入口（按钮 / 拖拽）共用同一套检查与提示。
     *
     * <p>校验（是不是 ogg、是否超限）刻意放在客户端而不是全交给服务端：这是唯一能立刻把失败原因
     * 告诉玩家的地方。服务端仍会独立再校验一次，不信任客户端。
     *
     * @param path 候选文件
     */
    private void upload(Path path) {
        if (!FileDropHandler.isReadableFile(path)) {
            message(Text.translatable("message.easyelevator.door_sound_upload_failed"));
            return;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(path);
            if (!DoorSoundPersistence.isOgg(bytes) || bytes.length > DoorSoundPersistence.MAX_AUDIO_BYTES) {
                message(Text.translatable("message.easyelevator.door_sound_upload_failed"));
                return;
            }
            ClientPlayNetworking.send(new ElevatorNetworking.DoorSoundUpload(station, bytes));
        } catch (Exception e) {
            LOGGER.warn("Could not read the picked arrival-sound file {}", path, e);
            message(Text.translatable("message.easyelevator.door_sound_upload_failed"));
        }
    }

    /**
     * 给本地玩家发一条 actionbar 提示（界面里唯一的即时反馈通道）。
     *
     * @param text 提示文本
     */
    private void message(Text text) {
        // client 是 Screen 持有的 MinecraftClient 引用；玩家可能还没进世界（理论上到不了这里），故判空。
        if (client!=null && client.player!=null) client.player.sendMessage(text,true);
    }

    /**
     * 绘制面板：一块不透明深色底 + 标题 + 副标题 + 设置区与动作区之间的分隔线。
     *
     * @param context 绘制上下文
     * @param mouseX 鼠标 X（像素）
     * @param mouseY 鼠标 Y（像素）
     * @param delta 渲染插值系数
     * 副作用：仅绘制；每帧递减试听闪烁计数。
     */
    @Override
    public void render(DrawContext context,int mouseX,int mouseY,float delta) {
        if (previewFlash>0) previewFlash--; // 闪烁按帧递减，够用且不必额外挂刻事件
        renderBackground(context,mouseX,mouseY,delta);
        context.fill(panelLeft,panelTop,panelLeft+CONTENT_W,panelTop+BLOCK_H,PANEL_COLOR);
        // 标题与副标题都画在面板内部，各自占一段高度（见 TITLE_H / HEADER_H 的说明）
        context.drawCenteredTextWithShadow(textRenderer,title,width/2,panelTop+PAD-2,TITLE_COLOR);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("screen.easyelevator.door_sound_station_floor",stationY,floorLabel),
                panelLeft+CONTENT_W/2,panelTop+PAD+TITLE_H+GAP+4,HINT_COLOR);
        // 设置区与动作区之间的分隔线：一条浅线即可，不必画整圈边框
        int lineY=panelTop+DIVIDER_TOP;
        context.fill(panelLeft+PAD,lineY,panelLeft+CONTENT_W-PAD,lineY+1,DIVIDER);
        // 上传提示：拖拽与粘贴两条路都写出来，玩家不必猜（按钮只是第三种备选）
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("screen.easyelevator.door_sound_drop"),
                panelLeft+CONTENT_W/2,lineY+GAP_SMALL,HINT_COLOR);
        super.render(context,mouseX,mouseY,delta);
    }

    /**
     * 当前音效的显示名：默认音效 / 自定义文件（带槽位号的文件名）/ 预设原版音效（显示其 ID）。
     *
     * @return 显示文本（不含开关状态标记）
     */
    private Text soundName() {
        if (DoorSounds.isCustom(choice)) {
            return Text.translatable("screen.easyelevator.sound_custom_id",DoorSoundPersistence.fileName(soundSlot));
        }
        if (DoorSounds.isPreset(choice)) {
            String id=DoorSounds.presetId(choice);
            return Text.translatable("screen.easyelevator.sound_id",id==null?"?":id);
        }
        return Text.translatable("screen.easyelevator.sound_choice_default");
    }

    /**
     * 值格要显示的完整文本：音效名 + 状态标记。
     *
     * <p>两个标记都是"每帧现拼"的：关掉时加"(已关闭)"一眼能看出这扇门到站不响；
     * 刚试听过时加 `▶` 前缀，玩家不用猜刚才听到的是哪一项。
     *
     * @return 要画在值格里的文本
     */
    private Text soundValue() {
        Text name=soundName();
        if (!enabled) return Text.translatable("screen.easyelevator.sound_off_value",name);
        if (previewFlash>0) return Text.translatable("screen.easyelevator.sound_flash",name);
        return name;
    }

    /** 覆盖原版背景绘制：只用一次淡黑叠加代替模糊着色器，保持世界画面清晰（与另两个面板一致）。
     * @param context 绘制上下文
     * @param mouseX 鼠标 X（像素）
     * @param mouseY 鼠标 Y（像素）
     * @param delta 渲染插值系数
     */
    @Override
    public void renderBackground(DrawContext context,int mouseX,int mouseY,float delta) {
        context.fill(0,0,width,height,0x18000000);
    }

    /** @return false：调音效期间不暂停单人游戏世界，否则电梯会停在半路。 */
    @Override
    public boolean shouldPause() { return false; }

    /** 关闭面板时取消拖拽登记：别的界面不该继承"拖文件上传"这个语义。 */
    @Override
    public void removed() {
        FileDropHandler.setTarget(null);
        super.removed();
    }

    /**
     * 面板上的按钮，两种外观共用一套栅格：
     * <ul>
     *   <li><b>操作键</b>（开关 / 箭头 / 选择文件 / 试听 / 基准层 / 关闭）：凸起的三层描边，
     *       悬停转亮；"已开启"的开关用一条不刺眼的青边表示状态。</li>
     *   <li><b>只读值格</b>（音效名）：凹陷的深色条，无边框、无悬停高亮，一眼就知道点不动。</li>
     * </ul>
     */
    private final class RowButton extends ButtonWidget {
        /** 该按钮是否处于"已开启/已激活"状态：决定描边颜色。 */
        private final BooleanSupplier toggled;
        /** 悬停提示与键盘朗读文本。 */
        private final Text narration;
        /** 每帧求值的显示文本；为 null 时退回构造时传入的固定文本。 */
        private final Supplier<Text> dynamic;
        /** 只读值格：画凹陷条、不接受悬停高亮、不参与键盘导航。 */
        private final boolean readOnly;

        /**
         * @param x 左边缘（绝对像素）
         * @param y 上边缘（绝对像素）
         * @param w 宽度（像素）
         * @param toggled 是否处于已开启状态（决定描边颜色）
         * @param tooltip 悬停提示与朗读文本；传 {@link Text#empty()} 表示只读值格
         * @param onPress 点击回调
         * @param dynamic 每帧求值的显示文本；null 表示用 {@code tooltip} 当固定文本
         */
        RowButton(int x,int y,int w,BooleanSupplier toggled,Text tooltip,Predicate onPress,Supplier<Text> dynamic) {
            super(x,y,w,ROW,tooltip,onPress,DEFAULT_NARRATION_SUPPLIER);
            this.toggled=toggled; this.narration=tooltip; this.dynamic=dynamic;
            this.readOnly=tooltip.getString().isEmpty();
            if (!readOnly) setTooltip(Tooltip.of(tooltip));
        }

        /**
         * 画控件本体（不调用父类，避免再叠一层原版长条贴图）。
         *
         * @param ctx 绘制上下文
         * @param mouseX 鼠标 X（像素）
         * @param mouseY 鼠标 Y（像素）
         * @param delta 渲染插值系数
         */
        @Override
        protected void renderWidget(DrawContext ctx,int mouseX,int mouseY,float delta) {
            int x=getX(), y=getY(), w=getWidth(), h=getHeight();
            Text label=dynamic==null?getMessage():dynamic.get();
            if (readOnly) {
                // 只读值格：凹陷深色条，绝不画悬停高亮
                boolean on=toggled.getAsBoolean();
                ctx.fill(x,y,x+w,y+h,VALUE_BG);
                ctx.fill(x,y,x+w-1,y,VALUE_INNER);
                ctx.drawCenteredTextWithShadow(textRenderer,trim(label,w-8),x+w/2,y+(h-8)/2,
                        on?VALUE_COLOR:0x77828F);
                return;
            }
            boolean lit=isHovered(), on=toggled.getAsBoolean();
            // 三层嵌套色块 = 描边 + 底面 + 内凹面；"已开启"用一条青边表示状态，不用整块青底
            ctx.fill(x,y,x+w,y+h, lit?0xFFE8F1F8:on?0xFF5FA898:0xFF5C6B7A);
            ctx.fill(x+1,y+1,x+w-1,y+h-1, lit?0xFF54697F:0xFF2A3543);
            ctx.fill(x+2,y+2,x+w-2,y+h-2, lit?0xFF41566C:on?0xFF233A36:0xFF1E2833);
            ctx.drawCenteredTextWithShadow(textRenderer,trim(label,w-6),x+w/2,y+(h-8)/2,
                    lit?0xFFFFFF:on?0xFFD9FFF4:0xD2DEE9);
        }

        /**
         * 把过长的文本按像素宽度截断并加省略号。
         *
         * <p>为什么必须截断：值格里可能显示音效 ID（{@code minecraft:block.amethyst_block.chime}）
         * 或自定义文件名，比格子宽得多；不截断就会压到相邻按钮上，整行看起来是坏的。
         *
         * @param text 原始文本
         * @param maxWidth 可用像素宽度
         * @return 截断后的文本；本来就放得下时原样返回
         */
        private Text trim(Text text,int maxWidth) {
            if (maxWidth<=0 || textRenderer.getWidth(text)<=maxWidth) return text;
            String raw=text.getString();
            int limit=maxWidth-textRenderer.getWidth("...");
            StringBuilder out=new StringBuilder();
            for (int i=0;i<raw.length();i++) {
                if (textRenderer.getWidth(out.toString()+raw.charAt(i))>limit) break;
                out.append(raw.charAt(i));
            }
            return Text.literal(out+"...");
        }

        /** @return 只读值格不参与 Tab 导航与朗读，避免"能聚焦却点不动"的困惑。 */
        @Override
        public boolean isNarratable() { return !readOnly; }

        /**
         * @return 恒为 false：本面板不使用键盘 Tab 导航。
         *
         * <p>为什么必须显式关掉：{@code ButtonWidget} 默认让"第一个控件"获得焦点，而焦点会走
         * {@link #renderWidget} 的悬停高亮分支——于是面板一打开，某个键就自己亮着白边，看起来像被选中了。
         * 这些键都可以用鼠标点，关掉键盘焦点对玩家没有损失。
         */
        @Override
        public boolean isFocused() { return false; }

        /** @return 键盘朗读用的说明：按钮当前的提示文本。 */
        @Override
        public void appendClickableNarrations(net.minecraft.client.gui.screen.narration.NarrationMessageBuilder builder) {
            builder.put(net.minecraft.client.gui.screen.narration.NarrationPart.TITLE,narration);
        }
    }

    /**
     * 按钮点击回调的别名（原版把 {@code PressAction} 定义在 {@link ButtonWidget} 里，
     * 这里自己起个短名字，让行内构造读起来干净些）。
     */
    @FunctionalInterface
    private interface Predicate extends ButtonWidget.PressAction { }
}
