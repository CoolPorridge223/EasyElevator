package org.DJB.easyelevator.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.ElevatorLine;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.logic.ElevatorStatus;

/**
 * 楼层门根方块的方块实体：只为"门扇滑动"提供连续、可插值的进度样本。
 *
 * <p>为什么需要它：{@link LandingDoorBlock#OPEN} 是离散的布尔方块状态，只能表达"关/开"，
 * 而方块状态驱动的方块模型在渲染时无法插值，于是门面只能在两套模型之间瞬间切换（原来看起来
 * 就是"一下子变成一堵墙"）。连续进度放在方块实体里，再交给
 * {@link org.DJB.easyelevator.client.LandingDoorRenderer} 逐帧插值绘制，才可能做到与轿厢门
 * 同样的平滑滑动。
 *
 * <p>进度来源：直接取在站轿厢的门进度（{@link LandingDoorBlock#leafProgress}），
 * 所以楼层门的两扇门扇与轿厢自带的两扇门扇同刻同值、同速同向，开关门完全同步；
 * 没有轿厢精确停靠在本层时恒为 0（门扇全关，门洞被完全封住）。
 *
 * <p>为什么不发同步包：进度是"已同步的轿厢门进度 + 已同步的 OPEN 方块状态"的纯函数，
 * 客户端与服务端各自就地算出的结果必然一致，因此不需要任何自定义包，也不写世界状态。
 *
 * <p>采样按刻对齐：{@link #sample()} 用世界刻号当闸门，一刻只推进一次样本。同一刻内无论
 * 碰撞查询被调用多少次、面板是否刷新、渲染跑多少帧，都共享同一对样本（上一刻/当前刻），
 * 渲染器再用 tickDelta 在两者之间线性插值——这与轿厢渲染器读 DataTracker 的上一刻/当前值
 * 完全同构，因此两层门在画面上不会互相错位。
 */
public final class LandingDoorBlockEntity extends BlockEntity {
    /** 当前刻的门扇进度：0 = 完全关闭（门扇封住门洞），1 = 完全打开（门扇收进两侧门框）。 */
    private float progress;
    /** 上一刻的门扇进度，供渲染插值使用。 */
    private float previousProgress;
    /** 上一次采样时的世界刻号；{@code Long.MIN_VALUE} 表示这一对样本还没被推进过。 */
    private long sampledTick = Long.MIN_VALUE;

    /**
     * 本门是否被指定为这条线路的<b>基准层</b>（潜行右键门上任意部件设置，见 {@code LandingDoorBlock#onUse}）。
     *
     * <p>基准层就是"1 层"：它上方的站点依次显示 2、3…，下方的依次显示 B1、B2…（见 {@code logic/FloorIndicator}）。
     * 一条线路最多一扇门带这个标记；标记写在方块实体里，因此随区块一起存档、拆掉门就自然失效
     * （整条线路回到默认编号：最低站点 = 1 层）。</p>
     */
    private boolean baseFloor;

    /** 本线路轿厢的当前楼层号（1 起，0 = 没有轿厢）：门框顶部的红色层号显示用，同样每刻只查一次。 */
    private int cabinFloor;

    /** 本线路轿厢的运行状态（上行/下行/停靠）：与 {@link #cabinFloor} 一起构成门框顶部的显示内容。 */
    private ElevatorStatus cabinStatus = ElevatorStatus.IDLE;

    /**
     * @param pos 根方块坐标（整扇门只有这一个部件持有方块实体）
     * @param state 根方块状态
     */
    public LandingDoorBlockEntity(BlockPos pos, BlockState state) { super(Easyelevator.LANDING_DOOR_BE,pos,state); }

    /** 不做插值的门扇进度，用于碰撞形状与联锁判定。
     * @return 当前刻进度 0..1（0 全关、1 全开） */
    public float openProgress() { sample(); return progress; }

    /** 渲染用的插值进度：与轿厢门用同一个 tickDelta 在上一刻/当前刻样本之间插值。
     * @param tickDelta 渲染插值系数，0..1，由渲染管线给出
     * @return 插值后的进度 0..1 */
    public float openProgress(float tickDelta) {
        sample();
        return MathHelper.lerp(MathHelper.clamp(tickDelta,0,1),previousProgress,progress);
    }

