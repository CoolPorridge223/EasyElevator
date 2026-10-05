package org.DJB.easyelevator.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.logic.DoorSoundPersistence;
import org.DJB.easyelevator.logic.DoorSounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 运行时资源包：把玩家上传的到站音效 {@code .ogg} 变成一条原版能播放的音效。
 *
 * <p><b>为什么必须这么做</b>：Minecraft 的音效系统只认资源包里的音频，{@code SoundManager}
 * 通过资源管理器按 {@code assets/<namespace>/sounds/<path>.ogg} 取文件，没有任何"直接播放磁盘上
 * 某个音频"的接口。因此想让"用户传入文件"生效，唯一稳的路子就是把这些文件写成一个真实存在的资源包，
 * 再让客户端把它加载进来——本类负责存盘、生成包与触发重载。</p>
 *
 * <p><b>两个目录，一个事实来源</b>：
 * <ul>
 *   <li><b>权威副本</b>：{@code config/easyelevator/arrival_sounds/arrival_N.ogg}。
 *       玩家上传的原始音频存这里，是唯一不可再生的数据。</li>
 *   <li><b>资源包</b>：{@code resourcepacks/easyelevator_custom/}。它永远是权威副本的忠实投影，
 *       可以随时删掉重来（{@link #rebuild()}）。</li>
 * </ul>
 * 两边用<b>同一个文件名</b>（{@code arrival_N}），所以重建就是"照抄目录"，不需要任何映射表。</p>
 *
 * <p><b>什么时候才重载资源</b>——这是本类最容易做错的地方。原版 {@code reloadResources()} 会弹出
 * 那个红色的 Mojang "加载资源中"画面，代价高且刺眼。因此：
 * <ul>
 *   <li>{@link #rebuild()} 先把包写到临时目录，算完指纹与上次<b>相同就直接放弃</b>，连文件都不动；</li>
 *   <li>只有在"包刚被启用"或"包内容真的变了"时，才由 {@link #ensureReady} 触发一次重载。</li>
 * </ul>
 * 于是"进存档"这条最常走的路（内容没变）<b>一次都不会闪红屏</b>；只有玩家上传/换掉音频那一下才重载一次。</p>
 *
 * <p>关键不变量：写入永远先落到同目录的 {@code .tmp} 再原子替换，因此资源重载绝不会读到写了一半的
 * {@code .ogg} 或 {@code sounds.json}。</p>
 */
public final class DoorSoundPack {
    /** 工具类，禁止实例化。 */
    private DoorSoundPack() { }

    /** 日志：只在失败路径与"真的重载了"时写，正常进存档全程静默。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("easyelevator/sound-pack");
    /** 资源包目录名，同时也是包名（{@code resourcepacks/easyelevator_custom/}）。 */
    private static final String PACK_DIR_NAME = "easyelevator_custom";
    /** 构建资源包时使用的临时目录名（与正式目录同级）。 */
    private static final String STAGING_DIR_NAME = "easyelevator_custom.building";
    /** 记录"上一次真正装进资源包的内容指纹"的文件名（放在 {@code config} 下，不进包，免得自己影响指纹）。 */
    private static final String FINGERPRINT_FILE = "sound_pack.sha1";
    /** 门槽音频在包内的相对目录（相对 {@code assets/easyelevator/sounds/}）。 */
    private static final String SLOT_DIR = "arrival";
    /** 单个音频文件的大小上限（字节），与网络层、持久层三处保持一致。 */
    public static final int MAX_AUDIO_BYTES = 512 * 1024;
    /** 本包使用的资源包格式版本；与 1.21.1 的资源包格式一致，写低了原版会警告"过时包"。 */
    private static final int PACK_FORMAT = 34;
    /** {@link #checkGameDirAssumption} 的一次性标记：同一条警告不重复刷屏。 */
    private static boolean gameDirWarned;

    /**
     * 某槽位音频在包内的相对路径（不含扩展名，写法与原版 {@code sounds.json} 一致）。
     *
     * @param slot 槽位号（内部夹到合法区间）
     * @return 形如 {@code arrival/arrival_3} 的相对路径
     */
    public static String audioPath(int slot) { return SLOT_DIR + "/" + DoorSounds.fileStem(slot); }

    /**
     * 从权威副本重建资源包。
     *
     * <p><b>没有音频就根本不建包</b>：一个空的资源包除了让玩家的资源包列表里多一条莫名其妙的条目、
     * 并给"包该不该启用"制造无谓状态之外没有任何作用。权威副本里一个 {@code .ogg} 都没有时，
     * 本方法会顺手把已经存在的空包删掉并返回 false。
     *
     * <p><b>内容没变就什么都不做</b>：先把整包写到临时目录并算出指纹，与上次真正装进资源包的指纹
     * 一致时直接丢弃临时目录并返回 false。这是"每次进存档都闪一次红屏"的根治办法——
     * 那种情况下内容本来就没变，不该碰资源包。
     *
     * @return 资源包内容是否发生了变化（true 表示调用方应该重载资源）
     */
    public static boolean rebuild() {
        Path dir = packDir();
        if (dir == null) return false;
        checkGameDirAssumption(dir);
        List<String> stems = DoorSoundPersistence.listStems();
        if (stems.isEmpty()) return removePack(dir);
        Path staging = dir.resolveSibling(STAGING_DIR_NAME);
        try {
            // 1) 构建到临时目录：失败时正式目录保持原样，玩家至少还有上一版音频可用
            deleteRecursively(staging);
            Files.createDirectories(staging);
            writeString(staging.resolve("pack.mcmeta"), packMeta());
            writeFromStore(staging,stems);
            writeString(staging.resolve("assets/" + Easyelevator.MOD_ID + "/sounds.json"), soundsJson(stems));
            // 2) 指纹没变就收工（连正式目录都不动）
            String fingerprint = fingerprint(staging);
            if (fingerprint.equals(readFingerprint())) {
                deleteRecursively(staging);
                return false;
            }
            // 3) 内容变了：用临时目录整体替换正式目录，再记下新指纹
            deleteRecursively(dir);
            Files.createDirectories(dir.getParent());
            Files.move(staging, dir, StandardCopyOption.REPLACE_EXISTING);
            writeFingerprint(fingerprint);
            LOGGER.info("Elevator arrival-sound pack updated ({} audio file(s)); reloading resources.", stems.size());
            return true;
        } catch (IOException e) {
            LOGGER.warn("Could not write the elevator arrival-sound resource pack at {}", dir, e);
            return false;
        }
    }

    /**
     * 删掉运行时资源包目录与内容指纹（权威副本里已经没有音频时调用，见 {@link #rebuild}）。
     *
     * <p>为什么要连指纹一起清掉：指纹是"上一次真正装进包里的内容"的记录。包被删掉之后，
     * 这份记录必须一并作废——否则玩家下次上传音频时，新包会与一个已不存在的旧状态作比较，
     * 逻辑上就说不通了。清空后重新构建必然判为"变了"，正好需要重载一次。
     *
     * @param dir 资源包目录
     * @return 是否真的删掉了东西（= 调用方需要重载一次，让那个包从资源管理器里消失）
     */
    private static boolean removePack(Path dir) {
        boolean existed = Files.exists(dir);
        try {
            deleteRecursively(dir);
            deleteRecursively(dir.resolveSibling(STAGING_DIR_NAME)); // 构建中途留下的临时目录也一并清掉
            writeFingerprint("");
            if (existed) LOGGER.info("No custom arrival audio left; removed the elevator sound resource pack.");
            return existed;
        } catch (IOException e) {
            LOGGER.warn("Could not remove the obsolete elevator sound resource pack at {}", dir, e);
            return false;
        }
    }

    /**
     * 检查"资源包写在客户端 run 目录下"这个前提是否成立，不成立就记一条明确日志。
     *
     * <p>为什么值得检查：本模组的<b>权威副本</b>目录由 Fabric 的 {@code gameDir} 推导
     * （{@code logic/DoorSoundPersistence}，服务端与客户端共用同一套逻辑），而<b>资源包</b>目录必须落在
     * {@code MinecraftClient.runDirectory} 下才会被原版扫到。正常情况下这两个根目录是同一个；
     * 万一某个启动器把它们分开，资源包就会写到原版看不见的地方，表现是"上传成功但听不到声音"。
     *
     * @param packDir 本次要写入的资源包目录
     */
    private static void checkGameDirAssumption(Path packDir) {
        if (gameDirWarned) return;
        try {
            Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
            if (!gameDir.resolve("resourcepacks").resolve(PACK_DIR_NAME).equals(packDir)) {
                gameDirWarned = true;
                LOGGER.warn("The client run directory and Fabric gameDir differ ({} vs {}); "
                        + "custom arrival sounds may be written where the game does not look for resource packs.",
                        gameDir, packDir);
            }
        } catch (RuntimeException e) {
            gameDirWarned = true; // 拿不到 gameDir 只是少一条诊断信息，不影响写包
        }
    }

    /**
     * 把权威副本里的音频全部写进（临时）包的 {@code arrival/} 目录，并清掉本模组留下的旧音频。
     *
     * <p><b>只碰自己写过的文件名</b>（匹配 {@code arrival_N}）：模组自带资源里也有
     * {@code assets/easyelevator/sounds/} 这个目录，与包里的同名目录会合并；认错文件就会误删别人的音频。
     *
     * @param staging 临时包根目录
     * @param stems 权威副本里现有音频的文件名主干（已排序，保证输出逐字节可复现）
     * @throws IOException 读写失败
     */
    private static void writeFromStore(Path staging,List<String> stems) throws IOException {
        Path target = staging.resolve("assets/" + Easyelevator.MOD_ID + "/sounds/" + SLOT_DIR);
        for (String stem : stems) {
            int slot = DoorSounds.slotOfStem(stem);
            if (slot < 0) continue;
            byte[] bytes = DoorSoundPersistence.read(slot);
            if (bytes.length == 0) continue; // 权威副本里读不到（并发删除）：跳过，不写空文件
            writeBytes(target.resolve(stem + ".ogg"), bytes);
        }
    }

    /**
     * 确保运行时资源包处于"已启用且内容已加载"的状态。
     *
     * <p>三步：把权威副本投影成包（{@link #rebuild}）→ 确保包被启用 → 只在"刚启用"或"内容变了"时重载一次。
     * 因此<b>进存档时如果音频没变，这里一次红屏都不会闪</b>。
     *
     * @param client 客户端实例
     */
    public static void ensureReady(MinecraftClient client) {
        if (client == null) return;
        boolean contentChanged = rebuild();
        boolean justEnabled = enable(client);
        if (contentChanged || justEnabled) forceReload(client);
    }

    /**
     * 确保运行时资源包已被客户端<b>启用</b>，并把这次改动<b>写进 {@code options.txt}</b>。
     *
     * <p><b>为什么要写 options.txt</b>：{@code ResourcePackManager.enable()} 只改内存里的
     * {@code enabled} 列表，磁盘上那份"启用过哪些包"的记录（{@code options.txt} 的
     * {@code resourcePacks:} 一行）不会跟着变。结果是<b>下次启动游戏时 {@code scanPacks()} 发现本包
     * 并未启用</b>，于是又"新启用一次"、又重载一次资源——表现就是每次进存档都闪一遍红色
     * Mojang 加载画面。启用之后立刻把选项写盘，这条路径才会收敛：第二次进游戏时本包已经是
     * "已启用"，{@link #enable} 直接返回 false，一次重载都不会发生。
     *
     * <p>必须在客户端主线程调用（{@code MinecraftClient} 与其选项都不是线程安全的）。
     *
     * @param client 客户端实例
     * @return true 表示本次调用"刚刚把它启用起来"（调用方需要重载一次让它生效）
     */
    public static boolean enable(MinecraftClient client) {
        if (client == null) return false;
        Path dir = packDir();
        // 包不存在（没有音频、或还没建过）就什么都不做：更不该为了一个空目录去折腾资源包管理器
        if (dir == null || !Files.isRegularFile(dir.resolve("pack.mcmeta"))) return false;
        try {
            ResourcePackManager manager = client.getResourcePackManager();
            manager.scanPacks(); // 重新扫目录：包是运行时创建的，不扫就看不见它
            if (alreadyEnabled(manager)) return false; // 已经启用（上一次会话记住的选择）
            // 还没启用：按扫描出来的真实 profile id 启用。原版目录包的 id 前缀是实现细节，
            // 所以先从扫描结果里认出本包，认不出再退回两种约定写法。
            String id = profileId(manager);
            if (id == null) {
                manager.enable("file/" + PACK_DIR_NAME);
                if (!alreadyEnabled(manager)) manager.enable(PACK_DIR_NAME);
            } else {
                manager.enable(id);
            }
            if (!alreadyEnabled(manager)) {
                // 启用失败（包格式被判为不兼容、目录被别的东西占名）：记一条日志并返回，
                // 不抛异常——玩家仍能用预设音效，只是听不到自定义文件。
                LOGGER.warn("Found the elevator sound pack but could not enable it; custom arrival audio stays silent.");
                return false;
            }
            persistEnabled(client);
            LOGGER.info("Enabled the elevator arrival-sound resource pack; reloading resources once.");
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to enable the elevator arrival-sound resource pack", e);
            return false;
        }
    }

    /**
     * 把"启用了哪些资源包"写进 {@code options.txt}，让这次启用跨启动保留。
     *
     * <p>两步都不能少：{@code refreshResourcePacks} 把资源包管理器的启用列表同步到
     * {@code GameOptions.resourcePacks}（{@code manager.enable()} 只改管理器自己的内存列表，
     * 选项对象并不知道），{@code write()} 再把它落盘。少了前者，写出去的文件里仍然是旧列表。
     *
     * <p>失败只记日志：写不进去的后果仅仅是下次启动再启用一次（多一次重载），不影响音效可用性。
     *
     * @param client 客户端实例
     */
    private static void persistEnabled(MinecraftClient client) {
        try {
            client.options.refreshResourcePacks(client.getResourcePackManager());
            client.options.write();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not persist the enabled resource pack list", e);
        }
    }

    /**
     * 强制重载资源，让刚写进资源包的音频立刻可播。
     *
     * <p>这是本模组<b>唯一</b>会触发原版"加载资源中"红色画面的地方，因此调用点必须严格控制：
     * 只有"包刚启用"或"包内容真的变了"才允许调用。调用前要先 {@link #rebuild()}。
     *
     * @param client 客户端实例
     */
    public static void forceReload(MinecraftClient client) {
        if (client == null) return;
        try {
            client.reloadResources();
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to reload resources after changing the elevator arrival sounds", e);
        }
    }

    /**
     * 算出（临时）包的内容指纹：{@code pack.mcmeta}、{@code sounds.json} 与全部 {@code .ogg} 的
     * 路径 + 内容一起摘要。
     *
     * <p>用"路径 + 内容"而不是只看文件名：换掉同一个槽位的音频时文件名不变、内容变了，
     * 只看文件名会漏掉这种最常见的情况。
     *
     * @param staging 临时包根目录
     * @return 十六进制 SHA-1 字符串
     * @throws IOException 读盘失败
     */
    private static String fingerprint(Path staging) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (Stream<Path> paths = Files.walk(staging)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(staging.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    digest.update(Files.readAllBytes(path));
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable", e); // JDK 必然带 SHA-1，这里只是防御
        }
    }

    /**
     * @return 上一次真正装进资源包的内容指纹；没有记录（首次运行）时返回空串
     */
    private static String readFingerprint() {
        Path file = fingerprintFile();
        try {
            return file == null || !Files.isRegularFile(file) ? "" : Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 记下本次装进资源包的内容指纹。
     *
     * @param fingerprint {@link #fingerprint} 的结果
     */
    private static void writeFingerprint(String fingerprint) {
        Path file = fingerprintFile();
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            writeString(file, fingerprint);
        } catch (IOException e) {
            LOGGER.warn("Could not record the elevator sound pack fingerprint", e);
        }
    }

    /**
     * @return 指纹文件 {@code <gameDir>/config/easyelevator/sound_pack.sha1}；客户端尚未就绪时返回 null
     */
    private static Path fingerprintFile() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.runDirectory == null) return null;
        return client.runDirectory.toPath().resolve("config").resolve(Easyelevator.MOD_ID).resolve(FINGERPRINT_FILE);
    }

    /**
     * @return 资源包目录 {@code <gameDir>/resourcepacks/easyelevator_custom}；客户端尚未就绪时返回 null
     */
    private static Path packDir() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.runDirectory == null) return null;
        return client.runDirectory.toPath().resolve("resourcepacks").resolve(PACK_DIR_NAME);
    }

    /**
     * 找出本包在资源包管理器里的 profile id。
     *
     * @param manager 资源包管理器（已 {@code scanPacks} 过）
     * @return 本包的 id；没有这个包时返回 null
     */
    private static String profileId(ResourcePackManager manager) {
        for (String id : manager.getIds()) {
            if (id.equals(PACK_DIR_NAME) || id.endsWith("/" + PACK_DIR_NAME)) return id;
        }
        return null;
    }

    /**
     * @param manager 资源包管理器
     * @return 本包当前是否已经在"已启用"列表里
     */
    private static boolean alreadyEnabled(ResourcePackManager manager) {
        for (String id : manager.getEnabledIds()) {
            if (id.equals(PACK_DIR_NAME) || id.endsWith("/" + PACK_DIR_NAME)) return true;
        }
        return false;
    }

    /**
     * 生成资源包内的 {@code sounds.json}：为每个已写入的音频声明一个音效事件。
     *
     * <p><b>音频路径必须写全命名空间</b>（{@code easyelevator:arrival/arrival_33}）。
     * 原版的 {@code sounds.json} 条目不会继承本文件的命名空间——写成相对路径
     * {@code "arrival/arrival_33"} 会被解析成 {@code minecraft:arrival/arrival_33}，
     * 于是游戏去找 {@code assets/minecraft/sounds/arrival/arrival_33.ogg}，找不到就报
     * {@code File minecraft:sounds/... does not exist}，表现为"文件明明在、就是不出声"。
     * （粒子等其它 JSON 确实有命名空间继承，sounds.json 没有——这一条踩过一次。）
     *
     * <p>手写 JSON 而不是用 Gson：这里只有固定形状的几个字段，键与路径全部由数字和常量拼成，
     * 不含引号或反斜杠，没有任何转义风险，却省掉一层对象模型。
     *
     * @param stems 已写入资源包的音频文件名主干（{@code arrival_N}）
     * @return 完整的 {@code sounds.json} 文本
     */
    private static String soundsJson(List<String> stems) {
        List<String> ordered = new ArrayList<>(stems);
        ordered.sort(Comparator.naturalOrder()); // 输出稳定：同一个包在不同机器上逐字节一致
        StringBuilder json = new StringBuilder("{\n");
        for (int i = 0; i < ordered.size(); i++) {
            String stem = ordered.get(i);
            String slot = stem.substring("arrival_".length());
            if (i > 0) json.append(",\n");
            json.append("  \"elevator_arrival_custom_").append(slot).append("\": {\n")
                    // 字幕复用同一个键：所有自定义到站音效在字幕上都叫"电梯到站（自定义音效）"
                    .append("    \"subtitle\": \"subtitles.easyelevator.elevator_arrival_custom\",\n")
                    .append("    \"sounds\": [\"").append(Easyelevator.MOD_ID).append(':')
                    .append(SLOT_DIR).append('/').append(stem).append("\"]\n")
                    .append("  }");
        }
        return json.append("\n}\n").toString();
    }

    /**
     * @return 资源包描述文件 {@code pack.mcmeta} 的文本
     */
    private static String packMeta() {
        return "{\n  \"pack\": {\n    \"pack_format\": " + PACK_FORMAT
                + ",\n    \"description\": \"EasyElevator custom arrival sounds\"\n  }\n}\n";
    }

    /**
     * 原子写文本：先写 {@code .tmp} 再替换，避免资源重载读到半个文件。
     *
     * @param file 目标文件
     * @param text 文本内容（UTF-8）
     * @throws IOException 写盘失败
     */
    private static void writeString(Path file, String text) throws IOException {
        writeBytes(file, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 原子写字节：与 {@link #writeString} 同一套做法。
     *
     * @param file 目标文件
     * @param bytes 内容
     * @throws IOException 写盘失败
     */
    private static void writeBytes(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // 少数文件系统（部分网络盘）不支持原子移动：退回普通替换，行为上仍然正确
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 递归删除目录（不存在时静默返回）。
     *
     * @param dir 要删除的目录
     * @throws IOException 删除失败
     */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            // 深度优先：先删文件再删空目录，否则目录非空会删除失败
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
