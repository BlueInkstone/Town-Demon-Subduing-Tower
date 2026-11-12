package com.towndemontower.core;
import java.util.Arrays;

import com.towndemontower.core.decorator.DockerSandboxStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * [V1.7] 容器沙箱加固：非 root 运行、进程数上限、extraArgs 白名单。
 *
 * <p>要证明的是三件事：① 默认参数里已有降权项；② 越界参数在<b>构造期</b>就被拒，
 * 不会等到 docker 启动；③ 白名单内的参数仍然可用（不误伤正常调参）。</p>
 */
public class V17SandboxHardeningTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        defaults();
        extraArgsRejected();
        extraArgsAllowed();
        ctorRejectsRoot();

        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- ① 默认即降权 ---------- */
    static void defaults() {
        DockerSandboxStrategy s = new DockerSandboxStrategy(5000);
        List<String> a = s.buildDockerArgs("echo hi");

        int u = a.indexOf("--user");
        check("沙箱-默认带--user", u >= 0);
        check("沙箱-默认用户为nobody", u >= 0 && u + 1 < a.size()
                && DockerSandboxStrategy.DEFAULT_USER.equals(a.get(u + 1)));
        check("沙箱-默认用户不是root", u >= 0 && !"0".equals(a.get(u + 1)));

        int p = a.indexOf("--pids-limit");
        check("沙箱-默认带--pids-limit", p >= 0 && p + 1 < a.size()
                && String.valueOf(DockerSandboxStrategy.DEFAULT_PIDS_LIMIT).equals(a.get(p + 1)));
        check("沙箱-仍保留cap-drop", a.contains("--cap-drop") && a.contains("ALL"));
        check("沙箱-仍保留network-none", a.contains("--network") && a.contains("none"));
        check("沙箱-仍保留read-only", a.contains("--read-only"));

        // 自定义 pids 上限生效
        DockerSandboxStrategy tuned = new DockerSandboxStrategy(
                "alpine:3.19", "sh", 3000, 64, true, true, Arrays.asList(),
                "1000:1000", 32);
        List<String> ta = tuned.buildDockerArgs("ls");
        check("沙箱-自定义pids生效", ta.contains("32") && ta.contains("--pids-limit"));
        check("沙箱-自定义用户生效", ta.contains("1000:1000"));
    }

    /* ---------- ② 越界参数在构造期即被拒 ---------- */
    static void extraArgsRejected() {
        check("沙箱-拒挂载宿主根", rejected(Arrays.asList("-v", "/:/host")));
        check("沙箱-拒卷挂载", rejected(Arrays.asList("--volume=/data:/data")));
        check("沙箱-拒特权模式", rejected(Arrays.asList("--privileged")));
        check("沙箱-拒host网络", rejected(Arrays.asList("--net=host")));
        check("沙箱-拒network参数", rejected(Arrays.asList("--network", "host")));
        check("沙箱-拒pid命名空间共享", rejected(Arrays.asList("--pid=host")));
        check("沙箱-拒加capability", rejected(Arrays.asList("--cap-add", "SYS_ADMIN")));
        check("沙箱-拒设备直通", rejected(Arrays.asList("--device", "/dev/sda")));
        check("沙箱-拒安全选项绕过", rejected(Arrays.asList("--security-opt=seccomp=unconfined")));
        check("沙箱-拒以root运行(-u)", rejected(Arrays.asList("-u", "root")));
        check("沙箱-拒以root运行(--user=0:0)", rejected(Arrays.asList("--user=0:0")));
        check("沙箱-拒以uid0运行", rejected(Arrays.asList("-u", "0")));
    }

    /* ---------- ③ 白名单内参数仍可用 ---------- */
    static void extraArgsAllowed() {
        DockerSandboxStrategy s = new DockerSandboxStrategy(
                "alpine:3.19", "sh", 3000, 64, true, true,
                Arrays.asList("--cpus", "2", "--pids-limit", "128"));
        List<String> a = s.buildDockerArgs("ls");
        check("沙箱-放行--cpus", a.contains("--cpus") && a.contains("2"));
        // extraArgs 在后，覆盖默认值：最后一个 --pids-limit 的值应为 128
        int last = a.lastIndexOf("--pids-limit");
        check("沙箱-放行--pids-limit覆盖", last >= 0 && last + 1 < a.size()
                && "128".equals(a.get(last + 1)));

        DockerSandboxStrategy nonRoot = new DockerSandboxStrategy(
                "alpine:3.19", "sh", 3000, 64, true, true, Arrays.asList("--user", "1000:1000"));
        List<String> na = nonRoot.buildDockerArgs("ls");
        int nu = na.lastIndexOf("--user");
        check("沙箱-放行非root用户覆盖", nu >= 0 && "1000:1000".equals(na.get(nu + 1)));
    }

    /* ---------- ④ 构造器直接给 root 同样被拒 ---------- */
    static void ctorRejectsRoot() {
        boolean threw = false;
        try {
            new DockerSandboxStrategy("alpine:3.19", "sh", 3000, 64, true, true,
                    new ArrayList<>(), "root", 64);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("沙箱-构造器拒root用户", threw);

        // runAsUser 传空 → 回落默认值，而不是"不限制"
        DockerSandboxStrategy blank = new DockerSandboxStrategy(
                "alpine:3.19", "sh", 3000, 64, true, true, Arrays.asList(), "  ", 64);
        check("沙箱-空用户回落默认", blank.buildDockerArgs("ls")
                .contains(DockerSandboxStrategy.DEFAULT_USER));
    }

    static boolean rejected(List<String> extra) {
        try {
            new DockerSandboxStrategy("alpine:3.19", "sh", 3000, 64, true, true, extra);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }
}
