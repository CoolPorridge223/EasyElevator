package org.DJB.easyelevator.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 把文件<b>拖进游戏窗口</b>当作"选择文件"——门设置面板上传自定义到站音效的主要入口。
 *
 * <p><b>为什么不让玩家点"选择文件"弹系统对话框</b>：那条路依赖 JDK 的 AWT，而不少启动器会给游戏 JVM
 * 加上 {@code -Djava.awt.headless=true}；更麻烦的是 AWT 会把 headless 状态缓存下来，运行时改属性也救不回来
 * （实测 {@code Toolkit} 已固定成 {@code HeadlessToolkit}）。为此写过一条"起 PowerShell 子进程弹系统原生
 * 对话框"的兜底路，但在部分机器上 PowerShell 会在 {@code OpenFileDialog.ShowDialog()} 里以退出码 1 结束、
 * 连窗口都不出现——环境差异无法在本机复现，继续加分支只是把不确定性堆高。拖拽不依赖任何对话框：
 * 文件路径由原版输入层直接交给我们，路径里带中文或空格也不影响。</p>
 *
 * <p><b>26.3 起不再自己挂 GLFW 的 drop 回调</b>：原版把"窗口拖入文件"接进了输入层，由
 * {@code MouseHandler.onDrop} 转成 {@code Screen.onFilesDrop(List&lt;Path&gt;)} 交给<b>当前打开的那个屏幕</b>；
 * 同时 26.3 的编译期类路径里已经没有 {@code org.lwjgl.glfw}（模组不再被暴露 LWJGL），旧写法根本无法编译。
 * 于是本类只保留与界面无关的那段逻辑——"从拖入的路径里挑出可用的那一个、记一行日志、交给面板"，
 * 由 {@link DoorSoundScreen#onFilesDrop} 调用。行为与旧版等价：只认第一个文件；只有门设置面板打开时才响应
 * （原版只把拖拽交给当前屏幕，因此"别的界面里拖文件"天然不会被处理，与旧版 target 判空是同一效果）。
 */
public final class FileDropHandler {
    /** 工具类，禁止实例化。 */
    private FileDropHandler() { }

    /** 拖拽过程的日志：只记"收到哪些文件"与失败原因。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("easyelevator/file-drop");

    /**
     * 处理一次拖拽：一次拖多个时只认第一个——一扇门只需要一段音频，多选没有语义。
     *
     * <p>合法性（是不是 ogg、是否超限）不在这一层判断，交给面板的上传逻辑统一提示，
     * 这样"拖拽"和"输入框粘贴路径"两条入口给出同一句话。
     *
     * @param screen 当前打开的设置面板；为 null（理论上到不了）时什么都不做
     * @param files  原版输入层解析出的文件路径列表
     */
    public static void onDrop(DoorSoundScreen screen, List<Path> files) {
        if (screen == null || files == null || files.isEmpty()) return;
        Path first = files.get(0);
        // 先无条件记一行：这一行是"回调到底有没有触发"的唯一证据。曾经不记，
        // 于是"拖了没反应"完全无法区分是"没接上"还是"路径没通过校验"。
        LOGGER.info("Drop event: {} file(s), first=[{}]", files.size(), first);
        LOGGER.info("Resolved dropped file to {} (regular file: {})", first, isReadableFile(first));
        screen.acceptDroppedFile(first);
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
