package org.DJB.easyelevator.client;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把文件<b>拖进游戏窗口</b>当作"选择文件"——门设置面板上传自定义到站音效的主要入口。
 *
 * <p><b>为什么不让玩家点"选择文件"弹系统对话框</b>：那条路依赖 JDK 的 AWT，而不少启动器会给游戏 JVM
 * 加上 {@code -Djava.awt.headless=true}；更麻烦的是 AWT 会把 headless 状态缓存下来，运行时改属性也救不回来
 * （实测 {@code Toolkit} 已固定成 {@code HeadlessToolkit}）。为此写过一条"起 PowerShell 子进程弹系统原生
 * 对话框"的兜底路，但在部分机器上 PowerShell 会在 {@code OpenFileDialog.ShowDialog()} 里以退出码 1 结束、
 * 连窗口都不出现——环境差异无法在本机复现，继续加分支只是把不确定性堆高。拖拽不依赖任何对话框：
 * 文件路径由 GLFW 直接交给我们，路径里带中文或空格也不影响。</p>
 *
 * <p>实现方式：给 GLFW 窗口挂一个 drop 回调（原版 1.21.1 的 {@code Screen} 没有拖拽钩子，只能走 GLFW）。
 * 回调由 {@code glfwPollEvents} 在<b>渲染线程</b>上同步触发，因此可以直接调用界面相关代码，不需要再切线程。</p>
 *
 * <p>关键约束：回调里拿到的路径数组是 GLFW 的临时内存，必须在回调返回前就把字符串读出来；
 * 而且回调本身不能抛异常（穿过 JNI 会变成难以定位的崩溃），所以整体包在 try/catch 里。</p>
 */
public final class FileDropHandler {
    /** 工具类，禁止实例化。 */
    private FileDropHandler() { }

    /** 拖拽过程的日志：只记"收到哪些文件"与失败原因。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("easyelevator/file-drop");
    /** 当前接收拖拽的面板；没有面板打开时为 null。用弱引用避免面板被长期持有。 */
    private static java.lang.ref.WeakReference<DoorSoundScreen> target;
    /** GLFW 回调要长期存活，必须由本类持有强引用，否则可能被 GC 掉而变成野指针。 */
    private static GLFWDropCallback callback;

    /**
     * 登记"当前正在等文件的设置面板"。面板打开时调用，关闭时传 null。
     *
     * @param screen 等待拖拽的面板；null 表示不再接收
     */
    public static void setTarget(DoorSoundScreen screen) { target = screen == null ? null : new java.lang.ref.WeakReference<>(screen); }

    /**
     * 给客户端窗口挂上拖拽回调；由客户端初始化时调用一次。
     *
     * <p>幂等：已经挂过就直接返回（重复挂会覆盖，行为一样，但没必要）。
     *
     * @param client 客户端实例
     */
    public static void register(MinecraftClient client) {
        if (callback != null) return; // 已经挂上
        if (client == null) {
            LOGGER.warn("Drag-and-drop uploads were not enabled: no client instance yet");
            return;
        }
        if (client.getWindow() == null) {
            // 曾经这里直接 return，什么都没记：窗口若还没就绪，回调就永远没挂上，而日志里一片安静，
            // 完全看不出"功能没生效"。现在由调用方每刻重试，并在这里留下原因。
            LOGGER.warn("Drag-and-drop uploads not enabled yet: the game window is not ready");
            return;
        }
        try {
            callback = GLFWDropCallback.create((window, count, names) -> {
                try {
                    onDrop(count, names);
                } catch (Throwable t) {
                    // 绝不能让异常穿回 GLFW/JNI：那会变成崩溃或难以定位的错误
                    LOGGER.warn("Failed to handle a dropped file", t);
                }
            });
            org.lwjgl.glfw.GLFW.glfwSetDropCallback(client.getWindow().getHandle(), callback);
            LOGGER.info("Drag-and-drop uploads enabled (window handle {})", client.getWindow().getHandle());
        } catch (RuntimeException e) {
            // 拿不到窗口句柄或 GLFW 不可用：拖拽就用不了，但模组其余功能照常
            LOGGER.warn("Could not enable drag-and-drop uploads", e);
            callback = null;
        }
    }

    /**
     * 处理一次拖拽。
     *
     * @param count 文件个数
     * @param names GLFW 传入的"以 0 结尾的 C 字符串指针数组"的首地址
     */
    private static void onDrop(int count, long names) {
        if (count <= 0) return;
        // 先无条件记一行：这一行是"回调到底有没有触发"的唯一证据。曾经不记，
        // 于是"拖了没反应"完全无法区分是"回调没挂上"还是"回调触发了但路径没通过校验"。
        String raw = readName(names, 0);
        LOGGER.info("Drop event: {} file(s), first=[{}]", count, raw);
        if (raw == null) return;
        Path path = toPath(raw);
        LOGGER.info("Resolved dropped file to {} (regular file: {})", path, isReadableFile(path));
        DoorSoundScreen screen = target == null ? null : target.get();
        if (screen == null) {
            LOGGER.info("No landing-door settings panel is open; ignoring the dropped file");
            return; // 没有面板在等文件：静默忽略，别在别的界面里弹提示
        }
        // 一次拖多个时只认第一个：一扇门只需要一段音频，多选没有语义
        screen.acceptDroppedFile(path);
    }

    /**
     * 把 GLFW 给的字符串转成路径，兼容"裸路径"与"file:// URI"两种形式。
     *
     * <p>为什么两种都要处理：Windows 上通常给的是裸路径（{@code C:\x\y.ogg}），但某些平台/驱动会给
     * {@code file:///C:/x/y.ogg}。直接对后者调 {@code Path.of} 会得到一个奇怪的相对路径，
     * 读文件失败后又只表现为一句"上传失败"，根本看不出真正原因。
     *
     * @param raw GLFW 给的原始字符串
     * @return 解析出的路径；无法解析时返回一个必定不存在的路径（让上层走"上传失败"分支）
     */
    private static Path toPath(String raw) {
        String value = raw.trim();
        // 有些来源会把路径用引号括起来（"C:\x y\a.ogg"），去掉首尾引号
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
        if (value.regionMatches(true, 0, "file:", 0, 5)) {
            try {
                return Path.of(java.net.URI.create(value));
            } catch (RuntimeException e) {
                LOGGER.warn("Could not parse the dropped file URI [{}]", value, e);
            }
        }
        return Path.of(value);
    }

    /**
     * 读出指针数组里第 {@code index} 个 C 字符串。
     *
     * @param names 指针数组首地址
     * @param index 下标（0 基）
     * @return 字符串；越界或内容为空时返回 null
     */
    private static String readName(long names, int index) {
        // 指针宽度由平台决定：Windows 64 位是 8 字节，用 Pointer 的大小而不是写死 8
        long address = MemoryUtil.memGetAddress(names + (long) index * org.lwjgl.system.Pointer.POINTER_SIZE);
        if (address == 0L) return null;
        String name = MemoryUtil.memUTF8(address);
        return name == null || name.isEmpty() ? null : name;
    }

    /**
     * 判断一个路径是不是可用的本地文件。
     *
     * <p>单独的静态入口是为了让面板只需关心"这个路径能不能读"，而不必重复这套判空与异常处理。
     *
     * @param path 路径
     * @return 存在且是普通文件时为 true
     */
    public static boolean isReadableFile(Path path) {
        try {
            return path != null && Files.isRegularFile(path);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
