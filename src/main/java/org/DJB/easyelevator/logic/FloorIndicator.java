package org.DJB.easyelevator.logic;

import java.util.List;

/**
 * 楼层显示的计算：把"轿厢当前高度 + 运行方向"换算成选站面板、门框顶部与轿厢内面板上显示的楼层号。
 *
 * <p>编号规则：站点按高度升序编号，<b>最底层 = 1 层</b>。显示值取"已经到过或经过的那一层"——
 * 上行时是已经经过的最高一层，下行时是已经经过的最低一层，因此层号只在<b>经过或到达一层</b>那一刻变化：
 * 轿厢以 0.20 格/刻运行，正好会精确落在整数楼层高度上，如果按"离哪层最近"来判定，
 * 经过每一层都会闪一下，这里刻意避免这种抖动。
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
     * @return 楼层号（1 起）；尚未经过任何站点（例如所有站点都在轿厢上方）时返回 0
     */
    public static int floorNumber(List<Integer> stationYs,double y,double previousY) {
        // 容差与到站判定同源：轿厢到站时会精确吸附到站点高度，逐刻累加也只会有微小浮点误差。
        double epsilon=ElevatorParameters.POSITION_EPSILON;
        boolean descending=y<previousY-epsilon;
        int floor=0;
        for(int i=0;i<stationYs.size();i++) {
            double stationY=stationYs.get(i);
            if(descending) {
                // 下行：显示已经过的最低一层，即第一个"仍在当前高度之上"的站点；没有则保持 0
                if(stationY>=y-epsilon) { floor=i+1; break; }
            } else {
                // 上行（含静止）：显示已经经过的最高一层，因此循环一路取到最后一个不超过当前高度的站点
                if(stationY<=y+epsilon) floor=i+1;
            }
        }
        return floor;
    }
}
