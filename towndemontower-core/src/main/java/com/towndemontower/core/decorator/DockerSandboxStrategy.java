package com.towndemontower.core.decorator;

import com.towndemontower.core.executor.CancellationToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * [V1.2.1] Docker 容器沙箱策略。替换 ProcessSandboxStrategy 即可获得容器级隔离，
 * 装饰器链与 Executor 接口保持不变。
 *
 * 隔离基线（默认开启，可在构造时关闭）：
 *   --network none   网络隔离，容器无法访问外网/内网
 *   --memory 128m    内存上限，防止 OOM 拖垮宿主
 *   --cap-drop ALL   丢弃全部 Linux capabilities，降权运行
 *   --read-only      只读根文件系统，写操作需显式挂载卷
 *   --user 65534:65534  V1.7：非 root 运行（此前 alpine 默认 uid 0，cap-drop 不等于降权）
 *   --pids-limit 64   V1.7：限制进程数，挡住 fork 炸弹
 *
 * <p><b>V1.7 extraArgs 收敛</b>：此前 extraArgs 原样拼进命令行，等于把
 * {@code -v /:/host}、{@code --privileged}、{@code --net=host} 这类"一键逃逸"
 * 交给调用方。现在只允许 {@code -u/--user}、{@code --pids-limit}、{@code --cpus}，
 * 出现其它参数<b>在构造期直接抛异常</b>（等于拒绝启动），不做静默忽略。
 * 显式指定 root 用户同样被拒。</p>
 *
 * <p>通过 docker CLI（ProcessBuilder）驱动，零新增依赖；命令以字符串形式传给容器内
 * shell（默认 sh -c），因此命令对用户侧而言是容器内的数据，不会污染宿主的 docker 调用。</p>
 */
public class DockerSandboxStrategy implements SandboxStrategy {

    /** docker CLI 不可用或启动失败时抛出，便于上层执行器降级为 SANDBOX_UNAVAILABLE。 */
    public static final class DockerUnavailableException extends RuntimeException {
        public DockerUnavailableException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /** 默认运行用户：nobody（65534）。容器内不再以 uid 0 执行命令。 */
    public static final String DEFAULT_USER = "65534:65534";

    /** 默认进程数上限。 */
    public static final long DEFAULT_PIDS_LIMIT = 64L;

    /**
     * extraArgs 白名单：只允许调整"运行身份 / 资源上限"，
     * 不允许改动命名空间、挂载、设备、权限与网络。
     */
    private static final List<String> ALLOWED_EXTRA_FLAGS =
            List.of("-u", "--user", "--pids-limit", "--cpus");

    private final String image;
    private final String shell;
    private final long timeoutMillis;
    private final long memoryMb;
    private final boolean networkDisabled;
    private final boolean readOnlyRoot;
    private final List<String> extraArgs;
    private final String runAsUser;
    private final long pidsLimit;

    public DockerSandboxStrategy(long timeoutMillis) {
        this("alpine:latest", timeoutMillis);
    }

    public DockerSandboxStrategy(String image, long timeoutMillis) {
        this(image, "sh", timeoutMillis, 128, true, true, new ArrayList<>());
    }

    public DockerSandboxStrategy(String image, String shell, long timeoutMillis,
                                 long memoryMb, boolean networkDisabled,
                                 boolean readOnlyRoot, List<String> extraArgs) {
        this(image, shell, timeoutMillis, memoryMb, networkDisabled, readOnlyRoot,
                extraArgs, DEFAULT_USER, DEFAULT_PIDS_LIMIT);
    }

    public DockerSandboxStrategy(String image, String shell, long timeoutMillis,
                                 long memoryMb, boolean networkDisabled,
                                 boolean readOnlyRoot, List<String> extraArgs,
                                 String runAsUser, long pidsLimit) {
        this.image = image;
        this.shell = shell;
        this.timeoutMillis = timeoutMillis;
        this.memoryMb = memoryMb;
        this.networkDisabled = networkDisabled;
        this.readOnlyRoot = readOnlyRoot;
        // 构造期即校验：非法 extraArgs 不等到运行才炸，而是根本装配不出策略对象
        this.extraArgs = validateExtraArgs(extraArgs);
        this.runAsUser = (runAsUser == null || runAsUser.isBlank()) ? DEFAULT_USER : runAsUser.trim();
        this.pidsLimit = pidsLimit > 0 ? pidsLimit : DEFAULT_PIDS_LIMIT;
        rejectRootUser(this.runAsUser);
    }

