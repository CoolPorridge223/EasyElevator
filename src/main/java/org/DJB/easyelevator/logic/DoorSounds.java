package org.DJB.easyelevator.logic;

import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import org.DJB.easyelevator.Easyelevator;

/**
 * 门音效目录：<b>某一扇门的"到站提示音"</b>能用的全部选项，以及"选项序号 → 音效事件 / 音频文件名"的换算。
 *
 * <p>在整体架构中的位置：这是一个<b>纯目录/常量表</b>，不含任何世界或网络逻辑。服务端把"选了第几号音效"
 * 存进方块实体（见 {@code block/LandingDoorBlockEntity} 的 NBT），运行时由 {@link #arrivalEvent} 把序号
 * 翻译成 {@link SoundEvent} 播放；客户端只把序号画成文字（{@link #presetId}）。
 * 因为翻译只依赖一张固定的表，服务端与客户端不必交换音效 ID——<b>序号本身就是协议</b>。</p>
 *
 * <p>三类选项，序号区间即分类：
 * <ul>
 *   <li><b>默认</b>（序号 {@link #DEFAULT} = 0）：本模组 {@code sounds.json} 里的
 *       {@code easyelevator:elevator_arrival}。用资源包替换这个 ID 就能一次性改掉所有"默认"的门。</li>
 *   <li><b>预设原版音效</b>（序号 1..n）：玩家不提供任何文件也能一键换上的钟/铃/音符盒等原版音效，
 *       任何客户端都有，无需资源包，多人服务器上天然全服一致。</li>
 *   <li><b>自定义文件</b>（序号 {@link #CUSTOM}）：由玩家上传本地 {@code .ogg}，模组把它写进运行时资源包
 *       {@code resourcepacks/easyelevator_custom/}。这些 ID 刻意不写进模组自带的 {@code sounds.json}
 *       （槽位数量取决于玩家实际配了多少扇门，编译期枚举不出来），而是由资源包自己声明：
 *       原版 {@code SoundManager} 按 ID 到"已加载音效表"里查，<b>资源包在就响、不在就静音</b>，
 *       两条路径都不会崩。</li>
 * </ul>
 *
 * <p>关键不变量：{@link #arrivalEvent(int, int)} 对任意 int 都能安全返回一个音效
 * （越界一律退回默认），因为序号来自存档 NBT 与网络包，两者都可能是旧版本或损坏的数据。</p>
 */
public final class DoorSounds {
    /** 工具类，禁止实例化。 */
    private DoorSounds() { }

    /** 默认选项的序号：本模组 {@code sounds.json} 中已配置好的 {@code easyelevator:elevator_arrival}。 */
    public static final int DEFAULT = 0;
    /** 自定义文件的序号：播放上传到运行时资源包里的音频；没有上传文件时为静音。 */
    public static final int CUSTOM = 1;

    /**
     * 上传音频的"门槽"数量上限：每个槽位对应一个独立音效事件。
     *
     * <p>为什么要分槽：一个音效事件在 {@code sounds.json} 里只能绑一条音频，而每扇门要各自换音频，
     * 所以每扇上传过音频的门占用一个槽位；槽位号存在门的方块实体里（{@code DoorSoundUpload} 协议、
     * 资源包文件名、事件 ID 三处都用它）。64 个槽位足够任何规模的建筑，同时把运行时资源包的体积
     * 与内存占用钉在可预期的范围内。
     */
    public static final int MAX_SLOTS = 64;

    /**
     * 预设原版音效的 ID 表（都适合做"到站提示"）。
     *
     * <p>用不存在的 ID（例如拼错）只会静音，不会崩，因此这张表可以放心扩充。
     */
    private static final String[] ARRIVAL_PRESETS = {
            "minecraft:block.bell.resonate",
            "minecraft:block.amethyst_block.chime",
            "minecraft:block.note_block.bell",
            "minecraft:block.note_block.pling",
            "minecraft:entity.experience_orb.pickup",
            "minecraft:entity.ender_eye.launch",
    };

    /** 第一个预设的序号：预设按序号 {@code PRESET_BASE + 下标} 排列，紧跟在 {@link #DEFAULT} 与 {@link #CUSTOM} 之后。 */
    public static final int PRESET_BASE = 2;
    /** 可选项总数（含默认与自定义）：序号合法区间是 {@code [0, CHOICE_COUNT)}。 */
    public static final int CHOICE_COUNT = PRESET_BASE + ARRIVAL_PRESETS.length;

    /**
     * 由方块坐标推导门槽号（{@code [0, MAX_SLOTS)}）。
     *
     * <p><b>为什么不能直接用 {@code BlockPos.hashCode() % MAX_SLOTS}</b>：那个 hashCode 是
     * {@code (y + x*z) * 31 + z} 这种形式，对"排在同一条线上、只差 y"的门分布极差——
     * 恰好是电梯门的典型布局，结果就是很多门挤到少数几个槽位上、互相覆盖音频。
     * 这里先把三个坐标按大步长混进一个 long，再做一次 murmur3 的收尾混合
     * （xor 移位 + 乘魔数），雪崩效应足够好，96 扇门撞进 64 个槽的期望冲突数远低于前者。
     *
     * <p>为什么由坐标推导而不是存一个自增号：自增号需要一份"全局分配表"，那张表本身就是新的存档状态
     * （谁先申请、拆了要不要回收、读档时会不会错位）。坐标哈希是<b>纯函数</b>——只要门还在原地，
     * 槽位就永远不变，拆了再放回来也还是同一个槽位，服务端与客户端算出的结果天然一致，
     * 不需要同步也不需要持久化。
     *
     * @param x 门根方块的 X
     * @param y 门根方块的 Y
     * @param z 门根方块的 Z
     * @return {@code [0, MAX_SLOTS)} 内的槽位号；同一扇门恒定不变
     */
    public static int slotFor(int x, int y, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (int) Math.floorMod(h, (long) MAX_SLOTS);
    }

