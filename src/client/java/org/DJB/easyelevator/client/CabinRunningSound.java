package org.DJB.easyelevator.client;

import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorParameters;

/** One positional loop per tracked car. Stops on arrival, obstruction, unload or disconnect.
 * 每个被追踪的轿厢对应一条循环播放的位置音效（运行声）。
 * 在整体架构中的位置：纯客户端表现层，只读取服务端同步的 Phase（DataTracker）；
 * 由 EasyelevatorClient 在 Phase == MOVING 时创建、离开 MOVING（到站/受阻/卸载/断线）时停止，
 * 因此运行声的启停与原子的状态机 Phase 严格一致，不会出现"门开着还在响"的情况。
 */
public final class CabinRunningSound extends MovingSoundInstance {
    private final AbstractCabinEntity cabin; // 声源跟随的轿厢；每刻读取其位置与 Phase
    /** 创建一条随轿厢移动的循环运行音效，并立即把声源定位到轿厢当前位置。
     * @param cabin 目标轿厢
     * 副作用：注册到客户端 SoundManager 的由调用方负责；本构造只设置循环、音量（RUNNING_VOLUME）与音高（SOUND_PITCH）。
     */
    public CabinRunningSound(AbstractCabinEntity cabin) {
        // 类别 BLOCKS：与方块类音效共用音量滑块；SoundInstance.createRandom() 提供随机种子以避免多条音效相位完全同步。
        super(Easyelevator.RUNNING,SoundCategory.BLOCKS,SoundInstance.createRandom());
        // repeatDelay = 0 刻：平滑无缝循环，不留静音间隔。音量 0.6、音高 1f 见 ElevatorParameters。
        this.cabin=cabin;repeat=true;repeatDelay=0;volume=ElevatorParameters.RUNNING_VOLUME;pitch=ElevatorParameters.SOUND_PITCH;updatePosition();
    }
    /** 把声源对齐到轿厢中心上方的 Y（轿厢中心 + 1 格），单位为格；使听感来自轿厢中部而不是底面。 */
    private void updatePosition() { x=cabin.getX();y=cabin.getY()+1;z=cabin.getZ(); }
    /** 每刻（客户端）检查是否继续播放。
     * 副作用：轿厢被移除或 Phase 不再是 MOVING 时调用 setDone() 终止这条循环音效；
     * 否则同步声源坐标，让声音平滑跟随轿厢升降（位置音效由声音引擎自行做多普勒/衰减）。
     */
    @Override public void tick() {
        if(cabin.isRemoved() || cabin.phase()!=ElevatorController.Phase.MOVING) setDone();
        else updatePosition();
    }
}
