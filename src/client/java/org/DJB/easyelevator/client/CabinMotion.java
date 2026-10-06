package org.DJB.easyelevator.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.MathHelper;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.network.ElevatorNetworking;
import org.DJB.easyelevator.network.RiderMove;
import java.util.Map;
import java.util.WeakHashMap;

/** Client physics and rendering use the same platform frame; player input remains vanilla. */
public final class CabinMotion {
    private static final Map<AbstractCabinEntity, Track> TRACKS = new WeakHashMap<>();
    private static AbstractCabinEntity tickCabin;
    private static long tickFrame;

    private static final class Track {
        long receivedAt, serverTick = Long.MIN_VALUE;
        double targetY, previousY, physicalY;
        Track(double y) { targetY = previousY = physicalY = y; }
    }

    private CabinMotion() { }

    public static void receive(ElevatorNetworking.MotionFrame frame) {
        var client = MinecraftClient.getInstance();
        if (client.world == null || !Double.isFinite(frame.y())
                || !(client.world.getEntityById(frame.entityId()) instanceof AbstractCabinEntity cabin)) return;
        Track track = TRACKS.computeIfAbsent(cabin, c -> new Track(c.getY()));
        if (frame.tick() <= track.serverTick) return;
        track.serverTick = frame.tick();
        track.targetY = frame.y();
        track.receivedAt = client.world.getTime();
        cabin.useClientMotion();
    }

    /** Run before vanilla player physics, so the moving floor cannot enter the player's feet. */
    public static void beginPlayerTick(ClientPlayerEntity player) {
        tickCabin = null;
        TRACKS.entrySet().removeIf(e -> e.getKey().isRemoved() || e.getKey().getWorld() != player.getWorld());
        for (var entry : TRACKS.entrySet()) {
            AbstractCabinEntity cabin = entry.getKey();
            Track track = entry.getValue();
            track.previousY = track.physicalY;
            double dy = track.targetY - cabin.getY();
            boolean fresh = player.getWorld().getTime() - track.receivedAt <= ElevatorParameters.MOTION_STALE_TICKS;
            boolean riding = tickCabin == null && fresh && player.isAlive() && cabin.containsPassenger(player)
                    && Math.abs(dy) <= ElevatorParameters.MOTION_SNAP_DISTANCE;
            // Commit once per player tick, never separately from the rider's floor transport.
            cabin.setPosition(cabin.getX(), track.targetY, cabin.getZ());
            track.physicalY = track.targetY;
            if (riding) {
                tickCabin = cabin;
                tickFrame = track.serverTick;
                AbstractCabinEntity.carryPassenger(player, dy);
            }
        }
    }

    public static void sendMovement(ClientPlayNetworkHandler handler, Packet<?> packet) {
        if (tickCabin != null && packet instanceof PlayerMoveC2SPacket move
                && ClientPlayNetworking.canSend(RiderMove.ID)) {
            // Wrap exactly the packet vanilla chose; no duplicate packet and no extra teleport.
            ClientPlayNetworking.send(RiderMove.of(tickCabin.getId(), tickFrame, move));
        } else handler.sendPacket(packet);
    }

    public static double renderY(AbstractCabinEntity cabin, float delta) {
        Track track = TRACKS.get(cabin);
        return track == null ? MathHelper.lerp(delta, cabin.lastRenderY, cabin.getY())
                : MathHelper.lerp(delta, track.previousY, track.physicalY);
    }

    public static void clear() { TRACKS.clear(); tickCabin = null; }
}