    /**
     * extraArgs 白名单校验：只放行 {@link #ALLOWED_EXTRA_FLAGS}，
     * 其余一律抛 {@link IllegalArgumentException}（等价于拒绝启动）。
     */
    private static List<String> validateExtraArgs(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (int i = 0; i < raw.size(); i++) {
            String a = raw.get(i);
            if (a == null || a.isBlank()) continue;
            String flag = a.contains("=") ? a.substring(0, a.indexOf('=')) : a;
            if (!ALLOWED_EXTRA_FLAGS.contains(flag)) {
                throw new IllegalArgumentException("extraArgs 含不允许的参数 [" + a + "]：只允许 "
                        + ALLOWED_EXTRA_FLAGS + "；挂载、特权、网络与命名空间类参数会直接击穿沙箱边界");
            }
            out.add(a);
            boolean userFlag = "-u".equals(flag) || "--user".equals(flag);
            if (a.contains("=")) {
                // --user=1000:1000 形式：值在同一 token 里
                if (userFlag) rejectRootUser(a.substring(a.indexOf('=') + 1));
            } else if (i + 1 < raw.size()) {
                // 白名单内的旗标都带值，紧随其后的 token 是值，不再当旗标校验
                String value = raw.get(i + 1);
                out.add(value);
                if (userFlag) rejectRootUser(value);
                i++;
            }
        }
        return out;
    }

    /** 拒绝以 root 身份运行（0 / root / 0:0 及其等价写法）。 */
    private static void rejectRootUser(String user) {
        if (user == null) return;
        String u = user.trim();
        int colon = u.indexOf(':');
        String name = colon >= 0 ? u.substring(0, colon) : u;
        if ("0".equals(name) || "root".equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("runAsUser 不允许为 root（" + user
                    + "）：容器内以 uid 0 运行会让 --cap-drop ALL 的降权失去意义");
        }
    }

    /** 探测 docker CLI 是否可用（运行前自检与单测使用）。 */
    public static boolean isDockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            return ok;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 纯函数：拼装 `docker run` 之后的参数（不含 "docker" 命令本身）。
     * 便于单测断言隔离基线，也便于审计/运维核验实际启动参数。
     */
    public List<String> buildDockerArgs(String command) {
        List<String> args = new ArrayList<>();
        args.add("run");
        args.add("--rm");                       // 退出即清理容器
        args.add("-i");                         // 交互式，便于透传命令
        if (networkDisabled) {
            args.add("--network");
            args.add("none");
        }
        args.add("--memory");
        args.add(memoryMb + "m");
        args.add("--cap-drop");
        args.add("ALL");
        if (readOnlyRoot) {
            args.add("--read-only");
        }
        // V1.7：非 root + 进程数上限。放在 extraArgs 之前，便于显式覆盖运行用户。
        args.add("--user");
        args.add(runAsUser);
        args.add("--pids-limit");
        args.add(String.valueOf(pidsLimit));
        args.addAll(extraArgs);                // 已白名单校验（仅 -u/--user/--pids-limit/--cpus）
        args.add(image);
        args.add(shell);
        args.add("-c");
        args.add(command);
        return args;
    }

    private String[] fullCommand(String command) {
        List<String> full = new ArrayList<>();
        full.add("docker");
        full.addAll(buildDockerArgs(command));
        return full.toArray(new String[0]);
    }

    @Override
    public String execute(String command, CancellationToken token) throws Exception {
        if (token != null && token.isCancelled()) {
            throw new java.util.concurrent.CancellationException(
                    "docker sandbox cancelled before start: " + command);
        }
        String[] cmd = fullCommand(command);
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new DockerUnavailableException("docker CLI unavailable or failed to start", e);
        }

        Thread watcher = null;
        if (token != null) {
            watcher = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    if (token.isCancelled()) {
                        p.destroyForcibly();
                        return;
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            });
            watcher.start();
        }
        try {
            boolean finished = p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new TimeoutException("docker sandbox timed out: " + command);
            }
            if (token != null && token.isCancelled()) {
                throw new java.util.concurrent.CancellationException("docker sandbox cancelled: " + command);
            }
            int exit = p.exitValue();
            String out = new String(p.getInputStream().readAllBytes());
            if (exit != 0) {
                throw new RuntimeException("docker run exited " + exit + ": " + out);
            }
            return out;
        } finally {
            if (watcher != null) watcher.interrupt();
        }
    }
}
