package org.DJB.easyelevator.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.DoorSoundPersistence;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.network.ElevatorNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Map;

/** 客户端模组入口：注册渲染器、S2C 数据包处理器、客户端刻回调与断线清理。
 * 在整体架构中的位置：客户端只读服务端同步的状态——MotionFrame 自定义包（绝对 double 位置）+ DataTracker
 * （PHASE、DOOR、FACING、TARGET_Y），本类负责把它们接到渲染（CabinRenderer）、承托（CabinMotion）、
 * 音效（CabinRunningSound）与选站面板（ElevatorScreen）上；移动包仍由服务端进行权威校验。
 */
public class EasyelevatorClient implements ClientModInitializer {
    /** 客户端入口的日志：启动时记一行"文件对话框走哪条路"，便于排查 headless 相关问题。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("easyelevator/client");
    // 轿厢实体 id -> 正在播放的运行声，保证同一轿厢只有一条循环音效；断线与 Phase 离开 MOVING 时都会清理。
    private final Map<Integer,CabinRunningSound> sounds=new HashMap<>();
    /**
     * 门音效资源包是否已经在本会话里检查过（只需检查一次：包在磁盘上，不在每次进服时变化）。
     *
     * <p>为什么需要这个标记：客户端进服时会让 {@link DoorSoundPack#reloadIfNeeded} 扫包并可能启用它，
     * 那是唯一需要它的时刻；每刻都扫一次纯属浪费。
     */
    private boolean doorSoundPackChecked;
    /**
     * 本刻是否收到过新的门槽音频、等着把它重建进资源包。
     *
     * <p>存在的理由：进服补齐会连续下发多条 {@code DoorSoundData}，逐条触发 {@code reloadResources()}
     * 会把整个资源管理器重建很多次（几百毫秒的卡顿叠加）。攒到刻末做一次即可，
     * 因为玩家不可能在同一刻内分辨出两次重载的差别。
     */
    private boolean doorSoundReloadPending;

