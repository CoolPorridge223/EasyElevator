package org.DJB.easyelevator.network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 服务端侧的收发层：本模组唯一直接碰 Fabric 通道 API 的地方。
 *
 * <p>职责：把"通道名 + 负载对象"翻译成 1.20.1 的"通道名 + 字节缓冲"，并在接收方向把缓冲解码回对象，
 * 交给各负载类自己的处理器。<b>业务代码（14 个负载类、7 个 C2S 处理器、面板快照与广播）全部不感知
 * 1.20.1 与 1.21 的 API 差异</b>——差异只在本类与 {@code client/ClientPackets} 里。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>接收回调运行在<b>网络线程</b>：缓冲必须在回调内同步解码完（回调返回后即被释放），
 *       因此 {@code decode} 紧跟在回调入口，业务处理器照旧自己用 {@code context.server().execute(...)}
 *       切回服务端主线程——与 1.21 侧的行为逐字一致。</li>
 *   <li>发送方向不做缓冲复用：每个包各建一个缓冲，避免共享缓冲被后续写入污染。</li>
 * </ul>
 */
public final class Packets {
    private Packets() { }

    /**
     * 向一个玩家发送负载：先按负载自己的线格式写进缓冲，再走对应通道。
     *
     * @param player 目标玩家
     * @param payload 负载；通道名与线格式都取自负载自身（{@link Payload#getId()} 与它的 {@code CODEC}）
     */
    public static void sendServer(ServerPlayerEntity player, Payload payload) {
        PacketByteBuf buf = PacketByteBufs.create();
        payload.write(buf);
        ServerPlayNetworking.send(player, payload.getId().id(), buf);
    }

    /**
     * 注册一个 S2C/C2S 通道的服务端接收器。
     *
     * <p>与 1.21 的 {@code registerGlobalReceiver(ID, (payload, context) -> ...)} 同形，
     * 只是多传一个解码器：1.20.1 的通道 API 只给字节缓冲，不给已解码对象。
     *
     * @param id 通道名
     * @param codec 该通道的线格式
     * @param handler 业务处理器；收到的已经是解码后的负载
     * @param <T> 负载类型
     */
    public static <T extends Payload> void onServer(Id<T> id, PayloadCodec<PacketByteBuf, T> codec, ServerHandler<T> handler) {
        ServerPlayNetworking.registerGlobalReceiver(id.id(), (server, player, networkHandler, buf, responseSender) ->
                handler.receive(codec.decode(buf), new ServerContext(server, player)));
    }

    /**
     * 服务端接收回调的上下文；只为让负载处理器里的写法与 1.21 侧（{@code context.server()} /
     * {@code context.player()}）逐字相同。
     *
     * @param server 服务端实例
     * @param player 发来这个包的玩家
     */
    public record ServerContext(MinecraftServer server, ServerPlayerEntity player) { }

    /** 服务端负载处理器。 */
    public interface ServerHandler<T extends Payload> {
        void receive(T payload, ServerContext context);
    }
}
