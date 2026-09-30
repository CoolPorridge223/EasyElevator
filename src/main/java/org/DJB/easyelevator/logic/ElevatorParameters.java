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
    // Landing doors occupy local Z=1.3125..1.5. Recess the entire front, not just
    // the leaves, so the floor, roof and side walls also clear the landing frame.
    public static final double CABIN_FRONT_Z = 1.3;
    public static final double CABIN_DOOR_BACK_Z = CABIN_FRONT_Z - .2;
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
