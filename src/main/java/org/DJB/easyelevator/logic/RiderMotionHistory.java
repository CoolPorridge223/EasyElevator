package org.DJB.easyelevator.logic;

import java.util.ArrayDeque;

/** Server-owned coordinate frames. Clients identify a sample, never supply the platform displacement. */
public final class RiderMotionHistory {
    public static final int MAX_AGE = 60;
    private record Frame(long tick, double y) { }
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();

    public void record(long tick, double y) {
        if (!Double.isFinite(y)) return;
        if (!frames.isEmpty() && tick <= frames.getLast().tick()) return;
        frames.addLast(new Frame(tick, y));
        while (frames.size() > MAX_AGE + 1) frames.removeFirst();
    }

    public double height(long tick, long now) {
        if (tick > now || tick < now - MAX_AGE) return Double.NaN;
        for (Frame frame : frames) if (frame.tick() == tick) return frame.y();
        return Double.NaN;
    }

    public static double rebase(double playerY, double seenCabinY, double currentCabinY) {
        return currentCabinY + (playerY - seenCabinY);
    }
}
