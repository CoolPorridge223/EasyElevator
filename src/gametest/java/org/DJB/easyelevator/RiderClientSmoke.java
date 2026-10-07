package org.DJB.easyelevator;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityPose;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.slf4j.LoggerFactory;

/**
 * "真客户端"乘坐冒烟测试：在真实 Minecraft 客户端里用模拟按键坐一趟高速电梯，用来覆盖 GameTest
 * 覆盖不到的客户端路径（客户端物理更新顺序、本地乘客与轿厢同刻推进、门与轿厢碰撞、玩家姿态）。
 * 一次性、可丢弃的世界；只存在于 opt-in 的测试模组里，<b>永不进发行包</b>。
 *
 * <p>它在 {@code src/gametest/resources/fabric.mod.json} 里登记为 {@code client} 入口点，
 * 但只有读到 JVM 系统属性 {@code easyelevator.riderSmoke}=true 时才会注册刻回调
 * （见 {@link #onInitializeClient()} 的首行判据），所以在任何正常游戏里挂载本模组都不会有副作用。</p>
 *
 * <p>如何运行：
 * <pre>{@code .\gradlew.bat -PriderTests runRiderClient}</pre>
 * 这个运行配置由 build.gradle 第 41~49 行在带 {@code -PriderTests} 时创建（不带该属性时它和
 * {@code easyelevator-test} 测试模组都不会存在），它已经做了三件事：把 gametest 源集加进运行类路径、
 * 以 {@code vmArg} 注入 {@code -Deasyelevator.riderSmoke=true}、并用
 * {@code --quickPlaySingleplayer RiderSmoke} 直接进入隔离的单人世界；运行目录为 build/run/riderClient。
 * 若要改用别的客户端运行配置（例如 {@code runClient}），必须自己把
 * {@code -Deasyelevator.riderSmoke=true} 作为<b>游戏 JVM</b>参数传进去（运行配置的 vmArg 或 IDE 的
 * VM options）——它由 {@code Boolean.getBoolean} 在游戏进程里读取，落在别处一律无效。</p>
 *
 * <p>流程（全部在 {@link #tick(MinecraftClient)} 里按刻推进）：清场并搭一条两层井道 → 在底层生成
 * 高速轿厢并把玩家传送进厢 → 请求上行 → 途中按下跳跃与侧移 → 到站门全开时结算"输入有没有生效" →
 * 按住前进走出门、再按住后退走回厢内 → 请求返回底层 → 门全开且稳定 10 刻后判定通过。
 * 失败一律以 {@code RIDER_CLIENT_SMOKE_FAILED} 记日志并退出游戏，通过则打印
 * {@code RIDER_CLIENT_SMOKE_PASSED}（含跳跃/侧移峰值与最终姿态）便于检索。</p>
 *
 * <p>守护的回归点（均来自 2.2.0「平滑移动修复」）：
 * <ul>
 *   <li><b>乘客保留自主移动</b>：坐着时跳跃与侧移都要真的产生位移——旧实现逐刻传送会把输入吃掉；</li>
 *   <li><b>上行到站出门不被压扁</b>：姿态一旦变成 {@link EntityPose#SWIMMING}（被门框/地板挤成爬行）
 *       即判失败，对应"上行到站出门时人物被迫趴下"的旧缺陷；</li>
 *   <li><b>不穿地板</b>：乘坐期间脚高不得掉到轿厢地板以下；</li>
 *   <li><b>能出去也能回来</b>：开门过程中走出门外再走回厢内，且不被服务端乘客名册拽回；</li>
 *   <li><b>整趟有界</b>：900 刻（45 秒）内必须跑完，启动等待另有 1200 刻（60 秒）上限，
 *       避免出现"挂在那里永远不结束"的测试。</li>
 * </ul>
 */
