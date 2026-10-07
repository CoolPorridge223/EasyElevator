package org.DJB.easyelevator;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.RiderMotionHistory;
import org.DJB.easyelevator.network.PlatformMovement;
import org.DJB.easyelevator.network.RiderMove;

/**
 * 乘客随行移动（rider movement）的服务端 GameTest 回归集，守护 2.2.0「平滑移动修复」立下的契约。
 *
 * <p>背景：2.2.0 之前，轿厢每刻用 requestTeleport 把乘客"搬"到新高度。逐刻传送会经由传送路径重置
 * 乘客的速度与落地状态，并在客户端触发传送确认与插值重置，于是玩家在轿厢里踩不住自己的移动输入、
 * 高延迟下被反复回拉。修法是两条：平台位移只叠加到乘客坐标上（{@link AbstractCabinEntity#carryPassenger}），
 * 以及把移动包与"客户端当时看到的那一帧轿厢高度"绑定（{@link RiderMove} + {@link RiderMotionHistory}），
 * 由服务端按自己的历史帧重定基后再交给原版校验。本类把这些只在多人、延迟、到站出门等组合下才暴露的
 * 缺陷固化成可重复执行的断言。</p>
 *
 * <p>守护的回归点：
 * <ul>
 *   <li>载客不得改写玩家的行走/跳跃速度，也不得把空中玩家按到地上（否则厢内无法自主移动、跳不起来）；</li>
 *   <li>平台位移必须原封不动地叠加给乘客，<b>包括状态机已切到 OPENING 的"到站最后一步"</b>；</li>
 *   <li>迟到的移动包要按包内样本编号回退到服务端当时的轿厢高度再重定基，跳跃的相对高度不能被吃掉；</li>
 *   <li>未知/伪造/过期的样本一律不给平台位移额度，但玩家自己的原版行走照常生效；</li>
 *   <li>真正的传送（/tp、名册归位、管理员移动）不能被电梯顺手带走；</li>
 *   <li>轿厢地板要算作落地支撑（否则报浮空被纠正甚至踢出），但这条豁免不能放大到"附近有轿厢"；</li>
 *   <li>到站后乘客身体仍能站在厢内不被压住，开门过程中离厢的在线玩家不被名册拉回。</li>
 * </ul>
 *
 * <p>如何运行：本测试源集默认不参与构建——只有带 {@code -PriderTests} 时 build.gradle 才会创建
 * {@code easyelevator-test} 测试模组并打开 GameTest（见 build.gradle 第 29~37 行，测试模组永不进发行包）。
 * 因此命令是：
 * <pre>{@code .\gradlew.bat -PriderTests runGameTest}</pre>
 * 也可以用 {@code .\tools\build.ps1 -Jdk <JDK21> -Task runGameTest}，但该包装脚本只透传任务名
 * （见 tools/build.ps1 第 30 行），不会替你补 {@code -PriderTests}，属性必须自己带上。
 * 运行目录是 build/run/gameTest；本类的清单登记在 src/gametest/resources/fabric.mod.json 的
 * {@code fabric-gametest} 入口点。</p>
 *
 * <p>被测对象与手法：全部测试用 {@code EMPTY_STRUCTURE} 现搭场景（不读存档、不额外生成世界），
 * 覆盖 {@link RiderMotionHistory}（服务端坐标帧）、{@link RiderMove}（延迟重定基）、
 * {@link AbstractCabinEntity#carryPassenger}（平台位移）、{@link AbstractCabinEntity#tick}
 * （到站最后一步与乘客名册）以及 ServerPlayNetworkHandlerMixin 的"轿厢地板算支撑"豁免。
 * 数值断言统一走 {@link #near}——容差 1e-7，与 {@code ElevatorParameters.POSITION_EPSILON}
 * 的"精确到站"语义同口径，因此浮点误差不会被误判成运动错误。</p>
 */
