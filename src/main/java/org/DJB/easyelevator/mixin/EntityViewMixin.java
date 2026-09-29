package org.DJB.easyelevator.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.EntityView;
import org.DJB.easyelevator.entity.CabinEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.ArrayList;
import java.util.List;

/** Adds only the hollow cabin shell; a solid entity bounding box would prevent entering the car. */
@Mixin(EntityView.class)
public interface EntityViewMixin {
    @Inject(method="getEntityCollisions", at=@At("RETURN"), cancellable=true)
    private void easyelevator$collisions(Entity entity, Box box, CallbackInfoReturnable<List<VoxelShape>> cir) {
        if (entity instanceof CabinEntity) return;
        EntityView view = (EntityView)this;
        var cabins = view.getEntitiesByClass(CabinEntity.class, box.expand(.001), c -> !c.isRemoved());
        if (cabins.isEmpty()) return;
        List<VoxelShape> result = new ArrayList<>(cir.getReturnValue());
        for (var cabin : cabins) for (Box part : cabin.collisionBoxes()) if (part.intersects(box)) result.add(VoxelShapes.cuboid(part));
        cir.setReturnValue(result);
    }
}
