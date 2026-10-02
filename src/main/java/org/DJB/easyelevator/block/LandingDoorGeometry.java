package org.DJB.easyelevator.block;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

/**
 * 楼层电梯门的纯几何：门框与两扇可动门扇的位置全部在这里定义，方块（碰撞/轮廓）与
 * 客户端渲染器（{@link org.DJB.easyelevator.client.LandingDoorRenderer}）共用同一份数据。
 *
 * <p>为什么单独成类：这里是纯算术，不引用任何方块状态、世界或方块实体，因此可以脱离游戏直接
 * 构造盒与形状来验证（与 {@code logic/} 里的状态机同样的思路），也避免"渲染一套几何、碰撞另一套"
 * 这种最容易出现穿模与闪烁的分叉。
 *
 * <p>坐标约定：整扇门用 1/16 格为单位，
 * {@code u} 沿门宽（0..{@link #DOOR_WIDTH}，方向为 {@code FACING.rotateYClockwise()}），
 * {@code v} 沿高度（0..{@link #DOOR_HEIGHT}），门的厚度方向固定为 3/16 格并贴在朝 FACING 的一侧。
 * 根方块（底部中心 COLUMN=1、LEVEL=0）位于 u=16..32、v=0..16。
 *
 * <p>几何组成（始终只有这两种角色，因此框与扇在画面上分得开）：
 * <ul>
 *   <li>门框：左立柱 u=0..3、右立柱 u=45..48、门楣 v=45..48，永远存在；</li>
 *   <li>门扇：左扇 u=3..23.5、右扇 u=24.5..45（关门，正中留 {@link #SEAM} 宽门缝 = 1/16 格），
 *       随进度向两侧门框收拢，全开时宽度归零、完全躲到立柱后面。</li>
 * </ul>
 */
public final class LandingDoorGeometry {
    private LandingDoorGeometry() { }

    /** 整扇门的宽度与高度：3 格宽 × 3 格高，单位 1/16 格（48/16 = 3 格）。 */
    public static final double DOOR_WIDTH = 48, DOOR_HEIGHT = 48;

    /** 门框立柱宽度、门楣高度，同时也是门面厚度，单位 1/16 格（3/16 = 0.1875 格）。 */
    public static final double FRAME = 3;

    /** 门楣下沿高度，单位 1/16 格，即门洞净高 45/16 = 2.8125 格（玩家与轿厢门都有余量）。 */
    public static final double LEAF_TOP = 45;

    /** 关门时两扇门扇之间可见门缝的宽度，单位 1/16 格（1/16 = 0.0625 格 ≈ 6 厘米，接近真实电梯门缝），
     * 位于门洞正中。想让门扇完全贴合就把这里改成 0（两扇背面相对，靠背面剔除不会闪烁）。 */
    public static final double SEAM = 1;

    /** 单扇门扇的滑动行程，单位 1/16 格：门洞扣掉两侧门框与中缝后的一半，正好 20/16 = 1.25 格。 */
    public static final double LEAF_TRAVEL = (DOOR_WIDTH - 2 * FRAME - SEAM) / 2;

    /** 形状缓存的进度量化档数：1/16 步长足以让碰撞平滑跟随滑动，又把缓存限制在几百个形状内。 */
    private static final int SHAPE_STEPS = 16;

    /**
     * 体素形状缓存，下标 = ((朝向序号 × 3 + 列) × 3 + 层) × (档数 + 1) + 进度档。
     *
     * <p>形状只由（朝向、列、层、进度档）决定，与维度、世界、具体坐标都无关，因此可以全局共享。
     * 用普通数组而不是同步容器：最坏情况是同一档位被两个线程各算一次，写入的是同一个不可变形状引用，
     * 结果是幂等的，省掉锁与并发开销（碰撞查询每刻会被调用很多次）。
     */
    private static final VoxelShape[] SHAPE_CACHE =
            new VoxelShape[Direction.values().length * 3 * 3 * (SHAPE_STEPS + 1)];