public class RiderMovementTests implements FabricGameTest {
    /**
     * 近似相等断言：容差 1e-7（格），与到站判定用的 POSITION_EPSILON 保持同一口径。
     *
     * <p>为什么不用 assertEquals：本类断言的都是双精度坐标，运动学结果必然带浮点残差；
     * 统一容差才能区分"真的差了半格"和"最后一位抖动"。失败信息里把期望值与实际值一起打出来，
     * 便于直接判断偏差量级（例如"差 2 格"= 平台位移没叠加，"差 0.02 格"= 到站吸附没走完）。
     *
     * @param ctx      GameTest 上下文
     * @param expected 期望值
     * @param actual   实际值
     * @param message  断言说明（会拼在期望/实际值之前）
     */
    private static void near(TestContext ctx, double expected, double actual, String message) {
        ctx.assertTrue(Math.abs(expected - actual) < 1e-7, message + ": " + expected + " != " + actual);
    }

    /**
     * 守住 {@link RiderMotionHistory} 的核心契约：<b>样本编号就是坐标权威</b>，只有服务端记过、
     * 且还落在有效期内的帧才能换算高度；过期、未来、重放的样本一律不得改写已记下的值。
     *
     * <p>场景：用 0.5 格/刻的匀速从 y=64 连续记 201 帧（tick 0..200，于是 tick=200 的高度是 164），
     * 然后按四种典型查询核对：当刻帧、延迟 10 刻的帧、未来帧（tick 201）、过期帧（tick 139，
     * 已超出 {@link RiderMotionHistory#MAX_AGE} = 60 刻的窗口）；最后用同一个 tick（200）
     * 写入一个明显异常的高度 900，模拟"迟到/被重放的旧包想把权威值顶掉"。
     *
     * <p>断言即契约：当刻 164；延迟帧 159（= 64 + 190 × 0.5，说明是按编号取历史值，
     * <b>不是</b>拿最新高度糊弄过去）；未来帧与过期帧必须是 NaN——{@link RiderMove#apply}
     * 正是靠 {@code Double.isFinite} 把这两种情况挡在重定基之外，否则 NaN 会直接污染玩家坐标；
     * 重复 tick 不覆盖已记录值（record 里 tick 非递增即丢弃），所以 200 帧仍是 164。
     * 这是"高延迟下客户端帧号可能超前或严重滞后"的第一道闸门。</p>
     *
     * <p>后半段遍历 4 种平台速度（−0.5 / −0.2 / +0.2 / +0.5 格/刻，含上下行）与 0..40 刻的延迟，
     * 逐个核对重定基公式 {@code current + (playerY − seenY)} 在任意延迟下都只是<b>平移</b>而不缩放：
     * 玩家相对轿厢地板的三个典型高度（0.2 站在地板、0.62 跳到一半、1.0 跳到最高）
     * 必须逐位保持。这就是"网络延迟不改变跳跃高度"的直接回归。</p>
     */
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void historyRejectsStaleAndUnknownFrames(TestContext ctx) {
        RiderMotionHistory history = new RiderMotionHistory();
        for (long tick = 0; tick <= 200; tick++) history.record(tick, 64 + tick * .5);
        near(ctx, 164, history.height(200, 200), "latest sample");
        near(ctx, 159, history.height(190, 200), "delayed sample");
        ctx.assertTrue(Double.isNaN(history.height(201, 200)), "future rejected");
        ctx.assertTrue(Double.isNaN(history.height(139, 200)), "expired rejected");
        history.record(200, 900);
        near(ctx, 164, history.height(200, 200), "duplicate cannot replace authority");
        for (double speed : new double[]{-.5, -.2, .2, .5}) {
            for (int delay = 0; delay <= 40; delay++) {
                double seen = 100 - speed * delay;
                for (double relative : new double[]{.2, .62, 1.0})
                    near(ctx, 100 + relative, RiderMotionHistory.rebase(seen + relative, seen, 100),
                            "delay must preserve relative jump height");
            }
        }
        ctx.complete();
    }

