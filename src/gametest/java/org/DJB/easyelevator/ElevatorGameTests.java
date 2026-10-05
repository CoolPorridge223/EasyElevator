package org.DJB.easyelevator;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.block.LandingDoorBlockEntity;
import org.DJB.easyelevator.block.LandingDoorGeometry;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.entity.HighSpeedCabinEntity;
import org.DJB.easyelevator.entity.ObservationCabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorLine;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.logic.FloorIndicator;

/**
 * EasyElevator 的 Fabric GameTest 集成测试集（源码在独立的 gametest 源集，不打入发布 JAR）。
 *
 * <p>与 src/test 下的纯逻辑单测不同，这里的每个用例都运行在真实 Minecraft 服务端测试世界上：
 * 用 {@link TestContext} 放置真实方块、生成真实实体、注册到刻回调，因此覆盖的是
 * “状态机 + 世界查询 + 方块/实体行为 + 网络包”这一层。用例全在空结构模板（EMPTY_STRUCTURE）上执行，
 * 结构内坐标必须用 ctx.getAbsolutePos(...) 换算成世界绝对坐标；每个用例对应 README「行为」清单
 * 或 docs/TESTING.md 验收清单中的一条，并且必须显式调用 ctx.complete() 才算通过。
 *
 * <p>入口由 src/gametest/resources/fabric.mod.json 的 fabric-gametest 声明，服务端启动时自动收集。
 */
