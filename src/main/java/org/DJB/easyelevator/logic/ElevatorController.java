package org.DJB.easyelevator.logic;

import java.util.ArrayDeque;
import java.util.List;

/** Server-authoritative, deterministic controller; deliberately independent of rendering/Minecraft. */
public final class ElevatorController {
    public enum Phase { OPEN, CLOSING, MOVING, OPENING, BLOCKED }
    public record Stop(long id, int y) { }
    public interface Environment {
        boolean valid(Stop stop);
        boolean canMove(double from, double to);
        boolean doorwayBlocked();
        void arrived(Stop stop);
    }
    public static final double SPEED = ElevatorParameters.SPEED;
    public static final int DOOR_TICKS = ElevatorParameters.DOOR_TICKS,
            DWELL_TICKS = ElevatorParameters.DWELL_TICKS, MAX_REQUESTS = ElevatorParameters.MAX_REQUESTS;
    private final ArrayDeque<Stop> queue = new ArrayDeque<>();
    private Stop target;
    private Phase phase = Phase.OPEN;
    private float door = 1;
    private int dwell = DWELL_TICKS;

    public Phase phase() { return phase; }
    public float door() { return door; }
    public Stop target() { return target; }
    public List<Stop> pending() { return List.copyOf(queue); }
    public boolean request(Stop stop, double y) {
        if (stop.equals(target) || queue.contains(stop)) return true;
        if (Math.abs(y - stop.y()) <= ElevatorParameters.POSITION_EPSILON && (phase == Phase.OPEN || phase == Phase.OPENING)) { dwell = DWELL_TICKS; return true; }
        if (queue.size() >= MAX_REQUESTS) return false;
        queue.addLast(stop);
        return true;
    }
    public double tick(double y, Environment env) {
        queue.removeIf(s -> !env.valid(s));
        if (target != null && !env.valid(target)) {
            // Never open between floors or forget a cancelled trip. A replacement request can recover it.
            target = null;
            phase = Phase.BLOCKED;
        }
        switch (phase) {
            case OPEN -> {
                if (dwell > 0) dwell--;
                if (dwell == 0 && !queue.isEmpty()) { target = queue.removeFirst(); phase = Phase.CLOSING; }
            }
            case CLOSING -> {
                if (env.doorwayBlocked()) { phase = Phase.OPENING; break; }
                door = Math.max(0, door - 1f / DOOR_TICKS);
                if (door < .0001f) { door = 0; phase = Phase.MOVING; }
            }
            case MOVING, BLOCKED -> {
                if (target == null && !queue.isEmpty()) target = queue.removeFirst();
                if (target == null) break;
                if (door > 0) { phase = Phase.CLOSING; break; }
                double remaining = target.y() - y;
                // No minimum movement quantum: even a sub-micrometre final distance is preserved.
                double next = Math.abs(remaining) <= SPEED + ElevatorParameters.POSITION_EPSILON
                        ? target.y() : y + Math.copySign(SPEED, remaining);
                if (!env.canMove(y, next)) { phase = Phase.BLOCKED; break; }
                phase = Phase.MOVING;
                y = next;
                if (y == target.y()) {
                    y = target.y(); env.arrived(target); target = null; phase = Phase.OPENING;
                }
            }
            case OPENING -> {
                door = Math.min(1, door + 1f / DOOR_TICKS);
                if (door > .9999f) {
                    door = 1; phase = Phase.OPEN; dwell = DWELL_TICKS;
                    // Anti-crush reopening preserves the interrupted request.
                    if (target != null) { queue.addFirst(target); target = null; }
                }
            }
        }
        return y;
    }
    public void restore(Phase phase, float door, Stop target, List<Stop> pending) {
        this.phase = phase; this.door = Math.max(0, Math.min(1, door)); this.target = target;
        queue.clear(); pending.stream().distinct().limit(MAX_REQUESTS).forEach(queue::addLast);
        dwell = DWELL_TICKS;
        if (phase == Phase.MOVING) { this.phase = Phase.BLOCKED; this.door = 0; }
    }
}
