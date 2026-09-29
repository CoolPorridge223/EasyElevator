package org.DJB.easyelevator;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.DJB.easyelevator.block.CallButtonBlock;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorLine;

public class ElevatorGameTests implements FabricGameTest {
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static BlockPos setup(TestContext ctx) {
        for(BlockPos p:BlockPos.iterate(0,1,0,7,10,7)) ctx.setBlockState(p,Blocks.AIR.getDefaultState());
        for(int y=1;y<=6;y++) ctx.setBlockState(new BlockPos(3,y,1),Easyelevator.RAIL.getDefaultState().with(ElevatorRailBlock.FACING,Direction.SOUTH));
        for(int y:new int[]{1,6}) ctx.setBlockState(new BlockPos(2,y,1),Easyelevator.CALL_BUTTON.getDefaultState().with(CallButtonBlock.FACING,Direction.WEST));
        return ctx.getAbsolutePos(new BlockPos(3,1,1));
    }
    private static CabinEntity spawn(TestContext ctx,BlockPos rail) {
        var cabin=new CabinEntity(Easyelevator.CABIN,ctx.getWorld());cabin.initialize(rail,Direction.SOUTH);
        require(cabin.spaceClear(cabin.getBoundingBox()),"Spawn area should be clear");
        require(ctx.getWorld().spawnEntity(cabin),"Cabin must spawn");return cabin;
    }
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void stationsAndHollowCollision(TestContext ctx) {
        BlockPos rail=setup(ctx);var line=ElevatorLine.scan(ctx.getWorld(),rail);
        require(line!=null && line.stops().size()==2,"Two call buttons yield two stations");
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
        require(cabin.requestStop(rail.up(5).west()),"Connected destination accepted");
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
        require(cabin.requestStop(rail.up(5).west()),"Request accepted while obstructed");
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
}
