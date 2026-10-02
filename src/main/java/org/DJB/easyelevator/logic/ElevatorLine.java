package org.DJB.easyelevator.logic;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.block.ElevatorRailBlock;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One unbroken, consistently oriented vertical column. Adjacent shafts stay separate.
 *
 * <p>职责：描述一条线路（line）——一段垂直连续、朝向一致的电梯轨道列，以及这条线上扫描出的站点（stop）列表。
 * 本身是纯数据 record，但 {@link #scan} 与 {@link #matches} 需要读取世界。</p>
 *
 * <p>在整体架构中的位置：ElevatorRailBlock 与 AbstractCabinEntity 都通过 scan() 从被点击/自身所在的轨道方块
 * 反推出整条线路，再用它确定运行范围、站点集合与轿厢归属；线路朝向同时是轿厢所在方向与轿厢门朝向。</p>
 *
 * <p>关键不变量：x/z 固定，不支持转弯/斜轨/分岔；[bottom, top] 范围内每一格都是同朝向电梯轨道；
 * 轿厢中心固定在线路朝向前方 2 格（见 {@link #centerX()}/{@link #centerZ()}）；
 * 水平相邻的轨道属于不同线路。坐标单位：x/z 为方块坐标，bottom/top 为轨道方块的 Y（单位：格）。</p>
 */
public record ElevatorLine(int x, int z, int bottom, int top, Direction facing, List<BlockPos> stops) {

    /**
     * 以 seed 为起点向上、向下扩展，扫描出整条线路及其站点。
     *
     * @param world 世界（只读查询，不做任何修改）
     * @param seed 被点击的轨道方块位置
     * @return 扫出的线路；seed 所在区块未加载或该处不是电梯轨道时返回 null
     * 为什么这样扫：轨道列是垂直连续的，所以只需从 seed 向两侧逐格匹配“同朝向轨道”，第一个不匹配处即为线路端点。
     * 站点只认根方块（3x3 门底部中心），且要求门的朝向与轨道一致、整扇门完整；
     * 门所在区块未加载时跳过该高度（既不记为站点也不报错，避免在未加载区误判线路断点）。
     */
    public static ElevatorLine scan(World world, BlockPos seed) {
        if (!world.isChunkLoaded(seed)) return null;
        BlockState state = world.getBlockState(seed);
        if (!state.isOf(Easyelevator.RAIL)) return null;
        Direction direction = state.get(ElevatorRailBlock.FACING);
        int low = seed.getY(), high = low;
        // 向下扩展：matches 同时负责“区块已加载”与“同朝向轨道”两项校验，任一不满足即停。
        while (low > world.getBottomY() && matches(world, new BlockPos(seed.getX(), low - 1, seed.getZ()), direction)) low--;
        // 向上扩展：上界用 world.getTopY() - 1，确保坐标始终在世界高度范围内。
        while (high < world.getTopY() - 1 && matches(world, new BlockPos(seed.getX(), high + 1, seed.getZ()), direction)) high++;
        List<BlockPos> stops = new ArrayList<>();
        for (int y = low; y <= high; y++) {
            BlockPos rail = new BlockPos(seed.getX(), y, seed.getZ());
            // 站点 = 该层轨道朝向前方 RAIL_DISTANCE（3）格、与轨道同 Y 的那扇门的根方块。
            BlockPos door = rail.offset(direction,LandingDoorBlock.RAIL_DISTANCE);
            if (!world.isChunkLoaded(door)) continue;
            BlockState b = world.getBlockState(door);
            // 同一高度只认“正面门”：必须是根方块、朝向与轨道一致、且整扇 3x3 门完整。
            if (LandingDoorBlock.isRoot(b) && b.get(LandingDoorBlock.FACING)==direction
                    && LandingDoorBlock.complete(world,door)) stops.add(door.toImmutable());
        }
        // 固定排序（Y 再 X 再 Z）：同一线路每次扫描得到的站点顺序必须一致，状态机行程与存档才可复现。
        stops.sort(Comparator.comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return new ElevatorLine(seed.getX(), seed.getZ(), low, high, direction, List.copyOf(stops));
    }

    /**
     * 判断 p 处是否为与 d 同朝向的电梯轨道。
     *
     * @param w 世界
     * @param p 待检查的位置
     * @param d 期望的线路朝向
     * @return true 表示该区块已加载且此处是朝向一致的电梯轨道
     * 说明：区块未加载时返回 false，扫描会在此处停下——把未加载区块当作断点而不是继续试探，
     * 避免把尚未加载的轨道误当成线路端点而截断线路。
     */
    public static boolean matches(World w, BlockPos p, Direction d) {
        if (!w.isChunkLoaded(p)) return false;
        BlockState s = w.getBlockState(p);
        return s.isOf(Easyelevator.RAIL) && s.get(ElevatorRailBlock.FACING) == d;
    }

    /** @return p 是否位于本线路列上（同 X/Z 且 Y 落在 [bottom, top] 内）。 */
    public boolean containsRail(BlockPos p) { return p.getX() == x && p.getZ() == z && p.getY() >= bottom && p.getY() <= top; }

    /** @return 轿厢中心 X（单位：格）：轨道中心 x+0.5 沿朝向前方偏移 2 格。 */
    public double centerX() { return x + .5 + facing.getOffsetX() * 2; }

    /** @return 轿厢中心 Z（单位：格）：轨道中心 z+0.5 沿朝向前方偏移 2 格。 */
    public double centerZ() { return z + .5 + facing.getOffsetZ() * 2; }

    /**
     * 找出属于本线路的轿厢。
     *
     * @param world 世界
     * @return 本线路上的轿厢列表；正常至多一个（每条线路最多一个轿厢），多出者来自异常存档
     * 说明：先用略大于井道的包围盒粗筛（X/Z 各 ±2 格，Y 覆盖 bottom-1 到 top+4，因为轿厢是 3x3x3），
     * 再按 railX/railZ 与 Y 范围精筛；Y 上下各放宽 0.01 格以容忍双精度位置误差。
     */
    public List<AbstractCabinEntity> cabins(World world) {
        return world.getEntitiesByClass(AbstractCabinEntity.class, new Box(centerX()-2, bottom-1, centerZ()-2, centerX()+2, top+4, centerZ()+2),
                c -> !c.isRemoved() && c.railX() == x && c.railZ() == z && c.getY() >= bottom - .01 && c.getY() <= top + .01);
    }
}
