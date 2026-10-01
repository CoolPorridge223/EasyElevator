package org.DJB.easyelevator.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorLine;

public class CabinItem extends Item {

    public CabinItem(Settings s) { super(s); }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        if (!ctx.getWorld().getBlockState(ctx.getBlockPos()).isOf(Easyelevator.RAIL)) return ActionResult.PASS;
        if (ctx.getWorld().isClient) return ActionResult.SUCCESS;
        var line = ElevatorLine.scan(ctx.getWorld(), ctx.getBlockPos());
        String error;
        if (line == null) error = "message.easyelevator.invalid_stop";
        else if (!line.cabins(ctx.getWorld()).isEmpty()) error = "message.easyelevator.existing_cabin";
        else {
            CabinEntity cabin = new CabinEntity(Easyelevator.CABIN, ctx.getWorld());
            cabin.initialize(ctx.getBlockPos(), line.facing());
            if (!cabin.spaceClear(cabin.getBoundingBox())) error = "message.easyelevator.obstructed";
            else if (ctx.getWorld().spawnEntity(cabin)) {
                if (ctx.getPlayer() == null || !ctx.getPlayer().isCreative()) ctx.getStack().decrement(1);
                return ActionResult.CONSUME;
            } else error = "message.easyelevator.obstructed";
        }
        if (ctx.getPlayer() != null) ctx.getPlayer().sendMessage(Text.translatable(error), true);
        return ActionResult.FAIL;
    }
}
