package com.towndemontower.core;

import com.towndemontower.core.contract.*;
import com.towndemontower.core.decorator.DockerSandboxExecutor;
import com.towndemontower.core.decorator.DockerSandboxStrategy;
import com.towndemontower.core.executor.CancellationToken;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.mode.AgentMode;

import java.util.List;
import java.util.Map;

/**
 * [V1.2.1] Docker 容器沙箱验证：参数装配、可用性探测、执行器拒绝路径（缺命令/未知工具/取消令牌），
 * 以及 docker 不可用时的优雅降级。纯 Java main，零依赖，JDK21 直接编译运行。
 */
public class V121DockerSandboxTest {
    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    static IntentObject intent(String tool, String command) {
        java.util.Map<String, Object> args = new java.util.HashMap<>();
        if (command != null) args.put("command", command);
        return new IntentObject(tool, args, 0.9, null, null, null);
    }

    static ExecutionContext ctx() {
        return new ExecutionContext("s", "t", "tenant", "agent", "owner", false, AgentMode.DEV_EXECUTION);
    }

    public static void main(String[] args) throws Exception {
        dockerSandbox();
        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void dockerSandbox() {
        DockerSandboxStrategy s = new DockerSandboxStrategy(10000);

        // 1) 隔离基线参数装配正确
        List<String> a = s.buildDockerArgs("echo hi");
        check("docker.args.rm", a.contains("run") && a.contains("--rm"));
        check("docker.args.network-none", a.contains("--network") && a.contains("none"));
        check("docker.args.cap-drop-all", a.contains("--cap-drop") && a.contains("ALL"));
        check("docker.args.memory", a.contains("--memory") && a.contains("128m"));
        check("docker.args.readonly", a.contains("--read-only"));
        check("docker.args.image", a.contains("alpine:latest"));
        check("docker.args.shell", a.contains("sh") && a.contains("-c") && a.contains("echo hi"));

        // 2) 自定义镜像与 shell 生效
        DockerSandboxStrategy custom = new DockerSandboxStrategy(
                "ubuntu:22.04", "bash", 5000, 256, false, false, java.util.Collections.emptyList());
        List<String> ca = custom.buildDockerArgs("ls");
        check("docker.custom-image", ca.contains("ubuntu:22.04") && ca.contains("bash"));
        check("docker.custom-no-network", !ca.contains("--network"));
        check("docker.custom-no-readonly", !ca.contains("--read-only"));

        // 3) 探测 docker 可用性（环境无关，仅打印）
        boolean avail = DockerSandboxStrategy.isDockerAvailable();
        System.out.println("[INFO] docker available=" + avail);

        // 4) 执行器：无需 docker 的拒绝路径
        SkillRegistry reg = new SkillRegistry();
        reg.register(new SkillDescriptor("run", "R1", new String[0], new String[0], new String[0], false, "docker"));
        DockerSandboxExecutor exec = new DockerSandboxExecutor(reg, s, null);

        SkillResult missing = exec.execute(intent("run", null), ctx());
        check("docker.missing-command",
                !missing.success && ErrorCodes.INTENT_FORMAT_INVALID.equals(missing.code));

        SkillResult unknown = exec.execute(intent("nope", "echo x"), ctx());
        check("docker.unknown-tool",
                !unknown.success && ErrorCodes.MODE_TOOL_NOT_ALLOWED.equals(unknown.code));

        // 5) 取消令牌：调用前即取消
        CancellationToken tok = new CancellationToken("d1");
        tok.cancel();
        DockerSandboxExecutor exec2 = new DockerSandboxExecutor(reg, s, tok);
        SkillResult cancelled = exec2.execute(intent("run", "echo x"), ctx());
        check("docker.cancelled", !cancelled.success && ErrorCodes.CANCELLED.equals(cancelled.code));

        // 6) docker 不可用时的优雅降级：不抛未捕获异常，返回明确拒绝码
        if (!avail) {
            SkillResult r = exec.execute(intent("run", "echo x"), ctx());
            check("docker.absence-handled",
                    !r.success && (ErrorCodes.SANDBOX_UNAVAILABLE.equals(r.code)
                            || ErrorCodes.GATE_SCRIPT_FAILED.equals(r.code)));
            System.out.println("[INFO] docker 不可用，已按预期降级: " + r.code);
        } else {
            // 环境确有 docker：真实跑一次验证容器隔离执行
            SkillResult r = exec.execute(intent("run", "echo container-ok"), ctx());
            check("docker.real-run", r.success && r.output != null && r.output.toString().contains("container-ok"));
        }
    }
}
