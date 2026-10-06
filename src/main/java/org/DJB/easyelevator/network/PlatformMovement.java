package org.DJB.easyelevator.network;

/** Implemented by the server connection mixin; keeps vanilla movement baselines in the cabin frame. */
public interface PlatformMovement {
    boolean easyelevator$canCarry();
    void easyelevator$carried(double dy);
}
