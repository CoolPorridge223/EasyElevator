package org.DJB.easyelevator.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.network.ElevatorNetworking;
import java.util.List;

/** 轿厢内的选站面板：分页列出当前线路的全部站点，点击按钮发送 SelectStop 请求。
 * 在整体架构中的位置：纯客户端 UI。站点列表由服务端在 OpenPanel 包中下发（服务端已校验乘客身份），
 * 面板自身不查询线路、不做权限判断；发请求后由服务端状态机决断，界面只是"请求"视图。
 * 关键约束：站点数可能超过一屏，因此用 page/rows 分页；rows 随窗口高度自适应（1..8 行）；
 * 打开面板不暂停游戏（shouldPause=false），因为轿厢仍需继续运行。
 */
public class ElevatorScreen extends Screen {
    private final int entityId; // 面板绑定的轿厢实体 id，用于定位实体与回发 SelectStop
    private final List<BlockPos> stops; // 服务端下发的站点列表（根方块位置，y 即站点高度，单位：格）；只读
    private int page, rows=6; // page：当前页（0 基）；rows：每页行数，init() 时按窗口高度重算
    /** 从服务端下发的 OpenPanel 包构造面板；界面文案由构造函数内的翻译键决定。
     * @param payload 服务端包：entityId 轿厢实体 id、stops 站点（根方块）列表
     */
    public ElevatorScreen(ElevatorNetworking.OpenPanel payload) {
        super(Text.translatable("screen.easyelevator.title")); entityId=payload.entityId();stops=payload.stops();
    }
    /** 构建控件。窗口尺寸变化或翻页（clearAndInit）时会被重新调用。
     * 副作用：向屏幕添加站点按钮、上一页/下一页与"完成"按钮；不发送任何数据包。
     */
    @Override
    protected void init() {
        // rows 由可用高度反推：预留 130 像素（标题、状态、页码、底部按钮）后每行 24 像素，限制在 1..8 行；
        // 同时把 page 收紧到合法范围，避免窗口缩小或站点减少后停在空页上。
        rows = Math.clamp((height - 130) / 24, 1, 8);
        page = Math.clamp((stops.size() - 1) / rows, 0, page);
        int left=width/2-110, top=height/2-(rows*24+100)/2;
        // 只为本页的站点建按钮；index 必须是 final 供 lambda 捕获（分页后 i 与页内行号不同）。
        for (int i=page*rows;i<Math.min(stops.size(),(page+1)*rows);i++) {
            int index=i; BlockPos stop=stops.get(i);
            // 按钮显示"楼层序号 + 站点 Y（格）"；点击只发请求包，真正移动由服务端状态机执行。
            addDrawableChild(ButtonWidget.builder(Text.translatable("screen.easyelevator.station",index+1,stop.getY()),button->{
                ClientPlayNetworking.send(new ElevatorNetworking.SelectStop(entityId,stop));
            }).dimensions(left,top+52+(i-page*rows)*24,220,20).build());
        }
        int bottom=top+52+rows*24;
        // 翻页用 clearAndInit() 重建控件（同时保留 page 值）；在首/末页时禁用对应按钮而不是隐藏，保持布局稳定。
        var previous=addDrawableChild(ButtonWidget.builder(Text.literal("<"),b->{page--;clearAndInit();}).dimensions(left,bottom,40,20).build());
        previous.active=page>0;
        var next=addDrawableChild(ButtonWidget.builder(Text.literal(">"),b->{page++;clearAndInit();}).dimensions(left+180,bottom,40,20).build());
        next.active=(page+1)*rows<stops.size();
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"),b->close()).dimensions(left+60,bottom,100,20).build());
    }
    /** 查找面板绑定的轿厢。@return 世界/实体未就绪或 id 已不对应轿厢时返回 null，调用方需判空。 */
    private CabinEntity cabin() {
        return client!=null && client.world!=null && client.world.getEntityById(entityId) instanceof CabinEntity c ? c : null;
    }
    /** 每刻检查面板是否仍然有效。副作用：轿厢消失、玩家离开世界或玩家已不是该轿厢乘客时自动关闭界面（close()），
     * 防止玩家站在地面或被推离轿厢后继续操作面板。
     */
    @Override
    public void tick() {
        var cabin = cabin();
        if (client != null && (cabin == null || client.player == null || !cabin.containsPassenger(client.player)))
            close();
    }
    /** 渲染面板：先画背景遮罩与面板底、再画标题/状态/页码/空列表提示，最后交给 super 绘制按钮等子控件。
     * @param mouseX 鼠标 X（像素，屏幕坐标）
     * @param mouseY 鼠标 Y（像素，屏幕坐标）
     * @param delta 渲染插值系数（0..1）
     * 副作用：仅绘制；状态文本每帧读取轿厢的 Y（格）与 Phase，因此会被服务端同步刷新。
     */
    @Override
    public void render(DrawContext context,int mouseX,int mouseY,float delta) {
        renderBackground(context,mouseX,mouseY,delta);
        int top=height/2-(rows*24+100)/2;
        // 面板底色 0xEC171E29（深色 + 不透明度 0xEC），比前面的全屏浅遮罩更实，保证文字可读。
        context.fill(width/2-122,top-8,width/2+122,top+rows*24+84,0xEC171E29);
        context.drawCenteredTextWithShadow(textRenderer,title,width/2,top,0xFFFFFF);
        var cabin=cabin();
        if(cabin!=null) {
            // 状态行："Y 坐标（格，保留 1 位小数）+ Phase 翻译键 phase.easyelevator.<小写阶段名>"。
            // Locale.ROOT 保证小数点是 '.'、阶段键不随语言环境变形（翻译键必须与语言文件一致）。
            Text status=Text.translatable("screen.easyelevator.status",String.format(java.util.Locale.ROOT,"%.1f",cabin.getY()),
                    Text.translatable("phase.easyelevator."+cabin.phase().name().toLowerCase(java.util.Locale.ROOT)));
            context.drawCenteredTextWithShadow(textRenderer,status,width/2,top+17,0xC3D5E8);
        }
        // 页码行：总站点数、当前页（1 基）、总页数（向上取整且至少 1 页）。
        context.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.easyelevator.count",stops.size(),page+1,Math.max(1,(stops.size()+rows-1)/rows)),width/2,top+33,0xA7B4C5);
        if(stops.isEmpty()) context.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.easyelevator.empty"),width/2,top+64,0xFFC27A);
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
}