public class RiderClientSmoke implements ClientModInitializer {
    /** 世界就绪后的客户端刻计数：驱动下面各阶段的时间点，同时是 900 刻（45 秒）整体超时的依据。 */
    private int ticks;
    /** 世界/玩家/内置服务器尚未就绪期间累加的刻数：每 100 刻打一次等待日志，超过 1200 刻（60 秒）判启动超时。 */
    private int startupTicks;
    /** 服务端创建出来的测试轿厢实体 id；-1 表示尚未创建。volatile 是因为它由服务端线程写入、客户端刻线程读取。 */
    private volatile int cabinId = -1;
    /**
     * 一次性阶段标志（各自只置位一次，串成一条线性状态机）：
     * <ul>
     *   <li>{@code setup}：场景已搭好（清场 → 铺 24 格轨道 → 两层站台与楼层门 → 生成高速轿厢 →
     *       玩家切生存并传送到厢内地板 80.2 格）；</li>
     *   <li>{@code requested}：已请求上行到 y=100。它同时是"开始采样跳跃/侧移"的起点；</li>
     *   <li>{@code jumped}：已在上升途中（83 &lt; y &lt; 88 这一段）按过一次跳跃 + 侧移。
     *       置位后下一段 else 会松开这两个键，因此测的是"按下的那一下有没有生效"，而不是长按；</li>
     *   <li>{@code exiting} / {@code reentering}：出门 / 回门阶段。两者既驱动开门的 doorCommand(true)，
     *       也决定按前进键还是后退键；</li>
     *   <li>{@code exitChecked}：已完成过一次"走出门外"的判定，防止 exiting 被重复触发；</li>
     *   <li>{@code returned}：已走回厢内并已请求下行返回底层，等待最终判定。</li>
     * </ul>
     */
    private boolean setup, requested, jumped, returned, exiting, reentering, exitChecked;
    /** 回到 y=80 且门已全开后的稳定刻数：连续 10 刻才宣告通过，避开"门刚开、乘客还没落稳"的那一瞬。 */
    private int arrivalTicks;
    /**
     * 乘坐期间的输入采样量：
     * <ul>
     *   <li>{@code maxJump}：乘客相对轿厢地板的最大抬升，即 {@code 玩家Y − 轿厢Y − 0.2} 的峰值。
     *       地板面是 0.2 格，所以它从 0 起涨；达到 0.2 以上才证明"跳"真的被保留了；</li>
     *   <li>{@code maxHorizontal}：相对 {@code startX} 的最大水平位移绝对值。轿厢只做竖直运动，
     *       因此这段位移只能来自玩家自己的输入；达到 0.005 以上才证明行走没被锁死；</li>
     *   <li>{@code startX}：发出上行请求那一刻乘客的 X，作为水平位移的基准点。</li>
     * </ul>
     */
    private double maxJump, maxHorizontal, startX;

