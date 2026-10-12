package org.DJB.easyelevator.network;

/**
 * 单个自定义负载的线格式读写器（1.20.1 版）。
 *
 * <p>形状与 1.21 的 {@code net.minecraft.network.codec.PacketCodec} 一致（同样的
 * {@code decode}/{@code encode} 方法名与泛型参数顺序），只是缓冲类型从 {@code RegistryByteBuf}
 * 换成 1.20.1 的 {@link net.minecraft.network.PacketByteBuf}。保留这个形状是为了让各负载类里的
 * 匿名实现与 1.21 分支逐字相同，跨版本同步时只需要看收发层。
 *
 * @param <B> 缓冲类型（本模组固定为 {@code PacketByteBuf}）
 * @param <V> 负载类型
 */
public interface PayloadCodec<B, V> {
    /**
     * 从缓冲按固定顺序读回一个负载。
     *
     * @param buf 只读缓冲；<b>必须在接收回调内同步读完</b>（回调返回后缓冲即被释放）
     * @return 解码出的负载
     */
    V decode(B buf);

    /**
     * 按与 {@link #decode} 完全对称的顺序写出一个负载。
     *
     * @param buf 目标缓冲
     * @param value 要写出的负载
     */
    void encode(B buf, V value);
}