    /**
     * 门扇内缘位置（整扇门坐标，单位 1/16 格）。
     *
     * <p>关门（progress = 0）时两扇各占门洞的一半，正中只留 {@link #SEAM} 宽的细门缝；
     * 开门（progress = 1）时两扇内缘收到门框立柱内侧，宽度归零、完全躲进门框后面，
     * 与轿厢门"向两侧收拢滑入门框"的动作完全一致。
     *
     * @param progress 门扇进度 0..1，超范围会被夹取
     * @param right true 取右扇左缘，false 取左扇右缘
     * @return 内缘的整扇门横坐标（1/16 格）
     */
    public static double leafEdge(float progress,boolean right) {
        double open=MathHelper.clamp(progress,0,1);
        return right?DOOR_WIDTH-FRAME-LEAF_TRAVEL*(1-open):FRAME+LEAF_TRAVEL*(1-open);
    }

    /**
     * 一扇门扇在<b>根方块</b>局部坐标系里的长方体（单位：格），供渲染使用；
     * 碰撞形状走 {@link #shape}，与它同源，因此画面与碰撞不会脱节。
     *
     * @param facing 门朝向
     * @param progress 门扇进度 0..1
     * @param right true 取右扇，false 取左扇
     * @return 门扇长方体（相对根方块原点）；进度到 1（宽度归零、已收进门框）时返回 null
     */
    public static Box leafBox(Direction facing,float progress,boolean right) {
        double inner=leafEdge(progress,right),outer=right?DOOR_WIDTH-FRAME:FRAME;
        if(Math.abs(inner-outer)<1e-6) return null;
        return doorBox(facing,Math.min(inner,outer),0,Math.max(inner,outer),LEAF_TOP);
    }

    /**
     * 整扇门坐标（1/16 格）→ <b>根方块</b>局部坐标盒（单位：格）。这里不裁剪，
     * 因此渲染整扇门时可以直接用（渲染原点就是根方块）。
     *
     * <p>门宽轴是 {@code FACING.rotateYClockwise()}：根的横向原点在门的正中，所以 u=16 对应根的 0、
     * u=0 对应根的 -1。门厚 3/16 格始终贴在朝 FACING 的一侧，因此 SOUTH / WEST 的横坐标需要镜像
     * （{@code 2 - u/16}），否则门框立柱与门扇会跑到另一侧。
     *
     * @param facing 门朝向
     * @param u1 起始横坐标（0..48，1/16 格）
     * @param v1 起始高度（0..48，1/16 格）
     * @param u2 结束横坐标
     * @param v2 结束高度
     * @return 相对根方块原点的长方体
     */
    public static Box doorBox(Direction facing,double u1,double v1,double u2,double v2) {
        double a=u1/16,b=u2/16,y1=v1/16,y2=v2/16;
        return switch(facing) {
            case NORTH -> new Box(a-1,y1,0,b-1,y2,FRAME/16);      // 门宽轴 +X，门面贴在方块北侧
            case SOUTH -> new Box(2-b,y1,1-FRAME/16,2-a,y2,1);    // 门宽轴 -X，需要镜像
            case EAST -> new Box(1-FRAME/16,y1,a-1,1,y2,b-1);     // 门宽轴 +Z，门面贴在方块东侧
            default -> new Box(0,y1,2-b,FRAME/16,y2,2-a);         // WEST：门宽轴 -Z，需要镜像
        };
    }

