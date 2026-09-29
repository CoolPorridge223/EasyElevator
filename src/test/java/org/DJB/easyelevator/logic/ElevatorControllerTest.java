package org.DJB.easyelevator.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Run with tools/test-logic.ps1. No Minecraft bootstrap or external test framework needed. */
public final class ElevatorControllerTest {
    private static final class Simulation implements ElevatorController.Environment {
        final ElevatorController control = new ElevatorController();
        final Set<ElevatorController.Stop> valid = new HashSet<>();
        final List<Integer> arrivals = new ArrayList<>();
        double y; boolean clear=true, doorway;
        void request(long id,int floor) { var s=new ElevatorController.Stop(id,floor);valid.add(s);check(control.request(s,y),"request accepted"); }
        void ticks(int count) {
            for(int i=0;i<count;i++) {
                double old=y; y=control.tick(y,this);
                check(Math.abs(y-old)<=ElevatorController.SPEED+.000001,"speed bounded");
                if(y!=old) check(control.door()==0,"never moves with doors open");
            }
        }
        public boolean valid(ElevatorController.Stop s) { return valid.contains(s); }
        public boolean canMove(double from,double to) { return clear; }
        public boolean doorwayBlocked() { return doorway; }
        public void arrived(ElevatorController.Stop s) { arrivals.add(s.y()); }
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        check(ElevatorController.SPEED == .20,"speed doubled to four blocks per second");
        var speed = new Simulation(); speed.request(1,100);
        speed.control.restore(ElevatorController.Phase.MOVING,0,new ElevatorController.Stop(1,100),List.of());
        speed.ticks(20);check(Math.abs(speed.y-4)<1e-12,"20 moving ticks travel exactly four blocks");
        for(double remaining:new double[]{.19999999, .00001, .00000005}) {
            var precise = new Simulation(); precise.y=5-remaining;precise.request(1,5);
            precise.control.restore(ElevatorController.Phase.MOVING,0,new ElevatorController.Stop(1,5),List.of());
            precise.ticks(1);check(precise.y==5 && precise.arrivals.equals(List.of(5)),"sub-step exact arrival without overshoot or deadzone");
        }
        var s=new Simulation();s.request(1,5);s.request(1,5);s.request(2,-3);s.ticks(400);
        check(s.arrivals.equals(List.of(5,-3)),"FIFO, deduplication, bidirectional exact arrival");
        check(s.y==-3 && s.control.phase()==ElevatorController.Phase.OPEN,"ends open at destination");

        s=new Simulation();s.request(1,10);s.ticks(80);double paused=s.y;s.clear=false;s.ticks(100);
        check(s.y==paused && s.control.phase()==ElevatorController.Phase.BLOCKED,"obstruction halts");
        s.clear=true;s.ticks(250);check(s.y==10 && s.arrivals.equals(List.of(10)),"repairs resume same request");

        s=new Simulation();s.doorway=true;s.request(1,5);s.ticks(200);
        check(s.y==0 && s.arrivals.isEmpty(),"door obstruction prevents departure");
        s.doorway=false;s.ticks(220);check(s.y==5,"anti-crush preserves request");

        s=new Simulation();s.request(1,10);s.ticks(90);s.valid.clear();double cancelled=s.y;s.ticks(80);
        check(s.y==cancelled && s.control.phase()==ElevatorController.Phase.BLOCKED && s.control.door()==0,"removed active stop halts with closed doors");
        s.request(2,0);s.ticks(250);check(s.y==0,"new request recovers cancelled trip");

        s=new Simulation();s.request(1,10);s.request(2,5);s.valid.remove(new ElevatorController.Stop(2,5));s.ticks(300);
        check(s.arrivals.equals(List.of(10)),"removed queued stop pruned");

        s=new Simulation();s.request(1,8);s.request(2,2);s.ticks(85);
        var restored=new Simulation();restored.valid.addAll(s.valid);restored.y=s.y;
        restored.control.restore(s.control.phase(),s.control.door(),s.control.target(),s.control.pending());
        restored.ticks(400);check(restored.arrivals.equals(List.of(8,2)),"reload preserves trip and queue");

        s=new Simulation();s.request(1,0);s.ticks(100);check(s.arrivals.isEmpty() && s.control.phase()==ElevatorController.Phase.OPEN,"current floor remains open");
        var c=new ElevatorController();for(int i=1;i<=128;i++) check(c.request(new ElevatorController.Stop(i,i),0),"queue capacity");
        check(!c.request(new ElevatorController.Stop(129,129),0),"queue flood bounded");
        testTimeline();
        System.out.println("PASS: 4 blocks/sec, sub-step exact arrival, frame interpolation/precision, FIFO/dedup, up/down arrival, door interlock, obstacle pause/resume, anti-crush, deleted stations, save/reload, current floor, queue limit.");
    }
    private static void testTimeline() {
        var timeline=new MotionTimeline();
        for(int i=0;i<=8;i++) timeline.add(100+i,64+i*.2,1000+i,64+Math.max(0,i-1)*.2);
        check(Math.abs(timeline.sample(1007.5,0)-65.1)<1e-12,"half-tick frame interpolates between authoritative samples");
        check(timeline.sample(1020,0)==65.6,"missing packets never extrapolate");
        timeline.add(107,100,1009,65.6);
        check(timeline.sample(1020,0)==65.6,"out-of-order sample ignored");
        var tiny=new MotionTimeline();tiny.add(1,100,10,100);tiny.add(2,100+1e-8,11,100);
        check(tiny.sample(13.5,0)>100,"double precision sub-quantum motion retained");
        var descending=new MotionTimeline();descending.add(1,5,1,5);descending.add(2,4.8,2,5);
        check(Math.abs(descending.sample(3.5,0)-4.9)<1e-12,"descent interpolates monotonically");
        descending.add(100,0,100,0);
        check(descending.sample(100,5)==0,"long idle resets stale history");
        descending.add(101,10,101,0);
        check(descending.sample(101,0)==10,"large teleport snaps rather than flying through the world");
    }
}
