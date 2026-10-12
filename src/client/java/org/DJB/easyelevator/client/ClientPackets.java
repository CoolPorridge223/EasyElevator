package org.DJB.easyelevator.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.PacketByteBuf;
import org.DJB.easyelevator.network.Id;
import org.DJB.easyelevator.network.Payload;
import org.DJB.easyelevator.network.PayloadCodec;

/**
 * 客户端侧的收发层：与 {@code network/Packets} 对称，同样只碰 Fabric 通道 API。
 *
 * <p>为什么单独一个类而不是放进 {@code network}：工程启用了 Loom 的
 * {@code splitEnvironmentSourceSets()}，主源集编译时根本看不到客户端类（{@code MinecraftClient}、
 * {@code ClientPlayNetworking}），因此客户端收发必须待在 client 源集里。
 *
 * <p>关键不变量：接收回调运行在<b>网络线程</b>，缓冲必须在回调内同步解码完；业务处理器照旧自己用
 * {@code context.client().execute(...)} 切回客户端线程。
 */
public final class ClientPackets {
    private ClientPackets() { }

    /**
     * 向服务端发送负载（C2S）。
     *
     * @param payload 负载；通道名与线格式都取自负载自身
     */
    public static void sendClient(Payload payload) {
        PacketByteBuf buf = PacketByteBufs.create();
        payload.write(buf);
        ClientPlayNetworking.send(payload.getId().id(), buf);
    }

    /**
     * 注册一个 S2C 通道的客户端接收器。
     *
     * @param id 通道名
     * @param codec 该通道的线格式
     * @param handler 业务处理器；收到的已经是解码后的负载
     * @param <T> 负载类型
     */
    public static <T extends Payload> void on(Id<T> id, PayloadCodec<PacketByteBuf, T> codec, ClientHandler<T> handler) {
        ClientPlayNetworking.registerGlobalReceiver(id.id(), (client, networkHandler, buf, responseSender) ->
                handler.receive(codec.decode(buf), new ClientContext(client)));
    }

    /**
     * 客户端接收回调的上下文；只为让负载处理器里的写法与 1.21 侧（{@code context.client()}）逐字相同。
     *
     * <p>{@code player()} 走访问器而不是构造时快照：网络线程收到包时 {@code client.player} 可能尚未就绪，
     * 需要时再取才拿得到当时的玩家。
     *
     * @param client 客户端实例
     */
    public record ClientContext(MinecraftClient client) {
        /** @return 当前客户端玩家；尚未进入世界时为 null */
        public ClientPlayerEntity player() { return client.player; }
    }

    /** 客户端负载处理器。 */
    public interface ClientHandler<T extends Payload> {
        void receive(T payload, ClientContext context);
    }
}
