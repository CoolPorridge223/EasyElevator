package org.DJB.easyelevator.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 取一个本地 {@code .ogg} 文件：优先用 AWT 的 {@link java.awt.FileDialog}，不可用时落到系统的原生对话框。
 *
 * <p><b>为什么需要两条路</b>：模组需要在"玩家点『选择文件…』"时弹出操作系统的文件选择框，而
 * JDK 自带的 AWT 对话框有一个致命前提——进程不能处于 headless 状态。很多启动器（含官方启动器与
 * 若干第三方启动器）会给游戏 JVM 加上 {@code -Djava.awt.headless=true}；更麻烦的是 <b>AWT 会把
 * headless 状态缓存下来</b>：只要进程里任何代码先碰过一次 {@code GraphicsEnvironment}，
 * 之后再改系统属性也无效（实测 {@code new FileDialog(...)} 依旧抛 {@code HeadlessException}，
 * {@code Toolkit} 已经固定成 {@code HeadlessToolkit}）。所以"运行时把属性改回 false"靠不住，
 * 必须有第二条完全不依赖 AWT 的路。</p>
 *
 * <p><b>兜底那条路怎么走</b>：起一个独立的 Windows PowerShell 子进程，让它用 .NET 的
 * {@code System.Windows.Forms.OpenFileDialog} 弹出<b>真正的系统原生对话框</b>，选中的路径由子进程
 * 写进一个 UTF-8 文件，父进程再读回来。之所以用文件而不是子进程的标准输出：一是不必处理控制台的
 * 编码（中文与空格路径实测能原样往返），二是本工程所在环境禁止用管道捕获子进程输出，用文件最稳。
 * 脚本与结果文件都用 {@link Files#createTempFile} 建在系统临时目录里，用完即删。</p>
 *
 * <p>关键不变量：无论走哪条路，<b>玩家取消都返回 null</b>（调用方据此静默返回，不弹任何提示）；
 * 只有"确实弹不出对话框"才由 {@link #pick} 抛 {@link UnavailableException}，让调用方给出一句能照做的提示。</p>
 */
public final class FilePicker {
    /** 工具类，禁止实例化。 */
    private FilePicker() { }

    /** 取文件过程的日志：只记"哪条路能用"与失败原因，正常运行时不产出噪音。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("easyelevator/file-picker");
    /** 原生对话框只筛 .ogg；筛选器为空会让玩家选错文件，这里显式给出扩展名。 */
    private static final String EXTENSION = ".ogg";
    /** 子进程把结果写在这里的成功上限——路径不会被截断，纯粹是防御性上限。 */
    private static final int MAX_RESULT_CHARS = 32768;

    /**
     * 弹窗让玩家选一个文件。
     *
     * <p>阻塞直到玩家选完或取消（与 AWT 模态对话框一样会短暂冻结游戏画面）。必须在客户端主线程调用。
     *
     * @param title 对话框标题
     * @return 玩家选中的文件路径；<b>玩家取消时返回 null</b>
     * @throws UnavailableException 两条路都走不通（既没有可用的 AWT，也没有可用的原生对话框）
     */
    public static Path pick(String title) throws UnavailableException {
        AwtResult awt = pickWithAwt(title);
        if (awt.picked() != null) return awt.picked();
        // 只有"对话框确实弹出来过、玩家没选"才算取消；没弹出来才落到原生那条路。
        // 否则一旦 AWT 建得出对象却显示不出来（GLFW/AWT 冲突等），就会被误判成取消、再也弹不出窗口。
        if (awt.shown()) return null;
        return pickWithNativeDialog(title);
    }

    /**
     * @return 当前进程的 AWT 是否处于 headless 状态（true 表示 AWT 弹不出窗口）
     */
    public static boolean isHeadless() { return Graphics.isHeadless(); }

    /**
     * 用 AWT 的 {@link java.awt.FileDialog} 取文件。
     *
     * <p>先尝试把 {@code java.awt.headless} 设回 {@code false}——如果进程里还没有人碰过 AWT，
     * 这一步就能救回来（这也是最常见的情形：启动器加了参数，但模组在 AWT 初始化之前就动手了）。
     *
     * @param title 对话框标题
     * @return 三态结果：选到了路径 / 弹出来过但没选 / 压根没弹出来（见 {@link AwtResult}）
     */
    private static AwtResult pickWithAwt(String title) {
        try {
            System.setProperty("java.awt.headless", "false");
        } catch (SecurityException e) {
            LOGGER.warn("Could not clear java.awt.headless", e);
        }
        try {
            java.awt.FileDialog dialog = new java.awt.FileDialog((java.awt.Frame) null, title, java.awt.FileDialog.LOAD);
            dialog.setFilenameFilter((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(EXTENSION));
            dialog.setVisible(true);
            // setVisible 返回过 = 对话框确实弹出来并被关掉了，此后没有文件名就是玩家取消
            String dir = dialog.getDirectory(), file = dialog.getFile();
            if (dir == null || file == null) return new AwtResult(null, true);
            Path picked = Path.of(dir, file);
            LOGGER.info("Picked {} through the AWT file dialog", picked);
            return new AwtResult(picked, true);
        } catch (java.awt.HeadlessException e) {
            LOGGER.info("AWT is headless in this process; falling back to the native dialog", e);
            return new AwtResult(null, false);
        } catch (RuntimeException e) {
            LOGGER.warn("AWT file dialog failed; falling back to the native dialog", e);
            return new AwtResult(null, false);
        }
    }

    /**
     * AWT 取文件的三态结果。
     *
     * <p>为什么要三态而不是"路径或 null"：{@code null} 同时代表"玩家取消"和"窗口没弹出来"两件事，
     * 而它们的后续动作完全相反——前者应当安静返回，后者必须落到原生对话框兜底。
     *
     * @param picked 选中的路径；没选到时为 null
     * @param shown 对话框是否<b>确实弹出来过</b>（能区分"取消"与"压根没弹出来"）
     */
    private record AwtResult(Path picked, boolean shown) { }

    /**
     * 用系统原生对话框取文件（独立 PowerShell 子进程 + UTF-8 结果文件）。
     *
     * <p>只有 Windows 上可用（靠 {@code powershell.exe} 与 .NET 的 WinForms）。其它系统直接抛
     * {@link UnavailableException}，由调用方给玩家一句提示，而不是假装成功。
     *
     * @param title 对话框标题
     * @return 选中的路径；玩家取消时为 null
     * @throws UnavailableException 找不到 PowerShell、或子进程执行失败
     */
    private static Path pickWithNativeDialog(String title) throws UnavailableException {
        Path dir = null, script = null, result = null, output = null;
        try {
            dir = Files.createTempDirectory("easyelevator-pick");
            script = dir.resolve("pick.ps1");
            result = dir.resolve("result.txt");
            output = dir.resolve("output.txt");
            Files.writeString(result, "", StandardCharsets.UTF_8); // 先清空，避免把上次的旧结果读成本次选择
            Files.writeString(script, script(title, result), StandardCharsets.UTF_8);
            String exe = powerShell();
            // 先做一次"能不能跑"的自检：脚本被策略拦住、powershell.exe 被改名等情况都能在这里查出来，
            // 而不是让玩家对着一个什么都不做的按钮猜。输出一并记进日志。
            probe(exe, output);
            int exit = run(exe, output, "-NoProfile", "-NonInteractive", "-STA",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString());
            String picked = Files.readString(result, StandardCharsets.UTF_8).trim();
            LOGGER.info("Native file dialog finished: exit={} selection=[{}]", exit, picked);
            if (picked.isEmpty()) return null; // 弹出来过但没选（或脚本内部出错时写回的空串）
            return Path.of(picked);
        } catch (IOException e) {
            // 建临时文件失败、启动子进程失败、读结果失败都走这里——必须把原因留给日志，
            // 否则玩家只会看到"弹不出窗口"，而真正的原因（权限/杀软策略/句柄）全丢了。
            LOGGER.warn("Native file dialog could not run (temp dir {})", dir, e);
            throw new UnavailableException("could not run the native file dialog", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 保留中断标志，让上层知道这次调用被打断过
            throw new UnavailableException("interrupted while waiting for the native file dialog", e);
        } catch (RuntimeException e) {
            // InvalidPathException 等：拿到的东西不是合法路径，等同于"没选到"
            LOGGER.warn("Native file dialog returned an unusable result", e);
            return null;
        } finally {
            deleteQuietly(script);
            deleteQuietly(result);
            deleteQuietly(output);
            deleteQuietly(dir);
        }
    }

    /**
     * 自检：确认 {@code powershell.exe} 真的能被执行（执行策略、杀软拦截、文件被改名等都在这一步暴露）。
     *
     * <p>只记日志、不抛异常：自检失败时仍然继续尝试弹窗——大多数情况下只是自检命令本身不被允许，
     * 真正的对话框脚本反而是能跑的；把这些信息写进日志是为了让失败可定位。
     *
     * @param exe PowerShell 可执行文件路径
     * @param output 子进程输出要落到哪个文件（与弹窗那条路共用，见 {@link #run}）
     */
    private static void probe(String exe, Path output) {
        try {
            int exit = run(exe, output, "-NoProfile", "-NonInteractive", "-Command", "exit 0");
            LOGGER.info("PowerShell probe: {} -> exit={} {}", exe, exit, readOutput(output));
        } catch (IOException e) {
            LOGGER.warn("PowerShell probe could not start {}", exe, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 启动一个 PowerShell 子进程并等它结束，子进程的标准输出与错误都写进 {@code output} 文件。
     *
     * <p><b>为什么是重定向到文件，而不是 {@code inheritIO()} 或默认管道</b>：
     * <ul>
     *   <li>默认管道会创建管道句柄——本工程所在环境禁止用管道捕获子进程输出，那样启动会失败；</li>
     *   <li>{@code inheritIO()} 要求父进程的 stdout/stderr 是<b>可继承的真实句柄</b>，
     *       而启动器常把它们接成管道或重定向到自己的日志，这时 {@code start()} 会直接抛
     *       {@code IOException}——实机表现正是"降级到原生对话框之后就什么都没有了"；</li>
     *   <li>重定向到文件不涉及任何管道，也不依赖父进程句柄，是这里唯一稳的做法；
     *       而且输出被留下来，失败时能直接看到 PowerShell 的报错原文。</li>
     * </ul>
     *
     * @param exe PowerShell 可执行文件路径
     * @param output 收集子进程 stdout/stderr 的文件（每步都会覆盖，因此每步都要先读走）
     * @param args PowerShell 参数
     * @return 子进程退出码
     * @throws IOException 启动失败
     * @throws InterruptedException 等待被中断
     */
    private static int run(String exe, Path output, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = exe;
        System.arraycopy(args, 0, command, 1, args.length);
        return new ProcessBuilder(command)
                .redirectErrorStream(true) // 合并到同一个文件，日志里顺序才正确
                .redirectOutput(output.toFile())
                .start()
                .waitFor();
    }

    /**
     * 读子进程留下的输出（可能包含它自己的报错）。
     *
     * @param output 输出文件
     * @return 内容；读不到时返回空串
     */
    private static String readOutput(Path output) {
        try {
            return Files.readString(output, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 生成原生对话框的 PowerShell 脚本。
     *
     * <p>脚本要点：{@code -STA} 是 WinForms 对话框的硬要求（Windows PowerShell 默认 MTA）；
     * 结果用 {@code WriteAllText} 以<b>无 BOM 的 UTF-8</b> 写出，中文与空格路径才能原样往返；
     * 取消时写出空字符串，由父进程判成"取消"。
     *
     * @param title 对话框标题（会被转义后写进脚本）
     * @param result 结果文件路径（会被转义后写进脚本）
     * @return 完整的脚本内容
     */
    private static String script(String title, Path result) {
        return "$ErrorActionPreference = 'Stop'\r\n"
                + "$out = ''\r\n"
                + "try {\r\n"
                + "  Add-Type -AssemblyName System.Windows.Forms\r\n"
                + "  $dlg = New-Object System.Windows.Forms.OpenFileDialog\r\n"
                + "  $dlg.Filter = 'OGG (*.ogg)|*.ogg|All files (*.*)|*.*'\r\n"
                + "  $dlg.Title = '" + singleQuoted(title) + "'\r\n"
                // 显式置顶：游戏窗口常常是全屏且抢占焦点，普通对话框可能被压在后面，玩家看不到就等于"没弹"。
                + "  $dlg.ShowHelp = $false\r\n"
                + "  if ($dlg.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { $out = $dlg.FileName }\r\n"
                // 弹不出对话框时也把空结果写回去：父进程据此判定"取消"，不会把异常当成路径。
                // 同时把异常原文写进日志（父进程的标准流），便于定位。
                + "} catch { [Console]::Error.WriteLine('OpenFileDialog failed: ' + $_.Exception.ToString()) }\r\n"
                + "[IO.File]::WriteAllText('" + singleQuoted(result.toAbsolutePath().toString())
                + "', $out, (New-Object System.Text.UTF8Encoding($false)))\r\n";
    }

    /**
     * 把一个字符串转义成 PowerShell 单引号字面量（把内部的单引号翻倍）。
     *
     * <p>必须转义：对话框标题来自本地化文本，结果路径来自临时目录，两者都可能含单引号。
     *
     * @param value 原始字符串
     * @return 可直接放进 {@code '...'} 的内容
     */
    private static String singleQuoted(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    /**
     * @return Windows PowerShell 的可执行文件路径
     * @throws UnavailableException 不是 Windows，或系统里找不到它
     */
    private static String powerShell() throws UnavailableException {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isEmpty()) throw new UnavailableException("no SystemRoot: not a Windows host");
        Path exe = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        if (!Files.isRegularFile(exe)) throw new UnavailableException("powershell.exe not found at " + exe);
        return exe.toString();
    }

    /**
     * 删除临时文件，失败只记日志。
     *
     * @param path 要删的文件；null 时什么也不做
     */
    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("Could not delete the temporary picker file {}", path, e);
        }
    }

    /**
     * AWT headless 状态的查询被单独包一层，方便将来换实现，也避免业务代码直接依赖 AWT 类。
     *
     * <p>注意 {@code GraphicsEnvironment.isHeadless()} <b>会把状态缓存下来</b>，因此它回答的是
     * "这个进程现在还能不能弹 AWT 窗口"，而不是"启动参数里有没有那个开关"——正是我们需要的判据。
     */
    private static final class Graphics {
        /** 工具类，禁止实例化。 */
        private Graphics() { }

        /**
         * @return true 表示 AWT 弹不出窗口
         */
        static boolean isHeadless() {
            try {
                return java.awt.GraphicsEnvironment.isHeadless();
            } catch (Throwable t) {
                LOGGER.warn("Could not query the AWT headless state", t);
                return true; // 查不出来就按"不可用"处理，走原生对话框那条路
            }
        }
    }

    /** 两条取文件的路都走不通时抛出，供调用方给出"照做就能解决"的提示。 */
    public static final class UnavailableException extends Exception {
        /** 序列化 id：本异常不跨进程传输，仅为消除告警。 */
        private static final long serialVersionUID = 1L;

        /**
         * @param message 原因说明（只进日志）
         */
        UnavailableException(String message) { super(message); }

        /**
         * @param message 原因说明（只进日志）
         * @param cause 底层异常
         */
        UnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
