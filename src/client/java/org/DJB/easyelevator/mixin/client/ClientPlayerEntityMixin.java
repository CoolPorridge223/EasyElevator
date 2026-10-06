package org.DJB.easyelevator.mixin.client;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.Packet;
import org.DJB.easyelevator.client.CabinMotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void easyelevator$moveFloor(CallbackInfo ci) {
        CabinMotion.beginPlayerTick((ClientPlayerEntity) (Object) this);
    }

    @Redirect(method = "sendMovementPackets", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayNetworkHandler;sendPacket(Lnet/minecraft/network/packet/Packet;)V"))
    private void easyelevator$relativeMove(ClientPlayNetworkHandler handler, Packet<?> packet) {
        CabinMotion.sendMovement(handler, packet);
    }
}
