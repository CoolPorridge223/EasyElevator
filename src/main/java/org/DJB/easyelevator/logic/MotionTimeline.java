package org.DJB.easyelevator.logic;

import java.util.ArrayDeque;

/** Bounded, non-extrapolating double-precision timeline, shared by model and local rider camera. */
public final class MotionTimeline {
    private record Sample(long tick, double y) { }
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private double clockOffset;

    public void add(long serverTick, double y, double localTick, double previousY) {
        if (!Double.isFinite(y) || !Double.isFinite(localTick) || !Double.isFinite(previousY)) return;
        Sample last = samples.peekLast();
        if (last != null && serverTick <= last.tick()) return;
        boolean snap = Math.abs(y - previousY) > ElevatorParameters.MOTION_SNAP_DISTANCE;
        if (last == null || serverTick - last.tick() > ElevatorParameters.MOTION_RESET_GAP_TICKS || snap) {
            samples.clear();
            clockOffset = localTick - serverTick;
            samples.add(new Sample(serverTick - 1, snap ? y : previousY));
        }
        samples.add(new Sample(serverTick, y));
        while (samples.size() > ElevatorParameters.MOTION_HISTORY_SIZE) samples.removeFirst();
    }

    public double sample(double localTick, double fallback) {
        if (samples.isEmpty()) return fallback;
        double targetTick = localTick - clockOffset - ElevatorParameters.INTERPOLATION_DELAY_TICKS;
        Sample before = samples.getFirst();
        if (targetTick <= before.tick()) return before.y();
        for (Sample after : samples) {
            if (targetTick <= after.tick()) {
                double alpha = (targetTick - before.tick()) / (after.tick() - before.tick());
                return before.y() + (after.y() - before.y()) * alpha;
            }
            before = after;
        }
        // Never predict through an obstacle or beyond a station when packets stop.
        return samples.getLast().y();
    }
}
