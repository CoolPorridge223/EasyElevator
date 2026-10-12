package org.DJB.easyelevator.network;

import net.minecraft.util.Identifier;

/**
 * 1.20.1 侧的自定义包标识：只有一个通道名。
 *
 * <p>为什么还要保留这个包装：1.21 起每个负载类型都要向 {@code PayloadTypeRegistry} 注册一个
 * {@code CustomPayload.Id}，本模组的 14 个包因此都是 {@code X.ID} / {@code X.CODEC} 的写法。
 * 1.20.1 没有 payload 体系（自定义包就是"通道名 + 字节流"），但为了让两端源文件尽量逐字相同、
 * 让跨版本同步只集中在收发层，这里保留同形的 {@code Id<T>}；其中的类型参数只为让 {@code X.ID}
 * 的声明与取值方式和 1.21 完全一致，不参与任何运行时判断。
 *
 * @param id 通道名（例如 {@code easyelevator:motion_frame}）
 * @param <T> 该通道承载的负载类型
 */
public record Id<T>(Identifier id) {
}
