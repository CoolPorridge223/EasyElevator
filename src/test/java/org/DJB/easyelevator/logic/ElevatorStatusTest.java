package org.DJB.easyelevator.logic;

/**
 * Run with tools/test-logic.ps1. No Minecraft bootstrap or external test framework needed.
 *
 * <p>运行状态显示（{@link ElevatorStatus}）的回归测试：只有"运行中且有目的站"才会上行/下行；
 * 开门、开门中、关门中、暂停以及关着门停在某层等待呼叫都算"停靠"；客户端位置插值略微滞后时
 * 不能把"刚到站"误判成还在上行/下行。
 */
public final class ElevatorStatusTest {
    /** 断言：失败即抛 AssertionError，由退出码/异常判定为失败。 */
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }

    /**
     * 测试入口：覆盖方向判定、各相位下的停靠判定、到站容差与翻译键后缀，全部通过后打印 PASS 行。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        var moving=ElevatorController.Phase.MOVING;
        // 运行中：按目的站在当前高度之上还是之下来判定
        check(ElevatorStatus.of(moving,72,64)==ElevatorStatus.UP,"target above means going up");
        check(ElevatorStatus.of(moving,64,72)==ElevatorStatus.DOWN,"target below means going down");
        // 目的站就是当前高度：已经到站，接下来是开门，算停靠
        check(ElevatorStatus.of(moving,64,64)==ElevatorStatus.IDLE,"target at the current height is parked");
        // 运行中却没有目的站 = 关着门停在某层等待呼叫，同样是停靠
        check(ElevatorStatus.of(moving,Integer.MIN_VALUE,64)==ElevatorStatus.IDLE,"moving without a target is parked");
        // 其余相位一律停靠：即使在等下一站（TARGET_Y 有值），门开着/正在开关/暂停都显示停靠
        for(var phase:new ElevatorController.Phase[]{ElevatorController.Phase.OPEN,ElevatorController.Phase.OPENING,
                ElevatorController.Phase.CLOSING,ElevatorController.Phase.BLOCKED})
            check(ElevatorStatus.of(phase,72,64)==ElevatorStatus.IDLE,"phase "+phase+" reports parked");
        // 客户端位置是插值出来的：差值小于 SYNC_POSITION_EPSILON 时应当算已到站，而不是继续显示方向
        check(ElevatorStatus.of(moving,72,72-ElevatorParameters.SYNC_POSITION_EPSILON/2)==ElevatorStatus.IDLE,
                "almost arrived counts as parked");
        check(ElevatorStatus.of(moving,72,72-.1)==ElevatorStatus.UP,"a visible distance still counts as going up");
        // 翻译键后缀必须与语言文件里的 status.easyelevator.* 一致
        check(ElevatorStatus.UP.key().equals("up")&&ElevatorStatus.DOWN.key().equals("down")
                &&ElevatorStatus.IDLE.key().equals("idle"),"translation key suffixes");

        System.out.println("PASS: elevator status (up/down/parked derived from synced phase + target, parked for every"
                + " non-moving phase, arrival tolerance against client interpolation lag).");
    }
}
