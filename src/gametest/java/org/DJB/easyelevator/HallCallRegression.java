package org.DJB.easyelevator;

import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.logic.ElevatorController.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** 纯控制器回归；GameTest 调用同一套断言，也可以用 JDK 21 独立执行 main。 */
public final class HallCallRegression {
    private static Stop floor(int floor) { return new Stop(floor, floor * 10); }
    private static HallCall call(int floor, boolean up) { return new HallCall(floor, floor * 10, up); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Ride implements ElevatorController.Environment {
        ElevatorController controller = new ElevatorController();
        double y;
        boolean blocked, overloaded, resume = true;
        final List<Integer> arrivals = new ArrayList<>();

        Ride(int start) {
            y = start * 10;
            controller.restore(Phase.MOVING, 0, null, List.of(), List.of(), Travel.NONE);
        }
        public boolean valid(Stop stop) { return true; }
        public boolean canMove(double from, double to) { return resume; }
        public boolean doorwayBlocked() { return blocked; }
        public boolean canResume() { return resume; }
        public boolean outOfPassengerNumLimit() { return overloaded; }
        public void arrived(Stop stop) { arrivals.add(stop.y() / 10); }
        void tick() {
            double previous = y;
            y = controller.tick(y, this);
            check(y == previous || controller.door() == 0, "movement requires closed doors");
        }
        void ticks(int count) { for (int i = 0; i < count; i++) tick(); }
        void until(BooleanSupplier done, String message) {
            for (int i = 0; i < 4000 && !done.getAsBoolean(); i++) tick();
            check(done.getAsBoolean(), message + ": y=" + y + " phase=" + controller.phase() + " arrivals=" + arrivals);
        }
        void hall(int at, boolean up) { check(controller.callHall(call(at, up), y), "hall accepted"); }
        void select(int at) { check(controller.request(floor(at), y), "car request accepted"); }
        void reload(boolean legacy) {
            var old = controller;
            controller = new ElevatorController();
            controller.restore(old.phase(), old.door(), old.target(), old.pending(), old.hallCalls(), old.travel(),
                    legacy ? null : old.stopService());
        }
    }

    private static Ride doubleCall(int start, boolean firstUp, int delay) {
        Ride ride = new Ride(start);
        ride.hall(2, firstUp);
        ride.ticks(delay);
        ride.hall(2, !firstUp);
        ride.until(() -> !ride.arrivals.isEmpty(), "first arrival");
        check(ride.arrivals.equals(List.of(2)), "first stop must be 2");
        check(ride.controller.hallCalls().size() == 2, "both lights remain until departure direction is known");
        return ride;
    }

    private static void finish(Ride ride, int destination) {
        boolean residualUp = destination == 1;
        boolean visitedDestination = ride.arrivals.contains(destination);
        for (int i = 0; i < 4000 && ride.arrivals.size() < 3; i++) {
            ride.tick();
            visitedDestination |= ride.arrivals.contains(destination);
            if (ride.arrivals.size() < 3) {
                check(ride.controller.hallCalls().contains(call(2, residualUp)), "residual light must survive until return");
            }
        }
        check(visitedDestination, "destination visited before return");
        check(ride.arrivals.equals(List.of(2, destination, 2)), "exact route: " + ride.arrivals);
        check(ride.controller.hallCalls().isEmpty(), "return extinguishes residual light");
        ride.ticks(300);
        check(ride.arrivals.size() == 3 && !ride.controller.hasRequests(), "no duplicate stops or stuck requests");
    }

    /** 每一种用户场景覆盖登记时序、门阶段、手动关门及保存时机，共 36 个组合。 */
    public static void matrixCase(int start, boolean firstUp, int destination) {
        for (int delay : new int[]{0, 1, 10}) {
            for (int closeMode = 0; closeMode < 3; closeMode++) {
                for (int reloadStage = 0; reloadStage < 4; reloadStage++) {
                    Ride ride = doubleCall(start, firstUp, delay);
                    if (reloadStage == 1) ride.reload(false);
                    if (closeMode == 2) ride.until(() -> ride.controller.phase() == Phase.OPEN, "fully open");
                    ride.select(destination);
                    if (closeMode != 0) check(ride.controller.forceClose(), "manual close accepted");
                    ride.until(() -> ride.controller.hallCalls().size() == 1, "one direction claimed");
                    check(ride.y == 20, "claim while still on floor 2");
                    check(ride.controller.hallCalls().contains(call(2, destination == 1)), "correct direction retained");
                    if (reloadStage == 2) ride.reload(false);
                    if (reloadStage == 3) {
                        ride.until(() -> Math.abs(ride.y - 20) > .5, "departed");
                        ride.reload(false);
                    }
                    finish(ride, destination);
                }
            }
        }
    }

    public static void interruptedDeparture() {
        for (int destination : new int[]{1, 3}) {
            for (int interruption = 0; interruption < 3; interruption++) {
                Ride ride = doubleCall(3, true, 1);
                ride.select(destination);
                ride.until(() -> ride.controller.phase() == Phase.CLOSING, "closing");
                ride.ticks(2);
                if (interruption == 0) ride.blocked = true;
                if (interruption == 1) ride.overloaded = true;
                if (interruption == 2) check(ride.controller.forceOpen(), "manual reopen");
                ride.until(() -> ride.controller.phase() == Phase.OPEN || ride.controller.phase() == Phase.OVERLOAD, "reopened");
                ride.reload(false);
                ride.ticks(5);
                check(ride.y == 20 && ride.controller.hallCalls().equals(List.of(call(2, destination == 1))), "reopen preserves other light");
                ride.blocked = false;
                ride.overloaded = false;
                finish(ride, destination);
            }
        }
        Ride ride = doubleCall(3, false, 1);
        ride.select(1);
        ride.until(() -> ride.controller.phase() == Phase.CLOSING, "closing before new opposite car request");
        ride.controller.forceOpen();
        ride.select(3);
        ride.until(() -> ride.arrivals.size() == 2, "committed destination");
        check(ride.arrivals.equals(List.of(2, 1)), "reopen must preserve departure commitment");
    }

    public static void openDoorCallsAndIdle() {
        Ride ride = new Ride(3);
        ride.hall(2, false);
        ride.until(() -> ride.arrivals.size() == 1, "single call arrival");
        check(ride.controller.hallCalls().isEmpty(), "single call still clears on arrival");
        ride.hall(2, false);
        check(ride.controller.hallCalls().isEmpty(), "served-direction duplicate only extends dwell");
        ride.hall(2, true);
        ride.hall(2, true);
        check(ride.controller.hallCalls().equals(List.of(call(2, true))), "opposite call registered once while opening");
        ride.select(1);
        finish(ride, 1);

        for (int start : new int[]{1, 3}) {
            Ride idle = doubleCall(start, true, 0);
            idle.until(() -> idle.controller.hallCalls().size() == 1, "idle first direction");
            idle.ticks(ElevatorController.DWELL_TICKS - 1);
            check(idle.controller.hallCalls().size() == 1, "full dwell before idle reversal");
            idle.tick();
            check(idle.controller.hallCalls().isEmpty() && idle.y == 20, "idle reversal without empty travel");
            idle.ticks(300);
            check(idle.arrivals.equals(List.of(2)), "idle service must not manufacture an extra arrival");
            idle.hall(2, true);
            idle.until(() -> idle.arrivals.size() == 2, "new same-floor call after idle door closure");
            check(idle.controller.hallCalls().isEmpty(), "fresh idle call must not be hidden by an old service session");
        }
    }

    public static void existingDispatchAndLegacyRestore() {
        Ride terminal = new Ride(1);
        terminal.select(3);
        terminal.hall(3, false);
        terminal.until(() -> terminal.arrivals.size() == 1, "car arrival at reversal floor");
        check(terminal.controller.hallCalls().contains(call(3, false)), "opposite terminal call waits for reversal");
        terminal.select(1);
        terminal.until(() -> terminal.controller.phase() == Phase.CLOSING, "terminal departure");
        check(terminal.controller.hallCalls().isEmpty(), "single terminal call served when departing in its direction");
        terminal.until(() -> terminal.arrivals.size() == 2, "terminal trip completed");
        terminal.ticks(300);
        check(terminal.arrivals.equals(List.of(3, 1)), "single terminal call does not manufacture a return trip");

        Ride ride = doubleCall(3, true, 1);
        ride.hall(1, true);
        ride.select(3);
        ride.until(() -> ride.arrivals.size() == 2, "existing lower request first");
        check(ride.arrivals.equals(List.of(2, 1)), "preserve existing direction priority");
        check(ride.controller.hallCalls().contains(call(2, true)), "up request pending until return");
        ride.until(() -> ride.arrivals.size() == 4, "finish sweep");
        check(ride.arrivals.equals(List.of(2, 1, 2, 3)), "same-direction pickup on return");

        for (boolean mirror : new boolean[]{false, true}) {
            Ride dispatch = new Ride(mirror ? 1 : 5);
            int end = mirror ? 5 : 1, middle = mirror ? 4 : 2;
            dispatch.hall(end, !mirror);
            dispatch.ticks(2);
            dispatch.hall(middle, !mirror);
            dispatch.until(() -> dispatch.arrivals.size() == 2, "oldest reverse pickup");
            check(dispatch.arrivals.equals(List.of(end, middle)), "reverse calls preserve dispatch order");
            Ride pickup = new Ride(mirror ? 1 : 5);
            pickup.hall(end, !mirror);
            pickup.ticks(2);
            pickup.hall(middle, mirror);
            pickup.until(() -> pickup.arrivals.size() == 2, "on-route pickup");
            check(pickup.arrivals.equals(List.of(middle, end)), "same-direction pickup still retargets");
        }

        Ride restored = doubleCall(3, true, 1);
        restored.reload(true);
        restored.select(1);
        finish(restored, 1);
        Ride oldUnknown = new Ride(2);
        oldUnknown.controller.restore(Phase.OPEN, 1, null, List.of(floor(3)),
                List.of(call(2, true), call(2, false)), Travel.NONE);
        oldUnknown.tick();
        check(oldUnknown.controller.hallCalls().size() == 2, "unknown legacy direction cannot clear both calls");
        oldUnknown.until(() -> oldUnknown.controller.hallCalls().size() == 1, "legacy departure");
        check(oldUnknown.controller.hallCalls().contains(call(2, false)), "legacy lower call protected");

        Ride fault = doubleCall(3, true, 1);
        fault.select(1);
        fault.until(() -> fault.y < 19, "depart before obstruction");
        fault.resume = false;
        fault.ticks(30);
        check(fault.controller.phase() == Phase.BLOCKED && fault.controller.hallCalls().contains(call(2, true)), "fault preserves return call");
        fault.reload(false);
        fault.resume = true;
        finish(fault, 1);
    }

    public static void main(String[] args) {
        for (int start : new int[]{3, 1}) for (boolean firstUp : new boolean[]{false, true})
            for (int destination : new int[]{1, 3}) matrixCase(start, firstUp, destination);
        interruptedDeparture();
        openDoorCallsAndIdle();
        existingDispatchAndLegacyRestore();
        System.out.println("PASS: 288 matrix combinations plus reopening, overload, idle, legacy restore and dispatch regressions");
    }
}
