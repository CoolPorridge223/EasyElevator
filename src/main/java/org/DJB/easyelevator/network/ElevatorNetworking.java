package org.DJB.easyelevator.network;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.block.BlockState;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.block.LandingDoorBlock;
import org.DJB.easyelevator.block.LandingDoorBlockEntity;
import org.DJB.easyelevator.entity.AbstractCabinEntity;
import org.DJB.easyelevator.logic.DoorArrivalSound;
import org.DJB.easyelevator.logic.DoorSoundPersistence;
import org.DJB.easyelevator.logic.DoorSounds;
import org.DJB.easyelevator.logic.ElevatorLine;
import org.DJB.easyelevator.logic.ElevatorParameters;
import org.DJB.easyelevator.logic.FloorIndicator;
import java.util.ArrayList;
import java.util.List;

/**
 * 电梯模组的网络层：14 个自定义包（7 个 S2C + 7 个 C2S）+ 打开站层面板 / 厅外呼叫面板 / 门设置面板的交互事件注册，是服务端权威数据流向客户端的唯一通道。
 *
 * <p>包的方向与用途（服务端始终是唯一数据源，客户端只读）：
 * <ul>
 *   <li>{@link MotionFrame}：S2C。轿厢绝对 double 高度，供客户端承托、碰撞与渲染共同使用。</li>
 *   <li>{@link RiderMove}：C2S。原版移动包及客户端使用的轿厢样本编号，用于服务端坐标系补偿。</li>
 *   <li>{@link OpenPanel} / {@link PanelState}：S2C。打开 / 刷新轿厢内选站面板（站点列表、停靠计划、基准层高度）。</li>
 *   <li>{@link OpenHallPanel} / {@link HallPanelState}：S2C。打开 / 刷新楼层门上的厅外呼叫面板（上、下两个方向的点亮状态）。</li>
 *   <li>{@link SelectStop} / {@link DoorCommand} / {@link HallCallButton}：C2S。轿厢内选站、开关门、以及厅外上/下呼叫。</li>
 * </ul>
 *
 * <p>关键不变量/约束：
 * <ul>
 *   <li>所有请求参数（轿厢实体 id、站点坐标）都由客户端携带，但一律视为<b>不可信输入</b>，
 *       服务端必须用自己世界里的实体与线路数据重新校验后才执行。</li>
 *   <li>网络包不承载运动速度或轨迹，只承载绝对位置样本；客户端绝不外推预测。</li>
 *   <li>负载类型与编解码器必须在任何收发之前注册，见 {@link #register()}。</li>
 * </ul>
 *
 * <p>单位约定：坐标与偏移量为格（方块）；{@code tick} 为世界时间，单位刻（tick）。
 */
