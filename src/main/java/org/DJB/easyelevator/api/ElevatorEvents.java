package org.DJB.easyelevator.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;

/** Server hooks for integrations. Client animations should read tracked phase and door progress. */
// 中文说明：面向整合方的服务端事件钩子。客户端动画不要订阅这些事件——它们只在服务端触发，
// 客户端应改为读取服务端同步的 DataTracker（PHASE、DOOR、FACING、TARGET_Y）与 MotionFrame 数据包。
/**
 * 模组对外扩展 API：以 Fabric 事件的形式暴露电梯状态变化与到站通知。
 *
 * <p>在整体架构中的位置：位于服务端权威链路的下游。状态机
 * {@link org.DJB.easyelevator.logic.ElevatorController} 本身不引用任何 Minecraft 类，
 * 也不抛事件；事件由世界适配层 {@link CabinEntity#tick()} 在每刻比对前后 Phase 后统一派发，
 * 因此所有回调都保证运行在服务端主线程、且与世界状态一致。</p>
 *
 * <p>关键不变量：事件在服务端触发且每刻至多触发一次；回调抛出的异常会中断同一事件剩余回调的派发，
 * 并向上冒泡打断 {@link CabinEntity#tick()}，所以整合方必须自行捕获异常且不得在此做耗时的世界查询。</p>
 */
public final class ElevatorEvents {

    // 纯静态事件容器，禁止实例化：避免整合方误以为需要自行创建实例才能订阅。
    private ElevatorEvents() { }

    // 电梯运行状态变化接口
    // 触发时机：CabinEntity.tick() 每刻写入 DataTracker 之后，若 Phase 与上一刻不同，则以 (before, after) 调用一次。
    /**
     * 电梯 Phase 变化回调。
     *
     * <p>状态机 Phase 循环为 OPEN -> CLOSING -> MOVING -> OPENING -> OPEN；
     * BLOCKED 不是失败而是暂停，条件恢复后会继续原行程。回调中可安全读取
     * {@link CabinEntity#phase()}、{@link CabinEntity#doorProgress(float)} 等已同步状态。</p>
     */
    public interface PhaseChanged {
        /**
         * 在轿厢 Phase 发生变化时被调用。
         *
         * @param cabin 发生变化的轿厢实体（服务端实例，读方块/实体前请确认所在区块已加载）
         * @param before 变化前的 Phase
         * @param after 变化后的 Phase，等于 {@code cabin.phase()}，与 before 必然不同
         */
        void onChange(CabinEntity cabin, ElevatorController.Phase before, ElevatorController.Phase after);
    }

    // 电梯到达接口
    // 触发时机：状态机判定轿厢精确到站（误差 <= POSITION_EPSILON = 1e-7 格）并通过 Environment.arrived 回调时，
    // 与到站音效 ARRIVAL 在同一刻派发。
    /**
     * 电梯到站回调。
     *
     * <p>与“进入 OPENING”不同：本回调表示轿厢位置已精确对齐站点，此时楼层门联锁才允许解锁。</p>
     */
    public interface Arrived {
        /**
         * 在轿厢精确到达某个站点时被调用。
         *
         * @param cabin 到站的轿厢实体
         * @param floorY 该站点的 Y 高度，单位为格（方块），等于站点根方块所在层的 Y 坐标
         */
        void onArrival(CabinEntity cabin, int floorY);
    }

    /**
     * Phase 变化事件：数组回调语义——按监听器注册顺序依次同步调用所有回调。
     *
     * <p>Fabric 的数组回调实现会在监听器集合变化时重建 invoker，因此注册/注销监听器后
     * 不必重新获取本字段，直接使用即可。任一回调抛异常会跳过其后注册的回调，
     * 且没有隔离机制，故回调内应自建 try/catch。</p>
     */
    public static final Event<PhaseChanged> PHASE_CHANGED = EventFactory.createArrayBacked(PhaseChanged.class,
            callbacks -> (cabin, before, after) -> {
                // Java 8 lambda 无法在运行时重载参数名，这里必须用 c 逐个转发（Fabric 无“批量回调”API）。
                for (var c : callbacks)
                    c.onChange(cabin, before, after);
    });

    /**
     * 到站事件：数组回调语义与 {@link #PHASE_CHANGED} 相同。
     *
     * <p>触发点紧挨着 ARRIVAL 音效播放，因此该事件每到达一个站点只会触发一次；
     * BLOCKED 后恢复运行的行程不会重复触发，除非轿厢重新经过一次精确到站判定。</p>
     */
    public static final Event<Arrived> ARRIVED = EventFactory.createArrayBacked(Arrived.class,
            callbacks -> (cabin, floorY) -> {
                for (var c : callbacks)
                    c.onArrival(cabin, floorY);
    });
}
