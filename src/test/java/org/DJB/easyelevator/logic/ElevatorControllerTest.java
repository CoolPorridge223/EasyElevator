package org.DJB.easyelevator.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Run with tools/test-logic.ps1. No Minecraft bootstrap or external test framework needed.
 *
 * <p>纯 Java 状态机（logic/ElevatorController）与显示轨迹（logic/MotionTimeline）的回归测试，
 * 覆盖 README「行为」清单里的每条运行期约定。
 *
 * <p>之所以能脱离游戏运行：ElevatorController 是服务端权威、确定性的纯 Java 状态机，不引用任何
 * Minecraft 类，它只通过 {@link ElevatorController.Environment} 回调索取世界信息（站点是否有效、
 * 这一步能否移动、门口是否有人、是否到站）。本测试用 {@link Simulation} 提供假的 Environment，
 * 于是可以用普通 {@code java} 直接跑 {@link #main}，用退出码 / AssertionError 判定成败，
 * 既不需要 Minecraft bootstrap，也不需要 JUnit 等外部测试框架（接口与实现在同一包内）。
 * 时间单位仍是游戏刻（1 秒 = 20 刻），长度单位是格（方块）。
 */
public final class ElevatorControllerTest {
    /**
     * 最小可用的假 Environment + 假轿厢：用字段代替世界查询，用一个控制器实例代替 AbstractCabinEntity。
     * 与服务端真实注入点（AbstractCabinEntity#tick 里的匿名 Environment）语义一一对应：
     * valid = 目的站的楼层门是否仍然完整且同线路，canMove = 井道/线路是否可通过，
     * doorwayBlocked = 门口是否有活体（防夹），arrived = 到站事件。
     */
    private static final class Simulation implements ElevatorController.Environment {
        final ElevatorController control; // 被测状态机（速度由构造参数决定：普通 0.20 / 高速 0.50 格/刻）
        final Set<ElevatorController.Stop> valid = new HashSet<>(); // 当前“世界里仍存在”的站点；Stop.id 是 BlockPos.asLong()
        final List<Integer> arrivals = new ArrayList<>(); // 记录 arrived() 回调的站点高度（格），用于断言到站顺序与次数
        double y; boolean clear=true, doorway; // y = 轿厢底部高度（格，绝对 double）；clear = 井道畅通；doorway = 门口有活体
        /** 以普通轿厢速度（{@link ElevatorParameters#SPEED}）构造仿真。 */
        Simulation() { this(ElevatorParameters.SPEED); }
        /** 以指定速度构造仿真：高速轿厢用 {@link ElevatorParameters#HIGH_SPEED}，用来验证"只有速度不同"。 */
        Simulation(double speed) { control = new ElevatorController(speed); }
        /** 登记一个站点（视为门完整、线路未变、区块已加载）并发出请求；y 是发出请求时的轿厢高度，用于“已在当前楼层”判定。 */
        void request(long id,int floor) { var s=new ElevatorController.Stop(id,floor);valid.add(s);check(control.request(s,y),"request accepted"); }
        /**
         * 按一次楼层门上的方向按钮（厅外呼叫）：登记站点后提交带方向的呼叫。
         * 与真实门一样，呼叫会一直保留在状态机里（门上的按钮保持点亮），直到轿厢到站开门。
         */
        void callHall(long id,int floor,boolean up) {
            valid.add(new ElevatorController.Stop(id,floor));
            check(control.callHall(new ElevatorController.HallCall(id,floor,up),y),"hall call accepted");
        }
        /**
         * 推进给定刻数，同时在每一刻复查两条核心不变量：
         * 单刻位移不超过本车速度（普通 0.20 / 高速 0.50 格/刻），以及门未完全关闭（door() != 0）时位置绝不变化。
         */
        void ticks(int count) {
            for(int i=0;i<count;i++) {
                double old=y; y=control.tick(y,this);
                // 1e-6 格的容差用于吸收 double 加法误差；速度取本实例自己的步长，因此高速车也受同一条不变量约束。
                check(Math.abs(y-old)<=control.speed()+.000001,"speed bounded");
                if(y!=old) check(control.door()==0,"never moves with doors open");
            }
        }
        /** 站点是否有效：真实实现要校验门完整、朝向与线路一致；这里直接增删集合来模拟门的放置与拆除。 */
        public boolean valid(ElevatorController.Stop s) { return valid.contains(s); }
        /** from/to 是本次单刻位移的两端（格）；这里忽略参数，只用 clear 开关模拟断轨、障碍或区块未加载。 */
        public boolean canMove(double from,double to) { return clear; }
        /** 门口活体检测（防夹）：为 true 时门必须从 CLOSING 打回 OPENING，且不得启动。 */
        public boolean doorwayBlocked() { return doorway; }
        /** 到站回调：真实实现会播放到站音效并触发 ARRIVED 事件；这里只记录高度。 */
        public void arrived(ElevatorController.Stop s) { arrivals.add(s.y()); }
    }
    /** 断言失败抛 AssertionError 并以非零退出码结束进程，因此无需任何测试框架。 */
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    /**
     * 测试入口：按 README「行为」清单逐条验证状态机，全部通过后打印 PASS 行。
     * 无参数；任何一条不变量被破坏都会抛 AssertionError 让调用脚本失败。
     */
    public static void main(String[] args) {
        // 参数手册约定：SPEED = 0.20 格/刻 = 4 格/秒，是旧版 0.10 的两倍。
        check(ElevatorController.SPEED == .20,"speed doubled to four blocks per second");
        var speed = new Simulation(); speed.request(1,100);
        // restore(MOVING, ...) 会被降级成 BLOCKED 并关门，正好构造“正在运行的存档”；随后连续运行 20 刻
        // 必须恰好走 4 格（0.20 格/刻 × 20 刻），说明降级后重新起步不丢速度、也没有额外停顿。
        speed.control.restore(ElevatorController.Phase.MOVING,0,new ElevatorController.Stop(1,100),List.of());
        speed.ticks(20);check(Math.abs(speed.y-4)<1e-12,"20 moving ticks travel exactly four blocks");
        // 三型轿厢共用一个状态机，差别只有注入的速度：普通/观光 0.20、高速 = 2.5 倍 = 0.50 格/刻 = 10 格/秒。
        // 速度是实例状态（构造时注入、运行中不变），因此不同型号互不影响。
        check(ElevatorParameters.HIGH_SPEED == ElevatorParameters.SPEED * 2.5,"high speed is exactly 2.5x the standard speed");
        check(ElevatorParameters.HIGH_SPEED == .50,"high speed is ten blocks per second");
        check(new ElevatorController().speed() == ElevatorParameters.SPEED,"default speed is the standard cabin speed");
        check(new ElevatorController(ElevatorParameters.HIGH_SPEED).speed() == ElevatorParameters.HIGH_SPEED,"speed is injected per instance");
        // 非正数/非有限的速度退化为默认值：坏存档或误改常量都不会让轿厢永远到不了站。
        check(new ElevatorController(0).speed() == ElevatorParameters.SPEED
                && new ElevatorController(Double.NaN).speed() == ElevatorParameters.SPEED,"invalid speed falls back to the default");
        var fast = new Simulation(ElevatorParameters.HIGH_SPEED); fast.request(1,100);
        fast.control.restore(ElevatorController.Phase.MOVING,0,new ElevatorController.Stop(1,100),List.of());
        // 同样 20 刻：高速车走 0.50 × 20 = 10 格，正好是普通车的 2.5 倍（4 格）。
        fast.ticks(20);check(Math.abs(fast.y-10)<1e-12,"20 high-speed ticks travel exactly ten blocks");
        // 两车并存时速度互不串台：高速车跑完 20 刻后，新建的普通车 20 刻仍然只走 4 格。
        var standardAfterFast = new Simulation(); standardAfterFast.request(1,100);
        standardAfterFast.control.restore(ElevatorController.Phase.MOVING,0,new ElevatorController.Stop(1,100),List.of());
        standardAfterFast.ticks(20);check(Math.abs(standardAfterFast.y-4)<1e-12,"per-instance speed does not leak between cabins");
        // 高速车最后一步同样必须精确吸附到站点高度（不跨过、不停在差一点的位置）：单步 0.5 格 < 1 格，所以整格站点不会被跳过。
        for(double remaining:new double[]{.49999999, .00001, .00000005}) {
            var preciseFast = new Simulation(ElevatorParameters.HIGH_SPEED); preciseFast.y=5-remaining;
            // 先 request 一次把站点登记进 valid 集合（真实世界里等价于"这扇门确实存在"），否则 tick 开头
            // 会把目的站判为已失效；随后 restore 显式写入目标，直接考察最后一步的对齐行为。
            preciseFast.request(1,5);
            preciseFast.control.restore(ElevatorController.Phase.MOVING,0,new ElevatorController.Stop(1,5),List.of());
            preciseFast.ticks(1);check(preciseFast.y==5 && preciseFast.arrivals.equals(List.of(5)),"high-speed sub-step exact arrival without overshoot");
        }
        // 高速车同样受"门未关闭不得移动"约束：门开着时一步都不能走。
        var fastDoor = new Simulation(ElevatorParameters.HIGH_SPEED); fastDoor.request(1,20); fastDoor.ticks(20); double still=fastDoor.y;
        check(still==0 && fastDoor.control.phase()==ElevatorController.Phase.OPEN,"high-speed cabin waits with doors open");
        // 最后一步的余量取三种量级：略小于 SPEED、1e-5 格、以及 5e-8 格（小于 POSITION_EPSILON = 1e-7 格）。
        // 都不能过冲，也不能因“步长太小”而卡住不动。
        for(double remaining:new double[]{.19999999, .00001, .00000005}) {
            var precise = new Simulation(); precise.y=5-remaining;precise.request(1,5);
            // 余量小于容限时 request() 会把当前位置当成“已在该楼层”而不入队，因此紧接着用 restore()
            // 显式写入目标，直接考察状态机最后一步的对齐行为。
            precise.control.restore(ElevatorController.Phase.MOVING,0,new ElevatorController.Stop(1,5),List.of());
            // 单刻内必须精确落在 5.0（等于站点高度，而不是“接近”），并恰好触发一次到站回调。
            precise.ticks(1);check(precise.y==5 && precise.arrivals.equals(List.of(5)),"sub-step exact arrival without overshoot or deadzone");
        }
        // 同一目的站重复请求只保留一次；不同高度的请求按"运行方向上的位置顺序"停靠（真实电梯不会越过
        // 同方向的楼层再去更远的那层），并且支持下行。
        var s=new Simulation();s.request(1,5);s.request(1,5);s.request(2,-3);s.ticks(400);
        check(s.arrivals.equals(List.of(5,-3)),"deduplication, position-ordered service, bidirectional exact arrival");
        // 到站后门完全打开并进入 OPEN（停留 DWELL_TICKS = 40 刻）。
        check(s.y==-3 && s.control.phase()==ElevatorController.Phase.OPEN,"ends open at destination");

        // canMove 返回 false（断轨、朝向不一致、障碍、区块未加载）时停在原地进入 BLOCKED 而不是失败；
        // paused 记录进入暂停前的高度，100 刻内必须一格不动。
        s=new Simulation();s.request(1,10);s.ticks(80);double paused=s.y;s.clear=false;s.ticks(100);
        check(s.y==paused && s.control.phase()==ElevatorController.Phase.BLOCKED,"obstruction halts");
        // BLOCKED 只是暂停：条件恢复后继续原行程，且不重复触发到站回调（arrivals 里只有一个 10）。
        s.clear=true;s.ticks(250);check(s.y==10 && s.arrivals.equals(List.of(10)),"repairs resume same request");

        // 门口始终有活体：门一进入 CLOSING 就被 doorwayBlocked() 打回 OPENING，轿厢一步都不能动。
        s=new Simulation();s.doorway=true;s.request(1,5);s.ticks(200);
        check(s.y==0 && s.arrivals.isEmpty(),"door obstruction prevents departure");
        // 活体让开后请求仍然有效（防夹重开时被中断的请求会放回队首），最终正常到站。
        s.doorway=false;s.ticks(220);check(s.y==5,"anti-crush preserves request");

        // 运行中目的站的门被拆掉（valid 清空）：必须原地暂停、门保持关闭，绝不在半空开门。
        s=new Simulation();s.request(1,10);s.ticks(90);s.valid.clear();double cancelled=s.y;s.ticks(80);
        check(s.y==cancelled && s.control.phase()==ElevatorController.Phase.BLOCKED && s.control.door()==0,"removed active stop halts with closed doors");
        // 新请求可以救回被取消的行程。
        s.request(2,0);s.ticks(250);check(s.y==0,"new request recovers cancelled trip");

        // 排队中的站点失效会在每刻开头被剔除，因此不会产生第二个到达事件。
        s=new Simulation();s.request(1,10);s.request(2,5);s.valid.remove(new ElevatorController.Stop(2,5));s.ticks(300);
        check(s.arrivals.equals(List.of(10)),"removed queued stop pruned");

        // 存档恢复：把 (Phase, Door, Target, Queue) 交给一个全新控制器，等价于 AbstractCabinEntity 的 NBT 载入。
        // 若存档时正在 MOVING，restore() 会降级为 BLOCKED 并关门，因此必须先重验线路再继续；
        // 这里线路仍然有效，于是行程与队列都完整保留（存档时正驶向 2 层，8 层还在队里，读档后依次停靠）。
        s=new Simulation();s.request(1,2);s.request(2,8);s.ticks(65);
        var restored=new Simulation();restored.valid.addAll(s.valid);restored.y=s.y;
        restored.control.restore(s.control.phase(),s.control.door(),s.control.target(),s.control.pending());
        restored.ticks(400);check(restored.arrivals.equals(List.of(2,8)),"reload preserves trip and queue");

        // 请求当前所在楼层：只重置停留计时，不入队、不关门、不产生到站回调。
        s=new Simulation();s.request(1,0);s.ticks(100);check(s.arrivals.isEmpty() && s.control.phase()==ElevatorController.Phase.OPEN,"current floor remains open");
        // 队列上限 MAX_REQUESTS = 128 个请求：第 128 个被接受，第 129 个被拒绝（返回 false），防止刷屏压垮队列。
        var c=new ElevatorController();for(int i=1;i<=128;i++) check(c.request(new ElevatorController.Stop(i,i),0),"queue capacity");
        check(!c.request(new ElevatorController.Stop(129,129),0),"queue flood bounded");
        // 手动"关门"键：队列为空也能把门关上并停在本层（真实电梯可以关着门等人），且不能把门关穿到负值。
        s=new Simulation();s.request(1,0);s.ticks(100);
        check(s.control.forceClose(),"close command accepted while doors are open");
        s.ticks(22);
        check(s.control.door()==0 && s.control.phase()==ElevatorController.Phase.MOVING && s.y==0,"manual close shuts doors and parks");
        check(!s.control.forceClose(),"close command rejected when doors are already shut");
        // 手动"开门"键：停在某一层、门已关好（相位 MOVING、无目的站）时重新开门。
        check(s.control.forceOpen(),"open command accepted at a station with doors shut");
        s.ticks(22);
        check(s.control.door()==1 && s.control.phase()==ElevatorController.Phase.OPEN,"manual open reopens the doors");
        // 正在关门时按"开门"：反向重新打开（手动反向，和防夹走同一条门联锁路径），
        // 而且"开门"是纯门操作——不改 target/queue，因此不会把本层排进呼叫队列、也不会让轿厢开走再回来。
        s.control.forceClose();s.ticks(4);
        check(s.control.door()>0 && s.control.door()<1 && s.control.phase()==ElevatorController.Phase.CLOSING,"closing in progress");
        check(s.control.forceOpen(),"open command reverses a closing door");
        check(s.control.target()==null && s.control.pending().isEmpty(),"reversing a close adds nothing to the call plan");
        s.ticks(22);
        check(s.control.door()==1 && s.control.phase()==ElevatorController.Phase.OPEN,"reversed door ends fully open");
        // 门已全开时按"开门"只续满停留时间（相当于按住开门键）：队列里的目的站要等到松手后才出发。
        // 停留时间 DWELL_TICKS = 40 刻，这里先只推进 20 刻，确保还在开门停靠阶段。
        s=new Simulation();s.request(1,0);s.request(2,10);s.ticks(20);
        check(s.control.phase()==ElevatorController.Phase.OPEN,"open before departure");
        for(int i=0;i<ElevatorParameters.DWELL_TICKS;i++) { s.control.forceOpen(); s.ticks(1); }
        check(s.control.phase()==ElevatorController.Phase.OPEN && s.y==0,"holding the open button delays departure");
        s.ticks(400);
        check(s.y==10 && s.arrivals.contains(10),"departure resumes after the open button is released");
        // 显示轨迹（客户端乘客镜头与轿厢模型共用）的插值、缺包、乱序、精度与复位行为。
        testTimeline();
        // 厅外方向呼叫（楼层门上的上/下按钮）与集选调度。
        testHallCalls();
        System.out.println("PASS: 4 blocks/sec, 2.5x high-speed cabin (10 blocks/sec) with identical door/lock/arrival rules, sub-step exact arrival, frame interpolation/precision, position-ordered service, up/down arrival, door interlock, obstacle pause/resume, anti-crush, manual door open/close, deleted stations, save/reload, current floor, queue limit, directional hall calls (collective control, no starvation, button lit until arrival), en-route reordering (a nearer same-direction request inserted while moving is served first).");
    }
    /**
     * 厅外呼叫（楼层门的上行 / 下行按钮）的调度行为测试。
     *
     * <p>覆盖现实电梯的"集选控制"要点：轿厢内选站是目的层（任意方向都服务），厅外呼叫带方向、只被顺路的
     * 那趟接走；呼叫在到站开门前一直保留（门上按钮保持点亮），到站即清；反方向的孤立呼叫也必须最终被服务
     * （绝不饥饿、绝不空转）。时间单位仍是刻，高度单位是格。
     */
    private static void testHallCalls() {
        // ① 顺路：车在 0 层，轿厢内先选 10 层，厅外按 5 层"上行"、8 层"下行"。
        //    上行途中只接上行呼叫与选站 → 先 5 再 10；到顶掉头下行时才接 8 的下行呼叫。
        var s=new Simulation();
        s.request(3,10); s.callHall(1,5,true); s.callHall(2,8,false);
        s.ticks(700);
        check(s.arrivals.equals(List.of(5,10,8)),"collective control: serve up calls and car calls going up, the down call on the way back");
        check(s.control.hallCalls().isEmpty(),"every hall call ends up served");
        check(s.control.travel()==ElevatorController.Travel.NONE,"idle resets the service direction");
        // ② 反方向孤立呼叫：车在 2 层、只有 8 层的"下行"呼叫。车必须空车上行到 8 才能接上他，
        //    到站即清（不允许因为方向不一致而永远不派车）。
        s=new Simulation(); s.y=2; s.callHall(1,8,false);
        s.ticks(400);
        check(s.arrivals.equals(List.of(8)),"an isolated opposite-direction call is still served (no starvation)");
        check(s.control.hallCalls().isEmpty(),"the isolated call is cleared on arrival");
        // ③ 按钮保持点亮：登记后一直留在状态机里，直到轿厢真的到站开门才清掉。
        s=new Simulation(); s.callHall(1,5,true);
        s.ticks(80);
        check(s.control.hallCalls().size()==1,"the button stays lit while the car is on its way");
        s.ticks(300);
        check(s.control.hallCalls().isEmpty()&&s.arrivals.equals(List.of(5)),"the call clears exactly when the car arrives");
        // ④ 车已停在本层且门开着时按呼叫：当场算完成（只续满停留），不会"先红一下再灭"。
        s=new Simulation(); s.y=5; s.valid.add(new ElevatorController.Stop(1,5));
        check(s.control.callHall(new ElevatorController.HallCall(1,5,true),5),"a call at the open floor completes at once");
        check(s.control.hallCalls().isEmpty()&&s.control.phase()==ElevatorController.Phase.OPEN,"no pending call is left behind");
        // ⑤ 同一站点同一方向重复按只保留一条；上/下是两条独立呼叫。
        s=new Simulation(); s.callHall(1,5,true); s.callHall(1,5,true);
        check(s.control.hallCalls().size()==1,"repeated presses of the same button merge");
        s.callHall(1,5,false);
        check(s.control.hallCalls().size()==2,"up and down calls at one station are independent");
        // ⑥ 楼层门被拆：该站的呼叫随之取消（按钮所在的门都没了）。
        s=new Simulation(); s.callHall(1,5,true); s.valid.clear(); s.ticks(1);
        check(s.control.hallCalls().isEmpty(),"removing the landing door cancels its hall calls");
        // ⑦ 存档往返：呼叫与服务方向一起保存，读档后继续服务（门还没被拆）。
        s=new Simulation(); s.callHall(1,5,true); s.ticks(60);
        var restored=new Simulation(); restored.valid.addAll(s.valid);
        restored.control.restore(s.control.phase(),s.control.door(),s.control.target(),s.control.pending(),
                s.control.hallCalls(),s.control.travel());
        restored.ticks(400);
        check(restored.arrivals.equals(List.of(5)),"hall calls survive save/reload and are served after it");
        check(restored.control.hallCalls().isEmpty(),"the reloaded call is cleared on arrival");
        // ⑧ 顺路重排（运行途中插入）：车已驶向 10 层，走到 2 层时有人在 5 层按了上行 —— 必须先在 5 层停，
        //    而不是径直开过（这是"插入即重排"的核心行为）。
        s=new Simulation(); s.request(3,10); s.ticks(65);           // 65 刻：门已关好、车刚离开 0 层（y=1.0）
        check(s.y>0&&s.y<10,"car is on its way to the far floor");
        s.callHall(1,5,true);                                       // 运行途中按 5 层上行
        s.ticks(400);
        check(s.arrivals.equals(List.of(5,10)),"a nearer on-the-way call overtakes the current destination");
        // ⑨ 同样地，运行途中按轿厢内选站也会被插到前面：先 5 后 10。
        s=new Simulation(); s.request(3,10); s.ticks(65); s.request(4,5); s.ticks(400);
        check(s.arrivals.equals(List.of(5,10)),"a nearer car call inserted en route is served first");
        // ⑩ 反方向的呼叫不会被"顺路"改道：车向上驶向 10 层时，5 层的下行呼叫要等掉头后才接。
        s=new Simulation(); s.request(3,10); s.ticks(65); s.callHall(1,5,false); s.ticks(400);
        check(s.arrivals.equals(List.of(10,5)),"an opposite-direction call is not served on the way up");
    }
    /**
     * MotionTimeline（客户端乘客镜头与轿厢模型共用的显示轨迹）的行为测试。
     * 核心不变量：只在本机已知样本之间插值，数据包停止时绝不外推，也不飞穿世界。
     * 参数单位：服务端刻 / 本地刻（都是游戏刻），高度是格（绝对 double）。
     */
    private static void testTimeline() {
        var timeline=new MotionTimeline();
        // 构造 9 个样本：服务端刻 100..108、高度 64 起每刻 +0.2 格（正好等于 SPEED），本地刻比服务端刻大
        // 900 刻（固定时钟偏移）；previousY 与服务端记录的上一高度一致，用于判断是否需要重置或吸附。
        for(int i=0;i<=8;i++) timeline.add(100+i,64+i*.2,1000+i,64+Math.max(0,i-1)*.2);
        // 采样点落在两个样本之间：targetTick = 1007.5 - 900(时钟偏移) - 2(INTERPOLATION_DELAY_TICKS) = 105.5，
        // 位于刻 105（65.0 格）与刻 106（65.2 格）之间 → 65.1 格。
        check(Math.abs(timeline.sample(1007.5,0)-65.1)<1e-12,"half-tick frame interpolates between authoritative samples");
        // 远超最后一个样本（刻 108 → 65.6 格）：数据包停止后必须停在最后一个已知高度，不得预测下一格。
        check(timeline.sample(1020,0)==65.6,"missing packets never extrapolate");
        // 乱序/过期样本（服务端刻 107 <= 最后样本刻 108）直接被丢弃，轨迹不会因此回退。
        timeline.add(107,100,1009,65.6);
        check(timeline.sample(1020,0)==65.6,"out-of-order sample ignored");
        // 两个样本只差 1e-8 格（远小于原版相对位置包的定点量化精度）：double 时间线必须保留这段
        // 亚量子位移，采样结果严格大于 100.0。
        var tiny=new MotionTimeline();tiny.add(1,100,10,100);tiny.add(2,100+1e-8,11,100);
        check(tiny.sample(13.5,0)>100,"double precision sub-quantum motion retained");
        // 下行方向：刻 1 → 5.0 格、刻 2 → 4.8 格，插值必须单调降到 4.9 格，算法与运动方向无关。
        var descending=new MotionTimeline();descending.add(1,5,1,5);descending.add(2,4.8,2,5);
        check(Math.abs(descending.sample(3.5,0)-4.9)<1e-12,"descent interpolates monotonically");
        // 与上一包间隔 98 刻 > MOTION_RESET_GAP_TICKS = 20 刻：清空历史并以“服务端刻 - 1”的伪样本重建基线，
        // 避免长时间空闲后从旧样本插值飞出去；此时传入的 fallback = 5 不应被使用。
        descending.add(100,0,100,0);
        check(descending.sample(100,5)==0,"long idle resets stale history");
        // 单包跳变 10 格 > MOTION_SNAP_DISTANCE = 4.0 格：按传送处理直接吸附到 10 格，绝不插值“飞穿”世界。
        descending.add(101,10,101,0);
        check(descending.sample(101,0)==10,"large teleport snaps rather than flying through the world");
    }
}
