package org.DJB.easyelevator.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import org.DJB.easyelevator.client.CabinMotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private Entity focusedEntity;
    @Shadow private float lastTickDelta;
    // Adjust the feet anchor before vanilla third-person wall clipping and eye-height/bobbing.
    @ModifyArg(method="update", at=@At(value="INVOKE", target="Lnet/minecraft/client/render/Camera;setPos(DDD)V", ordinal=0), index=1)
    private double easyelevator$smoothRiderHeight(double y) {
        return y + CabinMotion.cameraOffset(focusedEntity, lastTickDelta);
    }
}