    /**
     * 把槽位号夹到合法区间。
     *
     * @param slot 任意整数（来自存档或网络包）
     * @return {@code [0, MAX_SLOTS)} 内的槽位号
     */
    public static int clampSlot(int slot) { return slot < 0 ? 0 : Math.min(slot, MAX_SLOTS - 1); }

    /**
     * 某个门槽对应的自定义到站音效事件 ID。
     *
     * <p><b>服务端与客户端必须用同一套名字</b>：服务端拿它发包播放，客户端的运行时资源包拿它写
     * {@code sounds.json}（见 {@code client/DoorSoundPack}）。所以计算放在这个共用类里，两边都调它。
     *
     * @param slot 门槽号（内部会夹到 {@code [0, MAX_SLOTS)}）
     * @return 形如 {@code easyelevator:elevator_arrival_custom_3} 的标识符
     */
    public static Identifier soundId(int slot) {
        return Easyelevator.id("elevator_arrival_custom_" + clampSlot(slot));
    }

    /**
     * @param slot 门槽号
     * @return 该槽位的到站音效事件（见 {@link #soundId}）
     */
    public static SoundEvent slotEvent(int slot) { return SoundEvent.of(soundId(slot)); }

    /**
     * 某个槽位的音频文件名主干（不含目录与扩展名）。
     *
     * <p><b>这是"磁盘上的音频文件"与"音效事件 ID"之间唯一的对应规则</b>，因此必须放在共用类里：
     * 服务端写权威副本用它（{@code logic/DoorSoundPersistence}），客户端写运行时资源包也用它
     * （{@code client/DoorSoundPack}），两边文件名一致才能"服务端存、客户端放"。
     * 对应关系是 {@code elevator_arrival_custom_3} ⟷ {@code arrival_3.ogg}。
     *
     * @param slot 槽位号（内部夹到合法区间）
     * @return 形如 {@code arrival_3} 的文件名主干
     */
    public static String fileStem(int slot) { return "arrival_" + clampSlot(slot); }

    /**
     * 判断一个文件名主干（不含扩展名）是不是本模组生成的门槽音频。
     *
     * <p>用于两处：读取权威副本目录时忽略"用户自己丢进来的陌生文件"，以及重建资源包时
     * <b>只删自己写过的文件</b>（模组自带资源里也有同名的 {@code sounds/} 目录，粗暴清空会误删）。
     *
     * @param stem 文件名主干
     * @return 形如 {@code arrival_0} / {@code arrival_63} 时为 true
     */
    public static boolean isStem(String stem) {
        if (stem == null || !stem.startsWith("arrival_")) return false;
        String digits = stem.substring("arrival_".length());
        if (digits.isEmpty() || digits.length() > 2) return false;
        for (int i = 0; i < digits.length(); i++) if (digits.charAt(i) < '0' || digits.charAt(i) > '9') return false;
        return Integer.parseInt(digits) < MAX_SLOTS;
    }

    /**
     * 把文件名主干反解成槽位号（{@link #isStem} 的逆运算）。
     *
     * @param stem 文件名主干
     * @return 槽位号；不是本模组的文件名时返回 -1
     */
    public static int slotOfStem(String stem) {
        if (!isStem(stem)) return -1;
        return Integer.parseInt(stem.substring("arrival_".length()));
    }

    /**
     * 把选项序号翻译成要播放的音效事件——服务端唯一的解释入口。
     *
     * @param choice 选项序号；越界（旧存档、畸形网络包）时退回 {@link #DEFAULT}
     * @param slot   {@link #CUSTOM} 选项使用的门槽号（其他选项忽略它）；越界会夹到合法区间
     * @return 对应的音效事件；默认分支返回本模组的 {@code elevator_arrival}，
     *         因此<b>默认行为与加这个功能之前完全一致</b>
     */
    public static SoundEvent arrivalEvent(int choice, int slot) {
        if (choice == CUSTOM) return slotEvent(slot);
        int index = choice - PRESET_BASE;
        if (index >= 0 && index < ARRIVAL_PRESETS.length) {
            Identifier id = Identifier.tryParse(ARRIVAL_PRESETS[index]);
            if (id != null) return SoundEvent.of(id); // of() 不写注册表，纯 ID 包装；客户端按 ID 查已加载音效
        }
        return Easyelevator.ARRIVAL;
    }

    /**
     * 把选项序号夹到合法区间，供读存档与解码网络包时统一消毒。
     *
     * @param choice 任意整数
     * @return {@code [0, CHOICE_COUNT)} 内的序号；非法值一律变成 {@link #DEFAULT}
     */
    public static int clamp(int choice) { return choice >= 0 && choice < CHOICE_COUNT ? choice : DEFAULT; }

    /**
     * @param choice 选项序号
     * @return 该选项是不是"默认音效"
     */
    public static boolean isDefault(int choice) { return choice == DEFAULT; }

    /**
     * @param choice 选项序号
     * @return 该选项是不是"上传的自定义文件"
     */
    public static boolean isCustom(int choice) { return choice == CUSTOM; }

    /**
     * @param choice 选项序号
     * @return 该选项是不是预设原版音效
     */
    public static boolean isPreset(int choice) { return choice >= PRESET_BASE && choice < CHOICE_COUNT; }

    /**
     * @param choice 选项序号
     * @return 预设音效的原始 ID（例如 {@code minecraft:block.bell.resonate}）；不是预设时返回 null
     */
    public static String presetId(int choice) {
        int index = choice - PRESET_BASE;
        return isPreset(choice) ? ARRIVAL_PRESETS[index] : null;
    }
}
