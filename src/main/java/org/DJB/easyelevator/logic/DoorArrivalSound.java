package org.DJB.easyelevator.logic;

import net.minecraft.nbt.NbtCompound;

/**
 * 单扇楼层门的<b>到站提示音</b>设置：一个开关 + 一个音效选项。
 *
 * <p>在整体架构中的位置：这是"每扇门各自的到站音"这份数据的<b>不可变值对象</b>，
 * 由 {@code block/LandingDoorBlockEntity} 持有并写入区块存档，由网络层在服务端与客户端之间原样搬运。
 * 做成 record + 纯函数式 {@code withXxx} 的好处是：面板上的每一次点击都产生一个新值，
 * 服务端存档、回推快照、客户端重绘三处读到的永远是同一份自洽的数据。</p>
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code enabled}：到站时是否播放提示音。默认<b>开</b>（等于模组原有的"到站必响一次"行为），
 *       玩家可以在每扇门的面板里关掉或换成别的声音。</li>
 *   <li>{@code choice}：选项序号，见 {@link DoorSounds}。默认 {@link DoorSounds#DEFAULT}，
 *       即 {@code sounds.json} 里配置好的 {@code elevator_arrival}。</li>
 * </ul>
 *
 * <p>关键不变量：{@code choice} 永远落在 {@code [0, DoorSounds.CHOICE_COUNT)} 内——构造器与
 * {@link #readNbt} 都会调用 {@link DoorSounds#clamp}，因此存档里的坏数据、旧版本序号、
 * 畸形网络包都不会让后续代码拿到越界下标。</p>
 *
 * @param enabled 到站是否播放提示音
 * @param choice  提示音的选项序号（见 {@link DoorSounds}）
 */
public record DoorArrivalSound(boolean enabled, int choice) {
    /**
     * 出厂默认：<b>发声</b>，选项指向 {@code sounds.json} 里的 {@code elevator_arrival}。
     *
     * <p>为什么默认是开的：模组在此之前就是"到站必响一次"。若这里默认静音，升级后所有旧存档的门
     * 会突然全部安静——那是行为回退。默认开着 + 默认音效正好等于旧行为，玩家想安静就在面板里关掉。
     *
     * <p>本功能之前放置的门存档里从没写过 {@code ArrivalSound} 键，{@link #readNbt} 因此对缺失的
     * 开关回退到 {@link #enabled()}（= 发声），旧门升级后照旧会响，不需要逐扇重新设置。
     */
    public static final DoorArrivalSound DEFAULT = new DoorArrivalSound(true, DoorSounds.DEFAULT);
    /** 存档字段名（两个字段各自一个键；开关缺失 = 出厂默认的"发声"，序号缺失 = 默认音效）。 */
    private static final String KEY_ENABLED = "ArrivalSound", KEY_CHOICE = "ArrivalSoundChoice";

    /**
     * 紧凑构造器：把选项序号夹进合法区间。
     *
     * <p>为什么在构造器里消毒而不是在各个调用点：序号有两个外部来源（区块 NBT 与 C2S 网络包），
     * 只要这个 record 存在，就一定已经合法，调用方不需要（也不应该）再判一次边界。
     *
     * @param enabled 到站是否播放提示音
     * @param choice  提示音选项序号（越界自动退回默认）
     */
    public DoorArrivalSound {
        choice = DoorSounds.clamp(choice);
    }

    /**
     * 返回一份只改开关的新设置。
     *
     * @param value 新的开关
     * @return 新实例（本类不可变）
     */
    public DoorArrivalSound withEnabled(boolean value) { return new DoorArrivalSound(value, choice); }

    /**
     * 返回一份只改音效选项的新设置。
     *
     * @param value 新的选项序号
     * @return 新实例（本类不可变）
     */
    public DoorArrivalSound withChoice(int value) { return new DoorArrivalSound(enabled, value); }

    /**
     * 把设置写进方块实体 NBT（随区块存档）。
     *
     * <p><b>只写偏离出厂默认的那一侧</b>：出厂默认是"发声 + 默认音效"，因此默认设置的门一个字段都不写
     * （存档体积与加这个功能之前逐字节一致），只有玩家真的<b>关掉</b>了开关才写 {@code ArrivalSound=false}。
     * 这正是{@link #readNbt} 能把"缺失"解释成默认值的前提——若反过来写成"发声时才写 true"，
     * 关掉的设置就会因为没有字段而丢失，读回来又变成发声。
     *
     * @param nbt 目标 NBT
     */
    public void writeNbt(NbtCompound nbt) {
        if (!enabled) nbt.putBoolean(KEY_ENABLED, false);
        if (choice != DoorSounds.DEFAULT) nbt.putInt(KEY_CHOICE, choice);
    }

    /**
     * 从方块实体 NBT 读回设置；**缺失的开关按出厂默认处理（发声）**，缺失的序号按 0（默认音效）。
     *
     * <p>关键：{@code getBoolean} 的缺省值是 {@code false}，若直接用它会得出"本功能之前放置的门
     * 升级后全部变静音"——那是行为回退，不是默认值。因此这里显式区分"键不存在"与"键为 false"：
     * 前者是旧门 / 从未改过设置（→ 发声），后者才是玩家主动关掉。
     *
     * <p>兼容性说明：只有 {@link #writeNbt} 那侧的约定成立时（关掉才写 {@code false}），
     * 这个区分才是完整的。本功能早期版本的 {@code writeNbt} 恰好写反了（发声时写 {@code true}、
     * 关掉时什么都不写），那种存档里"关掉"没有留下任何痕迹，读回来会恢复成发声一次，
     * 需要在面板里再关一次。
     *
     * @param nbt 存档 NBT
     * @return 读回的设置（序号已由构造器夹进合法区间）
     */
    public static DoorArrivalSound readNbt(NbtCompound nbt) {
        boolean enabled = nbt.contains(KEY_ENABLED) ? nbt.getBoolean(KEY_ENABLED) : DEFAULT.enabled();
        return new DoorArrivalSound(enabled, nbt.getInt(KEY_CHOICE));
    }
}
