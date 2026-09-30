package org.DJB.easyelevator;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorLine;

public class ElevatorGameTests implements FabricGameTest {
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static BlockPos setup(TestContext ctx) {
        for(BlockPos p:BlockPos.iterate(0,1,0,7,10,7)) ctx.setBlockState(p,Blocks.AIR.getDefaultState());
        for(int y=1;y<=6;y++) ctx.setBlockState(new BlockPos(3,y,1),Easyelevator.RAIL.getDefaultState().with(ElevatorRailBlock.FACING,Direction.SOUTH));
        for(int y:new int[]{1,6}) {
            BlockPos door=ctx.getAbsolutePos(new BlockPos(3,y,4));
            var state=Easyelevator.LANDING_DOOR.getDefaultState().with(LandingDoorBlock.FACING,Direction.SOUTH);
            ctx.getWorld().setBlockState(door,state);
            Easyelevator.LANDING_DOOR.onPlaced(ctx.getWorld(),door,state,null,net.minecraft.item.ItemStack.EMPTY);
        }
        return ctx.getAbsolutePos(new BlockPos(3,1,1));
    }
    private static CabinEntity spawn(TestContext ctx,BlockPos rail) {
        var cabin=new CabinEntity(Easyelevator.CABIN,ctx.getWorld());cabin.initialize(rail,Direction.SOUTH);
        require(cabin.spaceClear(cabin.getBoundingBox()),"Spawn area should be clear");
        require(ctx.getWorld().spawnEntity(cabin),"Cabin must spawn");return cabin;
    }
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void landingPlacementDistanceAndDrops(TestContext ctx) {
        BlockPos rail=setup(ctx),origin=rail.south(3);
        ctx.getWorld().setBlockState(origin,Blocks.AIR.getDefaultState());
        var player=ctx.createMockPlayer(net.minecraft.world.GameMode.SURVIVAL);
        var item=new net.minecraft.item.ItemStack(Easyelevator.LANDING_DOOR);
        var door=(LandingDoorBlock)Easyelevator.LANDING_DOOR;
        for(int distance:new int[]{2,3,4}) {
            BlockPos target=rail.south(distance),support=target.down();
            ctx.getWorld().setBlockState(support,Blocks.STONE.getDefaultState());
            var placement=new net.minecraft.item.ItemPlacementContext(player,net.minecraft.util.Hand.MAIN_HAND,item,
                    new net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(support).add(0,.5,0),Direction.UP,support,false));
            require((door.getPlacementState(placement)!=null)==(distance==3),"Only distance-three placement is accepted");
        }
        var state=door.getDefaultState().with(LandingDoorBlock.FACING,Direction.SOUTH);
        ctx.getWorld().setBlockState(origin,state);door.onPlaced(ctx.getWorld(),origin,state,player,item);
        require(LandingDoorBlock.complete(ctx.getWorld(),origin),"One item builds one complete door");
        BlockPos side=origin.west().up();
        door.onBreak(ctx.getWorld(),side,ctx.getWorld().getBlockState(side),player);
        require(ctx.getWorld().getBlockState(origin).isAir(),"Breaking any part removes root");
        var drops=ctx.getWorld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class,new Box(origin).expand(3),
                e->e.getStack().isOf(Easyelevator.LANDING_DOOR.asItem()));
        require(drops.stream().mapToInt(e->e.getStack().getCount()).sum()==1,"Survival dismantling returns exactly one door item");
        drops.forEach(net.minecraft.entity.Entity::discard);ctx.complete();
    }
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void stationsAndHollowCollision(TestContext ctx) {
        BlockPos rail=setup(ctx);var line=ElevatorLine.scan(ctx.getWorld(),rail);
        require(line!=null && line.stops().size()==2,"Two complete landing doors yield two stations, not eighteen parts");
        CabinEntity cabin=spawn(ctx,rail);
        require(line.cabins(ctx.getWorld()).size()==1,"Cabin belongs to its rail line");
        var floor=new Box(cabin.getX()-.3,cabin.getY()+.1,cabin.getZ()-.3,cabin.getX()+.3,cabin.getY()+.25,cabin.getZ()+.3);
        require(!ctx.getWorld().getEntityCollisions(null,floor).isEmpty(),"Mixin must expose cabin floor collision");
        var interior=new Box(cabin.getX()-.3,cabin.getY()+.25,cabin.getZ()-.3,cabin.getX()+.3,cabin.getY()+2,cabin.getZ()+.3);
        require(ctx.getWorld().getEntityCollisions(null,interior).isEmpty(),"Cabin must be hollow, not solid");
        ctx.getWorld().setBlockState(rail.up(3),Blocks.AIR.getDefaultState());
        require(ElevatorLine.scan(ctx.getWorld(),rail).stops().size()==1,"Disconnected station excluded");
        cabin.discard();ctx.complete();
    }
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,tickLimit=250)
    public void travelAndDoorInterlock(TestContext ctx) {
        BlockPos rail=setup(ctx);CabinEntity cabin=spawn(ctx,rail);
        var rider=new net.minecraft.entity.decoration.ArmorStandEntity(ctx.getWorld(),cabin.getX(),cabin.getY()+.2,cabin.getZ());
        ctx.getWorld().spawnEntity(rider);
        require(cabin.requestStop(rail.up(5).south(3)),"Connected destination accepted");
        require(!cabin.requestStop(rail.up(5).east()),"Non-button destination rejected");
        ctx.runAtTick(30,()->require(cabin.getY()==rail.getY(),"Must dwell before departure"));
        ctx.runAtTick(70,()->{
            require(cabin.getY()>rail.getY() && cabin.getY()<rail.getY()+5,"Cabin moves continuously along rail");
            require(cabin.doorProgress(1)==0,"Doors closed during travel");
        });
        ctx.runAtTick(180,()->{
            require(Math.abs(cabin.getY()-(rail.getY()+5))<.001,"Precise destination alignment");
            require(cabin.phase()==ElevatorController.Phase.OPEN,"Arrival opens doors");
            require(Math.abs(rider.getY()-cabin.getY()-.2)<.02,"Standing passenger rides platform without falling through");
            rider.discard();cabin.discard();ctx.complete();
        });
    }
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void preciseMotionPacket(TestContext ctx) {
        var buffer=new net.minecraft.network.RegistryByteBuf(io.netty.buffer.Unpooled.buffer(),ctx.getWorld().getRegistryManager());
        try {
            var sent=new org.DJB.easyelevator.network.ElevatorNetworking.MotionFrame(42,1234,64.00000001,.2);
            org.DJB.easyelevator.network.ElevatorNetworking.MotionFrame.CODEC.encode(buffer,sent);
            var received=org.DJB.easyelevator.network.ElevatorNetworking.MotionFrame.CODEC.decode(buffer);
            require(received.equals(sent),"Absolute double coordinates must survive encoding with no quantization");
            ctx.complete();
        } finally {buffer.release();}
    }
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,tickLimit=320)
    public void obstructionAndRecovery(TestContext ctx) {
        BlockPos rail=setup(ctx);CabinEntity cabin=spawn(ctx,rail);
        BlockPos obstruction=BlockPos.ofFloored(cabin.getX(),rail.getY()+4,cabin.getZ());
        ctx.getWorld().setBlockState(obstruction,Blocks.STONE.getDefaultState());
        require(cabin.requestStop(rail.up(5).south(3)),"Request accepted while obstructed");
        ctx.runAtTick(120,()->{
            require(cabin.phase()==ElevatorController.Phase.BLOCKED,"Solid obstacle pauses car");
            require(cabin.getY()<rail.getY()+2,"Car must not intersect obstacle");
            ctx.getWorld().setBlockState(obstruction,Blocks.AIR.getDefaultState());
        });
        ctx.runAtTick(250,()->{
            require(Math.abs(cabin.getY()-(rail.getY()+5))<.001,"Clearing obstacle resumes trip");
            cabin.discard();ctx.complete();
        });
    }
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,tickLimit=240)
    public void landingDoorInterlock(TestContext ctx) {
        BlockPos rail=setup(ctx),lower=rail.south(3),upper=rail.up(5).south(3);
        require(LandingDoorBlock.complete(ctx.getWorld(),lower),"Placement constructs all nine parts");
        require(!ctx.getWorld().getBlockState(lower).get(LandingDoorBlock.OPEN),"No cabin: landing closed");
        CabinEntity cabin=spawn(ctx,rail);
        LandingDoorBlock.refresh(ctx.getWorld(),lower); LandingDoorBlock.refresh(ctx.getWorld(),upper);
        require(ctx.getWorld().getBlockState(lower).get(LandingDoorBlock.OPEN),"Parked cabin opens only its landing");
        require(!ctx.getWorld().getBlockState(upper).get(LandingDoorBlock.OPEN),"Other landing remains closed");
        require(!ctx.getWorld().getBlockState(upper).getCollisionShape(ctx.getWorld(),upper).isEmpty(),"Absent cabin leaves real collision barrier");
        require(cabin.requestStop(upper),"Door can call car");
        ctx.runAtTick(70,()->{
            require(!ctx.getWorld().getBlockState(lower).get(LandingDoorBlock.OPEN),"Departure closes old landing before movement");
            require(!ctx.getWorld().getBlockState(upper).get(LandingDoorBlock.OPEN),"Destination stays closed during travel");
        });
        ctx.runAtTick(130,()->{
            require(ctx.getWorld().getBlockState(upper).get(LandingDoorBlock.OPEN),"Arrival unlocks destination");
            require(!ctx.getWorld().getBlockState(lower).get(LandingDoorBlock.OPEN),"Empty landing stays locked");
            cabin.discard();
            LandingDoorBlock.refresh(ctx.getWorld(),upper);
            require(!ctx.getWorld().getBlockState(upper).get(LandingDoorBlock.OPEN),"Removing cabin immediately locks landing");
            ctx.getWorld().setBlockState(lower.up(),Blocks.AIR.getDefaultState());
            require(ctx.getWorld().getBlockState(lower).isAir(),"Breaking a door part removes its whole assembly");
            require(ElevatorLine.scan(ctx.getWorld(),rail).stops().size()==1,"Destroyed door removed from stations");
            ctx.complete();
        });
    }
}
