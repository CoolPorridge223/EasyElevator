package org.DJB.easyelevator.logic;

import java.util.List;

/**
 * 楼层显示的计算：把"轿厢当前高度 + 运行方向 + 本线路的基准层"换算成选站面板、门框顶部与轿厢内面板上显示的楼层号。
 *
 * <p>编号规则：站点按高度升序排列，其中一站点可以被指定为<b>基准层</b>（现实里的 1 层 / 门厅层）：
 * <ul>
 *   <li>基准层本身 = {@code 1}；</li>
 *   <li>比它高的依次 {@code 2, 3, 4 …}；</li>
 *   <li>比它低的依次 {@code B1, B2, B3 …}（即"地下 1 层、2 层…"，内部用负数 {@code -1, -2, -3 …} 表示）。</li>
 * </ul>
 * 未指定基准层时以最低站点为基准，编号与旧版本完全一致（最低 = 1 层）。
 *
 * <p>显示值取"已经到过或经过的那一层"——上行时是已经经过的最高一层，下行时是已经经过的最低一层，
 * 因此层号只在<b>经过或到达一层</b>那一刻变化：轿厢在巡航段以 0.20 或 0.50 格/刻运行、高度会精确经过
 * 整数楼层，如果按"离哪层最近"来判定，经过每一层都会闪一下，这里刻意避免这种抖动。
 *
 * <p>纯算术，不引用任何 Minecraft 类，因此可以脱离游戏直接跑测试（见 {@code FloorIndicatorTest}）。
 */
public final class FloorIndicator {
    /** 工具类，禁止实例化。 */
    private FloorIndicator() { }

    /**
     * 计算当前楼层号。
     *
     * @param stationYs 站点高度（方块 Y，单位格），必须按升序排列
     * @param y 轿厢当前高度（格）
     * @param previousY 上一刻高度（格），仅用于判断上行还是下行
     * @param baseY 本线路基准层的高度（格）；不在 {@code stationYs} 里（例如基准门被拆）时按最低站点处理
     * @return 楼层号：基准层 1、其上 2,3…、其下 -1(B1),-2(B2)…；尚未经过任何站点时返回 0
     */
    public static int floorNumber(List<Integer> stationYs,double y,double previousY,int baseY) {
        int index=reachedIndex(stationYs,y,previousY);
        return index<0 ? 0 : value(index,baseIndex(stationYs,baseY));
    }

    /**
     * 以最低站点为基准的楼层号，等价于旧版本行为（最低层 = 1）。
     *
     * @param stationYs 站点高度（方块 Y，单位格），必须按升序排列
     * @param y 轿厢当前高度（格）
     * @param previousY 上一刻高度（格）
     * @return 楼层号（1 起）；尚未经过任何站点时返回 0
     */
    public static int floorNumber(List<Integer> stationYs,double y,double previousY) {
        return floorNumber(stationYs,y,previousY,stationYs.isEmpty()?0:stationYs.get(0));
    }

    /**
     * 求"轿厢当前应该显示哪一站"的下标（0 基）。
     *
     * @param stationYs 站点高度（升序）
     * @param y 当前高度（格）
     * @param previousY 上一刻高度（格）
     * @return 已经到过或经过的站点下标；尚未经过任何站点（例如所有站点都在上方）时返回 -1
     *
     * <p>容差与到站判定同源（{@link ElevatorParameters#POSITION_EPSILON}）：轿厢到站时会精确吸附到站点高度，
     * 逐刻累加也只会有微小浮点误差。下行取"第一个仍在当前高度之上的站点"= 已经过的最低一层；
     * 上行（含静止）取"最后一个不超过当前高度的站点"= 已经过的最高一层。
     */
    private static int reachedIndex(List<Integer> stationYs,double y,double previousY) {
        double epsilon=ElevatorParameters.POSITION_EPSILON;
        boolean descending=y<previousY-epsilon;
        int index=-1;
        for(int i=0;i<stationYs.size();i++) {
            double stationY=stationYs.get(i);
            if(descending) {
                if(stationY>=y-epsilon) { index=i; break; }
            } else if(stationY<=y+epsilon) index=i;
        }
        return index;
    }

    /**
     * 求基准层在站点列表里的下标（0 基）。
     *
     * @param stationYs 站点高度（升序）
     * @param baseY 基准层高度（格）
     * @return 与 {@code baseY} 相等的站点下标；找不到（基准门被拆、列表为空，或未指定基准）时返回 0，即最低站点
     */
    public static int baseIndex(List<Integer> stationYs,int baseY) {
        for(int i=0;i<stationYs.size();i++) if(stationYs.get(i)==baseY) return i;
        return 0;
    }

    /**
     * 把站点下标换算成楼层号（基准层 = 1，其上 2,3…，其下 -1,-2…）。
     *
     * @param index 站点下标（0 基）
     * @param baseIndex 基准层下标（0 基）
     * @return 楼层号：{@code index - baseIndex + 1}（index ≥ baseIndex），否则 {@code index - baseIndex}（负数）
     */
    public static int value(int index,int baseIndex) {
        int delta=index-baseIndex;
        return delta>=0?delta+1:delta;
    }

    /**
     * 楼层号 → 显示文本，供选站面板、门框顶部与轿厢内面板共用（服务端算号、客户端算文本，口径必然一致）。
     *
     * @param floor 楼层号：正数 = 地面以上，负数 = 地下（B1 是 -1），0 = 尚未经过任何站点
     * @return {@code "3"}、{@code "B1"}，或占位符 {@code "--"}
     */
    public static String format(int floor) {
        if(floor==0) return "--";
        return floor>0?Integer.toString(floor):"B"+(-floor);
    }

    /**
     * 直接由站点下标求显示文本：等价于 {@code format(value(index, baseIndex))}，供选站面板铺按钮时使用。
     *
     * @param index 站点下标（0 基）
     * @param baseIndex 基准层下标（0 基）
     * @return 该站点按钮上应显示的文本，例如 {@code "1"}、{@code "B2"}
     */
    public static String label(int index,int baseIndex) {
        return format(value(index,baseIndex));
    }
}
