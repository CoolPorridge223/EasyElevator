package org.DJB.easyelevator.logic;

import java.util.List;

/**
 * Run with tools/test-logic.ps1. No Minecraft bootstrap or external test framework needed.
 *
 * <p>楼层显示（{@link FloorIndicator}）的回归测试：最底层 = 1 层；层号只在经过或到达一层时变化，
 * 上行/下行分别显示"已经过"的那一层，且经过楼层时不会来回抖动。
 */
public final class FloorIndicatorTest {
    /** 断言：失败即抛 AssertionError，由退出码/异常判定为失败。 */
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }

    /**
     * 测试入口：验证上行、下行、到站、边界与抖动行为，全部通过后打印 PASS 行。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        // 三个站点：64 / 72 / 80（最底层 = 1 层）。
        List<Integer> ys=List.of(64,72,80);

        // 停在最底层：显示 1 层（静止时按上行规则取"已经过"的那一层）。
        check(FloorIndicator.floorNumber(ys,64,64)==1,"parked at the lowest floor shows floor 1");
        // 上行途中：离开 64 之后仍显示 1 层，直到经过 72 那一刻才变成 2 层。
        check(FloorIndicator.floorNumber(ys,64.2,64)==1,"just left floor 1 still shows 1");
        check(FloorIndicator.floorNumber(ys,71.8,71.6)==1,"below floor 2 still shows 1");
        check(FloorIndicator.floorNumber(ys,72,71.8)==2,"passing floor 2 switches to 2");
        check(FloorIndicator.floorNumber(ys,72.2,72)==2,"just above floor 2 stays 2");
        check(FloorIndicator.floorNumber(ys,80,79.8)==3,"reaching the top floor shows 3");

        // 下行途中：离开 80 之后仍显示 3 层，直到经过 72 那一刻才变成 2 层，再经过 64 变成 1 层。
        check(FloorIndicator.floorNumber(ys,79.8,80)==3,"just left floor 3 still shows 3");
        check(FloorIndicator.floorNumber(ys,72.2,72.4)==3,"above floor 2 while descending shows 3");
        check(FloorIndicator.floorNumber(ys,72,72.2)==2,"passing floor 2 downwards switches to 2");
        check(FloorIndicator.floorNumber(ys,64,64.2)==1,"reaching the lowest floor shows 1");
        // 低于所有站点（例如井道延伸到最低层门之下）：静止或上行时"还没有经过任何一层"→ 0（面板显示 --）；
        // 下行时因为是从上面下来的，已经过的最低一层就是 1 层。
        check(FloorIndicator.floorNumber(ys,63,63)==0,"parked below every station has no floor number");
        check(FloorIndicator.floorNumber(ys,63,63.2)==1,"descending below the lowest station shows floor 1");
        // 高于所有站点：静止或上行时保持最高一层；下行时更高处还没有"已经过"的楼层 → 0。
        check(FloorIndicator.floorNumber(ys,81,81)==3,"parked above every station stays at the top floor");
        check(FloorIndicator.floorNumber(ys,81,81.2)==0,"descending above every station has no floor number yet");
        // 只有一个站点时也能正常工作。
        check(FloorIndicator.floorNumber(List.of(70),70,70)==1,"single station shows floor 1");

        // 抖动检查：逐刻模拟一次 64 -> 80 的上行（0.20 格/刻），层号必须单调不减且只在经过楼层时跳变
        // （起点已经算出 1 层，因此这里只统计之后的跳变次数：1 -> 2 -> 3 共 2 次）。
        int previous=FloorIndicator.floorNumber(ys,64,64), changes=0;
        double y=64, last=64;
        for(int tick=0;tick<=80;tick++) {
            int floor=FloorIndicator.floorNumber(ys,y,last);
            check(floor>=previous,"floor number never goes backwards while ascending");
            if(floor!=previous) { changes++; previous=floor; }
            last=y; y=Math.min(80,y+ElevatorParameters.SPEED);
        }
        check(changes==2,"ascending 64 -> 80 changes the floor exactly twice (1 -> 2 -> 3)");
        // 下行同样的检查：单调不增，且只变化两次（3 -> 2 -> 1）。
        previous=FloorIndicator.floorNumber(ys,80,80); changes=0; y=80; last=80;
        for(int tick=0;tick<=80;tick++) {
            int floor=FloorIndicator.floorNumber(ys,y,last);
            check(floor<=previous,"floor number never goes upwards while descending");
            if(floor!=previous) { changes++; previous=floor; }
            last=y; y=Math.max(64,y-ElevatorParameters.SPEED);
        }
        check(changes==2,"descending 80 -> 64 changes the floor exactly twice (3 -> 2 -> 1)");

        // ---- 基准层（潜行右键楼层门设置的"1 层"）----
        // 以中间那层（72）为基准：64 显示 B1、72 显示 1、80 显示 2；没有经过任何楼层时仍然是 0。
        check(FloorIndicator.baseIndex(ys,72)==1,"base floor index is looked up by height");
        check(FloorIndicator.baseIndex(ys,999)==0,"an unknown base height falls back to the lowest station");
        check(FloorIndicator.label(0,1).equals("B1")&&FloorIndicator.label(1,1).equals("1")&&FloorIndicator.label(2,1).equals("2"),
                "labels around the base floor are B1 / 1 / 2");
        check(FloorIndicator.floorNumber(ys,64,64,72)==-1,"below the base floor is basement 1");
        check(FloorIndicator.floorNumber(ys,72,71.8,72)==1,"the base floor itself is floor 1");
        check(FloorIndicator.floorNumber(ys,80,79.8,72)==2,"one floor above the base is floor 2");
        check(FloorIndicator.floorNumber(ys,63,63,72)==0,"below every station there is still no floor number");
        check(FloorIndicator.format(-1).equals("B1")&&FloorIndicator.format(-2).equals("B2")&&FloorIndicator.format(3).equals("3"),
                "format renders basements as B1 / B2 and floors as plain numbers");
        check(FloorIndicator.format(0).equals("--"),"an unknown floor shows the placeholder");
        // 基准层设在最底层（默认口径）：编号与旧版本完全一致，旧存档不会因为这次改动而变号。
        for(int stationY : new int[]{64,72,80}) check(FloorIndicator.floorNumber(ys,stationY,stationY,64)==FloorIndicator.floorNumber(ys,stationY,stationY),
                "a base floor at the lowest station keeps the historical numbering");

        System.out.println("PASS: floor indicator (lowest station = floor 1, updates only when passing or reaching a floor,"
                + " monotonic in both directions, no flicker at intermediate heights, configurable base floor with B1/B2 basements).");
    }
}
