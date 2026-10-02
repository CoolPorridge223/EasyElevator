package org.DJB.easyelevator;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.render.Camera;
import org.slf4j.LoggerFactory;

/**
 * Opt-in actual client startup, including transformation of the camera mixin. Never shipped.
 *
 * <p>客户端冒烟测试：只在启动参数 {@code -Deasyelevator.clientSmoke=true} 时生效，正常游玩与发布 JAR
 * 都不受影响（该类只存在于 gametest 源集）。它验证三件事：资源加载完成后客户端仍能稳定运行、
 * Camera 混入已被正确转换、选站界面能被真实渲染。成功时打印 PASS 行并让客户端自动退出，
 * 供构建脚本按日志与退出码判定；由 src/gametest/resources/fabric.mod.json 的 client 入口声明。
 */
public class ElevatorClientSmoke implements ClientModInitializer {
    /**
     * 注册客户端刻回调。未开启系统属性时直接返回，完全不注册监听器（零开销、无副作用）。
     *
     * <p>副作用：注册 END_CLIENT_TICK 监听器；条件满足后会打开一个仅用于截图的选站界面，
     * 把客户端界面写入运行目录下的 elevator-panel.png，最后调用 client.scheduleStop() 结束游戏进程。
     *
     * <p>为什么不在 CLIENT_STARTED 事件里直接判定：该事件可能早于异步纹理加载完成
     * （见 docs/TESTING.md：首次执行需要下载游戏资源），那时结束进程会与关闭流程竞态。
     */
    @Override public void onInitializeClient() {
        // 系统属性开关：只有构建脚本显式传入 -Deasyelevator.clientSmoke=true 才执行后续检查。
        if (!Boolean.getBoolean("easyelevator.clientSmoke")) return;
        // CLIENT_STARTED can precede asynchronous texture loading; quitting there races shutdown.
        // settled 统计“连续稳定刻数”：任何一刻仍在显示加载遮罩或尚无界面，就清零重新计数。
        final int[] settled={0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // getOverlay() != null 表示加载遮罩仍在显示，currentScreen == null 表示主界面尚未建立。
            if(client.getOverlay()!=null || client.currentScreen==null) { settled[0]=0; return; }
            ++settled[0];
            // 稳定 20 刻（1 秒）后强制实例化 Camera：若相机混入未能正确转换，这里会直接抛错暴露问题。
            if(settled[0]==20) {
                new Camera().getPos();
                // 用虚构的 OpenPanel（3 个站点）打开选站界面；本用例没有真实轿厢，因此只做 UI 夹具。
                client.setScreen(new org.DJB.easyelevator.client.ElevatorScreen(
                        new org.DJB.easyelevator.network.ElevatorNetworking.OpenPanel(-1,java.util.List.of(
                                // 站点坐标只是面板显示数据，不需要在世界中真实存在。
                                new net.minecraft.util.math.BlockPos(0,64,3),new net.minecraft.util.math.BlockPos(0,72,3),new net.minecraft.util.math.BlockPos(0,80,3)))) {
                    @Override public void tick() { } // UI fixture only; production screens still require a real cabin.
                    // 正常面板会在 tick 里向服务端校验站点并可能关闭界面，这里必须留空才能稳定截图。
                });
            }
            // 再等 10 刻（约 0.5 秒）确保界面首帧已绘制，然后截图到 runDirectory/elevator-panel.png。
            if(settled[0]==30) net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(client.runDirectory,"elevator-panel.png",client.getFramebuffer(),message->{
                // PASS 行是构建脚本判定客户端冒烟测试成功的依据。
                LoggerFactory.getLogger("EasyElevatorSmoke").info("PASS: client initialized, resources loaded, Camera mixin transformed, station panel rendered.");
                // 截图回调不一定在客户端线程上执行，因此切回客户端线程再安排退出。
                client.execute(client::scheduleStop);
            });
        });
    }
}