    /**
     * 守住 {@link AbstractCabinEntity#carryPassenger} 的不变量：<b>载客只是竖直平移，不是传送</b>——
     * 不许顺手清掉玩家的输入速度，也不许改写他的落地状态。
     *
     * <p>场景：造一个替身玩家（{@code ctx.createMockPlayer} 给的是普通 PlayerEntity 而不是
     * ServerPlayerEntity，因此"有未确认传送就暂停载客"那道闸门不参与，测的是实体层的纯位移行为），
     * 给它一个同时含水平与竖直分量的速度 (.13, .42, -.09)（疾跑 + 起跳的典型值，0.42 就是原版起跳初速），
     * 置为空中，然后依次以 +.2 / +.5 / −.2 / −.5 / 1e-6 格五种步长各载客一次：
     * 覆盖向上、向下的正常运行位移，也覆盖"最后一步只剩浮点残差"的极端情况。
     *
     * <p>断言三条，每条都对应一个真实症状：
     * <ul>
     *   <li>位置恰好平移 dy——平台位移不能多也不能少；</li>
     *   <li>{@code getVelocity()} 与放入前<b>完全相等</b>（精确比较，不是近似）：2.2.0 之前逐刻
     *       requestTeleport 的实现会经由传送路径把速度清掉，玩家在轿厢里"踩不住"自己的移动输入；</li>
     *   <li>空中的玩家不会被强制判成落地：跳跃中的乘客一旦被反复 setOnGround(true)，
     *       原版逻辑会立刻截断跳跃，表现为"电梯里跳不起来"。</li>
     * </ul>
     */
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void carryPreservesWalkJumpAndGroundState(TestContext ctx) {
        PlayerEntity player = ctx.createMockPlayer(GameMode.SURVIVAL);
        player.setPosition(2, 80.2, 2);
        Vec3d velocity = new Vec3d(.13, .42, -.09);
        player.setVelocity(velocity);
        player.setOnGround(false);
        for (double dy : new double[]{.2, .5, -.2, -.5, .000001}) {
            double y = player.getY();
            AbstractCabinEntity.carryPassenger(player, dy);
            near(ctx, y + dy, player.getY(), "platform translation");
            ctx.assertEquals(velocity, player.getVelocity(), "walk/jump velocity preserved");
            ctx.assertFalse(player.isOnGround(), "airborne player must not be forced grounded");
        }
        ctx.complete();
    }

    /**
     * 在测试结构里生成一台指定型号的轿厢，并把它绑定到测试原点 (2,4,1) 处的轨道上（朝向 SOUTH）。
     *
     * <p>返回的实体虽已放进世界，但本类所有测试都手工调用 {@code cabin.tick()} 同步驱动状态机，
     * 所以它的运动完全由测试控制、不受世界刻循环的干扰。轿厢中心落在轨道前方 2 格、底部与轨道同高
     * （见 {@link AbstractCabinEntity#initialize}），因此测试里"站点"的位置就是同一高度上的
     * {@code rail.south(3)} 处的门根方块。
     *
     * @param ctx  测试上下文
     * @param type 轿厢型号：普通 / 高速 / 观光
     * @return 已生成并完成初始化的轿厢实体
     */
    private static AbstractCabinEntity cabin(TestContext ctx, EntityType<? extends AbstractCabinEntity> type) {
        var cabin = type.create(ctx.getWorld());
        cabin.initialize(ctx.getAbsolutePos(new BlockPos(2, 4, 1)), Direction.SOUTH);
        ctx.getWorld().spawnEntity(cabin);
        return cabin;
    }