    /** 客户端初始化。副作用：注册实体渲染器与两个 S2C 包处理器，并挂载客户端刻与断线事件回调；不改世界状态。 */
    @Override
    public void onInitializeClient() {
        // 启动时把"这个进程能不能弹 AWT 窗口"记一行：headless 时门设置面板的"选择文件"会改走系统原生对话框，
        // 出问题时这一行能直接说明走的是哪条路（免去再猜一轮）。
        LOGGER.info("File dialog backend: {}", FilePicker.isHeadless() ? "native (AWT is headless)" : "AWT");
        // 拖拽上传：给窗口挂 GLFW 的 drop 回调。这是自定义到站音效最方便的入口——不依赖 AWT，
        // 也不依赖 PowerShell 弹窗（见 FileDropHandler）。
        // 这里只是第一次尝试；窗口若还没就绪，下面的刻回调会继续重试（挂上后函数自己就变成空操作）。
        FileDropHandler.register(net.minecraft.client.MinecraftClient.getInstance());
        // 四种轿厢（普通 / 高速 / 观光 / 强力）共用同一个渲染器：泛型参数取共同的父类，
        // 因此每个实体类型各注册一次即可；外观差异（观光型号的玻璃墙、强力型号的重载内饰与双灯补光）
        // 由实体自身的 glassWalls() / heavyDuty() 决定，而不是按实体类型分支——将来加型号时这里只需要多一行注册。
        EntityRendererRegistry.register(Easyelevator.CABIN,CabinRenderer::new);
        EntityRendererRegistry.register(Easyelevator.HIGH_SPEED_CABIN,CabinRenderer::new);
        EntityRendererRegistry.register(Easyelevator.OBSERVATION_CABIN,CabinRenderer::new);
        EntityRendererRegistry.register(Easyelevator.POWERFUL_CABIN,CabinRenderer::new);
        // 楼层门门扇是连续滑动的几何，方块模型做不到逐帧插值，因此交给方块实体渲染器绘制（门框仍由方块模型画）。
        BlockEntityRendererRegistry.register(Easyelevator.LANDING_DOOR_BE,LandingDoorRenderer::new);
        // 网络回调不在主线程：所有客户端状态修改都必须回到客户端线程（context.client().execute）执行，避免数据竞争。
        ClientPlayNetworking.registerGlobalReceiver(ElevatorNetworking.MotionFrame.ID,(payload,context)->
                context.client().execute(()->CabinMotion.receive(payload)));
        // 服务端校验过乘客身份与站点列表后才发 OpenPanel，这里直接开界面；面板内容全部来自包，不信任客户端本地状态。
        ClientPlayNetworking.registerGlobalReceiver(ElevatorNetworking.OpenPanel.ID,(payload,context)->
                context.client().execute(()->context.client().setScreen(new ElevatorScreen(payload))));
        // 停靠计划变化（到达、取消、新请求）时服务端会推 PanelState：只刷新"已经为这辆轿厢打开的面板"，
        // 因此不会给没开面板的乘客弹出界面；面板里据此把已加入计划的站点标红。
        ClientPlayNetworking.registerGlobalReceiver(ElevatorNetworking.PanelState.ID,(payload,context)->
                context.client().execute(()->{
                    if(context.client().currentScreen instanceof ElevatorScreen screen && screen.entityId()==payload.entityId())
                        screen.applyState(payload.planned(),payload.baseFloorY());
                }));
        // 厅外呼叫面板：右键楼层门时服务端下发 OpenHallPanel，这里直接开界面（内容全部来自包，不信任本地状态）。
        ClientPlayNetworking.registerGlobalReceiver(ElevatorNetworking.OpenHallPanel.ID,(payload,context)->
                context.client().execute(()->context.client().setScreen(new LandingDoorScreen(payload))));
        // 点亮状态刷新（登记成功 / 轿厢到站清扫 / 门被拆）：只更新"已经为这个站点打开的面板"，绝不会主动弹界面。
        ClientPlayNetworking.registerGlobalReceiver(ElevatorNetworking.HallPanelState.ID,(payload,context)->
                context.client().execute(()->{
                    if(context.client().currentScreen instanceof LandingDoorScreen screen && screen.station().equals(payload.station()))
                        screen.applyState(payload.up(),payload.down());
                }));
        // 门专属设置面板（潜行右键楼层门时服务端下发 OpenDoorPanel）：打开或刷新界面。
        // 面板内容全部来自包（单扇门的设置存在方块实体里），客户端不推导、不缓存，因此"别人改过的设置"也如实显示。
        ClientPlayNetworking.registerGlobalReceiver(ElevatorNetworking.OpenDoorPanel.ID,(payload,context)->
                context.client().execute(()->{
                    // 已经为同一扇门开着面板时只刷新内容：避免每次点按钮都重建界面（会打断连点）。
                    if(context.client().currentScreen instanceof DoorSoundScreen screen && screen.station().equals(payload.station()))
                        screen.apply(payload.enabled(),payload.choice(),payload.baseFloor(),payload.preview());
                    // 广播只刷新：不能抢走旁观者的游戏画面、背包或另一扇门的面板。
                    else if (payload.open()) context.client().setScreen(new DoorSoundScreen(payload));
                }));
        // 收到某个门槽的音频内容（服务端分发、或进服补齐的回应）：存进权威副本 -> 标记待重载。
        // 这一步让"上传者以外的玩家、以及上传之后才进服的人"也能听到同一段音频。
        // 只标记、不立刻重载：进服补齐会连着来很多条，逐条 reloadResources 会让资源管理器重建很多次
        // （每一次都弹那个红色 Mojang 画面）；真正的重载统一放在本刻末尾做一次。
        ClientPlayNetworking.registerGlobalReceiver(ElevatorNetworking.DoorSoundData.ID,(payload,context)->
                context.client().execute(()->{
                    if(payload.bytes().length==0) return; // 服务端也没有这份音频：保持静音，不做无谓重载
                    if(DoorSoundPersistence.store(payload.slot(),payload.bytes())) doorSoundReloadPending=true;
                }));
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            // 拖拽回调的重试：窗口可能在 onInitializeClient 时还没建好，那时挂不上；
            // 挂上之后 register 内部一行判断就返回，每刻调用没有代价。
            FileDropHandler.register(client);
            // 进服后的第一刻：把磁盘上的运行时资源包接上，并向服务端索取本机缺少的自定义音频。
            // 只做一次。注意 DoorSoundPack.ensureReady 内部会先比对内容指纹——音频没变时
            // <b>不会</b>重载资源，因此进存档这条最常走的路不会闪红屏（见该方法的说明）。
            if(!doorSoundPackChecked && client.world!=null) {
                doorSoundPackChecked=true;
                DoorSoundPack.ensureReady(client);
                // 请服务端把它现有的全部门槽音频发过来；服务端逐条回 DoorSoundData，本机按需存盘并重载。
                // 这样"音频上传之后才进服的玩家"也能听到同一段音频（专用服务器同样成立）。
                ClientPlayNetworking.send(new ElevatorNetworking.RequestDoorSound(ElevatorNetworking.RequestDoorSound.ALL_SLOTS));
            }
            // 本刻收到过新的门槽音频：一次性把它们重建进资源包并重载。
            // 放在刻末而不是收包处，是为了把"进服补齐"这种连续多条合并成一次重载。
            if(doorSoundReloadPending) {
                doorSoundReloadPending=false;
                DoorSoundPack.ensureReady(client);
            }
            // 先清理：实体卸载/移除或 Phase 离开 MOVING（到站、受阻、卡在门口）就停止运行声并移出映射；
            // removeIf 内返回 true 表示删除该条目，与 stop() 一样都是幂等的。
            sounds.entrySet().removeIf(entry->{
                var e=client.world==null?null:client.world.getEntityById(entry.getKey());
                if(!(e instanceof AbstractCabinEntity cabin) || cabin.isRemoved() || cabin.phase()!=ElevatorController.Phase.MOVING) {
                    client.getSoundManager().stop(entry.getValue());return true;
                }
                return false;
            });
            if(client.world==null) return;
            // 再补充：只为本刻处于 MOVING 的轿厢建立音效；computeIfAbsent 保证不会重复播放同一条循环。
            // 用实体 id 而非实体引用作键，避免实体对象在卸载后被旧映射长期持有。
            for(var e:client.world.getEntities()) if(e instanceof AbstractCabinEntity cabin && cabin.phase()==ElevatorController.Phase.MOVING)
                sounds.computeIfAbsent(cabin.getId(),id->{var sound=new CabinRunningSound(cabin);client.getSoundManager().play(sound);return sound;});
        });
        // 断线：停止所有循环音效并清空映射，同时丢弃 CabinMotion 的插值历史与本地乘客标记，
        // 否则进入新世界后可能拿旧世界的样本继续插值（实体 id 会复用）。
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{
            sounds.values().forEach(client.getSoundManager()::stop);sounds.clear();CabinMotion.clear();
            // 换个服务器/存档要重新检查资源包与补齐音频：不同的服务器可能配了不同的自定义音效。
            doorSoundPackChecked=false; doorSoundReloadPending=false;
        });
    }
}
