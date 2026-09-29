package org.DJB.easyelevator.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.logic.MotionTimeline;
import org.DJB.easyelevator.network.ElevatorNetworking;
import java.util.Map;
import java.util.WeakHashMap;

/** Rendering only. Authoritative positions, collision checks and teleport confirmations stay intact. */
public final class CabinMotion {
    private static final Map<CabinEntity, Track> TRACKS = new WeakHashMap<>();
    private static CabinEntity localRiderCabin;
    private static final class Track {
        final MotionTimeline timeline = new MotionTimeline();
        long receivedAt;
        double lastY, riderOffset = Double.NaN;
        boolean initialized;
    }
    private CabinMotion() { }
    public static void receive(ElevatorNetworking.MotionFrame frame) {
        var client = MinecraftClient.getInstance();
        if (client.world == null || !Double.isFinite(frame.y())
                || !(client.world.getEntityById(frame.entityId()) instanceof CabinEntity cabin)) return;
        Track track = TRACKS.computeIfAbsent(cabin, c -> new Track());
        long now = client.world.getTime();
        track.timeline.add(frame.tick(), frame.y(), now, track.initialized ? track.lastY : cabin.getY());
        track.receivedAt = now; track.lastY = frame.y(); track.initialized = true;
        track.riderOffset = frame.riderOffset();
        if (Double.isFinite(frame.riderOffset())) localRiderCabin = cabin;
        else if (localRiderCabin == cabin) localRiderCabin = null;
    }
    public static double renderY(CabinEntity cabin, float delta) {
        double vanilla = MathHelper.lerp(delta, cabin.lastRenderY, cabin.getY());
        Track track = TRACKS.get(cabin);
        if (track == null || cabin.getWorld().getTime() - track.receivedAt > ElevatorParameters.MOTION_STALE_TICKS) return vanilla;
        return track.timeline.sample(cabin.getWorld().getTime() + delta, vanilla);
    }
    public static double cameraOffset(Entity focused, float delta) {
        var client = MinecraftClient.getInstance();
        CabinEntity cabin = localRiderCabin;
        if (focused != client.player || cabin == null) return 0;
        Track track = TRACKS.get(cabin);
        if (cabin.isRemoved() || client.world != cabin.getWorld() || focused.isSpectator() || focused.hasVehicle()
                || track == null || !Double.isFinite(track.riderOffset)
                || cabin.getWorld().getTime() - track.receivedAt > ElevatorParameters.MOTION_STALE_TICKS
                || Math.abs(focused.getX() - cabin.getX()) > 1.5 || Math.abs(focused.getZ() - cabin.getZ()) > 1.5
                || Math.abs(focused.getY() - track.lastY - track.riderOffset) > .5) {
            localRiderCabin = null;
            return 0;
        }
        double visualY = renderY(cabin, delta);
        if (cabin.phase() != org.DJB.easyelevator.logic.ElevatorController.Phase.MOVING
                && Math.abs(visualY - track.lastY) <= ElevatorParameters.POSITION_EPSILON) return 0;
        return visualY + track.riderOffset - MathHelper.lerp(delta, focused.prevY, focused.getY());
    }
    public static void clear() { TRACKS.clear(); localRiderCabin = null; }
}
