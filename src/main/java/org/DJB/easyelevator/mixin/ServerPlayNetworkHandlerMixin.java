package org.DJB.easyelevator.mixin;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.network.PlatformMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin implements PlatformMovement {
    @Shadow public ServerPlayerEntity player;
    @Shadow private double lastTickY;
    @Shadow private double updatedY;
    @Shadow private Vec3d requestedTeleportPos;
    @Shadow private boolean floating;
    @Shadow private int floatingTicks;

    @Override public boolean easyelevator$canCarry() { return requestedTeleportPos == null; }

    @Override public void easyelevator$carried(double dy) {
        lastTickY += dy;
        updatedY += dy;
    }

    // Vanilla's floating test only checks blocks. A real cabin floor also counts as support.
    // Leave speed, collision, flight and teleport checks untouched for all other movement.
    @Inject(method = "onPlayerMove", at = @At("RETURN"))
    private void easyelevator$floorSupport(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if (!player.isOnGround() || player.isSpectator() || player.hasVehicle()) return;
        for (var cabin : player.getWorld().getEntitiesByClass(AbstractCabinEntity.class,
                player.getBoundingBox().expand(.01), c -> !c.isRemoved())) {
            if (cabin.supportsPassenger(player)) {
                floating = false;
                floatingTicks = 0;
                player.fallDistance = 0;
                break;
            }
        }
    }
}