    /**
     * 按世界刻推进一次样本；同一刻重复调用直接返回，保证插值窗口恰好是一刻。
     *
     * <p>用 {@code world.getBlockState(pos)} 而不是 {@link #getCachedState()}：方块状态随时
     * 可能被 {@link LandingDoorBlock#refresh} 改写，这里每刻只读一次，取最新值最稳妥。
     *
     * <p>副作用：更新 progress/previousProgress/cabinFloor/cabinStatus/sampledTick；只读世界，不写世界。
     */
    private void sample() {
        if (world==null) return; // 还没有加入世界（构造或加载过程中）：保持 0，避免空指针
        long now=world.getTime();
        if (now==sampledTick) return;
        BlockState state=world.getBlockState(pos);
        previousProgress=progress;
        // OPEN 为假说明联锁不成立（没有轿厢精确到站），此时门扇必定全关，不必再去查世界。
        progress=state.isOf(Easyelevator.LANDING_DOOR) && state.get(LandingDoorBlock.OPEN)
                ? LandingDoorBlock.leafProgress(world,pos) : 0f;
        // 门框顶部的显示内容（楼层号 + 运行状态）同样每刻只查一次，渲染器直接读缓存
        AbstractCabinEntity cabin=state.isOf(Easyelevator.LANDING_DOOR) ? cabinOf(world,state) : null;
        cabinFloor=cabin==null ? 0 : cabin.floorNumber();
        cabinStatus=cabin==null ? ElevatorStatus.IDLE : cabin.status();
        sampledTick=now;
    }

    /**
     * 本线路上的轿厢（没有则返回 null），供门框顶部的"运行状态 + 楼层号"显示使用。
     *
     * <p>为什么按车体世界坐标匹配、而不是 {@code railX()} / {@code railZ()}：那两个字段没有进
     * DataTracker，客户端上恒为 0；用它匹配会让客户端永远找不到轿厢（"轿厢门开了、楼层门不动"
     * 就是同一个原因）。这里用线路中心 + 高度区间定位车体，服务端与客户端都能算出一致结果。
     *
     * <p>只读查询：每刻由 {@link #sample()} 调用一次并缓存，渲染器直接读 {@link #cabinFloor()} 与
     * {@link #cabinStatus()}。
     *
     * @param world 世界
     * @param state 根方块状态（用其 FACING 反推轨道位置）
     * @return 本线路的轿厢；线路无效或没有轿厢时为 null
     */
    private AbstractCabinEntity cabinOf(World world,BlockState state) {
        ElevatorLine line=ElevatorLine.scan(world,LandingDoorBlock.railPos(state,pos));
        if(line==null) return null;
        double x=line.centerX(), z=line.centerZ();
        AbstractCabinEntity found=null;
        for(AbstractCabinEntity cabin:world.getEntitiesByClass(AbstractCabinEntity.class,
                new Box(x-1.6,line.bottom()-1,z-1.6,x+1.6,line.top()+4,z+1.6),
                c->!c.isRemoved() && Math.abs(c.getX()-x)<=ElevatorParameters.SYNC_POSITION_EPSILON
                        && Math.abs(c.getZ()-z)<=ElevatorParameters.SYNC_POSITION_EPSILON)) {
            // 一条线路上正常情况下只有一辆轿厢；万一世界里存在多辆（测试世界很常见），
            // 固定取实体 id 最小的那辆，保证门框显示稳定，不会在两辆车之间来回跳。
            if(found==null || cabin.getId()<found.getId()) found=cabin;
        }
        return found;
    }

    /**
     * @return 本线路轿厢的当前楼层号（1 起；0 = 没有轿厢），渲染门框顶部显示时使用；每刻更新一次
     */
    public int cabinFloor() { sample(); return cabinFloor; }

    /**
     * @return 本线路轿厢的运行状态（上行/下行/停靠），渲染门框顶部显示时使用；每刻更新一次
     */
    public ElevatorStatus cabinStatus() { sample(); return cabinStatus; }

    /**
     * @return 本门是否为这条线路的基准层（1 层）；编号计算全在服务端完成，因此这个值只在服务端有意义
     */
    public boolean baseFloor() { return baseFloor; }

    /**
     * 设置 / 清除基准层标记。
     *
     * @param value true = 本门成为整条线路的 1 层
     * 副作用：改动方块实体 NBT 并 markDirty()，随区块保存；不改方块状态、不发包。
     */
    public void setBaseFloor(boolean value) {
        if (baseFloor == value) return;
        baseFloor = value;
        markDirty();
    }

    /**
     * 把基准层标记写进方块实体 NBT（随区块存档）。
     *
     * @param nbt 目标 NBT
     * @param registries 注册表查询（本实体没有需要迁移的字段，仅透传给父类）
     */
    @Override protected void writeNbt(NbtCompound nbt,RegistryWrapper.WrapperLookup registries) {
        super.writeNbt(nbt,registries);
        if (baseFloor) nbt.putBoolean("BaseFloor",true); // 只写 true：默认门不必多一个 false 字段
    }

    /**
     * 从方块实体 NBT 读回基准层标记。
     *
     * @param nbt 存档 NBT
     * @param registries 注册表查询（透传父类）
     */
    @Override protected void readNbt(NbtCompound nbt,RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt,registries);
        baseFloor=nbt.getBoolean("BaseFloor");
    }
}
