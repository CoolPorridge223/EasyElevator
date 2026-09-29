package org.DJB.easyelevator.client;

import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorParameters;

/** One positional loop per tracked car. Stops on arrival, obstruction, unload or disconnect. */
public final class CabinRunningSound extends MovingSoundInstance {
    private final CabinEntity cabin;
    public CabinRunningSound(CabinEntity cabin) {
        super(Easyelevator.RUNNING,SoundCategory.BLOCKS,SoundInstance.createRandom());
        this.cabin=cabin;repeat=true;repeatDelay=0;volume=ElevatorParameters.RUNNING_VOLUME;pitch=ElevatorParameters.SOUND_PITCH;updatePosition();
    }
    private void updatePosition() { x=cabin.getX();y=cabin.getY()+1;z=cabin.getZ(); }
    @Override public void tick() {
        if(cabin.isRemoved() || cabin.phase()!=ElevatorController.Phase.MOVING) setDone();
        else updatePosition();
    }
}
