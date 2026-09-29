package org.DJB.easyelevator.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.api.ElevatorEvents;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorLine;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.network.ElevatorNetworking;
import java.util.ArrayList;
import java.util.List;

public class CabinEntity extends Entity {
    private static final TrackedData<Integer> PHASE = DataTracker.registerData(CabinEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> DOOR = DataTracker.registerData(CabinEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Integer> FACING = DataTracker.registerData(CabinEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> TARGET_Y = DataTracker.registerData(CabinEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private final ElevatorController controller = new ElevatorController();
    private int railX, railZ;
    private float previousDoor = 1;
    private int motionSettleTicks;
    public CabinEntity(EntityType<? extends CabinEntity> type, World world) { super(type, world); setNoGravity(true); }
    @Override protected void initDataTracker(DataTracker.Builder b) {
        b.add(PHASE, 0); b.add(DOOR, 1f); b.add(FACING, Direction.NORTH.getId()); b.add(TARGET_Y, Integer.MIN_VALUE);
    }
    public void initialize(BlockPos rail, Direction facing) {
        railX = rail.getX(); railZ = rail.getZ(); dataTracker.set(FACING, facing.getId());
        setPosition(railX + .5 + facing.getOffsetX()*2, rail.getY(), railZ + .5 + facing.getOffsetZ()*2);
    }
    public int railX() { return railX; }
    public int railZ() { return railZ; }
    public Direction facing() { return Direction.byId(dataTracker.get(FACING)); }
    public ElevatorController.Phase phase() { return ElevatorController.Phase.values()[dataTracker.get(PHASE)]; }
    public float doorProgress(float tickDelta) { return MathHelper.lerp(tickDelta, previousDoor, dataTracker.get(DOOR)); }
    public int targetY() { return dataTracker.get(TARGET_Y); }
    public ElevatorLine line() { return ElevatorLine.scan(getWorld(), new BlockPos(railX, MathHelper.floor(getY()+.0001), railZ)); }
    public boolean containsPassenger(Entity e) {
        Box b = e.getBoundingBox();
        return !e.isSpectator() && !e.hasVehicle() && b.minX >= getX()-1.31 && b.maxX <= getX()+1.31
                && b.minZ >= getZ()-1.31 && b.maxZ <= getZ()+1.31 && e.getY() >= getY()+.14 && e.getY() < getY()+2.7;
    }
    public boolean requestStop(BlockPos button) {
        ElevatorLine line = line();
        if (line == null || line.facing() != facing() || line.cabins(getWorld()).size() != 1 || !line.stops().contains(button)) return false;
        return controller.request(new ElevatorController.Stop(button.asLong(), button.getY()), getY());
    }
    @Override public boolean canHit() { return true; }
    @Override public boolean isCollidable() { return false; } // Hollow collision supplied by EntityViewMixin.
    @Override public boolean isPushable() { return false; }
    @Override public ActionResult interact(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (!getWorld().isClient) {
            if (player.isSneaking() && player.getStackInHand(hand).isEmpty() && phase() == ElevatorController.Phase.OPEN
                    && getWorld().getOtherEntities(this, getBoundingBox(), this::containsPassenger).isEmpty()) {
                if (!player.isCreative()) dropItem(Easyelevator.CABIN_ITEM);
                discard();
            } else if (containsPassenger(player)) ElevatorNetworking.open((ServerPlayerEntity) player, this);
            else player.sendMessage(Text.translatable("message.easyelevator.enter"), true);
        }
        return ActionResult.SUCCESS;
    }
    @Override public void tick() {
        previousDoor = dataTracker.get(DOOR);
        super.tick();
        if (getWorld().isClient) return;
        var before = controller.phase();
        final ElevatorLine currentLine = line();
        final boolean unique = currentLine != null && currentLine.facing() == facing() && currentLine.cabins(getWorld()).size() == 1;
        double nextY = controller.tick(getY(), new ElevatorController.Environment() {
            @Override public boolean valid(ElevatorController.Stop stop) {
                // A temporary gap must pause the trip, not erase its destination.
                BlockPos p = BlockPos.fromLong(stop.id());
                if (!getWorld().isChunkLoaded(p)) return true;
                var s = getWorld().getBlockState(p);
                return s.isOf(Easyelevator.CALL_BUTTON)
                        && org.DJB.easyelevator.block.CallButtonBlock.railPos(s, p).equals(new BlockPos(railX, stop.y(), railZ));
            }
            @Override public boolean canMove(double from, double to) {
                if (!unique || !currentLine.stops().contains(BlockPos.fromLong(controller.target().id()))) return false;
                int bottom = MathHelper.floor(Math.min(from, to)+.0001);
                int top = MathHelper.ceil(Math.max(from, to)-.0001);
                for (int y = bottom; y <= top; y++)
                    if (!ElevatorLine.matches(getWorld(), new BlockPos(railX,y,railZ), facing())) return false;
                Box swept = getBoundingBox().union(getBoundingBox().offset(0, to-from, 0)).contract(.001);
                if (!spaceClear(swept)) return false;
                // Stop for non-riders in the swept shell rather than crushing them.
                for (Entity e : getWorld().getOtherEntities(CabinEntity.this, swept, e -> !e.isSpectator() && !(e instanceof CabinEntity))) {
                    if (containsPassenger(e)) continue;
                    for (Box shell : collisionBoxes())
                        if (shell.union(shell.offset(0, to-from, 0)).intersects(e.getBoundingBox())) return false;
                }
                return true;
            }
            @Override public boolean doorwayBlocked() {
                return !getWorld().getOtherEntities(CabinEntity.this, localBox(-1.3,.2,1.15,1.3,2.8,1.6),
                        e -> !e.isSpectator() && e instanceof LivingEntity).isEmpty();
            }
            @Override public void arrived(ElevatorController.Stop stop) {
                sound(Easyelevator.ARRIVAL); ElevatorEvents.ARRIVED.invoker().onArrival(CabinEntity.this, stop.y());
            }
        });
        double dy = nextY - getY();
        if (dy != 0) {
            List<Entity> riders = getWorld().getOtherEntities(this, getBoundingBox(), this::containsPassenger);
            setPosition(getX(), nextY, getZ());
            for (Entity rider : riders) {
                // Explicit position sync prevents vanilla flying checks and descent fall damage.
                if (rider instanceof ServerPlayerEntity p)
                    p.networkHandler.requestTeleport(p.getX(), p.getY()+dy, p.getZ(), p.getYaw(), p.getPitch(),
                            java.util.EnumSet.of(PositionFlag.X_ROT, PositionFlag.Y_ROT));
                else rider.setPosition(rider.getX(), rider.getY()+dy, rider.getZ());
                rider.fallDistance = 0;
                rider.setVelocity(rider.getVelocity().multiply(1, 0, 1));
                rider.setOnGround(true);
            }
        }
        dataTracker.set(PHASE, controller.phase().ordinal()); dataTracker.set(DOOR, controller.door());
        dataTracker.set(TARGET_Y, controller.target() == null ? Integer.MIN_VALUE : controller.target().y());
        if (dy != 0) motionSettleTicks = ElevatorParameters.MOTION_SETTLE_TICKS;
        if (dy != 0 || motionSettleTicks > 0) {
            ElevatorNetworking.syncMotion(this);
            if (dy == 0) motionSettleTicks--;
        }
        if (before != controller.phase()) {
            if (controller.phase() == ElevatorController.Phase.CLOSING) sound(Easyelevator.DOOR_CLOSE);
            if (controller.phase() == ElevatorController.Phase.OPENING) sound(Easyelevator.DOOR_OPEN);
            ElevatorEvents.PHASE_CHANGED.invoker().onChange(this, before, controller.phase());
        }
    }
    private void sound(SoundEvent event) { getWorld().playSound(null, getX(), getY(), getZ(), event, SoundCategory.BLOCKS, ElevatorParameters.EVENT_VOLUME, ElevatorParameters.SOUND_PITCH); }
    public boolean spaceClear(Box box) {
        if (box.minY < getWorld().getBottomY() || box.maxY > getWorld().getTopY() || !getWorld().getWorldBorder().contains(box)) return false;
        for (BlockPos p : BlockPos.iterate(MathHelper.floor(box.minX), MathHelper.floor(box.minY), MathHelper.floor(box.minZ),
                MathHelper.floor(box.maxX), MathHelper.floor(box.maxY), MathHelper.floor(box.maxZ)))
            if (!getWorld().isChunkLoaded(p)) return false;
        for (VoxelShape shape : getWorld().getBlockCollisions(this, box)) if (!shape.isEmpty()) return false;
        return getWorld().getEntitiesByClass(CabinEntity.class, box, e -> e != this && !e.isRemoved()).isEmpty();
    }
    /** Geometry is in blocks. The local front (+Z) is rotated to the rail facing. */
    public Box localBox(double x1, double y1, double z1, double x2, double y2, double z2) {
        int fx = facing().getOffsetX(), fz = facing().getOffsetZ();
        double ax = x1*fz + z1*fx, az = -x1*fx + z1*fz;
        double bx = x2*fz + z2*fx, bz = -x2*fx + z2*fz;
        return new Box(getX()+Math.min(ax,bx), getY()+y1, getZ()+Math.min(az,bz), getX()+Math.max(ax,bx),getY()+y2,getZ()+Math.max(az,bz));
    }
    public List<Box> collisionBoxes() {
        List<Box> boxes = new ArrayList<>();
        boxes.add(localBox(-1.5,0,-1.5,1.5,.2,1.5));
        boxes.add(localBox(-1.5,2.8,-1.5,1.5,3,1.5));
        boxes.add(localBox(-1.5,.2,-1.5,-1.3,2.8,1.5));
        boxes.add(localBox(1.3,.2,-1.5,1.5,2.8,1.5));
        boxes.add(localBox(-1.3,.2,-1.5,1.3,2.8,-1.3));
        float p = dataTracker.get(DOOR);
        if (p < .999f) {
            boxes.add(localBox(-1.3,.2,1.3,-1.3*p,2.8,1.5));
            boxes.add(localBox(1.3*p,.2,1.3,1.3,2.8,1.5));
        }
        return boxes;
    }
    @Override protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putInt("RailX",railX); nbt.putInt("RailZ",railZ); nbt.putInt("Facing",facing().getId());
        nbt.putString("Phase",controller.phase().name()); nbt.putFloat("Door",controller.door());
        if (controller.target()!=null) nbt.putLong("Target",controller.target().id());
        NbtList list = new NbtList();
        for (var stop : controller.pending()) { NbtCompound s = new NbtCompound(); s.putLong("Button",stop.id()); list.add(s); }
        nbt.put("Queue",list);
    }
    @Override protected void readCustomDataFromNbt(NbtCompound nbt) {
        railX=nbt.getInt("RailX"); railZ=nbt.getInt("RailZ");
        Direction direction = Direction.byId(nbt.getInt("Facing"));
        dataTracker.set(FACING, (direction.getAxis().isHorizontal()?direction:Direction.NORTH).getId());
        ElevatorController.Phase phase;
        try { phase=ElevatorController.Phase.valueOf(nbt.getString("Phase")); } catch (IllegalArgumentException e) { phase=ElevatorController.Phase.BLOCKED; }
        var queue = new ArrayList<ElevatorController.Stop>(); var list=nbt.getList("Queue",10);
        for (int i=0; i<Math.min(list.size(), ElevatorController.MAX_REQUESTS); i++) queue.add(stop(list.getCompound(i).getLong("Button")));
        controller.restore(phase,nbt.getFloat("Door"),nbt.contains("Target")?stop(nbt.getLong("Target")):null,queue);
        dataTracker.set(PHASE,controller.phase().ordinal()); dataTracker.set(DOOR,controller.door()); previousDoor=controller.door();
        dataTracker.set(TARGET_Y,controller.target()==null?Integer.MIN_VALUE:controller.target().y());
    }
    private static ElevatorController.Stop stop(long packed) { return new ElevatorController.Stop(packed,BlockPos.fromLong(packed).getY()); }
}
