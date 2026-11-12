package com.towndemontower.core;

import java.util.HashSet;
import java.util.Arrays;

import com.towndemontower.core.audit.AuditRecord;
import com.towndemontower.core.audit.HashChainAuditStore;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

/**
 * [core/test] V1.9 / I3 —— 授权收口。
 *
 * <p>要证明的不是"端口能用"，而是<b>旧的自我授权路径已经不存在</b>：</p>
 * <ol>
 *   <li>引擎的 public 方法里不再有 {@code grant/revoke/revokeAll}（编译期收权）；</li>
 *   <li>端口是唯一写入通道，且行为（追加 / 精确回收 / 整员回收 / 覆盖）与原语义一致；</li>
 *   <li>经端口写入会留下带 actor 的审计；不经端口的读操作不受影响。</li>
 * </ol>
 */
public class V19PermissionTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        engineHasNoPublicWriteApi();
        portBehaviour();
        auditedPort();
        immutability();
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    /* ---------- ① 收权证据：反射看 public 表面 ---------- */

    static void engineHasNoPublicWriteApi() throws Exception {
        Method grant = PermissionEngine.class.getDeclaredMethod(
                "grant", String.class, String[].class);
        Method revoke = PermissionEngine.class.getDeclaredMethod(
                "revoke", String.class, String[].class);
        Method revokeAll = PermissionEngine.class.getDeclaredMethod(
                "revokeAll", String.class);

        check("收权-grant 非 public", !Modifier.isPublic(grant.getModifiers()));
        check("收权-revoke 非 public", !Modifier.isPublic(revoke.getModifiers()));
        check("收权-revokeAll 非 public", !Modifier.isPublic(revokeAll.getModifiers()));

        // 从外部包视角看：getMethod 只能取 public，取不到即"编译不过"的镜像
        boolean grantVisible = false;
        boolean revokeVisible = false;
        for (Method m : PermissionEngine.class.getMethods()) {
            if (m.getName().equals("grant")) grantVisible = true;
            if (m.getName().equals("revoke") || m.getName().equals("revokeAll")) {
                revokeVisible = true;
            }
        }
        check("收权-public 方法表里没有 grant", !grantVisible);
        check("收权-public 方法表里没有 revoke", !revokeVisible);

        // 读能力必须保留：否则接入方连判断权限都做不到
        boolean readable = false;
        for (Method m : PermissionEngine.class.getMethods()) {
            if (m.getName().equals("isGranted") || m.getName().equals("permissionsOf")) {
                readable = true;
            }
        }
        check("收权-读接口仍然 public", readable);
    }

    /* ---------- ② 端口行为与原语义一致 ---------- */

    static void portBehaviour() {
        PermissionEngine e = new PermissionEngine();
        PermissionProvisioner port = PermissionProvisioner.of(e);

        port.grant("a", "fs:write", "data:delete");
        check("端口-授予生效", e.isGranted("a", "fs:write") && e.isGranted("a", "data:delete"));

        port.grant("a", "log:read");
        check("端口-追加授予不覆盖", e.permissionsOf("a").size() == 3);

        check("端口-精确回收计数", port.revoke("a", "fs:write") == 1);
        check("端口-精确回收生效", !e.isGranted("a", "fs:write"));
        check("端口-精确回收不动其它", e.isGranted("a", "log:read"));
        check("端口-回收不存在的权限返回 0", port.revoke("a", "nope") == 0);

        check("端口-整员回收计数", port.revokeAll("a") == 2);
        check("端口-整员回收后为空", e.permissionsOf("a").isEmpty());
        check("端口-重复整员回收幂等", port.revokeAll("a") == 0);

        port.grant("b", "x:1", "x:2", "x:3");
        port.replace("b", Arrays.asList("y:1"));
        check("端口-覆盖式授予只留新权限", e.permissionsOf("b").equals(new HashSet<>(Arrays.asList("y:1"))));
        port.replace("b", Arrays.asList());
        check("端口-覆盖为空即清空", e.permissionsOf("b").isEmpty());

        port.grant("c", Arrays.asList("p:1", "p:2"));
        check("端口-集合重载生效", e.permissionsOf("c").size() == 2);

        // 空入参不应产生任何副作用
        port.grant(null, "x:1");
        port.grant("d", new String[0]);
        check("端口-空主体/空权限不写入", e.permissionsOf("d").isEmpty());
    }

    /* ---------- ③ 审计留痕 ---------- */

    static void auditedPort() {
        PermissionEngine e = new PermissionEngine();
        HashChainAuditStore audit = new HashChainAuditStore();
        PermissionProvisioner port = PermissionProvisioner.of(e, audit, "test-wiring");

        port.grant("agent-1", "fs:write");
        port.revoke("agent-1", "fs:write");
        port.grant("agent-2", "ops:deploy");
        port.replace("agent-2", Arrays.asList("log:read"));
        port.revokeAll("agent-2");

        List<AuditRecord> records = audit.all();
        check("审计-授予留痕", records.stream().anyMatch(r -> "PERMISSION_GRANTED".equals(r.action)));
        check("审计-回收留痕", records.stream().anyMatch(r -> "PERMISSION_REVOKED".equals(r.action)));
        check("审计-覆盖留痕", records.stream().anyMatch(r -> "PERMISSION_REPLACED".equals(r.action)));
        check("审计-整员回收留痕",
                records.stream().anyMatch(r -> "PERMISSION_REVOKED_ALL".equals(r.action)));
        check("审计-含 actor", records.stream()
                .filter(r -> "PERMISSION_GRANTED".equals(r.action))
                .allMatch(r -> r.payload.contains("actor=test-wiring")));
        check("审计-含主体", records.stream()
                .filter(r -> "PERMISSION_GRANTED".equals(r.action))
                .allMatch(r -> r.payload.contains("agent=agent-1")
                        || r.payload.contains("agent=agent-2")));
        check("审计-链仍完整", audit.verifyChain());

        // 无变更不应刷日志
        int before = audit.all().size();
        port.revoke("agent-3", "none");
        port.grant("agent-4");
        check("审计-空操作不写记录", audit.all().size() == before);

        // 未挂审计的端口不应产生记录
        HashChainAuditStore quiet = new HashChainAuditStore();
        PermissionProvisioner.of(e).grant("agent-5", "x");
        check("审计-未挂审计则不写", quiet.all().isEmpty());
    }

    /* ---------- ④ 读快照不可变 ---------- */

    static void immutability() {
        PermissionEngine e = new PermissionEngine();
        PermissionProvisioner.of(e).grant("a", "p:1", "p:2");
        Set<String> snap = e.permissionsOf("a");
        boolean immutable;
        try {
            snap.add("p:3");
            immutable = false;
        } catch (UnsupportedOperationException ex) {
            immutable = true;
        }
        check("只读-权限快照不可改", immutable);
        check("只读-快照未受影响", e.permissionsOf("a").size() == 2);
        check("只读-未知主体返回空集", e.permissionsOf("nobody").isEmpty());
        check("只读-assertAllowed 缺权限抛异常", throwsDenied(e));
    }

    static boolean throwsDenied(PermissionEngine e) {
        try {
            e.assertAllowed("a", new String[]{"p:9"});
            return false;
        } catch (RuntimeException ex) {
            return true;
        }
    }
}
