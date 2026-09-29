package org.DJB.easyelevator.network;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.CabinEntity;
import java.util.ArrayList;
import java.util.List;

public final class ElevatorNetworking {
    private ElevatorNetworking() { }
    // World height has at most four adjacent buttons per rail; prevent unbounded packet allocation.
    public static final int MAX_STOPS = 16384;
    /** Absolute doubles bypass the vanilla relative-entity packet's fixed-point position quantum. */
    public record MotionFrame(int entityId, long tick, double y, double riderOffset) implements CustomPayload {
        public static final Id<MotionFrame> ID = new Id<>(Easyelevator.id("motion_frame"));
        public static final PacketCodec<RegistryByteBuf,MotionFrame> CODEC = new PacketCodec<>() {
            @Override public MotionFrame decode(RegistryByteBuf b) {
                return new MotionFrame(b.readVarInt(), b.readLong(), b.readDouble(), b.readDouble());
            }
            @Override public void encode(RegistryByteBuf b, MotionFrame p) {
                b.writeVarInt(p.entityId()); b.writeLong(p.tick()); b.writeDouble(p.y()); b.writeDouble(p.riderOffset());
            }
        };
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    public static void syncMotion(CabinEntity cabin) {
        for (ServerPlayerEntity observer : PlayerLookup.tracking(cabin)) {
            double riderOffset = cabin.containsPassenger(observer) ? observer.getY() - cabin.getY() : Double.NaN;
            ServerPlayNetworking.send(observer, new MotionFrame(cabin.getId(), cabin.getWorld().getTime(), cabin.getY(), riderOffset));
        }
    }
    public record OpenPanel(int entityId, List<BlockPos> stops) implements CustomPayload {
        public static final Id<OpenPanel> ID = new Id<>(Easyelevator.id("open_panel"));
        public static final PacketCodec<RegistryByteBuf,OpenPanel> CODEC = new PacketCodec<>() {
            @Override public OpenPanel decode(RegistryByteBuf buf) {
                int id=buf.readVarInt(), size=buf.readVarInt();
                if (size<0 || size>MAX_STOPS) throw new IllegalArgumentException("Invalid elevator station count");
                var stops=new ArrayList<BlockPos>(); for(int i=0;i<size;i++) stops.add(buf.readBlockPos());
                return new OpenPanel(id,List.copyOf(stops));
            }
            @Override public void encode(RegistryByteBuf buf, OpenPanel p) {
                buf.writeVarInt(p.entityId); buf.writeVarInt(p.stops.size()); for(var stop:p.stops) buf.writeBlockPos(stop);
            }
        };
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    public record SelectStop(int entityId, BlockPos button) implements CustomPayload {
        public static final Id<SelectStop> ID = new Id<>(Easyelevator.id("select_stop"));
        public static final PacketCodec<RegistryByteBuf,SelectStop> CODEC = new PacketCodec<>() {
            @Override public SelectStop decode(RegistryByteBuf buf) { return new SelectStop(buf.readVarInt(),buf.readBlockPos()); }
            @Override public void encode(RegistryByteBuf buf,SelectStop p) { buf.writeVarInt(p.entityId);buf.writeBlockPos(p.button); }
        };
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    public static void register() {
        PayloadTypeRegistry.playS2C().register(MotionFrame.ID,MotionFrame.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenPanel.ID,OpenPanel.CODEC);
        PayloadTypeRegistry.playC2S().register(SelectStop.ID,SelectStop.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SelectStop.ID,(payload,context)->context.server().execute(()->{
            var player=context.player();
            if (player.isSpectator() || !player.isAlive()) return;
            var entity=player.getServerWorld().getEntityById(payload.entityId());
            // Never trust a client-provided cabin, station index, distance or line identifier.
            if (!(entity instanceof CabinEntity cabin) || !cabin.containsPassenger(player)) return;
            boolean accepted=cabin.requestStop(payload.button());
            player.sendMessage(Text.translatable(accepted?"message.easyelevator.selected":"message.easyelevator.invalid_stop"),true);
            // Refresh so buttons placed/broken while the screen was open are reflected immediately.
            open(player,cabin);
        }));
        UseItemCallback.EVENT.register((player,world,hand)->{
            if (hand!=Hand.MAIN_HAND || player.isSpectator() || player.isSneaking()) return TypedActionResult.pass(player.getStackInHand(hand));
            for (var cabin:world.getEntitiesByClass(CabinEntity.class,player.getBoundingBox(),c->c.containsPassenger(player))) {
                if (!world.isClient) open((ServerPlayerEntity)player,cabin);
                return TypedActionResult.success(player.getStackInHand(hand));
            }
            return TypedActionResult.pass(player.getStackInHand(hand));
        });
        UseBlockCallback.EVENT.register((player,world,hand,hit)->{
            if (hand!=Hand.MAIN_HAND || player.isSpectator() || player.isSneaking()) return ActionResult.PASS;
            for (var cabin:world.getEntitiesByClass(CabinEntity.class,player.getBoundingBox(),c->c.containsPassenger(player))) {
                if (!world.isClient) open((ServerPlayerEntity)player,cabin);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
    }
    public static void open(ServerPlayerEntity player,CabinEntity cabin) {
        var line=cabin.line();
        ServerPlayNetworking.send(player,new OpenPanel(cabin.getId(),line==null?List.of():line.stops().stream().limit(MAX_STOPS).toList()));
    }
}