    /**
     * 某个门格子的体素形状：轮廓、碰撞与测试都取这一份几何。
     *
     * <p>几何 = 三块常驻门框（左立柱、右立柱、门楣）+ 两扇随进度收拢的门扇。关闭时门扇填满门洞
     * （只留中缝），开启时收到两侧宽度归零，中间进度得到真正的"半开"形状，因此不会再出现
     * "瞬间变成一堵墙"。渲染用的门扇几何由 {@link #leafBox} 提供，与本方法同源。
     *
     * <p>结果按（朝向、列、层、进度档）缓存：碰撞查询每刻被调用很多次，而跨格裁剪与 union 有开销，
     * 缓存后稳态下只是一次数组读取。
     *
     * @param facing 门朝向
     * @param column 列 0..2（1 为根所在中列）
     * @param level 层 0..2（0 为底行）
     * @param progress 门扇进度 0..1
     * @return 该格子的体素形状；门洞位置为空形状
     */
    public static VoxelShape shape(Direction facing,int column,int level,float progress) {
        int step=MathHelper.clamp(Math.round(MathHelper.clamp(progress,0,1)*SHAPE_STEPS),0,SHAPE_STEPS);
        int index=((facing.ordinal()*3+column)*3+level)*(SHAPE_STEPS+1)+step;
        VoxelShape cached=SHAPE_CACHE[index];
        if(cached!=null) return cached;
        // 用量化后的进度建形状，保证"同一缓存档位 ⇒ 同一几何"
        float q=step/(float)SHAPE_STEPS;
        double left=leafEdge(q,false),right=leafEdge(q,true);
        VoxelShape shape=VoxelShapes.empty();
        shape=add(shape,partBox(facing,column,level,0,0,FRAME,DOOR_HEIGHT));                    // 左门框立柱
        shape=add(shape,partBox(facing,column,level,DOOR_WIDTH-FRAME,0,DOOR_WIDTH,DOOR_HEIGHT)); // 右门框立柱
        shape=add(shape,partBox(facing,column,level,0,LEAF_TOP,DOOR_WIDTH,DOOR_HEIGHT));         // 门楣
        shape=add(shape,partBox(facing,column,level,FRAME,0,left,LEAF_TOP));                     // 左门扇
        shape=add(shape,partBox(facing,column,level,right,0,DOOR_WIDTH-FRAME,LEAF_TOP));         // 右门扇
        SHAPE_CACHE[index]=shape;
        return shape;
    }

    /** 把一个盒并进形状；盒为 null（该格不含这份几何）时原样返回。
     * @param shape 已累积的形状
     * @param box 待并入的盒，可为 null
     * @return 并入后的形状 */
    private static VoxelShape add(VoxelShape shape,Box box) {
        return box==null?shape:VoxelShapes.union(shape,VoxelShapes.cuboid(box));
    }

    /**
     * 某个门格子的形状盒：先按整扇门坐标求出根方块局部盒，再平移进该部件的局部坐标系并夹到本格
     * 0..1（即 0..16 个 1/16 单位），没有交集时返回 null。
     *
     * <p>为什么要夹：形状必须是"本格内部"的几何。门扇会跨格（例如左扇从第 0 列伸进第 1 列），
     * 第 0 列的碰撞只应包含落在第 0 列里的那部分。
     *
     * @param facing 门朝向
     * @param column 列 0..2（1 为根所在中列）
     * @param level 层 0..2（0 为底行）
     * @param u1 起始横坐标（整扇门坐标，1/16 格）
     * @param v1 起始高度（整扇门坐标，1/16 格）
     * @param u2 结束横坐标
     * @param v2 结束高度
     * @return 该格内的局部盒；无交集时为 null
     */
    private static Box partBox(Direction facing,int column,int level,double u1,double v1,double u2,double v2) {
        Box box=doorBox(facing,u1,v1,u2,v2);
        // 该部件相对根方块的偏移（格）：沿门宽轴移动 column-1 格，向上移动 level 格
        int lateral=column-1;double dx=0,dz=0,dy=level;
        switch(facing) {
            case NORTH -> dx=lateral;
            case SOUTH -> dx=-lateral;
            case EAST -> dz=lateral;
            default -> dz=-lateral;
        }
        double x1=Math.max(0,box.minX-dx),x2=Math.min(1,box.maxX-dx);
        double y1=Math.max(0,box.minY-dy),y2=Math.min(1,box.maxY-dy);
        double z1=Math.max(0,box.minZ-dz),z2=Math.min(1,box.maxZ-dz);
        return x2<=x1||y2<=y1||z2<=z1?null:new Box(x1,y1,z1,x2,y2,z2);
    }
}
