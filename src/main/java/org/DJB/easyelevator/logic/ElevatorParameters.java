package org.DJB.easyelevator.logic;

/** Compile-time tuning values. Units and coupled geometry are documented in docs/PARAMETERS.md. */
public final class ElevatorParameters {
    private ElevatorParameters() { }
    public static final int TICKS_PER_SECOND = 20;
    public static final double SPEED = 0.20; // 4 blocks/second, twice the original 0.10
    public static final double POSITION_EPSILON = 1.0e-7;
    public static final int DOOR_TICKS = 20;
    public static final int DWELL_TICKS = 40;
    public static final int MAX_REQUESTS = 128;
    public static final int INTERPOLATION_DELAY_TICKS = 2;
    public static final int MOTION_HISTORY_SIZE = 32;
    public static final int MOTION_RESET_GAP_TICKS = 20;
    public static final int MOTION_STALE_TICKS = 10;
    public static final double MOTION_SNAP_DISTANCE = 4.0;
    public static final int MOTION_SETTLE_TICKS = INTERPOLATION_DELAY_TICKS + 2;
    public static final float EVENT_VOLUME = .8f;
    public static final float RUNNING_VOLUME = .6f;
    public static final float SOUND_PITCH = 1f;
}
