package org.DJB.easyelevator.network;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.CabinEntity;
import java.util.ArrayList;
import java.util.List;

/**
 * 电梯模组的网络层：三个自定义包 + 打开站层面板的交互事件注册，是服务端权威数据流向客户端的唯一通道。
 *
 * <p>包的方向与用途（服务端始终是唯一数据源，客户端只读）：
 * <ul>
 *   <li>{@link MotionFrame}：S2C（服务端到客户端）。轿厢的绝对 double 高度与本地乘客偏移量，
 *       只服务渲染与本地乘客镜头插值，客户端不得据此改写真实位置。</li>
 *   <li>{@link OpenPanel}：S2C。告诉客户端"打开站层面板"以及该线路的全部站点（根方块坐标）。</li>
 *   <li>{@link SelectStop}：C2S（客户端到服务端）。玩家在面板里点了一个站点，请求停靠。</li>
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
    /** Absolute doubles bypass the vanilla relative-entity packet's fixed-point position quantum. */
    /**
     * 轿厢运动帧（服务端 -> 客户端）：一帧绝对高度样本 + 该观测者自身的乘客偏移量。
     *
     * <p>用绝对 double 而非原版相对实体位置包，是为了绕开原版位置包的定点量化
     * （1/4096 格的离散步长 + 逐包加法），避免低速运行时出现台阶跳动与累计漂移。
     *
     * <p>为什么只有单帧而没有速度/轨迹：客户端 {@code client/CabinMotion} 与 {@code logic/MotionTimeline}
     * 只在"本机已收到的两个样本之间"插值，绝不做外推，因此服务端无需（也不能）下发预测信息。
     *
     * @param entityId 轿厢实体在网络层中的运行时 id，单位：无；同一存档内不保证稳定
     * @param tick 采集该样本时的世界时间，单位：刻（tick），用于把样本对齐到客户端时间轴
     * @param y 轿厢本体的绝对 Y 坐标，单位：格（方块）
     * @param riderOffset 该接收者自身 Y 与轿厢 Y 的差值，单位：格；非乘客为 {@link Double#NaN}，
     *                    表示"本机玩家不在这个轿厢里"，客户端据此禁用乘客镜头补偿
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
     * 乘客偏移量按接收者单独计算：乘客得到真实的自身 Y 与轿厢 Y 之差，非乘客得到 {@link Double#NaN}。
     *
     * @param cabin 要同步的轿厢，必须位于服务端世界
     */
    public static void syncMotion(CabinEntity cabin) {
        for (ServerPlayerEntity observer : PlayerLookup.tracking(cabin)) {
            double riderOffset = cabin.containsPassenger(observer) ? observer.getY() - cabin.getY() : Double.NaN;
            ServerPlayNetworking.send(observer, new MotionFrame(cabin.getId(), cabin.getWorld().getTime(), cabin.getY(), riderOffset));
        }
    }
    /**
     * 打开站层面板（服务端 -> 客户端）：携带轿厢实体 id、该线路全部站点的根方块坐标，
     * 以及当前的"停靠计划"（正在执行的目的站 + 排队中的站点）。
     *
     * <p>站点列表来自服务端 {@code ElevatorLine#stops()}，即"完整 3x3 楼层门的底部中心方块"位置，
     * 客户端只用它渲染按钮并原样回传坐标，不做任何线路推断。计划列表只用于把已加入计划的按钮标红。
     *
     * @param entityId 轿厢实体 id；客户端点击后必须原样回传，服务端据此重新定位轿厢
     * @param stops 线路上的站点（楼层门根方块）列表，元素个数不超过 {@link #MAX_STOPS}
     * @param planned 停靠计划：目的站在前、排队站点随后，都用根方块坐标表示；只影响高亮
     */
    public record OpenPanel(int entityId, List<BlockPos> stops, List<BlockPos> planned) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:open_panel}。 */
        public static final Id<OpenPanel> ID = new Id<>(Easyelevator.id("open_panel"));
        /** 线格式编解码器；解码时对站点数量做上限校验，见下。 */
        public static final PacketCodec<RegistryByteBuf,OpenPanel> CODEC = new PacketCodec<>() {
            /** 读回面板内容：先用 VarInt 读数量并校验范围，再逐个读 BlockPos。 */
            @Override public OpenPanel decode(RegistryByteBuf buf) {
                int id=buf.readVarInt();
                // 数量在循环之前校验：负数会让循环直接不执行，超大值则可能在分配阶段就耗尽内存。
                List<BlockPos> stops=readPositions(buf,"station");
                List<BlockPos> planned=readPositions(buf,"planned stop");
                // 复制成不可变列表，避免把仍在复用的读缓冲数据或可变集合泄漏给后续逻辑。
                return new OpenPanel(id,stops,planned);
            }
            /** 写出面板内容：实体 id、站点数量与坐标、计划数量与坐标。 */
            @Override public void encode(RegistryByteBuf buf, OpenPanel p) {
                buf.writeVarInt(p.entityId);
                writePositions(buf,p.stops);
                writePositions(buf,p.planned);
            }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 面板状态刷新（服务端 -> 客户端）：只携带"停靠计划"。
     *
     * <p>与 {@link OpenPanel} 的分工：本包<b>永远不会打开面板</b>，只用于刷新已经打开的面板
     * （例如某个站点已经到达、目的站被拆、队列被清理），因此可以放心地按变化推送，
     * 不会给没开面板的乘客弹出界面。
     *
     * @param entityId 轿厢实体 id；客户端只在该轿厢的面板正开着时才应用
     * @param planned 停靠计划：目的站在前、排队站点随后；空列表表示当前没有任何计划
     */
    public record PanelState(int entityId, List<BlockPos> planned) implements CustomPayload {
        /** 该负载的类型 id，注册与路由键：{@code easyelevator:panel_state}。 */
        public static final Id<PanelState> ID = new Id<>(Easyelevator.id("panel_state"));
        /** 线格式编解码器：与 OpenPanel 共用同一套坐标列表读写。 */
        public static final PacketCodec<RegistryByteBuf,PanelState> CODEC = new PacketCodec<>() {
            /** 读回一次计划快照。 */
            @Override public PanelState decode(RegistryByteBuf buf) { return new PanelState(buf.readVarInt(),readPositions(buf,"planned stop")); }
            /** 写出一次计划快照。 */
            @Override public void encode(RegistryByteBuf buf,PanelState p) { buf.writeVarInt(p.entityId);writePositions(buf,p.planned); }
        };
        /** @return 负载类型 id，框架据此把包分发到对应接收器 */
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
    /**
     * 把轿厢当前的停靠计划推送给正在追踪它的客户端。
     *
     * <p>由 {@code CabinEntity} 在"计划发生变化"的那一 tick 调用（到达、取消、新请求等），
     * 而不是每刻推送：队列变化一次最多几十字节，且只影响已经打开面板的玩家。
     *
     * @param cabin 目标轿厢
     * @param planned 停靠计划（目的站在前、排队站点随后）
     */
    public static void syncPanel(CabinEntity cabin,List<BlockPos> planned) {
        var payload=new PanelState(cabin.getId(),List.copyOf(planned));
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
     * <p>这是本模组唯一的 C2S 输入。包内只允许出现"哪个轿厢 + 哪个站点坐标"，
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
     * <p>副作用：注册 2 个 S2C / 1 个 C2S 负载类型、1 个服务端全局接收器，
     * 并注册两个右键事件（对空处使用物品、对方块使用物品），两者都会打开站层面板。
     * 负载类型必须先于任何收发注册，否则客户端与服务端会因缺少 id 而断连。
     */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(MotionFrame.ID,MotionFrame.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenPanel.ID,OpenPanel.CODEC);
        PayloadTypeRegistry.playS2C().register(PanelState.ID,PanelState.CODEC);
        PayloadTypeRegistry.playC2S().register(SelectStop.ID,SelectStop.CODEC);
        PayloadTypeRegistry.playC2S().register(DoorCommand.ID,DoorCommand.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SelectStop.ID,(payload,context)->context.server().execute(()->{
            // 处理器在网络线程被调用，而实体/方块查询必须在服务端主线程执行，故整体切回主线程。
            var player=context.player();
            // 旁观者或已死亡玩家不应能操作轿厢；这里再挡一次，避免只靠客户端 ui 状态。
            if (player.isSpectator() || !player.isAlive()) return;
            var entity=player.getServerWorld().getEntityById(payload.entityId());
            // Never trust a client-provided cabin, station index, distance or line identifier.
            // 客户端可伪造任意实体 id：必须确认它在本玩家世界内、确实是轿厢、且本玩家是车上乘客。
            if (!(entity instanceof CabinEntity cabin) || !cabin.containsPassenger(player)) return;
            // 坐标同样不可信，交由轿厢对照真实线路校验（不在线路上或朝向不符时返回 false）。
            boolean accepted=cabin.requestStop(payload.button());
            player.sendMessage(Text.translatable(accepted?"message.easyelevator.selected":"message.easyelevator.invalid_stop"),true);
            // Refresh so buttons placed/broken while the screen was open are reflected immediately.
            // 重新下发面板：界面停留期间可能有人增删了站点，用服务端最新快照覆盖客户端旧列表。
            open(player,cabin);
        }));
        // 面板上的"开门 / 关门"键：与选站同样不信任客户端，服务端重新定位轿厢、核对乘客身份，
        // 开门还要求车体精确停在某个完整站点（CabinEntity.doorCommand 内部用线路站点列表校验）。
        ServerPlayNetworking.registerGlobalReceiver(DoorCommand.ID,(payload,context)->context.server().execute(()->{
            var player=context.player();
            if (player.isSpectator() || !player.isAlive()) return;
            var entity=player.getServerWorld().getEntityById(payload.entityId());
            if (!(entity instanceof CabinEntity cabin) || !cabin.containsPassenger(player)) return;
            if (cabin.doorCommand(payload.open())) open(player,cabin); // 成功后刷一次面板：计划可能已经变了
            else player.sendMessage(Text.translatable(payload.open()
                    ?"message.easyelevator.door_no_station":"message.easyelevator.door_close_locked"),true);
        }));
        UseItemCallback.EVENT.register((player,world,hand)->{
            // 只看主手：副手会随主手重复触发；潜行 + 空手是拆除轿厢的保留组合，不能与之抢事件。
            if (hand!=Hand.MAIN_HAND || player.isSpectator() || player.isSneaking()) return TypedActionResult.pass(player.getStackInHand(hand));
            // 用玩家自身碰撞箱查询，等价于"玩家是否站在轿厢内部的 3x3 空间里"。
            for (var cabin:world.getEntitiesByClass(CabinEntity.class,player.getBoundingBox(),c->c.containsPassenger(player))) {
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
            for (var cabin:world.getEntitiesByClass(CabinEntity.class,player.getBoundingBox(),c->c.containsPassenger(player))) {
                if (!world.isClient) open((ServerPlayerEntity)player,cabin);
                return ActionResult.SUCCESS;
            }
            // PASS 而非 SUCCESS：没站在轿厢里时必须把这次使用让给楼层门/轨道等其它方块逻辑。
            return ActionResult.PASS;
        });
    }
    /**
     * 向指定玩家下发该轿厢当前线路的站层面板快照。
     *
     * <p>副作用：发送一个 {@link OpenPanel} 包（客户端收到后打开或刷新界面）。
     *
     * @param player 目标玩家，必须是服务端玩家实体
     * @param cabin 轿厢；线路由服务端实时扫描轨道列得到（{@link CabinEntity#line()}）
     */
    public static void open(ServerPlayerEntity player,CabinEntity cabin) {
        // line 可能为 null：轨道被破坏或区块未加载时，仍要让界面正常打开并显示空列表，而不是报错或卡住。
        var line=cabin.line();
        // limit(MAX_STOPS) 与解码端的上限校验配对；正常线路的站点数远小于该值，这里是防御性截断。
        ServerPlayNetworking.send(player,new OpenPanel(cabin.getId(),
                line==null?List.of():line.stops().stream().limit(MAX_STOPS).toList(),
                cabin.plannedStops()));
    }
}