    /**
     * 注册刻回调，并做"要不要接管这个客户端"的总开关判定。
     *
     * <p>为什么需要开关：本类被打进 opt-in 的测试模组，而 {@code client} 入口点在任何一个装了该模组的
     * 客户端里都会被调用。只有系统属性 {@code easyelevator.riderSmoke}=true（riderClient 运行配置已注入）
     * 时才注册回调，因此对正常游戏零影响。</p>
     *
     * <p>回调的三条职责：
     * <ol>
     *   <li>世界、本地玩家或内置服务器还没就绪时只记等待日志（每 100 刻一条，便于判断卡在哪一步），
     *       超过 1200 刻判定为启动超时并退出游戏；</li>
     *   <li>就绪后把每一刻交给 {@link #tick(MinecraftClient)} 推进状态机；</li>
     *   <li>把任何 {@link Throwable}（断言失败、意外异常）都<b>先松开本测试按下的所有按键再退出游戏</b>：
     *       否则失败现场会留下"前进键一直按着"的状态，掩盖真实原因，也会让日志难以判读。
     *       日志用固定前缀 {@code RIDER_CLIENT_SMOKE_FAILED}，方便按前缀筛查结果。</li>
     * </ol>
     */
    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("easyelevator.riderSmoke")) return;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null || client.getServer() == null) {
                if (++startupTicks % 100 == 0) LoggerFactory.getLogger("rider-smoke").info("Waiting for test world: {}", client.currentScreen);
                if (startupTicks > 1200) {
                    LoggerFactory.getLogger("rider-smoke").error("RIDER_CLIENT_SMOKE_FAILED: startup timeout");
                    client.scheduleStop();
                }
                return;
            }
            try { tick(client); }
            catch (Throwable error) {
                LoggerFactory.getLogger("rider-smoke").error("RIDER_CLIENT_SMOKE_FAILED", error);
                client.options.rightKey.setPressed(false);
                client.options.jumpKey.setPressed(false);
                client.options.forwardKey.setPressed(false);
                client.options.backKey.setPressed(false);
                client.scheduleStop();
            }
        });
    }

    /**
     * 状态机的单刻推进：只有世界、本地玩家与内置服务器都就绪时才会被调用（见 {@link #onInitializeClient()}）。
     *
     * <p>阶段顺序固定，每一步都依赖前一步的产物：搭场景（0）→ 等轿厢同步到客户端并请求上行（1）
     * → 途中按一次跳跃 + 侧移（2）→ 持续采样输入与姿态（3）→ 到站结算输入是否真的生效（4）
     * → 走出门再走回厢（5）→ 返回底层后宣告通过并退出游戏（6）。任何不满足的断言都抛
     * {@link AssertionError}，由外层刻回调统一记日志、松开按键并退出游戏。</p>
     *
     * @param client 当前客户端实例（由刻回调传入，避免再去取静态实例）
     */
    private void tick(MinecraftClient client) {
        // 本刻计数：下面各阶段都以它计时，也是 900 刻整体超时的依据。
        ticks++;
        // 阶段 0：一次性搭场景。世界改动必须丢到服务端线程（execute）里做，本刻客户端还看不到轿厢实体。
        if (!setup) {
            setup = true;
            client.getServer().execute(() -> {
                // 先清掉上一次跑留下的轿厢，保证井道里只有本测试创建的那一台（线路唯一性是 requestStop 的前提）。
                var world = client.getServer().getOverworld();
                world.getEntitiesByClass(AbstractCabinEntity.class,
                        new net.minecraft.util.math.Box(-3, 78, -2, 5, 110, 8), c -> true)
                        .forEach(AbstractCabinEntity::discard);
                // 井道：轨道列 (0, 80..103, 0)；两层站台的地板分别是 y=80 与 y=100，
                // 站点门根在轨道朝向前方 3 格处（即 (0,80,3) 与 (0,100,3)），与下面 requestStop 的目标一致。
                BlockPos rail = new BlockPos(0, 80, 0);
                for (BlockPos p : BlockPos.iterate(new BlockPos(-3, 79, -2), new BlockPos(4, 108, 6)))
                    world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                for (int y = 0; y <= 23; y++) world.setBlockState(rail.up(y),
                        Easyelevator.RAIL.getDefaultState().with(ElevatorRailBlock.FACING, Direction.SOUTH), 2);
                for (int y : new int[]{0, 20}) {
                    for (int x = -1; x <= 1; x++) for (int z = 4; z <= 7; z++)
                        world.setBlockState(new BlockPos(x, 79 + y, z), Blocks.STONE.getDefaultState(), 2);
                    BlockPos root = rail.up(y).south(3);
                    for (int column = 0; column < 3; column++) for (int level = 0; level < 3; level++)
                        world.setBlockState(root.offset(Direction.WEST, column - 1).up(level),
                                Easyelevator.LANDING_DOOR.getDefaultState().with(LandingDoorBlock.FACING, Direction.SOUTH)
                                        .with(LandingDoorBlock.COLUMN, column).with(LandingDoorBlock.LEVEL, level), 2);
                }
                // 用高速轿厢：80 ↔ 100 的整个来回必须塞进 900 刻内，普通速度会让全局超时先触发。
                var cabin = Easyelevator.HIGH_SPEED_CABIN.create(world);
                cabin.initialize(rail, Direction.SOUTH);
                world.spawnEntity(cabin);
                cabinId = cabin.getId();
                // 玩家切生存（创造模式在原版移动校验里有豁免，测不出真实乘客行为），再直接传送到厢内：
                // 80.2 = 地板 80 + 0.2，正是 supportsPassenger 认定"站在地板上"的高度。
                ServerPlayerEntity player = client.getServer().getPlayerManager().getPlayer(client.player.getUuid());
                player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                player.networkHandler.requestTeleport(cabin.getX(), 80.2, cabin.getZ(), 0, 0);
            });
        }
        // 全局超时：任何阶段卡住都必须以失败收场，不能留下一个永不退出的测试进程。
        if (ticks > 900) throw new AssertionError("client round trip timed out");
        // 轿厢实体还没同步到客户端（或服务端线程里的 setup 尚未跑完）：本刻不做任何判定。
        if (!(client.world.getEntityById(cabinId) instanceof AbstractCabinEntity cabin)) return;
        // 阶段 1：确认玩家真的已经在厢内（传送与实体同步都要时间）之后，才请求上行到 (0,100,3)，
        // 并把此刻的 X 记为水平位移基准 startX。
        if (!requested && ticks > 40 && cabin.containsPassenger(client.player)) {
            requested = true;
            startX = client.player.getX();
            client.getServer().execute(() -> ((AbstractCabinEntity) client.getServer().getOverworld()
                    .getEntityById(cabinId)).requestStop(new BlockPos(0, 100, 3)));
        }
        // 阶段 2：在上升途中（轿厢高度 83~88 之间）按一次跳跃 + 侧移，模拟乘客在轿厢里自己跳和横着走；
        // 其余时刻一律松开，因此测到的是"按下的那一下有没有生效"，而不是长按。
        if (requested && cabin.getY() > 83 && cabin.getY() < 88 && !jumped) {
            jumped = true;
            client.options.jumpKey.setPressed(true);
            client.options.rightKey.setPressed(true);
        } else {
            client.options.jumpKey.setPressed(false);
            client.options.rightKey.setPressed(false);
        }
        // 阶段 3：连续采样输入效果，并守住两条"客户端物理没被破坏"的硬条件。
        if (requested) {
            // maxJump 以地板面为基准（地板 0.2 格），所以它从 0 起涨；maxHorizontal 只能来自玩家自己的输入。
            maxJump = Math.max(maxJump, client.player.getY() - cabin.getY() - .2);
            maxHorizontal = Math.max(maxHorizontal, Math.abs(client.player.getX() - startX));
            // 姿态被挤成爬行 = 门框或地板把乘客压扁了，这是上行到站出门时最典型的症状。
            if (client.player.getPose() == EntityPose.SWIMMING) throw new AssertionError("rider forced crawling");
            // 脚高掉到地板上沿 0.14 格以下 = 穿进地板（0.14 是 containsPassenger 的下界容差）；
            // 出门/回门阶段人已在厢外，本条件不适用。
            if (!exiting && !reentering && client.player.getY() < cabin.getY() + .14)
                throw new AssertionError("rider fell through floor");
        }
        // 阶段 4：到达顶层且门已全开时结算这一趟——如果玩家输入被逐刻传送吃掉，
        // maxJump / maxHorizontal 会停在 0 附近，这一步就会失败。
        if (!returned && !exiting && !reentering && !exitChecked && cabin.getY() == 100 && cabin.phase() == ElevatorController.Phase.OPEN) {
            exiting = true;
            // 阈值：跳跃至少要把人抬离地板 0.2 格，侧移至少 0.005 格（轿厢只做竖直运动，这点位移只能来自玩家输入）。
            if (maxJump < .2 || maxHorizontal < .005) throw new AssertionError("input not preserved: jump=" + maxJump + " walk=" + maxHorizontal);
        }
        // 阶段 5：真实地走出门、再走回厢内。doorCommand(true) 每刻续开，避免门在离厢途中自己关上。
        if (exiting || reentering) {
            client.getServer().execute(() -> ((AbstractCabinEntity) client.getServer().getOverworld()
                    .getEntityById(cabinId)).doorCommand(true));
            client.options.forwardKey.setPressed(exiting);
            client.options.backKey.setPressed(reentering);
            // 出门判定：走出轿厢与门框范围（轿厢中心 Z≈2.5，站台铺在 Z=4..7）后切换成"往回走"。
            if (exiting && client.player.getZ() > 5.2) {
                exiting = false; reentering = true; exitChecked = true;
                client.options.forwardKey.setPressed(false);
            // 回门判定：走回厢内并重新被判定为乘客，才算"出去再回来"完成，随后请求下行返回底层。
            } else if (reentering && client.player.getZ() <= 2.6 && cabin.containsPassenger(client.player)) {
                reentering = false; returned = true;
                client.options.backKey.setPressed(false);
                LoggerFactory.getLogger("rider-smoke").info("RIDER_EXIT_REENTRY_PASSED: no crawling after upward arrival");
                client.getServer().execute(() -> ((AbstractCabinEntity) client.getServer().getOverworld()
                        .getEntityById(cabinId)).requestStop(new BlockPos(0, 80, 3)));
            }
        }
        // 阶段 6：回到起点层、门已全开，再稳定 10 刻（确认乘客已落稳，而不是刚开门的那一瞬）即宣告通过并退出游戏。
        if (returned && cabin.getY() == 80 && cabin.phase() == ElevatorController.Phase.OPEN) {
            if (++arrivalTicks < 10) return;
            LoggerFactory.getLogger("rider-smoke").info("RIDER_CLIENT_SMOKE_PASSED: round trip, jump={}, walk={}, standing={}",
                    maxJump, maxHorizontal, client.player.getPose());
            client.scheduleStop();
        }
    }
}