    /**
     * 造一个会被原版移动校验真实对待的替身服务端玩家，并把它摆到轿厢地板 +0.2 格处。
     *
     * <p>三个准备动作缺一不可，否则测到的就不是在线乘客的行为：
     * <ul>
     *   <li>反射读出原版 {@code requestedTeleportId} 并立刻回一个确认包：把 mock 玩家摆进世界时
     *       原版会挂起一次待确认传送，不确认的话 {@code easyelevator$canCarry()} 一直为 false，
     *       载客会被静默跳过，后面的断言全部变成空转；</li>
     *   <li>切成生存模式：创造模式玩家在原版移动校验里享受豁免（速度/飞行检查口径不同），
     *       只有生存模式才等价于普通乘客；</li>
     *   <li>{@code syncWithPlayerPosition()}：让服务端把当前位置当成既成事实，
     *       否则随后第一个移动包会被算成一次巨大位移而触发纠正传送，掩盖真正要测的东西。</li>
     * </ul>
     *
     * <p>脚高取 {@code cabin.getY() + .2}（＝轿厢地板面），也正是
     * {@link AbstractCabinEntity#supportsPassenger} 判定"算支撑"的高度（±0.025 格）。
     *
     * @param ctx   测试上下文
     * @param cabin 乘客要站的轿厢
     * @return 已就位、且没有待确认传送的替身玩家
     */
    private static ServerPlayerEntity player(TestContext ctx, AbstractCabinEntity cabin) {
        ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
        try {
            var id = ServerPlayNetworkHandler.class.getDeclaredField("requestedTeleportId");
            id.setAccessible(true);
            player.networkHandler.onTeleportConfirm(new TeleportConfirmC2SPacket(id.getInt(player.networkHandler)));
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
        player.changeGameMode(GameMode.SURVIVAL);
        player.setPosition(cabin.getX(), cabin.getY() + .2, cabin.getZ());
        player.setOnGround(true);
        player.networkHandler.syncWithPlayerPosition();
        return player;
    }

    /**
     * 守护"迟到的高延迟移动包"：服务端必须用<b>包内样本编号</b>找回乘客当时看到的轿厢高度，
     * 把这次移动重定基到轿厢当前位置，既不能被原版速度/碰撞校验判成异常而回踢纠正传送，
     * 也不能把起跳的相对高度吃掉。
     *
     * <p>场景：生成高速轿厢与一名真实替身玩家后，{@code cabin.tick()} 先记下客户端能看到的那一帧
     * （{@code frame} = 世界时间，{@code seenY} = 当时的地板高度），随后<b>不 tick 轿厢</b>就把它整体
     * 上移 2 格，并同步把乘客平移 2 格——等价于"轿厢已经上去了，而乘客这一步的移动包还在路上"。
     * 这一步刻意不动水平坐标，于是后面观察到的水平位移只能来自包本身。</p>
     *
     * <p>第一个包按乘客<b>当初看到的</b>高度给出（{@code seenY + .2}，即站在地板上），并带 +.1 的水平位移。
     * 断言：水平位移被接受、竖直被重定基到 {@code cabin.getY() + .2}（相对地板高度 0.2 逐位保持）、
     * 且没有产生纠正传送（canCarry 仍为真）。若不重定基，这个"比服务端当前坐标低 2 格"的包会被原版
     * 当成非法移动直接纠正，玩家每刻被拽回去——这正是 2.2.0 要修的抖动来源。</p>
     *
     * <p>第二个包把相对高度换成 .62 且 {@code ground=false}（起跳瞬间）。断言重定基后仍是
     * {@code cabin.getY() + .62}、玩家保持空中、同样不需要传送：迟到的跳跃包必须保住"跳"这个
     * 相对位移，不能被吸附回地板。</p>
     */
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void delayedPacketsKeepWalkingAndJumping(TestContext ctx) {
        var cabin = cabin(ctx, Easyelevator.HIGH_SPEED_CABIN);
        var player = player(ctx, cabin);
        cabin.tick(); // Record the actual client frame before the platform moves ahead.
        long frame = ctx.getWorld().getTime();
        double seenY = cabin.getY();
        cabin.setPosition(cabin.getX(), seenY + 2, cabin.getZ());
        AbstractCabinEntity.carryPassenger(player, 2);
        var move = new RiderMove(cabin.getId(), frame, player.getX() + .1, seenY + .2,
                player.getZ(), 32, 8, true, true, true);
        move.apply(player);
        near(ctx, cabin.getX() + .1, player.getX(), "horizontal movement accepted");
        near(ctx, cabin.getY() + .2, player.getY(), "delayed packet rebased");
        ctx.assertTrue(((PlatformMovement) player.networkHandler).easyelevator$canCarry(), "no correction teleport");
        new RiderMove(cabin.getId(), frame, player.getX() + .1, seenY + .62,
                player.getZ(), 32, 8, false, true, true).apply(player);
        near(ctx, cabin.getY() + .62, player.getY(), "jump preserved in cabin frame");
        ctx.assertFalse(player.isOnGround(), "jump remains airborne");
        ctx.assertTrue(((PlatformMovement) player.networkHandler).easyelevator$canCarry(), "jump needs no teleport");
        player.discard(); cabin.discard();
        ctx.complete();
    }

    /**
     * 守护"真传送优先"：电梯载客必须给待确认的传送让路，不能把乘客顺手带到平台的新高度上。
     *
     * <p>场景：乘客站进普通轿厢后，由服务端发起一次真实的 requestTeleport（+10 格），
     * 代表 /tp、名册归位或管理员移动。此时原版 networkHandler 里挂着 requestedTeleportPos，
     * {@code easyelevator$canCarry()} 为 false，于是 {@link AbstractCabinEntity#carryPassenger}
     * 必须在改坐标之前<b>直接返回</b>，连 fallDistance 也不该动。
     *
     * <p>断言：载客调用之后玩家高度仍等于传送目标值（没有被叠加那 .5 格）。
     * 漏掉这条判据的后果有两层：落点被平台位移污染（玩家去不了该去的地方），
     * 以及传送确认到达后服务端坐标与自己记下的位置对不上，触发持续纠正——与"主动离开的乘客被拽回轿厢"
     * 属于同一类缺陷。
     */
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void genuineTeleportIsNotCarried(TestContext ctx) {
        var cabin = cabin(ctx, Easyelevator.CABIN);
        var player = player(ctx, cabin);
        player.networkHandler.requestTeleport(player.getX(), player.getY() + 10, player.getZ(), 0, 0);
        double y = player.getY();
        AbstractCabinEntity.carryPassenger(player, .5);
        near(ctx, y, player.getY(), "pending teleport must retain its destination");
        player.discard(); cabin.discard();
        ctx.complete();
    }

    /**
     * 守护 ServerPlayNetworkHandlerMixin 的"轿厢地板也算真实支撑"豁免，以及它<b>不能</b>被扩大化的边界。
     *
     * <p>背景：原版浮空检查只把方块当支撑（mixin 自己的注释就是这么写的）。乘客站在轿厢地板（实体）上时
     * 脚下没有方块，原版会把这种"没有方块支撑的落地"记成浮空：轻则每刻纠正，重则 floatingTicks 累积到
     * 阈值后被判"浮空过久"踢出。mixin 在 {@code onPlayerMove} 返回时把 supportsPassenger
     * （脚高落在地板 0.2±0.025 格内、轿厢未被移除）的轿厢地板当作支撑，清掉 floating 与 floatingTicks
     * 并清零下落距离。
     *
     * <p>场景用反射读原版私有字段 {@code floating} 作为观测点，两半互为对照：先让站在厢内的玩家上报
     * {@code OnGroundOnly(true)}，断言 floating 已被清成 false（豁免生效，玩家能安全站在电梯里）；
     * 再把玩家挪到轿厢侧方 5 格（同高度，附近只剩空气）并重新同步位置后上报 {@code OnGroundOnly(false)}，
     * 断言原版自己算出的判定不被本模组改写（此处仍是 true）。后一半才是这条测试的真正价值：
     * 豁免必须精确等于 supportsPassenger 的判据，一旦被写成"附近有轿厢就免检"，
     * 玩家就能在轿厢旁边随意谎报落地。
     */
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void actualFloorClearsFloatingButAirDoesNot(TestContext ctx) throws Exception {
        var cabin = cabin(ctx, Easyelevator.CABIN);
        var player = player(ctx, cabin);
        var floating = ServerPlayNetworkHandler.class.getDeclaredField("floating");
        floating.setAccessible(true);
        player.networkHandler.onPlayerMove(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.OnGroundOnly(true));
        ctx.assertFalse(floating.getBoolean(player.networkHandler), "entity floor counts as real support");
        player.setPosition(cabin.getX() + 5, cabin.getY() + 1, cabin.getZ());
        player.networkHandler.syncWithPlayerPosition();
        player.networkHandler.onPlayerMove(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.OnGroundOnly(false));
        ctx.assertTrue(floating.getBoolean(player.networkHandler), "nearby air must retain vanilla floating check");
        player.discard(); cabin.discard(); ctx.complete();
    }

    /**
     * 守护"样本编号是唯一凭证"：包里的样本号对不上服务端历史时，电梯<b>一分平台位移都不给</b>，
     * 但玩家自己的原版行走照常生效。
     *
     * <p>场景：普通轿厢 tick 一次留下真实样本后，直接投递一个样本号为 {@code Long.MAX_VALUE} 的移动包
     * ——一个未来的、服务端从未记录过的帧，同时覆盖了越界/伪造样本号的这一类输入。
     * 包本身是合法的原版移动：带 +.1 的水平位移与 {@code ground=true}。
     *
     * <p>断言：竖直坐标一格不动，且水平位移被接受（{@code cabin.getX() + .1}）。
     * 竖直不动是因为 {@link AbstractCabinEntity#motionHeight} 对未知帧返回 NaN，而
     * {@link RiderMove#apply} 用 {@code Double.isFinite} 把它挡在重定基之外——若漏掉这道判断，
     * {@code y − NaN} 会把乘客坐标直接写成 NaN。水平照常则说明拦下的是"平台位移额度"，
     * 不是"乘客不许动"：没有样本可用时应退回纯原版移动，而不是丢弃这次输入。
     */
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void forgedSampleCannotAddPlatformDisplacement(TestContext ctx) {
        var cabin = cabin(ctx, Easyelevator.CABIN);
        var player = player(ctx, cabin);
        cabin.tick();
        double y = player.getY();
        new RiderMove(cabin.getId(), Long.MAX_VALUE, player.getX() + .1, y, player.getZ(),
                0, 0, true, true, false).apply(player);
        near(ctx, y, player.getY(), "unknown sample has no transport credit");
        near(ctx, cabin.getX() + .1, player.getX(), "fallback still uses vanilla walking");
        player.discard(); cabin.discard(); ctx.complete();
    }

    /**
     * 在指定根方块处造一扇完整的 3×3 楼层门（朝向 SOUTH），即一层"站点"。
     *
     * <p>根方块 = 门底部中心；门宽 3 格（COLUMN 0..2 依次对应根方块西侧 −1、0、+1），高 3 格（LEVEL 0..2）。
     * 必须<b>整扇完整且朝向与轨道一致</b>，{@code ElevatorLine.scan} 才会把它算作站点，
     * 因此这个方法实际定义了"这条测试线路上有哪些楼层"。方块更新标志用 2（不触发邻块连锁更新），
     * 避免测试世界里冒出与本次回归无关的更新与声音。
     *
     * @param ctx  测试上下文
     * @param root 门根方块的世界坐标（应位于轨道朝向前方 3 格处）
     */
    private static void door(TestContext ctx, BlockPos root) {
        for (int column = 0; column < 3; column++) for (int level = 0; level < 3; level++)
            ctx.getWorld().setBlockState(root.offset(Direction.WEST, column - 1).up(level),
                    Easyelevator.LANDING_DOOR.getDefaultState().with(LandingDoorBlock.FACING, Direction.SOUTH)
                            .with(LandingDoorBlock.COLUMN, column).with(LandingDoorBlock.LEVEL, level), 2);
    }

    /**
     * 一趟完整行程的共享场景与断言，由六个 {@code *Arrival} 测试按「型号 × 方向」调用。
     *
     * <p>场景搭建（全部在 EMPTY_STRUCTURE 里现搭，不读存档）：生成指定型号轿厢并 initialize 到轨道上
     * （朝向 SOUTH，轿厢中心在轨道前方 2 格）；由于 EMPTY_STRUCTURE 自带边界屏障，先<b>显式清空整条井道</b>
     * （轨道左右各 1 格、朝向前方 1~3 格、高 10 格），否则轿厢会被屏障卡住或被判成故障；
     * 再铺 9 格同朝向轨道（y..y+8），并在 y 与 y+6 处各造一扇完整楼层门（{@link #door}）——
     * 站点 = 轨道朝向前方 3 格处的门根方块，于是这条测试线路恰好两层，目标层为上行 y+6、下行 y。
     * 下行时先把轿厢摆到 y+6 再上人；随后放入"地板 +0.2 格"的替身玩家（{@link #player}），
     * 并在进入循环之前先断言 requestStop 被受理：受理失败说明线路/站点/朝向这套搭建根本不成立，
     * 后面所有断言都会变成无意义的噪声。
     *
     * <p>核心断言（最多 400 刻，逐刻执行）：
     * <ul>
     *   <li><b>逐刻对齐</b>：乘客高度恒等于 {@code cabin.getY() + .2}。这条断言<b>包含到站那一小步</b>——
     *       状态机到站时会先把相位切成 OPENING，如果按相位提前跳过这一步的位移写回，乘客就会永远差
     *       最后一步（到站瞬间脚下"空一下"，或被原版浮空/位移检查纠正）。这是 2.2.0 修的 bug 之一，
     *       对应 AbstractCabinEntity.tick 里 "Include the final arrival step even when the controller
     *       already switched to OPENING" 那行注释；</li>
     *   <li><b>不传真</b>：全程 {@code easyelevator$canCarry()} 为真——载客只叠加位移、不产生任何纠正传送，
     *       否则在线乘客会被每刻回拉（多人场景下的抖动甚至踢出）；</li>
     *   <li><b>真的到站</b>：必须出现"高度精确等于目标层、且相位为 OPENING"的一刻。失败信息把相位、
     *       当前高度、目标层、线路扫描结果与空间检查一起打出来，便于区分"根本没走到"（运动或联锁问题）
     *       与"走到了但状态不对"；</li>
     *   <li><b>到站后身体仍站得住</b>：用收窄 1e-7 的包围盒做 isSpaceEmpty 检查，保证乘客没有被门框、
     *       轿厢正面或残留的边界屏障压进方块里（客户端冒烟测试里"被压成趴下"的 SWIMMING 断言，
     *       在服务端就对应这条）；</li>
     *   <li><b>开门过程中离厢不被拉回</b>：到站后把乘客挪到门口内侧（仍在轿厢水平范围内、位于地板上方），
     *       再 tick 一刻，断言 Z 坐标原样保留。守的是"在线玩家正常离开时被存档恢复名册
     *       （passengers / recoveringPassengers）用 requestTeleport 拽回厢内"的旧缺陷：
     *       只有真正掉线、实体已不在世界里的乘客才允许归位。</li>
     * </ul>
     *
     * <p>这里直接同步调用 {@code cabin.tick()}，而不是交给世界刻循环：替身玩家的原版刻会引入与本次回归
     * 无关的位移与状态，逐刻断言需要完全确定的驱动顺序。
     *
     * @param ctx  测试上下文
     * @param type 轿厢型号：普通 / 高速 / 观光（三型共用同一套运动与载客实现，只有步长不同）
     * @param up   true = 上行（y → y+6），false = 下行（y+6 → y）
     */
    private static void trip(TestContext ctx, EntityType<? extends AbstractCabinEntity> type, boolean up) {
        var cabin = cabin(ctx, type);
        BlockPos rail = new BlockPos(cabin.railX(), (int) cabin.getY(), cabin.railZ());
        // The built-in empty structure has boundary barriers; open the entire test shaft explicitly.
        for (BlockPos pos : BlockPos.iterate(rail.add(-1, 0, 1), rail.add(1, 9, 3)))
            ctx.getWorld().setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
        for (int y = 0; y <= 8; y++) ctx.getWorld().setBlockState(rail.up(y),
                Easyelevator.RAIL.getDefaultState().with(ElevatorRailBlock.FACING, Direction.SOUTH), 2);
        door(ctx, rail.south(3)); door(ctx, rail.up(6).south(3));
        if (!up) cabin.setPosition(cabin.getX(), rail.getY() + 6, cabin.getZ());
        var player = player(ctx, cabin);
        double target = rail.getY() + (up ? 6 : 0);
        ctx.assertTrue(cabin.requestStop(rail.up(up ? 6 : 0).south(3)), "trip accepted");
        // Run the actual entity/controller synchronously so vanilla fake-player ticking adds no unrelated motion.
        boolean arrived = false;
        for (int i = 0; i < 400; i++) {
            cabin.tick();
            near(ctx, cabin.getY() + .2, player.getY(), "floor/rider alignment including arrival step");
            ctx.assertTrue(((PlatformMovement) player.networkHandler).easyelevator$canCarry(), "ride must not teleport");
            if (Math.abs(cabin.getY() - target) < 1e-7 && cabin.phase() == ElevatorController.Phase.OPENING) {
                arrived = true; break;
            }
        }
        ctx.assertTrue(arrived, "must actually arrive: phase=" + cabin.phase() + " y=" + cabin.getY()
                + " target=" + target + " line=" + cabin.line() + " clear="
                + cabin.spaceClear(cabin.getBoundingBox().union(cabin.getBoundingBox().offset(0, target-cabin.getY(), 0)).contract(.001)));
        ctx.assertTrue(ctx.getWorld().isSpaceEmpty(player, player.getBoundingBox().contract(1e-7)),
                "standing body must fit after arrival");
        // Leave during OPENING: the online roster must not teleport the rider back.
        player.setPosition(player.getX(), target + .2, cabin.getZ() + 1.2);
        cabin.tick();
        near(ctx, cabin.getZ() + 1.2, player.getZ(), "exit retained");
        player.discard(); cabin.discard();
        ctx.complete();
    }

    /**
     * 普通轿厢（0.20 格/刻 = 4 格/秒）上行到站：{@link #trip} 的基准组合。
     *
     * <p>步长最小，因此它是另外五个组合的对照基准——这里失败说明问题出在三型共用的运动/载客/名册逻辑上，
     * 而不是某一型号的步长。守护的回归点是：到站最后一步位移不丢、全程无纠正传送、
     * 到站后乘客身体仍站得住、以及开门过程中离厢不被名册拉回。
     */
    @GameTest(templateName = EMPTY_STRUCTURE) public void normalUpArrival(TestContext ctx) { trip(ctx, Easyelevator.CABIN, true); }
    /**
     * 普通轿厢下行到站（y+6 → y）：与上行共用全部断言，方向相反。
     *
     * <p>单独跑下行不是重复：减速段与到站吸附的方向相反，平台向下运动时乘客脚下地板"离开"的方式也不同，
     * 名册归位与离厢判定会走到另外的分支。若有人把最后一步位移或其它补偿写成只适配上行，
     * 这条会立刻失败。
     */
    @GameTest(templateName = EMPTY_STRUCTURE) public void normalDownArrival(TestContext ctx) { trip(ctx, Easyelevator.CABIN, false); }
    /**
     * 高速轿厢（0.50 格/刻 = 10 格/秒，普通梯的 2.5 倍）上行到站。
     *
     * <p>单刻位移最大、最接近"一步半格"的边界，所以到站吸附与最后一步位移一旦算错，误差会被放大 2.5 倍；
     * 同时验证高速档仍满足"单步不超过 1 格、不会跨过整格站点"的约束
     * （见 HighSpeedCabinEntity 的说明：提速不影响任何语义，只影响每刻走多远）。
     */
    @GameTest(templateName = EMPTY_STRUCTURE) public void expressUpArrival(TestContext ctx) { trip(ctx, Easyelevator.HIGH_SPEED_CABIN, true); }
    /**
     * 高速轿厢下行到站：最大步长 × 反向减速的组合。
     *
     * <p>守护"速度只是一个步长参数"这条不变量——高速梯向下到站时同样必须逐刻与乘客对齐、精确吸附，
     * 到站后乘客站得住并能正常离厢，不能因为步长大而在最后一刻把乘客甩在地板下或顶上。
     */
    @GameTest(templateName = EMPTY_STRUCTURE) public void expressDownArrival(TestContext ctx) { trip(ctx, Easyelevator.HIGH_SPEED_CABIN, false); }
    /**
     * 观光轿厢上行到站。
     *
     * <p>观光型号的速度、碰撞、载客判定与门时序与普通型号逐条相同，差别只在客户端渲染
     * （玻璃墙面是纯渲染提示，见 ObservationCabinEntity 的说明）。这条断言的作用就是把
     * "观光梯没有独立的运动/载客分支"钉住：一旦有人为了玻璃外观去改动碰撞壳、乘客判定或门时序，
     * 这里的逐刻对齐与到站后的空间检查会立刻报错。
     */
    @GameTest(templateName = EMPTY_STRUCTURE) public void glassUpArrival(TestContext ctx) { trip(ctx, Easyelevator.OBSERVATION_CABIN, true); }
    /**
     * 观光轿厢下行到站：三型 × 双向共六种组合的最后一种。
     *
     * <p>与观光上行共享全部断言、方向相反。六个组合齐备，才能保证"型号"与"方向"这两个维度上
     * 都没有走偏的分支（三型共用同一套状态机，六条用例是这套共用实现的最小完备覆盖）。
     */
    @GameTest(templateName = EMPTY_STRUCTURE) public void glassDownArrival(TestContext ctx) { trip(ctx, Easyelevator.OBSERVATION_CABIN, false); }
}
