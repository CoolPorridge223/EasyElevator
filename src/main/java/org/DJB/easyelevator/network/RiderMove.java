package org.DJB.easyelevator.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.RiderMotionHistory;

/** One vanilla movement packet paired atomically with the cabin sample used by client physics. */
public record RiderMove(int cabinId, long tick, double x, double y, double z,
                        float yaw, float pitch, boolean ground, boolean position, boolean look)
        implements CustomPayload {
    public static final Id<RiderMove> ID = new Id<>(Easyelevator.id("rider_move"));
    public static final PacketCodec<RegistryByteBuf, RiderMove> CODEC = new PacketCodec<>() {
        @Override public RiderMove decode(RegistryByteBuf b) {
            return new RiderMove(b.readVarInt(), b.readLong(), b.readDouble(), b.readDouble(),
                    b.readDouble(), b.readFloat(), b.readFloat(), b.readBoolean(), b.readBoolean(), b.readBoolean());
        }
        @Override public void encode(RegistryByteBuf b, RiderMove p) {
            b.writeVarInt(p.cabinId); b.writeLong(p.tick);
            b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z);
            b.writeFloat(p.yaw); b.writeFloat(p.pitch);
            b.writeBoolean(p.ground); b.writeBoolean(p.position); b.writeBoolean(p.look);
        }
    };

    public static RiderMove of(int cabinId, long tick, PlayerMoveC2SPacket p) {
        return new RiderMove(cabinId, tick, p.getX(0), p.getY(0), p.getZ(0),
                p.getYaw(0), p.getPitch(0), p.isOnGround(), p.changesPosition(), p.changesLook());
    }

    @Override public Id<? extends CustomPayload> getId() { return ID; }

    public PlayerMoveC2SPacket vanilla(double adjustedY) {
        if (position && look) return new PlayerMoveC2SPacket.Full(x, adjustedY, z, yaw, pitch, ground);
        if (position) return new PlayerMoveC2SPacket.PositionAndOnGround(x, adjustedY, z, ground);
        if (look) return new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, ground);
        return new PlayerMoveC2SPacket.OnGroundOnly(ground);
    }

    public void apply(ServerPlayerEntity player) {
        double adjustedY = y;
        if (position && player.isAlive() && !player.isSpectator() && !player.hasVehicle()
                && player.getWorld().getEntityById(cabinId) instanceof AbstractCabinEntity cabin
                && cabin.containsPassenger(player)) {
            double seenY = cabin.motionHeight(tick);
            // Only accept an actual recent server sample and a body close to this cabin.
            // The final packet still passes all vanilla speed and collision validation.
            double relativeY = y - seenY;
            if (Double.isFinite(seenY) && relativeY >= -.1 && relativeY <= 2.8
                    && Math.abs(x - cabin.getX()) <= 1.9 && Math.abs(z - cabin.getZ()) <= 1.9) {
                adjustedY = RiderMotionHistory.rebase(y, seenY, cabin.getY());
            }
        }
        player.networkHandler.onPlayerMove(vanilla(adjustedY));
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (packet, context) ->
                context.server().execute(() -> packet.apply(context.player())));
    }
}
