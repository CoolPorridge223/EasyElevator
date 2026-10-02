package org.DJB.easyelevator.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.network.ElevatorNetworking;
import java.util.HashMap;
import java.util.Map;

/** 客户端模组入口：注册渲染器、S2C 数据包处理器、客户端刻回调与断线清理。
 * 在整体架构中的位置：客户端只读服务端同步的状态——MotionFrame 自定义包（绝对 double 位置）+ DataTracker
 * （PHASE、DOOR、FACING、TARGET_Y），本类负责把它们接到渲染（CabinRenderer）、相机（CameraMixin/CabinMotion）、
 * 音效（CabinRunningSound）与选站面板（ElevatorScreen）上，从不向服务端写入世界状态（只发 SelectStop 请求）。
 */
public class EasyelevatorClient implements ClientModInitializer {
    // 轿厢实体 id -> 正在播放的运行声，保证同一轿厢只有一条循环音效；断线与 Phase 离开 MOVING 时都会清理。
    private final Map<Integer,CabinRunningSound> sounds=new HashMap<>();

    /** 客户端初始化。副作用：注册实体渲染器与两个 S2C 包处理器，并挂载客户端刻与断线事件回调；不改世界状态。 */
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(Easyelevator.CABIN,CabinRenderer::new);
        // 网络回调不在主线程：所有客户端状态修改都必须回到客户端线程（context.client().execute）执行，避免数据竞争。
        ClientPlayNetworking.registerGlobalReceiver(ElevatorNetworking.MotionFrame.ID,(payload,context)->
                context.client().execute(()->CabinMotion.receive(payload)));
        // 服务端校验过乘客身份与站点列表后才发 OpenPanel，这里直接开界面；面板内容全部来自包，不信任客户端本地状态。
        ClientPlayNetworking.registerGlobalReceiver(ElevatorNetworking.OpenPanel.ID,(payload,context)->
                context.client().execute(()->context.client().setScreen(new ElevatorScreen(payload))));
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            // 先清理：实体卸载/移除或 Phase 离开 MOVING（到站、受阻、卡在门口）就停止运行声并移出映射；
            // removeIf 内返回 true 表示删除该条目，与 stop() 一样都是幂等的。
            sounds.entrySet().removeIf(entry->{
                var e=client.world==null?null:client.world.getEntityById(entry.getKey());
                if(!(e instanceof CabinEntity cabin) || cabin.isRemoved() || cabin.phase()!=ElevatorController.Phase.MOVING) {
                    client.getSoundManager().stop(entry.getValue());return true;
                }
                return false;
            });
            if(client.world==null) return;
            // 再补充：只为本刻处于 MOVING 的轿厢建立音效；computeIfAbsent 保证不会重复播放同一条循环。
            // 用实体 id 而非实体引用作键，避免实体对象在卸载后被旧映射长期持有。
            for(var e:client.world.getEntities()) if(e instanceof CabinEntity cabin && cabin.phase()==ElevatorController.Phase.MOVING)
                sounds.computeIfAbsent(cabin.getId(),id->{var sound=new CabinRunningSound(cabin);client.getSoundManager().play(sound);return sound;});
        });
        // 断线：停止所有循环音效并清空映射，同时丢弃 CabinMotion 的插值历史与本地乘客标记，
        // 否则进入新世界后可能拿旧世界的样本继续插值（实体 id 会复用）。
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{sounds.values().forEach(client.getSoundManager()::stop);sounds.clear();CabinMotion.clear();});
    }
}
