package org.DJB.easyelevator.logic;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 自定义到站音效的<b>权威副本</b>存储：负责把上传的 {@code .ogg} 落到磁盘、以及把它们读回来。
 *
 * <p>在整体架构中的位置：本类是"上传的音频到底存在哪"的唯一答案。它<b>不涉及任何 Minecraft 类</b>
 * （只用 Fabric 的 {@code FabricLoader} 拿游戏目录），因此服务端与客户端都能安全使用——
 * 这是必要的，因为：</p>
 * <ul>
 *   <li><b>服务端</b>必须能存：玩家在多人服务器上传音频时，"这份音频属于这扇门"这条事实要由服务端持有，
 *       才能把它分发给其他玩家（见 {@code network/ElevatorNetworking} 的 {@code DoorSoundData}）；</li>
 *   <li><b>客户端</b>必须能存：收到服务端分发的音频后写进自己的副本，再用它重建运行时资源包
 *       （见 {@code client/DoorSoundPack}）。</li>
 * </ul>
 *
 * <p><b>为什么目录要按 gameDir 解析</b>：单人/局域网的集成服务器与客户端<b>共用同一个进程与目录</b>，
 * 因此两边指向同一个位置，上传一次即可立刻发声；专用服务器上服务端写自己的目录、客户端写各自的目录，
 * 具体内容由网络层负责搬运。两者的根都是 Fabric 的 {@code gameDir}，所以用同一个方法解析即可。</p>
 *
 * <p>关键不变量：所有写入都先落到同目录的 {@code .tmp} 再原子替换。资源重载可能在任何时刻发生，
 * 读到写了一半的 {@code .ogg} 会让解码器报错。</p>
 */
public final class DoorSoundPersistence {
    /** 工具类，禁止实例化。 */
    private DoorSoundPersistence() { }

    /** 权威副本目录名（在 {@code <gameDir>/config/easyelevator/} 下）。 */
    public static final String STORE_DIR_NAME = "arrival_sounds";
    /** 单个音频文件的大小上限（字节），与网络层、客户端资源包三处保持一致。 */
    public static final int MAX_AUDIO_BYTES = 512 * 1024;

    /**
     * @return 权威副本目录 {@code <gameDir>/config/easyelevator/arrival_sounds}
     */
    public static Path storeDir() {
        return FabricLoader.getInstance().getGameDir().resolve("config")
                .resolve(org.DJB.easyelevator.Easyelevator.MOD_ID).resolve(STORE_DIR_NAME);
    }

    /**
     * 保存一份音频到权威副本。
     *
     * @param slot  门槽号（内部夹到合法区间，与音效 ID 的算法一致）
     * @param bytes 完整的 {@code .ogg} 内容；会重新校验魔数与大小，不信任调用方
     * @return 写入成功时为 true；内容非法或写盘失败时为 false
     */
    public static boolean store(int slot, byte[] bytes) {
        if (!isOgg(bytes) || bytes.length > MAX_AUDIO_BYTES) return false;
        try {
            Path dir = storeDir();
            Files.createDirectories(dir);
            writeAtomic(dir.resolve(fileName(slot)), bytes);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 读回一份音频。
     *
     * @param slot 门槽号
     * @return 文件内容；没有这个文件、不是 ogg 或超过上限时返回<b>空数组</b>
     *         （而不是 null：网络层要把它直接当成"这个槽位没有音频"发出去）
     */
    public static byte[] read(int slot) {
        Path file = storeDir().resolve(fileName(slot));
        try {
            if (!Files.isRegularFile(file)) return new byte[0];
            byte[] bytes = Files.readAllBytes(file);
            return isOgg(bytes) && bytes.length <= MAX_AUDIO_BYTES ? bytes : new byte[0];
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /**
     * 某个槽位的音频文件名。
     *
     * @param slot 槽位号（内部夹到合法区间）
     * @return 形如 {@code arrival_3.ogg} 的文件名
     */
    public static String fileName(int slot) { return DoorSounds.fileStem(slot) + ".ogg"; }

    /**
     * 列出权威副本目录里现存的音频文件名主干（已排序）。
     *
     * <p>用途：服务端在客户端索取时要把"自己有哪些音频"列出来；客户端启动时也用它决定
     * "需不需要重建资源包"。
     *
     * @return 文件名主干（{@code arrival_N}）列表；目录不存在时返回空列表
     */
    public static java.util.List<String> listStems() {
        Path dir = storeDir();
        if (!Files.isDirectory(dir)) return java.util.List.of();
        java.util.List<String> stems = new java.util.ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path file : entries.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".ogg")) continue;
                String stem = name.substring(0, name.length() - ".ogg".length());
                if (DoorSounds.isStem(stem)) stems.add(stem);
            }
        } catch (IOException e) {
            return java.util.List.of();
        }
        stems.sort(Comparator.naturalOrder());
        return stems;
    }

    /**
     * 判断一段字节是不是 Ogg 容器（{@code OggS} 魔数）。
     *
     * @param bytes 文件内容
     * @return 是合法 ogg 时为 true
     */
    public static boolean isOgg(byte[] bytes) {
        return bytes != null && bytes.length > 4 && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S';
    }

    /**
     * 原子写文件：先写 {@code .tmp} 再替换。
     *
     * @param file 目标文件
     * @param bytes 内容
     * @throws IOException 写盘失败
     */
    private static void writeAtomic(Path file, byte[] bytes) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // 少数文件系统不支持原子移动：退回普通替换，行为上仍然正确
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
