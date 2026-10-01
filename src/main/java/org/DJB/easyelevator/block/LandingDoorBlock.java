package org.DJB.easyelevator.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.*;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorLine;
import org.DJB.easyelevator.logic.ElevatorParameters;

/** A 3x3 landing door. The bottom centre is the one and only station/controller. */
public final class LandingDoorBlock extends HorizontalFacingBlock {

    public static final MapCodec<LandingDoorBlock> CODEC = createCodec(LandingDoorBlock::new);

    public static final IntProperty COLUMN = IntProperty.of("column", 0, 2);

    public static final IntProperty LEVEL = IntProperty.of("level", 0, 2);

    public static final BooleanProperty OPEN = Properties.OPEN;

    public static final int RAIL_DISTANCE = 3;

    public LandingDoorBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING,Direction.NORTH).with(COLUMN,1).with(LEVEL,0).with(OPEN,false));
    }

    @Override
    public MapCodec<LandingDoorBlock> getCodec() { return CODEC; }

    @Override
    protected void appendProperties(StateManager.Builder<Block,BlockState> b) { b.add(FACING,COLUMN,LEVEL,OPEN); }

    public static boolean isRoot(BlockState s) { return s.isOf(Easyelevator.LANDING_DOOR) && s.get(COLUMN)==1 && s.get(LEVEL)==0; }

    public static BlockPos root(BlockState s, BlockPos p) {
        return p.offset(s.get(FACING).rotateYClockwise(),1-s.get(COLUMN)).down(s.get(LEVEL));
    }

    public static BlockPos railPos(BlockState s,BlockPos p) { return root(s,p).offset(s.get(FACING).getOpposite(),RAIL_DISTANCE); }

    private static BlockPos part(BlockPos root,Direction facing,int column,int level) { return root.offset(facing.rotateYClockwise(),column-1).up(level); }

    public static boolean complete(World world,BlockPos root) {
        BlockState state=world.getBlockState(root);
        if(!isRoot(state)) return false;
        Direction facing=state.get(FACING);
        for(int row=0;row<3;row++) for(int col=0;col<3;col++) {
            BlockPos p=part(root,facing,col,row);
            if(!world.isChunkLoaded(p)) return false;
            BlockState s=world.getBlockState(p);
            if(!s.isOf(Easyelevator.LANDING_DOOR) || s.get(FACING)!=facing || s.get(COLUMN)!=col || s.get(LEVEL)!=row) return false;
        }
        return true;
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockPos root=ctx.getBlockPos();
        World world=ctx.getWorld();
        for(Direction facing:Direction.Type.HORIZONTAL) {
            if(!ElevatorLine.matches(world,root.offset(facing.getOpposite(),RAIL_DISTANCE),facing)) continue;
            for(int row=0;row<3;row++) for(int col=0;col<3;col++) {
                BlockPos p=part(root,facing,col,row);
                if(world.isOutOfHeightLimit(p) || !world.getWorldBorder().contains(p) || !world.isChunkLoaded(p)
                        || !world.getBlockState(p).isReplaceable()
                        || (ctx.getPlayer()!=null && (!world.canPlayerModifyAt(ctx.getPlayer(),p)
                        || !ctx.getPlayer().canPlaceOn(p,ctx.getSide(),ctx.getStack())))) return null;
            }
            return getDefaultState().with(FACING,facing);
        }
        if(!world.isClient && ctx.getPlayer()!=null) ctx.getPlayer().sendMessage(Text.translatable("message.easyelevator.door_placement"),true);
        return null;
    }

    @Override
    public void onPlaced(World world,BlockPos p,BlockState s,LivingEntity placer,ItemStack stack) {
        super.onPlaced(world,p,s,placer,stack);
        if(world.isClient || !isRoot(s)) return;
        for(int row=0;row<3;row++) for(int col=0;col<3;col++) {
            if(row==0 && col==1) continue;
            world.setBlockState(part(p,s.get(FACING),col,row),s.with(COLUMN,col).with(LEVEL,row),Block.NOTIFY_ALL);
        }
        refresh(world,p);
        world.scheduleBlockTick(p,this,1);
    }

    @Override
    protected void scheduledTick(BlockState s,ServerWorld world,BlockPos p,Random random) {
        if(!isRoot(s)) return;
        refresh(world,p);
        world.scheduleBlockTick(p,this,1);
    }

    private static boolean mayOpen(World world,BlockPos origin) {
        if(!complete(world,origin)) return false;
        BlockState state=world.getBlockState(origin);
        BlockPos rail=railPos(state,origin);
        Direction facing=state.get(FACING);
        if(!ElevatorLine.matches(world,rail,facing)) return false;
        double x=rail.getX()+.5+facing.getOffsetX()*2,z=rail.getZ()+.5+facing.getOffsetZ()*2;
        var cars=world.getEntitiesByClass(CabinEntity.class,new net.minecraft.util.math.Box(x-1.6,origin.getY()-.01,z-1.6,x+1.6,origin.getY()+3,z+1.6),
                c->!c.isRemoved() && c.railX()==rail.getX() && c.railZ()==rail.getZ() && c.facing()==facing
                        && Math.abs(c.getY()-origin.getY())<=ElevatorParameters.POSITION_EPSILON);
        if(cars.size()!=1) return false;
        CabinEntity car=cars.getFirst();
        return Math.abs(car.getY()-origin.getY())<=ElevatorParameters.POSITION_EPSILON
                && car.phase()!=ElevatorController.Phase.MOVING && car.phase()!=ElevatorController.Phase.BLOCKED
                && car.doorProgress(1)>0;
    }

    public static void refresh(World world,BlockPos origin) {
        if(world.isClient) return;
        BlockState state=world.getBlockState(origin);
        if(!isRoot(state)) return;
        boolean open=mayOpen(world,origin);
        for(int row=0;row<3;row++) for(int col=0;col<3;col++) {
            BlockPos p=part(origin,state.get(FACING),col,row);
            BlockState cell=world.getBlockState(p);
            if(cell.isOf(Easyelevator.LANDING_DOOR) && root(cell,p).equals(origin) && cell.get(OPEN)!=open)
                world.setBlockState(p,cell.with(OPEN,open),Block.NOTIFY_LISTENERS);
        }
    }

    /** Only this car's own landing faces are permitted to overlap its front shell. */
    public static boolean belongsToCabin(World world,BlockPos p,BlockState state,CabinEntity car) {
        BlockPos rail=railPos(state,p);
        return state.get(FACING)==car.facing() && rail.getX()==car.railX() && rail.getZ()==car.railZ()
                && complete(world,root(state,p));
    }

    @Override
    protected ActionResult onUse(BlockState state,World world,BlockPos pos,PlayerEntity player,BlockHitResult hit) {
        if(!world.isClient) {
            BlockPos origin=root(state,pos);
            ElevatorLine line=complete(world,origin)?ElevatorLine.scan(world,railPos(state,pos)):null;
            var cabins=line==null?java.util.List.<CabinEntity>of():line.cabins(world);
            if(cabins.size()!=1) player.sendMessage(Text.translatable(cabins.isEmpty()?"message.easyelevator.no_cabin":"message.easyelevator.multiple_cabins"),true);
            else player.sendMessage(Text.translatable(cabins.getFirst().requestStop(origin)?"message.easyelevator.called":"message.easyelevator.invalid_stop"),true);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public BlockState onBreak(World world,BlockPos pos,BlockState state,PlayerEntity player) {
        if(!world.isClient && !isRoot(state)) {
            BlockPos origin=root(state,pos);
            if(isRoot(world.getBlockState(origin))) world.breakBlock(origin,!player.isCreative(),player);
        }
        return super.onBreak(world,pos,state,player);
    }

    @Override
    protected void onStateReplaced(BlockState state,World world,BlockPos pos,BlockState next,boolean moved) {
        if(!world.isClient && !next.isOf(this)) {
            BlockPos origin=root(state,pos);
            for(int row=0;row<3;row++) for(int col=0;col<3;col++) {
                BlockPos p=part(origin,state.get(FACING),col,row);
                if(p.equals(pos)) continue;
                BlockState cell=world.getBlockState(p);
                if(cell.isOf(this) && root(cell,p).equals(origin)) world.setBlockState(p,Blocks.AIR.getDefaultState(),Block.NOTIFY_ALL);
            }
        }
        super.onStateReplaced(state,world,pos,next,moved);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState s,BlockView w,BlockPos p,ShapeContext c) { return shape(s); }

    @Override
    protected VoxelShape getCollisionShape(BlockState s,BlockView w,BlockPos p,ShapeContext c) {
        // Enforce the landing interlock even before a scheduled visual-state refresh runs.
        if(w instanceof World world && !world.isClient && s.get(OPEN) && !mayOpen(world,root(s,p))) s=s.with(OPEN,false);
        return shape(s);
    }

    private static VoxelShape shape(BlockState s) {
        double minX=0,maxX=16,minY=0;
        if(s.get(OPEN)) {
            if(s.get(LEVEL)==2) minY=13;
            else if(s.get(COLUMN)==0) maxX=3;
            else if(s.get(COLUMN)==2) minX=13;
            else return VoxelShapes.empty();
        }
        return switch(s.get(FACING)) {
            case NORTH -> Block.createCuboidShape(minX,minY,0,maxX,16,3);
            case EAST -> Block.createCuboidShape(13,minY,minX,16,16,maxX);
            case SOUTH -> Block.createCuboidShape(16-maxX,minY,13,16-minX,16,16);
            default -> Block.createCuboidShape(0,minY,16-maxX,3,16,16-minX);
        };
    }
}