public class ElevatorGameTests implements FabricGameTest {
    /** 极简断言：失败即抛 AssertionError，由 GameTest 框架记为该用例失败。 */
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
    /**
     * 搭建本组用例共用的场景并返回线路种子坐标。
     *
     * <p>结构内 (0,1,0)..(7,10,7) 全部清成空气；(3,1,1)..(3,6,1) 放 6 格高、一致朝南（SOUTH）的
     * 电梯轨道列（即一条线路：轿厢位于轨道南侧、轿厢门也朝南）；再在 (3,1,4) 与 (3,6,4) 各放一扇
     * 完整 3x3 楼层电梯门，两扇门的高度就是这条线路上的两个站点。
     *
     * @param ctx GameTest 上下文，提供结构内相对坐标与方块读写
     * @return 底部轨道方块的世界绝对坐标（ElevatorLine.scan 的种子点）
     */
    private static BlockPos setup(TestContext ctx) {
        // 清出一块 8x8 的水平范围、y=1..10 的空气区，避免模板残留方块干扰放置判定与碰撞检查。
        for(BlockPos p:BlockPos.iterate(0,1,0,7,10,7)) ctx.setBlockState(p,Blocks.AIR.getDefaultState());
        // 连续 6 格轨道，全部朝南：轨道朝向 = 轿厢所在方向 = 轿厢门朝向。
        for(int y=1;y<=6;y++) ctx.setBlockState(new BlockPos(3,y,1),Easyelevator.RAIL.getDefaultState().with(ElevatorRailBlock.FACING,Direction.SOUTH));
        for(int y:new int[]{1,6}) {
            // 门的根方块必须位于轨道朝向前方 RAIL_DISTANCE = 3 格、与轨道同 Y；这里 y=1 与 y=6 两层各一扇。
            BlockPos door=ctx.getAbsolutePos(new BlockPos(3,y,4));
            var state=Easyelevator.LANDING_DOOR.getDefaultState().with(LandingDoorBlock.FACING,Direction.SOUTH);
            ctx.getWorld().setBlockState(door,state);
            // 只写根方块状态是不够的：一件物品生成整扇门靠的是 onPlaced 补齐其余 8 块，这里手动调用它。
            Easyelevator.LANDING_DOOR.onPlaced(ctx.getWorld(),door,state,null,net.minecraft.item.ItemStack.EMPTY);
        }
        return ctx.getAbsolutePos(new BlockPos(3,1,1));
    }
    /**
     * 在给定轨道位置生成一个朝南的电梯轿厢。
     *
     * @param ctx  GameTest 上下文（提供世界与相对坐标）
     * @param rail 底部轨道方块坐标；initialize 会据此确定线路与位置（中心在轨道朝向前方 2 格、底部同 Y）
     * @return 已加入世界、井道清空的轿厢实体
     */
    private static CabinEntity spawn(TestContext ctx,BlockPos rail) {
        // initialize 决定线路（railX/railZ/Facing）以及世界坐标下的中心位置与朝向。
        var cabin=new CabinEntity(Easyelevator.CABIN,ctx.getWorld());cabin.initialize(rail,Direction.SOUTH);
        spawn(ctx,cabin);return cabin;
    }
    /**
     * 把已经 initialize 过的任意型号轿厢（普通 / 高速 / 观光）放进世界，前提与 {@link #spawn(TestContext, BlockPos)} 相同。
     *
     * <p>三种型号的尺寸、井道要求与碰撞完全一致，因此这里不需要按型号分支——这正是"继承同一个父类"带来的好处。
     *
     * @param ctx GameTest 上下文
     * @param cabin 已设定线路与坐标的轿厢实体
     */
    private static void spawn(TestContext ctx,AbstractCabinEntity cabin) {
        // 先确认 3x3x3 井道（含地板与顶盖）没有被方块或其它轿厢占用，否则测试前提不成立。
        require(cabin.spaceClear(cabin.getBoundingBox()),"Spawn area should be clear");
        // spawnEntity 返回 false 表示实体未能加入世界（例如被世界边界拒绝），必须显式失败而不是继续。
        require(ctx.getWorld().spawnEntity(cabin),"Cabin must spawn");
    }
    /**
     * 覆盖 README「搭建和使用」第 3、7 步与 docs/TESTING.md 第 2 条：门的距离规则与整扇门的拆卸回收。
     *
     * <p>不变量：只有距轨道朝向前方正好 3 格、且同 Y 的底部中心能放下楼层门（2 格与 4 格都被拒绝）；
     * 一件物品生成完整 9 块；拆掉任意一块（这里故意拆非根方块）会连带移除整扇门，生存模式恰好掉落 1 个门物品。
     *
     * @param ctx GameTest 上下文；结束时调用 ctx.complete()
     */
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void landingPlacementDistanceAndDrops(TestContext ctx) {
        // origin = 轨道朝向前方 3 格的门根方块位置；先清空，再按不同距离试探放置。
        BlockPos rail=setup(ctx),origin=rail.south(3);
        ctx.getWorld().setBlockState(origin,Blocks.AIR.getDefaultState());
        // 生存模式玩家：拆卸时会真实掉落物品（创造模式不掉落），用于验证“只返还一个门物品”。
        var player=ctx.createMockPlayer(net.minecraft.world.GameMode.SURVIVAL);
        var item=new net.minecraft.item.ItemStack(Easyelevator.LANDING_DOOR);
        var door=(LandingDoorBlock)Easyelevator.LANDING_DOOR;
        for(int distance:new int[]{2,3,4}) {
            BlockPos target=rail.south(distance),support=target.down();
            ctx.getWorld().setBlockState(support,Blocks.STONE.getDefaultState());
            // getPlacementState 是纯查询、不写世界；这里用“支撑方块顶面”的命中结果模拟在三个不同距离放置。
            var placement=new net.minecraft.item.ItemPlacementContext(player,net.minecraft.util.Hand.MAIN_HAND,item,
                    new net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(support).add(0,.5,0),Direction.UP,support,false));
            // 只有 distance == 3 能得到非 null 放置状态；其余距离会被拒绝，并给玩家发一条 actionbar 提示。
            require((door.getPlacementState(placement)!=null)==(distance==3),"Only distance-three placement is accepted");
        }
        // 先写入根方块状态（默认 COLUMN=1、LEVEL=0），再调用 onPlaced 补齐其余 8 块。
        var state=door.getDefaultState().with(LandingDoorBlock.FACING,Direction.SOUTH);
        ctx.getWorld().setBlockState(origin,state);door.onPlaced(ctx.getWorld(),origin,state,player,item);
        // complete() 要求 9 块的 FACING/COLUMN/LEVEL 与根方块完全自洽，等价于“一件物品生成一整扇门”。
        require(LandingDoorBlock.complete(ctx.getWorld(),origin),"One item builds one complete door");
        // side 是根方块西侧上一格，即 COLUMN=0、LEVEL=1：故意拆非根方块，检验整扇门连带移除。
        BlockPos side=origin.west().up();
        door.onBreak(ctx.getWorld(),side,ctx.getWorld().getBlockState(side),player);
        // onBreak 会把根方块一起 breakBlock（生存掉落），onStateReplaced 再清掉其余部分。
        require(ctx.getWorld().getBlockState(origin).isAir(),"Breaking any part removes root");
        // 掉落物可能落在 3x3 门的任意一格附近，因此按根方块周围 3 格范围搜索门物品实体。
        var drops=ctx.getWorld().getEntitiesByClass(net.minecraft.entity.ItemEntity.class,new Box(origin).expand(3),
                e->e.getStack().isOf(Easyelevator.LANDING_DOOR.asItem()));
        // 一件物品生成一整扇门，拆掉也只返还 1 个，绝不能按 9 块赔 9 个。
        require(drops.stream().mapToInt(e->e.getStack().getCount()).sum()==1,"Survival dismantling returns exactly one door item");
        // 清掉掉落物再结束用例；GameTest 未调用 complete() 会被判定为失败。
        drops.forEach(net.minecraft.entity.Entity::discard);ctx.complete();
    }
    /**
     * 覆盖 README「轿厢正面模型与碰撞内收 0.2 格、与楼层门框留 0.0125 格间隙避免重叠闪烁」与
     * docs/TESTING.md 第 17 条：四种朝向 × 五个轿厢门进度 × 三个楼层门门扇位置 × 3x3 门格，全部不许相交。
     *
     * <p>比较的是「楼层门几何」（{@link LandingDoorGeometry#shape}，与渲染和碰撞同源）与
     * 「轿厢碰撞壳」（collisionBoxes），因此覆盖了门框常驻几何与滑动到任意位置的门扇。
     * 每对盒子各外扩 0.005 格再判相交（两侧合计 0.01 格），
     * 因此实际要求间隙大于 0.01 格；设计值 0.0125 格只剩 0.0025 格余量，退化会立刻失败。
     *
     * @param ctx GameTest 上下文；结束时调用 ctx.complete()
     */
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void cabinClearsLandingDoorInEveryDirection(TestContext ctx) {
        // 种子轨道位于结构内 (3,1,3)；这里不需要真的放方块，几何检查只依赖实体坐标与方块状态对象。
        BlockPos rail=ctx.getAbsolutePos(new BlockPos(3,1,3));
        for(Direction facing:new Direction[]{Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST}) {
            // 每个朝向都用新实体，避免上一轮的相位/门进度残留影响结果。
            CabinEntity cabin=new CabinEntity(Easyelevator.CABIN,ctx.getWorld());
            cabin.initialize(rail,facing);
            // 先取一份默认存档内容，后面只在其中改写 Phase 与 Door 两个字段。
            var saved=new net.minecraft.nbt.NbtCompound();
            cabin.writeNbt(saved);
            // 门根方块位于轨道朝向前方 RAIL_DISTANCE = 3 格处；只算坐标，不需要真的存在门方块。
            BlockPos origin=rail.offset(facing,LandingDoorBlock.RAIL_DISTANCE);
            // 用 NBT 直接注入 Phase/Door，绕过状态机把门固定在 0 / 0.25 / 0.5 / 0.75 / 1 五个进度上，
            // 否则状态机的时序不允许在这些中间值上长期停留。
            for(float progress:new float[]{0,.25f,.5f,.75f,1}) {
                saved.putString("Phase",progress==1?"OPEN":"OPENING");
                saved.putFloat("Door",progress);
                cabin.readNbt(saved);
                var shells=cabin.collisionBoxes();
                // 碰撞壳组成：地板、顶盖、左右侧壁、后壁恒为 5 个；门未全开时再加两扇滑动门，共 7 个。
                // 门洞就是整个正面（与楼层门同一套做法），全开时两扇宽度归零、不再生成。
                require(shells.size()==(progress==1?5:7),"Check both shell and sliding leaves");
                // 楼层门现在也是连续滑动的：全关（0）、半开（0.5）、全开（1）三种门扇位置都要与轿厢壳保持间隙。
                // 直接调用 LandingDoorGeometry.shape（与渲染、碰撞同源的纯几何），绕开方块实体与联锁改写。
                for(float landing:new float[]{0,.5f,1}) for(int row=0;row<3;row++) for(int col=0;col<3;col++) {
                    // col-1/row 把 3x3 门格映射成以根方块为中心的内部坐标，rotateYClockwise 给出门的横向轴。
                    BlockPos pos=origin.offset(facing.rotateYClockwise(),col-1).up(row);
                    // 门框常驻、门扇随进度收拢，两种几何都要参与检查：真正会闪烁的是资源模型与渲染几何。
                    for(Box part:LandingDoorGeometry.shape(facing,col,row,landing).getBoundingBoxes()) {
                        // part.offset(pos) 把 0..1 的局部形状搬到世界坐标；expand(.005) 是数值容差。
                        Box landingBox=part.offset(pos);
                        for(Box shell:shells) require(!shell.expand(.005).intersects(landingBox),
                                "Cabin must clear landing door/frame with a visible gap: "+facing+", cabin progress="+progress+", landing leaves="+landing);
                    }
                }
            }
            // 用完即弃，防止上一朝向的轿厢被后续线路扫描或碰撞查询看到。
            cabin.discard();
        }
        ctx.complete();
    }
    /**
     * 覆盖「一扇 3x3 门只算一个站点、轿厢归属其轨道线路、轿厢是空心而非实心、断轨后站点失效」。
     *
     * <p>不变量：ElevatorLine.scan 得到 2 个站点（两扇完整门）而不是 18 块方块；线路内只有 1 个轿厢；
     * mixin（EntityViewMixin）为空心轿厢提供地板碰撞，而内部空间不产生实体碰撞；抽掉一格轨道把线路
     * 断成两段后，上半段的门不再是本站点。
     *
     * @param ctx GameTest 上下文；结束时调用 ctx.complete()
     */
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void stationsAndHollowCollision(TestContext ctx) {
        // setup 已放好 y=1 与 y=6 两扇完整门，线路扫描应得到 2 个站点。
        BlockPos rail=setup(ctx);var line=ElevatorLine.scan(ctx.getWorld(),rail);
        // 9 块方块合并成 1 个站点：2 扇门 = 2 个站点，而不是 18 个。
        require(line!=null && line.stops().size()==2,"Two complete landing doors yield two stations, not eighteen parts");
        CabinEntity cabin=spawn(ctx,rail);
        // 轿厢的 railX/railZ/facing 与线路一致，因此只属于这一条线路（每条线路最多一个轿厢）。
        require(line.cabins(ctx.getWorld()).size()==1,"Cabin belongs to its rail line");
        // 贴近地板表面取一个薄盒：设计地板面在轿厢底部上方 0.2 格，因此查 y+0.1..y+0.25。
        var floor=new Box(cabin.getX()-.3,cabin.getY()+.1,cabin.getZ()-.3,cabin.getX()+.3,cabin.getY()+.25,cabin.getZ()+.3);
        // 轿厢本体 isCollidable() = false，空心碰撞由 EntityViewMixin 注入，所以这里必须非空。
        require(!ctx.getWorld().getEntityCollisions(null,floor).isEmpty(),"Mixin must expose cabin floor collision");
        // 地板之上到顶盖之下的内部空间必须完全空心，玩家才能自由站立而不是被当成实心方块。
        var interior=new Box(cabin.getX()-.3,cabin.getY()+.25,cabin.getZ()-.3,cabin.getX()+.3,cabin.getY()+2,cabin.getZ()+.3);
        require(ctx.getWorld().getEntityCollisions(null,interior).isEmpty(),"Cabin must be hollow, not solid");
        // 抽掉 (3,4,1) 这格轨道把 6 格线路断成两段；从底部轨道扫描只剩 y=1 的站点。
        ctx.getWorld().setBlockState(rail.up(3),Blocks.AIR.getDefaultState());
        require(ElevatorLine.scan(ctx.getWorld(),rail).stops().size()==1,"Disconnected station excluded");
        cabin.discard();ctx.complete();
    }
    /**
     * 覆盖 README 行为「门口有生物会重新开门并保留原请求」在轿厢正面内收 0.2 格之后的边界情形：
     * 乘客站在内收门槛处（轿厢局部 z ≈ +0.87 格）时仍必须被 doorwayBlocked 检测到。
     *
     * <p>时刻表（tickLimit = 250 刻）：tick 80 轿厢仍停在底层且门已被防夹重新打开，把乘客移到
     * 轿厢中心；tick 240 应到达轨道上方 5 格的目的站，证明被中断的请求没有丢失。
     *
     * @param ctx GameTest 上下文；结束时调用 ctx.complete()
     */
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,tickLimit=250)
    public void recessedDoorStillDetectsPassengers(TestContext ctx) {
        BlockPos rail=setup(ctx);CabinEntity cabin=spawn(ctx,rail);
        // 乘客放在内收门之后（轿厢中心 +0.87 格）：防夹检测盒的局部 z 范围是 [CABIN_DOOR_BACK_Z-0.15, 1.6]
        // = [0.95, 1.6] 格，盔甲架包围盒（约 0.5 格宽）仍与之相交，这正是内收后必须保住的行为。
        var rider=new net.minecraft.entity.decoration.ArmorStandEntity(ctx.getWorld(),cabin.getX(),cabin.getY()+.2,cabin.getZ()+.87);
        ctx.getWorld().spawnEntity(rider);
        // requestStop 只看站点是否存在，不检查门口有没有人，因此请求照旧入队；被拦住的只是关门出发。
        require(cabin.requestStop(rail.up(5).south(3)),"Destination accepted while doorway occupied");
        ctx.runAtTick(80,()->{
            // 80 刻 > DWELL_TICKS(40) + DOOR_TICKS(20)：轿厢必须仍停在底层，一步都不能走。
            require(cabin.getY()==rail.getY(),"Passenger at recessed door must prevent departure");
            // 门应先关后被防夹打回 OPENING，因此进度严格大于 0。
            require(cabin.doorProgress(1)>0,"Recessed door must reopen instead of trapping passenger");
            // 把乘客移到轿厢中心（局部 z=0），退出防夹检测盒。
            rider.setPosition(cabin.getX(),cabin.getY()+.2,cabin.getZ());
        });
        ctx.runAtTick(240,()->{
            // 门控请求仍排在队首：乘客让开后继续原行程，精确到达 rail.y+5。
            require(cabin.getY()==rail.getY()+5,"Clearing recessed doorway resumes pending trip");
            rider.discard();cabin.discard();ctx.complete();
        });
    }
    /**
     * 覆盖 README 行为「默认速度 4 格/秒、门未完全关闭不能移动、最后一步精确对齐、乘客随轿厢移动」：
     * 一条 5 格高的行程在 tick 70 处于途中且门全关，tick 180 精确到站并进入 OPEN。
     *
     * <p>同时验证 requestStop 只接受本站点（真实存在的楼层门根方块），拒绝井道旁的任意坐标。
     *
     * @param ctx GameTest 上下文；结束时调用 ctx.complete()
     */
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,tickLimit=250)
    public void travelAndDoorInterlock(TestContext ctx) {
        BlockPos rail=setup(ctx);CabinEntity cabin=spawn(ctx,rail);
        // 盔甲架站在轿厢内部（局部 z=0）充当乘客：验证地板承载，而不是骑乘实体。
        var rider=new net.minecraft.entity.decoration.ArmorStandEntity(ctx.getWorld(),cabin.getX(),cabin.getY()+.2,cabin.getZ());
        ctx.getWorld().spawnEntity(rider);
        // 目的站是轨道上方 5 格、前方 3 格的合法门位置（setup 放的第二扇门）。
        require(cabin.requestStop(rail.up(5).south(3)),"Connected destination accepted");
        // rail.up(5).east() 只是井道旁的普通坐标，不是任何站点，必须被拒绝。
        require(!cabin.requestStop(rail.up(5).east()),"Non-button destination rejected");
        // tick 30 仍在 DWELL_TICKS = 40 刻的开门停留期内，位置不得变化。
        ctx.runAtTick(30,()->require(cabin.getY()==rail.getY(),"Must dwell before departure"));
        ctx.runAtTick(70,()->{
            // 已经在途中（0 < 位移 < 5 格），说明是连续运行而不是瞬移。
            require(cabin.getY()>rail.getY() && cabin.getY()<rail.getY()+5,"Cabin moves continuously along rail");
            // 运行期间门必须完全关闭（doorProgress 在 0..1 之间，1 = 全开）。
            require(cabin.doorProgress(1)==0,"Doors closed during travel");
        });
        ctx.runAtTick(180,()->{
            // 到站误差小于 1e-3 格（设计容差为 POSITION_EPSILON = 1e-7 格），门已进入 OPEN。
            require(Math.abs(cabin.getY()-(rail.getY()+5))<.001,"Precise destination alignment");
            require(cabin.phase()==ElevatorController.Phase.OPEN,"Arrival opens doors");
            // 乘客高度跟随轿厢不超过 0.02 格，说明地板确实托住了站立实体而没有穿模下落。
            require(Math.abs(rider.getY()-cabin.getY()-.2)<.02,"Standing passenger rides platform without falling through");
            rider.discard();cabin.discard();ctx.complete();
        });
    }
    /**
     * 覆盖「三种轿厢共用同一个父类，型号差异只体现在"速度"与"外观开关"上」。
     *
     * <p>只做型号接线层的集成校验（真正跑起来的运动与调度由纯 Java 状态机测试覆盖：
     * 那里已经逐刻验证了 0.20 / 0.50 格/刻的位移与到站精度）：
     * <ul>
     *   <li>高速型号的速度恰好是普通型号的 2.5 倍（构造时注入的是 {@code HIGH_SPEED}，不是默认值）；</li>
     *   <li>观光型号速度为默认值，且只有它声明玻璃外观；</li>
     *   <li>三种型号的碰撞外壳逐盒相同——尺寸、井道要求与门联锁因此完全一致，换型号不用改建井道。</li>
     * </ul>
     *
     * @param ctx GameTest 上下文；结束时调用 ctx.complete()
     */
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void cabinVariantsShareSpeedAndShell(TestContext ctx) {
        BlockPos rail=setup(ctx);
        var normal=new CabinEntity(Easyelevator.CABIN,ctx.getWorld()); normal.initialize(rail,Direction.SOUTH);
        var fast=new HighSpeedCabinEntity(Easyelevator.HIGH_SPEED_CABIN,ctx.getWorld()); fast.initialize(rail,Direction.SOUTH);
        var glass=new ObservationCabinEntity(Easyelevator.OBSERVATION_CABIN,ctx.getWorld()); glass.initialize(rail,Direction.SOUTH);
        require(normal.speed()==ElevatorParameters.SPEED,"standard cabin uses the standard speed");
        require(fast.speed()==ElevatorParameters.HIGH_SPEED&&fast.speed()==ElevatorParameters.SPEED*2.5,
                "high-speed cabin runs at exactly 2.5x the standard speed");
        require(glass.speed()==ElevatorParameters.SPEED,"observation cabin keeps the standard speed");
        require(glass.glassWalls()&&!normal.glassWalls()&&!fast.glassWalls(),"only the observation cabin declares glass walls");
        require(fast.collisionBoxes().equals(normal.collisionBoxes())&&glass.collisionBoxes().equals(normal.collisionBoxes()),
                "all three cabin types share one collision shell, so they fit the same shaft");
        normal.discard(); fast.discard(); glass.discard();
        ctx.complete();
    }
    /**
     * 覆盖「厅外上/下呼叫」与「潜行右键设置基准层后重新编号」两项功能。
     *
     * <p>不变量：按下厅外按钮后呼叫进入状态机并一直保留（楼层门面板据此把按钮点亮），
     * 轿厢到站开门那一刻自动清除；把某扇门设为基准层后，该层显示 1、其上依次 2,3…、其下显示 B1（内部为负数）。
     *
     * @param ctx GameTest 上下文；结束时调用 ctx.complete()
     */
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,tickLimit=400)
    public void hallCallsAndFloorBase(TestContext ctx) {
        BlockPos rail=setup(ctx),lower=rail.south(3),upper=rail.up(5).south(3);
        CabinEntity cabin=spawn(ctx,rail);
        // ① 在上层按下"上行"：呼叫进入状态机，并且只有这个方向是挂着的（另一个方向的按钮不亮）。
        require(cabin.requestHallCall(upper,false),"hall call accepted at the upper landing (downwards: nothing above the top floor)");
        require(cabin.hasHallCall(upper,false)&&!cabin.hasHallCall(upper,true),"only the pressed direction is pending");
        require(cabin.hallCalls().size()==1,"exactly one pending hall call");
        ctx.runAtTick(130,()->{
            // ② 车到站开门后呼叫自动清除（按钮熄灭）。
            require(cabin.hallCalls().isEmpty(),"the hall call clears once the car arrives");
            require(Math.abs(cabin.getY()-upper.getY())<1e-6,"car parked at the called floor");
            // ③ 把上层门设为基准层（等价于潜行右键它）：标记写进方块实体，轿厢据此知道"1 层"在哪。
            var door=ctx.getWorld().getBlockEntity(upper);
            require(door instanceof LandingDoorBlockEntity,"upper landing owns the block entity");
            ((LandingDoorBlockEntity)door).setBaseFloor(true);
            require(cabin.baseFloorY()==upper.getY(),"the cabin reports the new base floor");
            // ④ 在基准层选下层：车关门下行。
            require(cabin.requestStop(lower),"car call down to the basement");
        });
        ctx.runAtTick(135,()->{
            // 基准层就是 1 层（门框顶部、轿厢内面板与选站面板共用同一口径）。
            require(cabin.floorNumber()==1,"the base floor shows as floor 1");
            require(FloorIndicator.format(cabin.floorNumber()).equals("1"),"display text of the base floor");
        });
        ctx.runAtTick(340,()->{
            // 低于基准层的站点显示 B1（内部编号 -1），因此门框/面板上写的是"B1"而不是"0"。
            require(Math.abs(cabin.getY()-lower.getY())<1e-6,"car reached the lower landing");
            require(cabin.floorNumber()==-1,"below the base floor the number is basement 1");
            require(FloorIndicator.format(cabin.floorNumber()).equals("B1"),"basement renders as B1");
            cabin.discard();ctx.complete();
        });
    }
    /**
     * 覆盖 README「double 运动样本」：自定义 MotionFrame 包用绝对 double 同步轿厢位置，
     * 编解码往返必须逐位保持数值，不能被原版相对位置包的定点量化截断。
     *
     * <p>用 64.00000001 格（小数部分仅 1e-8 格，远小于 POSITION_EPSILON = 1e-7 格）构造帧，
     * round-trip 后 equals 必须成立。
     *
     * @param ctx GameTest 上下文；结束时调用 ctx.complete()
     */
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void preciseMotionPacket(TestContext ctx) {
        // RegistryByteBuf 是自定义 payload 的真实序列化缓冲（带注册表上下文），与服务端发包时一致。
        var buffer=new net.minecraft.network.RegistryByteBuf(io.netty.buffer.Unpooled.buffer(),ctx.getWorld().getRegistryManager());
        try {
            // 帧内容：实体 id、服务端刻、绝对 Y（格）、乘客相对偏移（格）。
            var sent=new org.DJB.easyelevator.network.ElevatorNetworking.MotionFrame(42,1234,64.00000001,.2);
            org.DJB.easyelevator.network.ElevatorNetworking.MotionFrame.CODEC.encode(buffer,sent);
            // 编码再解码，等价于服务端发包 + 客户端收包这一整条链路。
            var received=org.DJB.easyelevator.network.ElevatorNetworking.MotionFrame.CODEC.decode(buffer);
            // record 的 equals 逐字段比较 double，因此等价于断言“零量化损失”。
            require(received.equals(sent),"Absolute double coordinates must survive encoding with no quantization");
            ctx.complete();
        } finally {buffer.release();} // 无论断言是否失败都要释放 Netty 缓冲，避免测试进程泄漏。
    }
    /**
     * 覆盖 README 行为「运行区域有方块障碍时停止，清障后继续原行程」：
     * 井道里放一块石头后轿厢进入 BLOCKED 且不得与障碍相交；清障后继续前往原目的站。
     *
     * <p>tickLimit = 320 刻：BLOCKED 是暂停而不是失败，不存在超时放弃。
     *
     * @param ctx GameTest 上下文；结束时调用 ctx.complete()
     */
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,tickLimit=320)
    public void obstructionAndRecovery(TestContext ctx) {
        BlockPos rail=setup(ctx);CabinEntity cabin=spawn(ctx,rail);
        // 障碍放在轿厢上方 4 格、与轿厢同一 (x,z)：轿厢占 y..y+3 格高，因此 y+4 是上行时第一个撞到的方块。
        BlockPos obstruction=BlockPos.ofFloored(cabin.getX(),rail.getY()+4,cabin.getZ());
        ctx.getWorld().setBlockState(obstruction,Blocks.STONE.getDefaultState());
        // 井道里已有障碍时请求本身照旧被接受：拒绝发生在 canMove 阶段，而不是请求阶段。
        require(cabin.requestStop(rail.up(5).south(3)),"Request accepted while obstructed");
        ctx.runAtTick(120,()->{
            // 状态必须是 BLOCKED（暂停而非失败），且车体没有穿进障碍：轿厢顶端 y+3 仍应低于障碍 y+4。
            require(cabin.phase()==ElevatorController.Phase.BLOCKED,"Solid obstacle pauses car");
            require(cabin.getY()<rail.getY()+2,"Car must not intersect obstacle");
            // 清掉障碍，验证条件恢复后能继续。
            ctx.getWorld().setBlockState(obstruction,Blocks.AIR.getDefaultState());
        });
        ctx.runAtTick(250,()->{
            // 原请求仍然有效，精确到达 rail.y+5（误差小于 1e-3 格）。
            require(Math.abs(cabin.getY()-(rail.getY()+5))<.001,"Clearing obstacle resumes trip");
            cabin.discard();ctx.complete();
        });
    }
    /**
     * 覆盖门联锁（README 行为第 5 条与 docs/TESTING.md 第 2 条）：楼层门只有在「唯一的轿厢精确停在
     * 同层（误差 <= POSITION_EPSILON = 1e-7 格）且轿厢门正在打开」时才开启，其余情况一律保留真实碰撞。
     *
     * <p>时刻表（tickLimit = 240 刻）：放好门但还没有轿厢时保持关闭；生成轿厢后只有它所在楼层解锁，
     * tick 70 正在出发时旧楼层重新锁闭、目的楼层在途中保持关闭；tick 130 到站解锁目的楼层；
     * 随后撤掉轿厢，楼层门立即重新锁闭；最后拆一块就移除整扇门并让站点减少。
     *
     * @param ctx GameTest 上下文；结束时调用 ctx.complete()
     */
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,tickLimit=240)
    public void landingDoorInterlock(TestContext ctx) {
        // lower/upper 是 setup 放好的两扇门的根方块（底部中心），也就是两个站点。
        BlockPos rail=setup(ctx),lower=rail.south(3),upper=rail.up(5).south(3);
        require(LandingDoorBlock.complete(ctx.getWorld(),lower),"Placement constructs all nine parts");
        // 没有轿厢时必须保持锁闭，不能残留上一次运行留下的 OPEN 状态。
        require(!ctx.getWorld().getBlockState(lower).get(LandingDoorBlock.OPEN),"No cabin: landing closed");
        CabinEntity cabin=spawn(ctx,rail);
        // 手动刷新两扇门的视觉状态，等价于同一服务端刻里 CabinEntity 对本站点列表的刷新。
        LandingDoorBlock.refresh(ctx.getWorld(),lower); LandingDoorBlock.refresh(ctx.getWorld(),upper);
        // 轿厢停在底层：只有它自己那一层的门解锁，上层仍然关闭。
        require(ctx.getWorld().getBlockState(lower).get(LandingDoorBlock.OPEN),"Parked cabin opens only its landing");
        require(!ctx.getWorld().getBlockState(upper).get(LandingDoorBlock.OPEN),"Other landing remains closed");
        // 门扇进度样本：整扇门只有根方块持有方块实体，其余 8 格必须是 null（否则一次开合要算 9 份）。
        require(ctx.getWorld().getBlockEntity(lower) instanceof LandingDoorBlockEntity,"Root part owns the landing door block entity");
        require(!(ctx.getWorld().getBlockEntity(lower.up()) instanceof LandingDoorBlockEntity),"Other parts create no block entities");
        // 楼层门门扇与轿厢门门扇同刻同值：这正是"两层门同一动画"的保证。
        var doorEntity=(LandingDoorBlockEntity)ctx.getWorld().getBlockEntity(lower);
        require(Math.abs(doorEntity.openProgress()-cabin.doorProgress(1))<1e-4f,"Landing leaves follow the cabin door progress");
        // 回归：railX/railZ 没有进 DataTracker，客户端上恒为 0。这里把这两个字段清零（世界坐标、
        // 朝向、相位、门进度都原样保留）来模拟客户端视角——门扇进度必须仍然找得到这辆轿厢，
        // 否则就会出现"轿厢门开了、楼层门不动"。校验完立刻用原始 NBT 还原，后面的行程不受影响。
        var original=new net.minecraft.nbt.NbtCompound();
        cabin.writeNbt(original);
        var clientView=new net.minecraft.nbt.NbtCompound();
        cabin.writeNbt(clientView);
        clientView.putInt("RailX",0); clientView.putInt("RailZ",0);
        cabin.readNbt(clientView);
        require(cabin.railX()==0&&cabin.railZ()==0,"Simulated client cabin has no rail fields");
        require(Math.abs(LandingDoorBlock.leafProgress(ctx.getWorld(),lower)-cabin.doorProgress(1))<1e-4f,
                "Progress lookup must not depend on the cabin rail fields");
        cabin.readNbt(original);
        // 关闭的门必须有真实碰撞，玩家不能在没有轿厢时穿过去。
        require(!ctx.getWorld().getBlockState(upper).getCollisionShape(ctx.getWorld(),upper).isEmpty(),"Absent cabin leaves real collision barrier");
        require(cabin.requestStop(upper),"Door can call car");
        ctx.runAtTick(70,()->{
            // 出发阶段（门已关、开始移动）：旧楼层必须先锁闭，目的楼层在途中保持关闭，避免半空/提前开门。
            require(!ctx.getWorld().getBlockState(lower).get(LandingDoorBlock.OPEN),"Departure closes old landing before movement");
            require(!ctx.getWorld().getBlockState(upper).get(LandingDoorBlock.OPEN),"Destination stays closed during travel");
        });
        ctx.runAtTick(130,()->{
            // 到站开门后才解锁目的楼层；没有轿厢停留的楼层必须保持锁闭。
            require(ctx.getWorld().getBlockState(upper).get(LandingDoorBlock.OPEN),"Arrival unlocks destination");
            require(!ctx.getWorld().getBlockState(lower).get(LandingDoorBlock.OPEN),"Empty landing stays locked");
            // 楼层显示：轨道的两扇门分别在 y 与 y+5，轿厢从底层升到这里，层号应当从 1 变为 2。
            require(cabin.floorNumber()==2,"Arrival updates the displayed floor number");
            cabin.discard();
            // 轿厢消失后联锁立即恢复锁闭（不依赖下一次计划刻）。
            LandingDoorBlock.refresh(ctx.getWorld(),upper);
            require(!ctx.getWorld().getBlockState(upper).get(LandingDoorBlock.OPEN),"Removing cabin immediately locks landing");
            // 拆掉门的任意一块（根方块上方一格）应连带移除整扇门。
            ctx.getWorld().setBlockState(lower.up(),Blocks.AIR.getDefaultState());
            require(ctx.getWorld().getBlockState(lower).isAir(),"Breaking a door part removes its whole assembly");
            // 门消失后线路扫描只剩底层一个站点，被拆的站点不再可被呼叫。
            require(ElevatorLine.scan(ctx.getWorld(),rail).stops().size()==1,"Destroyed door removed from stations");
            ctx.complete();
        });
    }
    /**
     * 覆盖面板底部"开门 / 关门"键的服务端判定（README 行为与 docs/TESTING.md 面板一节）。
     *
     * <p>不变量：关门键在没有目的站时也能把门关上并停在本层；门已关好时再按关门被拒绝；
     * 开门键只在车体<b>精确停在某个完整站点</b>时受理，抬到楼层之间必须拒绝——绝不允许半空开门；
     * 开门走的是"请求当前这一层"的同一条状态机路径，因此到站吸附、楼层门联锁都照旧生效。
     *
     * <p>时刻表（tickLimit = 120 刻）：生成轿厢时门是全开的 → tick 40 关门键已把门关到位（20 刻）；
     * 随后按开门键，tick 80 门重新全开；再抬到半空按开门键必须被拒绝，最后清场。
     *
     * @param ctx GameTest 上下文；结束时调用 ctx.complete()
     */
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,tickLimit=120)
    public void panelDoorButtons(TestContext ctx) {
        BlockPos rail=setup(ctx);
        CabinEntity cabin=spawn(ctx,rail);
        // 轿厢生成时状态机初始相位就是 OPEN、门全开，因此关门键应当被受理；门已关好时再按必须被拒绝。
        require(cabin.doorCommand(false),"Close button accepted while the doors are open");
        ctx.runAtTick(40,()->{
            require(cabin.doorProgress(1)<=.001f,"Close button shuts the doors");
            require(cabin.phase()==ElevatorController.Phase.MOVING,"After closing with an empty queue the cabin parks at the floor");
            require(Math.abs(cabin.getY()-rail.getY())<=ElevatorParameters.POSITION_EPSILON,"Parked cabin keeps its floor");
            require(!cabin.doorCommand(false),"Close button rejected when the doors are already shut");
            // 楼层显示：轿厢一直停在最底层，因此面板/门框/轿厢内面板显示的层号应当是 1。
            require(cabin.floorNumber()==1,"Parked cabin shows floor 1");
            // 停在站点上按开门：等价于请求当前这一层，状态机会把它当成零距离行程，到站后开门。
            require(cabin.doorCommand(true),"Open button accepted while parked at a station");
            ctx.runAtTick(80,()->{
                require(cabin.doorProgress(1)>=.999f,"Open button reopens the doors at the station");
                // 抬到楼层之间（±0.5 格）：任何开门请求都必须被拒绝，否则就会出现半空开门。
                cabin.setPosition(cabin.getX(),rail.getY()+.5,cabin.getZ());
                require(!cabin.doorCommand(true),"Open button rejected between floors");
                cabin.discard();ctx.complete();
            });
        });
    }

    /**
     * 覆盖 docs/TESTING.md 验收第 9 项与"运行中保存退出、再次进入掉出电梯"这一玩家报告的 bug：
     * 读档后的轿厢必须先等存档时的乘客回到世界，才能继续原行程。
     *
     * <p>根因（本用例的第一条断言就是钉它）：轿厢是区块实体，随区块载入；玩家实体由登录流程单独载入，
     * 必然晚于区块实体。存档里的行程在载入第一刻就会被 BLOCKED 分支恢复（门关着、目的站还在），
     * 于是轿厢抢在乘客出现之前开走；乘客随后被放回自己的存档坐标——已经空掉的井道——脚下没有地板，
     * 直接掉出电梯。
     *
     * <p>用例把"存档→退出→再次进入"在同一个测试世界里复现：运行途中把轿厢写成 NBT、丢弃原实体、
     * 用同一份 NBT 生成一辆全新轿厢（等价于读档），并让乘客暂时离开这条井道（等价于玩家实体尚未载入）。
     * 时刻表（tickLimit = 300 刻）：
     * <ul>
     *   <li>tick 70：运行途中存档并"读档"，把乘客移出井道；</li>
     *   <li>tick 85：读档后的轿厢必须一步都没走，且目的站与门状态照旧；</li>
     *   <li>tick 95：乘客回到世界，但位置比存档时低 1 格（客户端首帧还没有轿厢碰撞时会先掉一段）；</li>
     *   <li>tick 130：乘客已被放回厢内地板（相对高度 0.2 格）并随厢继续上行；</li>
     *   <li>tick 250：原行程照常完成——精确到达目的站、开门，乘客全程都还在车上。</li>
     * </ul>
     *
     * @param ctx GameTest 上下文；结束时调用 ctx.complete()
     */
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,tickLimit=300)
    public void loadedCabinWaitsForItsPassenger(TestContext ctx) {
        BlockPos rail=setup(ctx),upper=rail.up(5).south(3);
        CabinEntity cabin=spawn(ctx,rail);
        // 乘客是 mock 玩家（服务端真实玩家同样走这条路）：站在轿厢中心的地板上，下一刻就会进入乘客名册。
        var rider=ctx.createMockPlayer(net.minecraft.world.GameMode.SURVIVAL);
        rider.setPosition(cabin.getX(),cabin.getY()+.2,cabin.getZ());
        require(ctx.getWorld().spawnEntity(rider),"Passenger must spawn");
        require(cabin.requestStop(upper),"Destination accepted");
        // 跨回调共享的状态：读档后的轿厢、存档时的轿厢高度与乘客高度。lambda 只能捕获 effectively final，
        // 因此与其它用例一样用单元素数组当可变槽位。
        final CabinEntity[] loaded={null};
        final double[] savedCabinY={0},savedRiderY={0};
        ctx.runAtTick(70,()->{
            // 前提：确实处于"运行途中"（门已关、离开底层、还没到站），否则后面的断言没有意义。
            require(cabin.getY()>rail.getY()&&cabin.getY()<rail.getY()+5,"Cabin must be mid-trip before saving");
            require(cabin.containsPassenger(rider),"Passenger must be aboard before saving");
            var saved=new net.minecraft.nbt.NbtCompound();
            cabin.writeNbt(saved); // 等价于"保存并退出"时写入实体 NBT：位置、相位、目的站与乘客名册都在里面
            savedCabinY[0]=cabin.getY(); savedRiderY[0]=rider.getY();
            // 玩家实体随登出一起卸载：移出这条井道（仍在世界里，但 findLostPassenger 找不到他）。
            rider.setNoGravity(true); // 临时关掉重力：免得在这条"世界之外"的空地上摔死，干扰后面的"重新登录"
            rider.setPosition(cabin.getX()+20,cabin.getY(),cabin.getZ());
            cabin.discard(); // 旧实体随区块卸载
            var reloaded=new CabinEntity(Easyelevator.CABIN,ctx.getWorld());
            reloaded.readNbt(saved); // 再次进入游戏：从存档恢复一辆全新轿厢
            require(ctx.getWorld().spawnEntity(reloaded),"Reloaded cabin must spawn");
            loaded[0]=reloaded;
        });
        ctx.runAtTick(85,()->{
            CabinEntity car=loaded[0];
            // 修好之前这里会是 savedCabinY + 0.2 * 15：轿厢抢跑了一大截，乘客被留在空掉的井道里。
            require(car.getY()==savedCabinY[0],"Loaded cabin must not move before its passenger is back");
            require(car.phase()==ElevatorController.Phase.BLOCKED,"Waiting for the passenger is a blocked trip, not a cancelled one");
            require(car.targetY()==upper.getY(),"Destination survives the wait");
        });
        ctx.runAtTick(95,()->{
            CabinEntity car=loaded[0];
            // 乘客重新回到世界：位置比存档时低 1 格，模拟"客户端首帧还没有轿厢碰撞、先掉下去一段"。
            rider.setNoGravity(false);
            rider.setPosition(car.getX(),savedRiderY[0]-1,car.getZ());
            require(!car.containsPassenger(rider),"Fallen passenger is not inside the cabin yet");
        });
        ctx.runAtTick(130,()->{
            CabinEntity car=loaded[0];
            // 已经放回厢内：相对地板高度回到 0.2 格附近，并且轿厢带着他继续往目的站走。
            require(Math.abs(rider.getY()-car.getY()-.2)<.02,"Passenger is put back on the cabin floor");
            require(car.containsPassenger(rider),"Passenger counts as aboard again");
            require(car.getY()>savedCabinY[0],"Trip continues once the passenger is back");
        });
        ctx.runAtTick(250,()->{
            CabinEntity car=loaded[0];
            require(Math.abs(car.getY()-(rail.getY()+5))<.001,"Original destination reached after the wait");
            require(car.phase()==ElevatorController.Phase.OPEN,"Arrival opens the doors");
            require(Math.abs(rider.getY()-car.getY()-.2)<.02,"Passenger rode the whole trip without falling out");
            rider.discard();car.discard();ctx.complete();
        });
    }
}
