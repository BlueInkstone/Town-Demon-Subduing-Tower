package com.towndemontower.core.decorator;
import com.towndemontower.core.util.IO;

import com.towndemontower.core.executor.CancellationToken;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * [V1.2] 进程级沙箱策略（容器级沙箱的过渡实现）。
 * 以独立 OS 进程执行命令（而非在 Agent JVM 内反射调用），提供基础隔离边界；
 * 真正的 Docker/资源/网络隔离在 V1.2.1 通过替换此策略完成，装饰器链无需改动。
 */
public class ProcessSandboxStrategy implements SandboxStrategy {
    private final long timeoutMillis;

    public ProcessSandboxStrategy(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String execute(String command, CancellationToken token) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(shell(command));
        pb.redirectErrorStream(true);
        Process p = pb.start();

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
                    } catch (InterruptedException e) {
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
                throw new TimeoutException("sandbox command timed out: " + command);
            }
            if (token != null && token.isCancelled()) {
                throw new java.util.concurrent.CancellationException("sandbox command cancelled: " + command);
            }
            return new String(IO.readAllBytes(p.getInputStream()));
        } finally {
            if (watcher != null) watcher.interrupt();
        }
    }

    private static String[] shell(String command) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return new String[]{"cmd", "/c", command};
        return new String[]{"sh", "-c", command};
    }
}