public final class ElevatorNetworking {
    /** 工具类，禁止实例化。 */
    private ElevatorNetworking() { }
    // 世界高度也远达不到这个站点数；这是防无界分配的兜底上限，正常线路只会用到其中极小一部分。
    /** 单个 {@link OpenPanel} 允许携带的站点数量上限，单位：个站点；用于防止恶意/损坏包触发无界内存分配。 */
    public static final int MAX_STOPS = 16384;
    /** 门音效选项序号缺失时的哨兵值：{@link DoorSoundCommand#choice()} 为它表示"本次只改开关、不动选项"。 */
    public static final int CHOICE_UNCHANGED = -1;
    /** 单个门音效音频的大小上限（字节），与 {@code client/DoorSoundPack} 的校验保持一致（512 KiB）。 */
    public static final int MAX_AUDIO_BYTES = 512 * 1024;
    /** {@link DoorSoundUpload} 自定义包负载的大小上限（字节）：音频上限 + 1 KiB 余量（坐标、方向、长度前缀）。 */
    public static final int MAX_PAYLOAD_BYTES = MAX_AUDIO_BYTES + 1024;
    /** {@link OpenDoorPanel#floorLabel()} 允许的最大长度（字符）：层号显示文本极短（"B12"），给足余量即可。 */
    public static final int MAX_FLOOR_LABEL = 32;
    /**
     * 轿厢运动帧（服务端 -> 客户端）：一帧绝对高度样本 + 该观测者自身的乘客偏移量。
     *
     * <p>用绝对 double 而非原版相对实体位置包，是为了绕开原版位置包的定点量化
     * （1/4096 格的离散步长 + 逐包加法），避免低速运行时出现台阶跳动与累计漂移。
     *
     * <p>为什么只有单帧而没有速度/轨迹：客户端 {@code client/CabinMotion}
     * 只在"本机已收到的两个样本之间"插值，绝不做外推，因此服务端无需（也不能）下发预测信息。
     *
     * @param entityId 轿厢实体在网络层中的运行时 id，单位：无；同一存档内不保证稳定
     * @param tick 采集该样本时的世界时间，单位：刻（tick），用于把样本对齐到客户端时间轴
     * @param y 轿厢本体的绝对 Y 坐标，单位：格（方块）
     * @param riderOffset 该接收者自身 Y 与轿厢 Y 的差值，单位：格；非乘客或已停止运行时为 {@link Double#NaN}，
     *                    保留此旧协议字段；2.1.2 客户端自行判定乘客，不再使用它锁定相机
     */
    public record MotionFrame(int entityId, long tick, double y, double riderOffset) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:motion_frame}。 */
        public static final Id<MotionFrame> ID = new Id<>(Easyelevator.id("motion_frame"));
        /** 线格式编解码器：字段顺序必须与 {@link #decode}/{@link #encode} 严格一致，否则两端静默错位。 */
        public static final PacketCodec<RegistryByteBuf,MotionFrame> CODEC = new PacketCodec<>() {
            /** 从字节流读回一帧：VarInt id、long 时间、两个 double（Y 与乘客偏移量）。 */
            @Override public MotionFrame decode(RegistryByteBuf b) {
                return new MotionFrame(b.readVarInt(), b.readLong(), b.readDouble(), b.readDouble());
            }
            /** 按与解码完全对称的顺序写出各字段；顺序一旦改动即为协议不兼容。 */
            @Override public void encode(RegistryByteBuf b, MotionFrame p) {
                b.writeVarInt(p.entityId()); b.writeLong(p.tick()); b.writeDouble(p.y()); b.writeDouble(p.riderOffset());
            }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 向所有正在追踪该轿厢的玩家广播一帧绝对位置。
     *
     * <p>只在轿厢本刻确实发生了位移、或位移结束后的 {@code MOTION_SETTLE_TICKS}（4 刻）稳定期内被调用，
     * 因此静止时没有持续的网络流量。
     *
     * <p>副作用：向每个追踪者发包（每玩家一帧，偏移量因人而异，不能用广播包代替）。
     * 乘客偏移量按接收者单独计算：运行中的乘客得到真实的自身 Y 与轿厢 Y 之差，其他情况得到 {@link Double#NaN}。
     *
     * @param cabin 要同步的轿厢，必须位于服务端世界
     */
    public static void syncMotion(AbstractCabinEntity cabin) {
        for (ServerPlayerEntity observer : PlayerLookup.tracking(cabin)) {
            // 到站（包括最后一帧位移）立即释放镜头；停车补帧只平滑轿厢，不再绑定乘客。
            double riderOffset = cabin.phase() == org.DJB.easyelevator.logic.ElevatorController.Phase.MOVING
                    && cabin.hasTarget() && cabin.containsPassenger(observer)
                    ? observer.getY() - cabin.getY() : Double.NaN;
            ServerPlayNetworking.send(observer, new MotionFrame(cabin.getId(), cabin.getWorld().getTime(), cabin.getY(), riderOffset));
        }
    }
    /**
     * 打开站层面板（服务端 -> 客户端）：携带轿厢实体 id、该线路全部站点的根方块坐标、
     * 当前的"停靠计划"（正在执行的目的站 + 排队中的站点），以及本线路的基准层高度。
     *
     * <p>站点列表来自服务端 {@code ElevatorLine#stops()}，即"完整 3x3 楼层门的底部中心方块"位置，
     * 客户端只用它渲染按钮并原样回传坐标，不做任何线路推断。计划列表只用于把已加入计划的按钮标红；
     * 基准层高度（{@code baseFloorY}）用于给按钮编号：基准层是 1 层，其下依次 B1、B2…
     * （{@code Integer.MIN_VALUE} 表示没有基准层，客户端按"最低站点 = 1 层"编号）。
     *
     * @param entityId 轿厢实体 id；客户端点击后必须原样回传，服务端据此重新定位轿厢
     * @param stops 线路上的站点（楼层门根方块）列表，元素个数不超过 {@link #MAX_STOPS}
     * @param planned 停靠计划：目的站在前、排队站点随后，都用根方块坐标表示；只影响高亮
     * @param baseFloorY 基准层（1 层）的高度，单位格；没有基准层时为 {@link Integer#MIN_VALUE}
     */
    public record OpenPanel(int entityId, List<BlockPos> stops, List<BlockPos> planned, int baseFloorY) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:open_panel}。 */
        public static final Id<OpenPanel> ID = new Id<>(Easyelevator.id("open_panel"));
        /** 线格式编解码器；解码时对站点数量做上限校验，见下。 */
        public static final PacketCodec<RegistryByteBuf,OpenPanel> CODEC = new PacketCodec<>() {
            /** 读回面板内容：先用 VarInt 读数量并校验范围，再逐个读 BlockPos，最后读基准层高度。 */
            @Override public OpenPanel decode(RegistryByteBuf buf) {
                int id=buf.readVarInt();
                // 数量在循环之前校验：负数会让循环直接不执行，超大值则可能在分配阶段就耗尽内存。
                List<BlockPos> stops=readPositions(buf,"station");
                List<BlockPos> planned=readPositions(buf,"planned stop");
                // 复制成不可变列表，避免把仍在复用的读缓冲数据或可变集合泄漏给后续逻辑。
                return new OpenPanel(id,stops,planned,buf.readVarInt());
            }
            /** 写出面板内容：实体 id、站点数量与坐标、计划数量与坐标、基准层高度。 */
            @Override public void encode(RegistryByteBuf buf, OpenPanel p) {
                buf.writeVarInt(p.entityId);
                writePositions(buf,p.stops);
                writePositions(buf,p.planned);
                buf.writeVarInt(p.baseFloorY);
            }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 面板状态刷新（服务端 -> 客户端）：携带"停靠计划"与本线路的基准层高度。
     *
     * <p>与 {@link OpenPanel} 的分工：本包<b>永远不会打开面板</b>，只用于刷新已经打开的面板
     * （例如某个站点已经到达、目的站被拆、队列被清理，或别的玩家改了基准层让编号整体变化），
     * 因此可以放心地按变化推送，不会给没开面板的乘客弹出界面。
     *
     * @param entityId 轿厢实体 id；客户端只在该轿厢的面板正开着时才应用
     * @param planned 停靠计划：目的站在前、排队站点随后；空列表表示当前没有任何计划
     * @param baseFloorY 基准层（1 层）的高度，单位格；没有基准层时为 {@link Integer#MIN_VALUE}
     */
    public record PanelState(int entityId, List<BlockPos> planned, int baseFloorY) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:panel_state}。 */
        public static final Id<PanelState> ID = new Id<>(Easyelevator.id("panel_state"));
        /** 线格式编解码器：与 OpenPanel 共用同一套坐标列表读写。 */
        public static final PacketCodec<RegistryByteBuf,PanelState> CODEC = new PacketCodec<>() {
            /** 读回一次计划快照。 */
            @Override public PanelState decode(RegistryByteBuf buf) { return new PanelState(buf.readVarInt(),readPositions(buf,"planned stop"),buf.readVarInt()); }
            /** 写出一次计划快照。 */
            @Override public void encode(RegistryByteBuf buf,PanelState p) { buf.writeVarInt(p.entityId);writePositions(buf,p.planned);buf.writeVarInt(p.baseFloorY); }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 把轿厢当前的停靠计划推送给正在追踪它的客户端。
     *
     * <p>由 {@code AbstractCabinEntity} 在"计划发生变化"的那一 tick 调用（到达、取消、新请求等），
     * 而不是每刻推送：队列变化一次最多几十字节，且只影响已经打开面板的玩家。
     *
     * @param cabin 目标轿厢
     * @param planned 停靠计划（目的站在前、排队站点随后）
     */
    public static void syncPanel(AbstractCabinEntity cabin,List<BlockPos> planned) {
        var payload=new PanelState(cabin.getId(),List.copyOf(planned),cabin.baseFloorY());
        for (ServerPlayerEntity observer : PlayerLookup.tracking(cabin)) ServerPlayNetworking.send(observer,payload);
    }
    /**
     * 面板上的"开门 / 关门"按键（客户端 -> 服务端）。
     *
     * <p>与服务端的关系：客户端只表达"我想开门/关门"，是否允许由服务端判定——开门必须先由服务端确认
     * 车体精确停在某个完整站点（否则就是半空开门），关门只有在门处于打开过程时才生效。
     *
     * @param entityId 轿厢实体 id，由服务端在 {@link OpenPanel} 中给出；服务端仍会重新核对归属
     * @param open true = 开门，false = 关门
     */
    public record DoorCommand(int entityId, boolean open) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:door_command}。 */
        public static final Id<DoorCommand> ID = new Id<>(Easyelevator.id("door_command"));
        /** 线格式编解码器：VarInt 实体 id + 一个布尔命令方向。 */
        public static final PacketCodec<RegistryByteBuf,DoorCommand> CODEC = new PacketCodec<>() {
            /** 读回一次按键；语义校验全部留给服务端处理器。 */
            @Override public DoorCommand decode(RegistryByteBuf buf) { return new DoorCommand(buf.readVarInt(),buf.readBoolean()); }
            /** 写出一次按键。 */
            @Override public void encode(RegistryByteBuf buf,DoorCommand p) { buf.writeVarInt(p.entityId);buf.writeBoolean(p.open); }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 打开"厅外呼叫面板"（服务端 -> 客户端）：右键楼层门时下发，客户端据此弹出上/下/关闭三个按钮。
     * <p>与选站面板 {@link OpenPanel} 一样，面板内容全部来自服务端：这里带上该站两个方向当前是否已有呼叫，
     * 因此关掉面板再打开、或别人按过按钮之后再打开，按钮的点亮状态都仍然正确。
     *
     * @param station 楼层门根方块位置（站点）
     * @param up 该站的上行按钮当前是否点亮
     * @param down 该站的下行按钮当前是否点亮
     * @param showUp 是否显示上行按钮（最顶层之上没有站点时为 false）
     * @param showDown 是否显示下行按钮（最底层之下没有站点时为 false）
     */
    public record OpenHallPanel(BlockPos station, boolean up, boolean down, boolean showUp, boolean showDown) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:open_hall_panel}。 */
        public static final Id<OpenHallPanel> ID = new Id<>(Easyelevator.id("open_hall_panel"));
        /** 线格式编解码器：一个 BlockPos 加两个布尔。 */
        public static final PacketCodec<RegistryByteBuf,OpenHallPanel> CODEC = new PacketCodec<>() {
            /** 读回面板初值。 */
            @Override public OpenHallPanel decode(RegistryByteBuf buf) { return new OpenHallPanel(buf.readBlockPos(),buf.readBoolean(),buf.readBoolean(),buf.readBoolean(),buf.readBoolean()); }
            /** 写出面板初值。 */
            @Override public void encode(RegistryByteBuf buf,OpenHallPanel p) { buf.writeBlockPos(p.station);buf.writeBoolean(p.up);buf.writeBoolean(p.down);buf.writeBoolean(p.showUp);buf.writeBoolean(p.showDown); }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 刷新厅外呼叫面板上的按钮状态（服务端 -> 客户端）：只更新"哪个方向还在呼叫"，绝不用它打开界面。
     *
     * @param station 楼层门根方块位置（站点）
     * @param up 该站上行按钮是否点亮
     * @param down 该站下行按钮是否点亮
     */
    public record HallPanelState(BlockPos station, boolean up, boolean down) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:hall_panel_state}。 */
        public static final Id<HallPanelState> ID = new Id<>(Easyelevator.id("hall_panel_state"));
        /** 线格式编解码器：与 {@link OpenHallPanel} 同构。 */
        public static final PacketCodec<RegistryByteBuf,HallPanelState> CODEC = new PacketCodec<>() {
            /** 读回一次点亮状态。 */
            @Override public HallPanelState decode(RegistryByteBuf buf) { return new HallPanelState(buf.readBlockPos(),buf.readBoolean(),buf.readBoolean()); }
            /** 写出一次点亮状态。 */
            @Override public void encode(RegistryByteBuf buf,HallPanelState p) { buf.writeBlockPos(p.station);buf.writeBoolean(p.up);buf.writeBoolean(p.down); }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 按下楼层门面板上的上行 / 下行按钮（客户端 -> 服务端）。
     *
     * <p>与选站请求一样，包里的站点坐标视为不可信输入：服务端会重新确认它是本线路的完整楼层门，
     * 并确认这条线路恰好有一辆轿厢，然后才登记带方向的厅外呼叫。
     *
     * @param station 楼层门根方块位置（站点）
     * @param up true = 上行按钮，false = 下行按钮
     */
    public record HallCallButton(BlockPos station, boolean up) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:hall_call_button}。 */
        public static final Id<HallCallButton> ID = new Id<>(Easyelevator.id("hall_call_button"));
        /** 线格式编解码器：一个 BlockPos 加一个方向布尔。 */
        public static final PacketCodec<RegistryByteBuf,HallCallButton> CODEC = new PacketCodec<>() {
            /** 读回一次按键；语义校验全部留给服务端处理器。 */
            @Override public HallCallButton decode(RegistryByteBuf buf) { return new HallCallButton(buf.readBlockPos(),buf.readBoolean()); }
            /** 写出一次按键。 */
            @Override public void encode(RegistryByteBuf buf,HallCallButton p) { buf.writeBlockPos(p.station);buf.writeBoolean(p.up); }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 打开 / 刷新"某扇门自己的设置面板"（服务端 -> 客户端）：潜行右键楼层门时下发。
     *
     * <p>与厅外呼叫面板 {@link OpenHallPanel} 的分工：那个面板管"叫电梯"，这个面板管
     * <b>这扇门自己的装修属性</b>——开关门音效的开关、音效选项，以及"把这一站设为基准层"。
     * 两者互不干扰：普通右键照旧打开呼叫面板，只有潜行右键才打开本面板。</p>
     *
     * <p>面板内容全部来自服务端：单扇门的设置存在门的方块实体里（随区块存档），
     * "这一站是第几层"由服务端按线路现算。客户端只画界面、把点击原样回传，不推导任何状态，
     * 因此关掉面板再打开、或别的玩家刚改过设置，看到的都是最新的真实值。</p>
     *
     * <p>为什么设置拆成标量而不是直接搬一个领域对象：网络包是客户端与服务端之间的
     * 协议，只该承载原始值；把领域对象拼装留给各自的处理器，协议就不会因为领域类改字段而变。</p>
     *
     * @param station   该扇门的根方块坐标（底部中心）
     * @param floorLabel 这一站当前的层号显示文本（服务端按基准层算好，例如 {@code "3"} / {@code "B1"}）
     * @param enabled   到站提示音的开关
     * @param choice    到站提示音的选项序号
     * @param baseFloor 这一站是否就是整条线路的基准层（1 层）
     * @param soundSlot 该门在运行时资源包里的音效槽位（面板据此显示自定义音频的文件名）
     * @param open      true 允许主动打开面板；false 仅刷新已经打开的同站点面板
     * @param preview   本次下发前服务端是否刚试听过（非 0 表示面板上那一行要闪一下）
     */
    public record OpenDoorPanel(BlockPos station, String floorLabel,
                                boolean enabled, int choice,
                                boolean baseFloor, int soundSlot, int preview, boolean open) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:open_door_panel}。 */
        public static final Id<OpenDoorPanel> ID = new Id<>(Easyelevator.id("open_door_panel"));
        /** {@link #preview} 的取值：本次没有试听。 */
        public static final int PREVIEW_NONE = 0;
        /** {@link #preview} 的取值：服务端刚试听过到站提示音。 */
        public static final int PREVIEW_PLAYED = 1;
        /** 线格式编解码器：坐标、层号文本、两个设置标量、基准层、槽位、试听标记、打开标记。 */
        public static final PacketCodec<RegistryByteBuf,OpenDoorPanel> CODEC = new PacketCodec<>() {
            /** 读回一份面板快照；层号文本做了长度上限校验，避免畸形包构造超长字符串。 */
            @Override public OpenDoorPanel decode(RegistryByteBuf buf) {
                BlockPos station=buf.readBlockPos();
                String label=buf.readString(MAX_FLOOR_LABEL);
                boolean enabled=buf.readBoolean(); int choice=buf.readVarInt();
                boolean baseFloor=buf.readBoolean();
                int slot=buf.readVarInt();
                int preview=buf.readVarInt();
                return new OpenDoorPanel(station,label,enabled,choice,baseFloor,slot,preview,buf.readBoolean());
            }
            /** 写出面板快照：字段顺序必须与解码严格一致。 */
            @Override public void encode(RegistryByteBuf buf,OpenDoorPanel p) {
                buf.writeBlockPos(p.station());
                buf.writeString(p.floorLabel(),MAX_FLOOR_LABEL);
                buf.writeBoolean(p.enabled()); buf.writeVarInt(p.choice());
                buf.writeBoolean(p.baseFloor());
                buf.writeVarInt(p.soundSlot());
                buf.writeVarInt(p.preview());
                buf.writeBoolean(p.open());
            }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 修改某扇门的门音效设置（客户端 -> 服务端）。
     *
     * <p>只在玩家真的点了开关/箭头时才发；服务端重新校验门与权限，然后落进方块实体 NBT、
     * 并把最新快照回推给所有正在看这扇门面板的人（全服同步：同一扇门在所有人眼里配置一致）。</p>
     *
     * @param station 楼层门根方块坐标
     * @param enabled 改动后的开关
     * @param choice  改动后的音效选项序号；{@code < 0}（{@link #CHOICE_UNCHANGED}）表示本次只改开关、不动选项
     * @param preview 是否要服务端当场把提示音播一次（试听）
     */
    public record DoorSoundCommand(BlockPos station, boolean enabled, int choice, boolean preview) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:door_sound_command}。 */
        public static final Id<DoorSoundCommand> ID = new Id<>(Easyelevator.id("door_sound_command"));
        /** 线格式编解码器：坐标 + 开关 + 选项 + 试听。 */
        public static final PacketCodec<RegistryByteBuf,DoorSoundCommand> CODEC = new PacketCodec<>() {
            /** 读回一次修改；语义校验全部留给服务端处理器。 */
            @Override public DoorSoundCommand decode(RegistryByteBuf buf) {
                return new DoorSoundCommand(buf.readBlockPos(),buf.readBoolean(),buf.readVarInt(),buf.readBoolean());
            }
            /** 写出一次修改。 */
            @Override public void encode(RegistryByteBuf buf,DoorSoundCommand p) {
                buf.writeBlockPos(p.station());buf.writeBoolean(p.enabled());
                buf.writeVarInt(p.choice());buf.writeBoolean(p.preview());
            }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 把这一站设为 / 取消整条线路的基准层（客户端 -> 服务端）。
     *
     * <p>原来的入口是"潜行右键门"；现在潜行右键改为打开设置面板，这个动作变成面板里的一个按钮，
     * 行为与判据完全保留（见 {@code LandingDoorBlock#setFloorBase}）：一条线路最多一扇门带标记，
     * 设置时清掉同线其它门的标记。</p>
     *
     * @param station 楼层门根方块坐标
     * @param on      true = 设为基准层（1 层），false = 取消本门的基准层标记（回到默认编号）
     */
    public record SetBaseFloor(BlockPos station, boolean on) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:set_base_floor}。 */
        public static final Id<SetBaseFloor> ID = new Id<>(Easyelevator.id("set_base_floor"));
        /** 线格式编解码器：坐标 + 一个开关。 */
        public static final PacketCodec<RegistryByteBuf,SetBaseFloor> CODEC = new PacketCodec<>() {
            /** 读回一次设置；服务端会重新确认站点与线路。 */
            @Override public SetBaseFloor decode(RegistryByteBuf buf) { return new SetBaseFloor(buf.readBlockPos(),buf.readBoolean()); }
            /** 写出一次设置。 */
            @Override public void encode(RegistryByteBuf buf,SetBaseFloor p) { buf.writeBlockPos(p.station());buf.writeBoolean(p.on()); }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 上传一个自定义到站音效文件（客户端 -> 服务端），内容就是完整的 {@code .ogg} 字节。
     *
     * <p>为什么整文件一次发完而不是分片：每个音频 ≤ {@link #MAX_AUDIO_BYTES}（512 KiB），
     * 自定义包负载上限（{@link #MAX_PAYLOAD_BYTES}）与之匹配；一次发完省掉重组状态机、顺序号与超时清理，
     * 而这点体积对现代连接完全不算什么。</p>
     *
     * <p>客户端携带的坐标与字节全部视为<b>不可信输入</b>：服务端会重新校验玩家身份、整扇门、
     * ogg 魔数与大小上限，然后才写入权威副本目录。</p>
     *
     * @param station 楼层门根方块坐标
     * @param bytes   完整的 {@code .ogg} 文件内容
     */
    public record DoorSoundUpload(BlockPos station, byte[] bytes) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:door_sound_upload}。 */
        public static final Id<DoorSoundUpload> ID = new Id<>(Easyelevator.id("door_sound_upload"));
        /** 线格式编解码器：坐标 + 长度前缀的字节数组（读取时先校验长度上限）。 */
        public static final PacketCodec<RegistryByteBuf,DoorSoundUpload> CODEC = new PacketCodec<>() {
            /** 读回一份上传：长度在分配之前校验，见 {@link #MAX_PAYLOAD_BYTES}。 */
            @Override public DoorSoundUpload decode(RegistryByteBuf buf) {
                return new DoorSoundUpload(buf.readBlockPos(),buf.readByteArray(MAX_PAYLOAD_BYTES));
            }
            /** 写出一次上传。 */
            @Override public void encode(RegistryByteBuf buf,DoorSoundUpload p) {
                buf.writeBlockPos(p.station());buf.writeByteArray(p.bytes());
            }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 某个门槽音频的<b>内容</b>（双向使用）：服务端用它把字节发给需要的客户端，客户端用它向服务端索取。
     *
     * <p>有它之后，自定义音效在<b>专用服务器</b>上也能全服一致——收到文件的一方把它存进自己的权威副本目录
     * 并重建资源包，不必依赖服务器另外托管一个资源包 URL，真正做到"装上模组就能用"。</p>
     *
     * @param slot  门槽号
     * @param bytes 完整的 {@code .ogg} 内容；空数组表示"该槽位没有音频"
     */
    public record DoorSoundData(int slot, byte[] bytes) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:door_sound_data}。 */
        public static final Id<DoorSoundData> ID = new Id<>(Easyelevator.id("door_sound_data"));
        /** 线格式编解码器：与 {@link DoorSoundUpload} 同构，只是用门槽号代替方块坐标。 */
        public static final PacketCodec<RegistryByteBuf,DoorSoundData> CODEC = new PacketCodec<>() {
            /** 读回一份音频；长度在分配之前校验。 */
            @Override public DoorSoundData decode(RegistryByteBuf buf) {
                return new DoorSoundData(buf.readVarInt(),buf.readByteArray(MAX_PAYLOAD_BYTES));
            }
            /** 写出一份音频。 */
            @Override public void encode(RegistryByteBuf buf,DoorSoundData p) {
                buf.writeVarInt(p.slot());buf.writeByteArray(p.bytes());
            }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 请求某个门槽的音频内容（客户端 -> 服务端）。
     *
     * <p>用于<b>进服时补齐</b>：玩家可能在音频上传之后才进入服务器（或换了台机器、删过
     * {@code config/easyelevator/arrival_sounds/}），此时他本机没有这份音频。客户端在进服后的第一刻发一个
     * {@link #ALL_SLOTS} 表示"把服务端有的都给我"，服务端逐个回 {@link DoorSoundData}。</p>
     *
     * @param slot 需要的门槽号；{@link #ALL_SLOTS} 表示"服务端有多少都发给我"
     */
    public record RequestDoorSound(int slot) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:request_door_sound}。 */
        public static final Id<RequestDoorSound> ID = new Id<>(Easyelevator.id("request_door_sound"));
        /** {@link #slot()} 的哨兵值：请求"服务端现有的全部门槽音频"（进服补齐用）。 */
        public static final int ALL_SLOTS = -1;
        /** 线格式编解码器：一个定长标量。 */
        public static final PacketCodec<RegistryByteBuf,RequestDoorSound> CODEC = new PacketCodec<>() {
            /** 读回一次请求。 */
            @Override public RequestDoorSound decode(RegistryByteBuf buf) { return new RequestDoorSound(buf.readVarInt()); }
            /** 写出一次请求。 */
            @Override public void encode(RegistryByteBuf buf,RequestDoorSound p) { buf.writeVarInt(p.slot()); }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 读一个坐标列表：先读 VarInt 数量并做上限校验，再逐个读 BlockPos，最后复制成不可变列表。
     *
     * @param buf 读缓冲
     * @param what 出错信息里用的名字（仅用于排错）
     * @return 不可变坐标列表
     */
    private static List<BlockPos> readPositions(RegistryByteBuf buf,String what) {
        int size=buf.readVarInt();
        // 数量在循环之前校验：负数会让循环直接不执行，超大值则可能在分配阶段就耗尽内存。
        if(size<0 || size>MAX_STOPS) throw new IllegalArgumentException("Invalid elevator "+what+" count");
        var list=new ArrayList<BlockPos>(Math.min(size,64));
        for(int i=0;i<size;i++) list.add(buf.readBlockPos());
        return List.copyOf(list);
    }
    /**
     * 写一个坐标列表。
     *
     * @param buf 写缓冲
     * @param list 坐标列表
     */
    private static void writePositions(RegistryByteBuf buf,List<BlockPos> list) {
        buf.writeVarInt(list.size()); for(BlockPos pos:list) buf.writeBlockPos(pos);
    }
    /**
     * 请求停靠（客户端 -> 服务端）：玩家在站层面板里点选的站点。
     *
     * <p>这是轿厢内面板唯一的 C2S 输入。包内只允许出现"哪个轿厢 + 哪个站点坐标"，
     * 因为服务端可以完全自主地重新推导其它一切（轿厢是否属于该玩家、站点是否在当前线路上）。
     *
     * @param entityId 轿厢实体 id，由服务端在 {@link OpenPanel} 中给出；服务端仍会重新核对
     * @param button 被点击的站点，即楼层门根方块的坐标（保留历史字段名 button，与旧版按钮语义兼容）
     */
    public record SelectStop(int entityId, BlockPos button) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:select_stop}。 */
        public static final Id<SelectStop> ID = new Id<>(Easyelevator.id("select_stop"));
        /** 线格式编解码器：一段 VarInt 实体 id 加一个 BlockPos。 */
        public static final PacketCodec<RegistryByteBuf,SelectStop> CODEC = new PacketCodec<>() {
            /** 读回一次点击；此处不做语义校验，全部推迟到服务端处理器里带上世界上下文再判断。 */
            @Override public SelectStop decode(RegistryByteBuf buf) { return new SelectStop(buf.readVarInt(),buf.readBlockPos()); }
            /** 写出一次点击。 */
            @Override public void encode(RegistryByteBuf buf,SelectStop p) { buf.writeVarInt(p.entityId);buf.writeBlockPos(p.button); }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 注册全部自定义负载与交互事件；由模组初始化时调用一次。
     *
     * <p>副作用：注册 7 个 S2C / 7 个 C2S 负载类型、7 个服务端全局接收器
     * （另有 {@link RiderMove#register()} 注册它自己的一对负载），
     * 并注册两个右键事件（对空处使用物品、对方块使用物品），两者都会打开轿厢内的站层面板。
     * 负载类型必须先于任何收发注册，否则客户端与服务端会因缺少 id 而断连。
     */
    public static void register() {
        RiderMove.register();
        PayloadTypeRegistry.playS2C().register(MotionFrame.ID,MotionFrame.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenPanel.ID,OpenPanel.CODEC);
        PayloadTypeRegistry.playS2C().register(PanelState.ID,PanelState.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenHallPanel.ID,OpenHallPanel.CODEC);
        PayloadTypeRegistry.playS2C().register(HallPanelState.ID,HallPanelState.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenDoorPanel.ID,OpenDoorPanel.CODEC);
        PayloadTypeRegistry.playS2C().register(DoorSoundData.ID,DoorSoundData.CODEC);
        PayloadTypeRegistry.playC2S().register(SelectStop.ID,SelectStop.CODEC);
        PayloadTypeRegistry.playC2S().register(DoorCommand.ID,DoorCommand.CODEC);
        PayloadTypeRegistry.playC2S().register(HallCallButton.ID,HallCallButton.CODEC);
        PayloadTypeRegistry.playC2S().register(DoorSoundCommand.ID,DoorSoundCommand.CODEC);
        PayloadTypeRegistry.playC2S().register(SetBaseFloor.ID,SetBaseFloor.CODEC);
        PayloadTypeRegistry.playC2S().register(DoorSoundUpload.ID,DoorSoundUpload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestDoorSound.ID,RequestDoorSound.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SelectStop.ID,(payload,context)->context.server().execute(()->{
            // 处理器在网络线程被调用，而实体/方块查询必须在服务端主线程执行，故整体切回主线程。
            var player=context.player();
            // 旁观者或已死亡玩家不应能操作轿厢；这里再挡一次，避免只靠客户端 ui 状态。
            if (player.isSpectator() || !player.isAlive()) return;
            var entity=player.getServerWorld().getEntityById(payload.entityId());
            // Never trust a client-provided cabin, station index, distance or line identifier.
            // 客户端可伪造任意实体 id：必须确认它在本玩家世界内、确实是轿厢、且本玩家是车上乘客。
            if (!(entity instanceof AbstractCabinEntity cabin) || !cabin.containsPassenger(player)) return;
            // 坐标同样不可信，交由轿厢对照真实线路校验（不在线路上或朝向不符时返回 false）。
            boolean accepted=cabin.requestStop(payload.button());
            player.sendMessage(Text.translatable(accepted?"message.easyelevator.selected":"message.easyelevator.invalid_stop"),true);
            // Refresh so buttons placed/broken while the screen was open are reflected immediately.
            // 重新下发面板：界面停留期间可能有人增删了站点，用服务端最新快照覆盖客户端旧列表。
            open(player,cabin);
        }));
        // 面板上的"开门 / 关门"键：与选站同样不信任客户端，服务端重新定位轿厢、核对乘客身份，
        // 开门还要求车体精确停在某个完整站点（AbstractCabinEntity.doorCommand 内部用线路站点列表校验）。
        ServerPlayNetworking.registerGlobalReceiver(DoorCommand.ID,(payload,context)->context.server().execute(()->{
            var player=context.player();
            if (player.isSpectator() || !player.isAlive()) return;
            var entity=player.getServerWorld().getEntityById(payload.entityId());
            if (!(entity instanceof AbstractCabinEntity cabin) || !cabin.containsPassenger(player)) return;
            if (cabin.doorCommand(payload.open())) open(player,cabin); // 成功后刷一次面板：计划可能已经变了
            else player.sendMessage(Text.translatable(payload.open()
                    ?"message.easyelevator.door_no_station":"message.easyelevator.door_close_locked"),true);
        }));
        // 楼层门面板上的"上行 / 下行"按钮：坐标不可信，服务端重新扫描线路、核对"恰好一辆轿厢"后登记厅外呼叫；
        // 登记完立刻把该站最新的点亮状态回推给附近客户端（含按按钮的这位），按钮当场变红。
        ServerPlayNetworking.registerGlobalReceiver(HallCallButton.ID,(payload,context)->context.server().execute(()->{
            var player=context.player();
            if (player.isSpectator() || !player.isAlive()) return;
            var world=player.getServerWorld();
            BlockPos origin=payload.station();
            if (!world.isChunkLoaded(origin)) return;
            BlockState state=world.getBlockState(origin);
            if (!LandingDoorBlock.isRoot(state) || !LandingDoorBlock.complete(world,origin)) { player.sendMessage(Text.translatable("message.easyelevator.invalid_stop"),true); return; }
            ElevatorLine line=ElevatorLine.scan(world,LandingDoorBlock.railPos(state,origin));
            if (line==null || line.facing()!=state.get(LandingDoorBlock.FACING) || !line.stops().contains(origin)) { player.sendMessage(Text.translatable("message.easyelevator.invalid_stop"),true); return; }
            var cabins=line.cabins(world);
            if (cabins.isEmpty()) { player.sendMessage(Text.translatable("message.easyelevator.no_cabin"),true); return; }
            if (cabins.size()!=1) { player.sendMessage(Text.translatable("message.easyelevator.multiple_cabins"),true); return; }
            var cabin=cabins.getFirst();
            boolean accepted=cabin.requestHallCall(origin,payload.up());
            player.sendMessage(Text.translatable(accepted?"message.easyelevator.hall_queued":"message.easyelevator.invalid_stop",
                    Text.translatable(payload.up()?"screen.easyelevator.hall_up":"screen.easyelevator.hall_down")),true);
            syncHallState(world,origin,cabin.hasHallCall(origin,true),cabin.hasHallCall(origin,false));
        }));
        // 门专属设置面板上的任意一次修改（开关 / 换音效 / 试听）：坐标不可信，服务端重新定位整扇门，
        // 把改动落进方块实体 NBT（随区块存档），然后把最新快照回推给所有正在看这扇门的人（全服同步）。
        ServerPlayNetworking.registerGlobalReceiver(DoorSoundCommand.ID,(payload,context)->context.server().execute(()->{
            var player=context.player();
            if (player.isSpectator() || !player.isAlive()) return;
            var world=player.getServerWorld();
            BlockPos origin=payload.station();
            LandingDoorBlockEntity door=doorEntity(world,origin);
            if (door==null) { player.sendMessage(Text.translatable("message.easyelevator.invalid_stop"),true); return; }
            DoorArrivalSound settings=door.arrivalSound();
            // choice < 0（CHOICE_UNCHANGED）表示"本次只改开关、不动选项"；否则整份替换。
            settings=settings.withEnabled(payload.enabled())
                    .withChoice(payload.choice()<0?settings.choice():payload.choice());
            door.setArrivalSound(settings);
            // 试听：由服务端以权威音效当场播一次，让玩家立刻听到自己刚选的东西（客户端只负责解码播放）。
            int preview=playArrivalPreview(world,origin,door,payload.preview());
            broadcastDoorPanel(world,origin,preview);
        }));
        // 面板上的"设为基准层"按钮：行为与旧版的"潜行右键门"完全一致（见 LandingDoorBlock#setFloorBase），
        // 只是入口从右键组合挪进了设置界面。
        ServerPlayNetworking.registerGlobalReceiver(SetBaseFloor.ID,(payload,context)->context.server().execute(()->{
            var player=context.player();
            if (player.isSpectator() || !player.isAlive()) return;
            LandingDoorBlock.setFloorBase(player.getServerWorld(),payload.station(),player,payload.on());
        }));
        // 自定义到站音效上传：客户端携带的字节视为不可信输入——重新校验玩家、整扇门、ogg 魔数与大小上限，
        // 通过后才写入权威副本目录。落盘失败只回一条提示（音效是表现层，不该把服务端刻带崩）。
        ServerPlayNetworking.registerGlobalReceiver(DoorSoundUpload.ID,(payload,context)->context.server().execute(()->{
            var player=context.player();
            if (player.isSpectator() || !player.isAlive()) return;
            byte[] bytes=payload.bytes();
            if (bytes.length==0 || bytes.length>MAX_AUDIO_BYTES) { player.sendMessage(Text.translatable("message.easyelevator.door_sound_upload_failed"),true); return; }
            var world=player.getServerWorld();
            BlockPos origin=payload.station();
            LandingDoorBlockEntity door=doorEntity(world,origin);
            if (door==null) { player.sendMessage(Text.translatable("message.easyelevator.invalid_stop"),true); return; }
            int slot=door.soundSlot();
            if (!DoorSoundPersistence.store(slot,bytes)) {
                player.sendMessage(Text.translatable("message.easyelevator.door_sound_upload_failed"),true); return;
            }
            // 把落点写进日志：槽位号是坐标哈希出来的，玩家在 config 里看到的文件名（arrival_<槽>.ogg）
            // 只有配合这一行才能对上号——排查"上传了但没声"时很有用。
            org.slf4j.LoggerFactory.getLogger("easyelevator/doorsound").info(
                    "Stored arrival sound for door {} as slot {} -> {}",origin.toShortString(),slot,DoorSoundPersistence.fileName(slot));
            player.sendMessage(Text.translatable("message.easyelevator.door_sound_uploaded",
                    Text.translatable("screen.easyelevator.door_sound_row")),true);
            // 上传完切到"自定义文件"并打开：玩家不必再点两下才能听到结果。
            door.setArrivalSound(door.arrivalSound().withChoice(DoorSounds.CUSTOM).withEnabled(true));
            // 把音频内容发给站点附近所有客户端：它们各自存进自己的权威副本目录并重建资源包，
            // 于是"上传者之外的玩家"也能听到同一段音频（专用服务器上同样成立，不需要托管资源包 URL）。
            var data=new DoorSoundData(slot,bytes);
            for (ServerPlayerEntity observer : PlayerLookup.around(world,origin,64d)) ServerPlayNetworking.send(observer,data);
            int preview=playArrivalPreview(world,origin,door,true);
            broadcastDoorPanel(world,origin,preview);
        }));
        // 客户端索取音频内容：具体槽位按需索取；ALL_SLOTS 表示"进服补齐"——把服务端现有的全部门槽音频
        // 都发给它。这是"上传者以外的玩家、以及上传之后才进服的人"能听到同一段音频的唯一途径，
        // 因此专用服务器上同样全服一致，不需要另外托管资源包 URL。
        // 鉴权只需"非旁观且存活"：音效是公开的表现层数据（谁都能在同一站听到），
        // 且总量被 MAX_SLOTS 与单文件上限钉死，一次补齐最多几十条包。
        ServerPlayNetworking.registerGlobalReceiver(RequestDoorSound.ID,(payload,context)->context.server().execute(()->{
            var player=context.player();
            if (player.isSpectator() || !player.isAlive()) return;
            if (payload.slot()!=RequestDoorSound.ALL_SLOTS) {
                ServerPlayNetworking.send(player,new DoorSoundData(payload.slot(),DoorSoundPersistence.read(payload.slot())));
                return;
            }
            // 进服补齐：最多 MAX_SLOTS 个门槽，读不到的槽位直接跳过（不回空包，省一半流量）。
            for (String stem : DoorSoundPersistence.listStems()) {
                int slot=DoorSounds.slotOfStem(stem);
                if (slot<0) continue; // 文件名异常：跳过，不猜
                byte[] bytes=DoorSoundPersistence.read(slot);
                if (bytes.length>0) ServerPlayNetworking.send(player,new DoorSoundData(slot,bytes));
            }
        }));
        UseItemCallback.EVENT.register((player,world,hand)->{
            // 只看主手：副手会随主手重复触发；潜行 + 空手是拆除轿厢的保留组合，不能与之抢事件。
            if (hand!=Hand.MAIN_HAND || player.isSpectator() || player.isSneaking()) return TypedActionResult.pass(player.getStackInHand(hand));
            // 用玩家自身碰撞箱查询，等价于"玩家是否站在轿厢内部的 3x3 空间里"。
            for (var cabin:world.getEntitiesByClass(AbstractCabinEntity.class,player.getBoundingBox(),c->c.containsPassenger(player))) {
                // 仅在逻辑服务端发包；客户端返回成功即可，否则会收到重复面板。
                if (!world.isClient) open((ServerPlayerEntity)player,cabin);
                return TypedActionResult.success(player.getStackInHand(hand));
            }
            return TypedActionResult.pass(player.getStackInHand(hand));
        });
        UseBlockCallback.EVENT.register((player,world,hand,hit)->{
            // 与上面的 UseItem 路径互补：视线命中方块时走这里，未命中方块时走上一个回调。
            // 条件必须完全一致，否则"对着方块右键"与"对着空气右键"行为会分叉。
            if (hand!=Hand.MAIN_HAND || player.isSpectator() || player.isSneaking()) return ActionResult.PASS;
            for (var cabin:world.getEntitiesByClass(AbstractCabinEntity.class,player.getBoundingBox(),c->c.containsPassenger(player))) {
                if (!world.isClient) open((ServerPlayerEntity)player,cabin);
                return ActionResult.SUCCESS;
            }
            // PASS 而非 SUCCESS：没站在轿厢里时必须把这次使用让给楼层门/轨道等其它方块逻辑。
            return ActionResult.PASS;
        });
    }
    /**
     * 把某个站点的厅外呼叫点亮状态推给附近客户端（登记、到站清扫、门被拆时各发一次）。
     *
     * <p>只发给站点附近 64 格内的玩家：面板是在门口打开的，按按钮的人一定在这个范围内，
     * 不必为一条呼叫惊动整条线路上的所有人；没开面板的客户端只更新一份缓存，不影响画面。
     *
     * @param world 世界；非服务端世界时直接返回（本方法只在服务端调用）
     * @param station 楼层门根方块位置
     * @param up 该站上行按钮是否点亮
     * @param down 该站下行按钮是否点亮
     */
    public static void syncHallState(World world,BlockPos station,boolean up,boolean down) {
        if (!(world instanceof ServerWorld server)) return;
        var payload=new HallPanelState(station.toImmutable(),up,down);
        for (ServerPlayerEntity observer : PlayerLookup.around(server,station,64d)) ServerPlayNetworking.send(observer,payload);
    }
    /**
     * 向指定玩家下发该轿厢当前线路的站层面板快照。
     *
     * <p>副作用：发送一个 {@link OpenPanel} 包（客户端收到后打开或刷新界面）。
     *
     * @param player 目标玩家，必须是服务端玩家实体
     * @param cabin 轿厢；线路由服务端实时扫描轨道列得到（{@link AbstractCabinEntity#line()}）
     */
    public static void open(ServerPlayerEntity player,AbstractCabinEntity cabin) {
        // line 可能为 null：轨道被破坏或区块未加载时，仍要让界面正常打开并显示空列表，而不是报错或卡住。
        var line=cabin.line();
        // limit(MAX_STOPS) 与解码端的上限校验配对；正常线路的站点数远小于该值，这里是防御性截断。
        ServerPlayNetworking.send(player,new OpenPanel(cabin.getId(),
                line==null?List.of():line.stops().stream().limit(MAX_STOPS).toList(),
                cabin.plannedStops(), cabin.baseFloorY()));
    }
    /**
     * 取某个楼层门根方块对应的方块实体；不是完整的楼层门时返回 null。
     *
     * <p>共用校验：坐标来自客户端、一律不可信，因此所有门设置相关的处理器都要先过这一关
     * （整扇 9 格齐全才认，残缺的门没有可设置的站点语义）。
     *
     * @param world 服务端世界
     * @param origin 候选根方块坐标
     * @return 门的方块实体；不是本模组楼层门的根方块或整扇门不完整时为 null
     */
    private static LandingDoorBlockEntity doorEntity(ServerWorld world,BlockPos origin) {
        if (!world.isChunkLoaded(origin)) return null;
        if (!LandingDoorBlock.isRoot(world.getBlockState(origin))) return null;
        if (!LandingDoorBlock.complete(world,origin)) return null;
        return world.getBlockEntity(origin) instanceof LandingDoorBlockEntity door ? door : null;
    }
    /**
     * 在站点位置试听一次到站提示音（服务端权威播放）。
     *
     * <p>为什么由服务端播而不是客户端自己播：音色由"这门选的选项 + 服务端的权威数据"决定，
     * 让客户端自己拼一份很容易和服务端分叉；服务端播一次，客户端只负责解码，听到的必然就是到站会响的那一声。
     *
     * @param world 服务端世界
     * @param origin 站点根方块坐标
     * @param door 该门的方块实体
     * @param wanted 玩家是否要求了试听；false 时直接返回 {@link OpenDoorPanel#PREVIEW_NONE}
     * @return {@link OpenDoorPanel#PREVIEW_NONE} 或 {@link OpenDoorPanel#PREVIEW_PLAYED}
     */
    private static int playArrivalPreview(ServerWorld world,BlockPos origin,LandingDoorBlockEntity door,boolean wanted) {
        if (!wanted) return OpenDoorPanel.PREVIEW_NONE;
        net.minecraft.sound.SoundEvent event=door.arrivalEvent();
        if (event==null) return OpenDoorPanel.PREVIEW_NONE; // 开关关着、或自定义槽位还没有音频
        world.playSound(null,origin.getX()+.5,origin.getY()+.5,origin.getZ()+.5,event,
                net.minecraft.sound.SoundCategory.BLOCKS,ElevatorParameters.EVENT_VOLUME,ElevatorParameters.SOUND_PITCH);
        return OpenDoorPanel.PREVIEW_PLAYED;
    }
    /**
     * 把某扇门的设置面板快照推给该站附近所有玩家（打开面板时、以及任何人改了设置之后）。
     *
     * <p><b>全服同步就落在这里</b>：一个人在面板上改了开关或换了音效，服务端存进方块实体之后立刻
     * 把最新快照推给附近所有人，因此别人打开同一扇门看到的就是同一个设置。</p>
     *
     * <p>范围与 {@link #syncHallState} 一致（站点 64 格内）：改提示音是本地装修行为，
     * 不必惊动全服所有维度；没开这个面板的客户端收到快照后直接丢弃。</p>
     *
     * @param world 世界
     * @param origin 站点根方块坐标
     * @param preview 本次要提示的试听状态（见 {@link OpenDoorPanel#PREVIEW_NONE} 等常量）
     */
    public static void broadcastDoorPanel(World world,BlockPos origin,int preview) {
        if (!(world instanceof ServerWorld server)) return;
        OpenDoorPanel payload=doorPanel(server,origin,preview,false);
        if (payload==null) return;
        for (ServerPlayerEntity observer : PlayerLookup.around(server,origin,64d)) ServerPlayNetworking.send(observer,payload);
    }
    /**
     * 把某扇门的设置面板快照单独发给一个玩家（潜行右键打开面板时用）。
     *
     * <p>与 {@link #broadcastDoorPanel} 共用 {@link #doorPanel} 构造快照，因此"自己点开的"与
     * "别人改完推给我的"两条路径看到的内容必然一致。
     *
     * @param player 收件玩家
     * @param origin 站点根方块坐标
     * @param preview 本次要提示的试听状态（打开面板时恒为 {@link OpenDoorPanel#PREVIEW_NONE}）
     */
    public static void sendDoorPanel(ServerPlayerEntity player,BlockPos origin,int preview) {
        if (!(player.getServerWorld() instanceof ServerWorld server)) return;
        OpenDoorPanel payload=doorPanel(server,origin,preview,true);
        if (payload!=null) ServerPlayNetworking.send(player,payload);
    }
    /**
     * 构造某扇门的设置面板快照（打开面板与"改动后刷新"两条路径的唯一来源）。
     *
     * <p>整扇门不完整时也照样构造：面板显示层号占位与开关，玩家点按钮会收到服务端的提示，
     * 比"潜行右键没反应"更好理解。只有方块实体真的不存在（区块未加载、不是根方块）时才返回 null。
     *
     * @param server 服务端世界
     * @param origin 站点根方块坐标
     * @param preview 本次要提示的试听状态
     * @param open 是否允许客户端主动打开界面
     * @return 面板快照；拿不到门时返回 null
     */
    private static OpenDoorPanel doorPanel(ServerWorld server,BlockPos origin,int preview,boolean open) {
        if (!(doorEntity(server,origin) instanceof LandingDoorBlockEntity door)) return null;
        DoorArrivalSound sound=door.arrivalSound();
        return new OpenDoorPanel(origin.toImmutable(),floorLabel(server,origin),
                sound.enabled(),sound.choice(),door.baseFloor(),door.soundSlot(),preview,open);
    }
    /**
     * 算某个站点当前的层号显示文本（例如 {@code "3"} / {@code "B1"}），供设置面板显示。
     *
     * <p>复用 {@code logic/FloorIndicator} 的同一套编号：基准层 = 1 层，其上 2、3…，其下 B1、B2…。
     * 因此设置面板上显示的层号与门框顶部、轿厢内面板、选站按钮三处永远一致，
     * 不会出现"面板说 3 层、门框说 4 层"这种分叉。线路扫不出来（轨道被拆）时回退成 {@code "--"}。
     *
     * @param world 服务端世界
     * @param origin 站点根方块坐标
     * @return 该站的层号文本；线路无效时返回 {@code "--"}
     */
    private static String floorLabel(ServerWorld world,BlockPos origin) {
        ElevatorLine line=ElevatorLine.scan(world,LandingDoorBlock.railPos(world.getBlockState(origin),origin));
        if (line==null) return FloorIndicator.format(0);
        List<BlockPos> stops=line.stops();
        int index=stops.indexOf(origin);
        if (index<0) return FloorIndicator.format(0);
        return FloorIndicator.label(index,FloorIndicator.baseIndex(stationYs(stops),baseFloorY(world,stops)));
    }
    /**
     * 取一条线路上所有站点的高度（升序），供 {@link FloorIndicator} 计算层号。
     *
     * @param stops 站点根方块列表（{@code ElevatorLine#stops()} 已按 Y 升序）
     * @return 与 {@code stops} 一一对应的高度列表
     */
    private static List<Integer> stationYs(List<BlockPos> stops) {
        List<Integer> ys=new ArrayList<>(stops.size());
        for (BlockPos stop:stops) ys.add(stop.getY());
        return ys;
    }
    /**
     * 找出这条线路的基准层高度。
     *
     * <p>基准层标记写在某一扇门的方块实体里（见 {@code LandingDoorBlockEntity#baseFloor}）；
     * 这里现扫一遍站点取它，而不是缓存：设置面板是低频操作，而"谁被标成基准层"随时可能被改，
     * 现算永远是最新的。
     *
     * @param world 服务端世界
     * @param stops 站点根方块列表
     * @return 基准层高度（格）；没有任何门带标记时返回 {@link Integer#MIN_VALUE}，
     *         即 {@link FloorIndicator#baseIndex} 会退回"最低站点 = 1 层"的默认编号
     */
    private static int baseFloorY(ServerWorld world,List<BlockPos> stops) {
        for (BlockPos stop:stops)
            if (world.getBlockEntity(stop) instanceof LandingDoorBlockEntity door && door.baseFloor()) return stop.getY();
        return Integer.MIN_VALUE;
    }
}
