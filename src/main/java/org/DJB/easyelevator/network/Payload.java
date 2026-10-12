package org.DJB.easyelevator.network;

import net.minecraft.network.PacketByteBuf;

/**
 * 1.20.1 侧的自定义负载：一个通道名 + "把自己写进缓冲"的能力。
 *
 * <p>对应 1.21 的 {@code CustomPayload}：那边接收器直接拿到已解码的对象，这边接收器拿到的是
 * {@link PacketByteBuf}，因此每个负载必须自己提供 {@link #write(PacketByteBuf)}。
 *
 * <p><b>约束</b>：{@link #write} 与同名字段 {@code CODEC} 的 {@code encode} 必须是同一个线格式——
 * 实现在 {@code CODEC} 上的 {@code decode}/{@code encode} 是唯一权威，{@code write} 只是转发，
 * 因此不存在"两处顺序不一致"的可能。
 */
public interface Payload {
    /** @return 本负载的通道名包装；发送方据此选择通道 */
    Id<?> getId();

    /**
     * 把本负载的全部字段按 {@code CODEC} 声明的顺序写入缓冲。
     *
     * @param buf 目标缓冲；由收发层创建，调用方不负责释放
     */
    void write(PacketByteBuf buf);
}
