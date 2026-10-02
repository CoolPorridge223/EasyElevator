package org.DJB.easyelevator.client;

import net.minecraft.util.Util;
import org.DJB.easyelevator.logic.ElevatorStatus;

/**
 * 运行方向的<b>闪烁箭头</b>：楼层门框顶部与轿厢内面板不再写"电梯上行 / 电梯下行 / 停靠"，
 * 而是用 ▲ / ▼ 表示方向、<b>停靠时整块留空</b>，并且让箭头一闪一闪。
 *
 * <p>为什么抽成一个类：两处显示（{@link LandingDoorRenderer} 与 {@link CabinRenderer}）必须同源——
 * 同一状态用同一字符、闪烁同相，玩家从走廊看向轿厢时不会看到两个节奏不一致的箭头；
 * 字号、字符宽度这些约定也集中在一处，改一个地方两处一起变。
 *
 * <p>闪烁用墙钟毫秒（{@link Util#getMeasuringTimeMs()}）而不是游戏刻：渲染帧率与游戏刻无关，
 * 而且打开暂停菜单（游戏刻停止）时箭头仍会继续闪，不会突然定住。
 *
 * <p>纯客户端表现层：不读世界状态、不发包；状态本身仍来自服务端同步的 Phase + 目的站高度
 * （{@link ElevatorStatus#of}），服务端与客户端算出同一个结果。
 */
final class StatusArrow {
    /** 工具类，禁止实例化。 */
    private StatusArrow() { }
    /** 单向亮/灭时长（毫秒）：亮 0.5 秒、灭 0.5 秒，一个周期 1 秒，容易辨认又不刺眼。 */
    static final long BLINK_MS=500;
    /** 上箭头字符（Unicode U+25B2），与楼层门呼叫面板上的"上行"按钮同一个字符。 */
    static final String UP="▲";
    /** 下箭头字符（Unicode U+25BC）。 */
    static final String DOWN="▼";

    /**
     * @param status 运行状态
     * @return 是否"正在运行"：只有上行 / 下行才显示箭头，停靠（含开门、暂停等静止相位）一律留空
     */
    static boolean moving(ElevatorStatus status) { return status==ElevatorStatus.UP||status==ElevatorStatus.DOWN; }

    /** @return 闪烁的"亮"相；灭的那半个周期两处显示同时留空，因此视觉上是同一个箭头在闪 */
    static boolean lit() { return (Util.getMeasuringTimeMs()/BLINK_MS)%2==0; }

    /**
     * 取当前该显示的箭头字符。
     *
     * @param status 运行状态
     * @return 上行 {@link #UP}、下行 {@link #DOWN}、停靠或灭相时为空串（调用方按空串留空处理）
     */
    static String glyph(ElevatorStatus status) {
        if (!moving(status)||!lit()) return "";
        return status==ElevatorStatus.UP?UP:DOWN;
    }

    /**
     * 取"箭头本身"的宽度，用于给显示留位。
     *
     * <p>为什么要按固定宽度留位：箭头会闪烁（半周期不画字），如果居中排版时按"当前画不画"算宽度，
     * 楼层号就会跟着左右跳动；这里始终按箭头字符的宽度留位，箭头只在原位亮灭。
     *
     * @param textRenderer 字体渲染器
     * @return 上/下箭头字符宽度的较大值（像素）
     */
    static int width(net.minecraft.client.font.TextRenderer textRenderer) {
        return Math.max(textRenderer.getWidth(net.minecraft.text.Text.literal(UP)),textRenderer.getWidth(net.minecraft.text.Text.literal(DOWN)));
    }
}
