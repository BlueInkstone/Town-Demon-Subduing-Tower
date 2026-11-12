package com.towndemontower.core.executor;

import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.core.approval.HumanApprovalPolicy;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ErrorCodeCatalog;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.decorator.GovernancePass;
import com.towndemontower.core.mode.AgentMode;
import com.towndemontower.core.mode.DefaultModeToolPolicy;
import com.towndemontower.core.policy.DefaultRiskGate;
import com.towndemontower.core.policy.SimpleShellAstAnalyzer;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Collections;
import java.util.HashMap;
import java.nio.file.Paths;

/**
 * [core] 网关"只裁决不执行"链的契约测试：GW-01 引擎 golden + GW-06 不执行/不发牌。
 *
 * <p>与 {@code contract/gateway-golden.json} 及 {@code contract/verify_codes.py} 的 GW-01 段落同源：
 * 这里跑的是真实 core 链，保证"同一 IntentObject → 同一裁决"可在 Java 侧复算。</p>
 */
public class GatewayVerdictChainTest {

    private static final String AGENT = "gw-agent";

    private Executor buildChain(FileAuditStore audit) {
        SkillRegistry registry = new SkillRegistry();
        PermissionEngine pe = new PermissionEngine();
        PermissionProvisioner provisioner = PermissionProvisioner.of(pe, null, "test");
        List<String> perms = new ArrayList<>();
        for (String name : registry.names()) {
            SkillDescriptor d = registry.get(name);
            if (d != null && d.requiredPerms != null) {
                for (String p : d.requiredPerms) perms.add(p);
            }
        }
        provisioner.grant(AGENT, perms.toArray(new String[0]));
        return ExecutorFactory.buildVerdictOnly(
                registry,
                new DefaultModeToolPolicy(),
                new DefaultRiskGate(new SimpleShellAstAnalyzer()),
                pe,
                new HumanApprovalPolicy(),
                null,
                audit);
    }

    private static ExecutionContext ctx() {
        return new ExecutionContext("s1", "t1", "tenant", AGENT, "owner", false, AgentMode.DEV_EXECUTION);
    }

    private static FileAuditStore tempAudit(String tag) throws Exception {
        return new FileAuditStore(Paths.get(System.getProperty("java.io.tmpdir"),
                "gw-chain-" + tag + "-" + System.nanoTime() + ".log"));
    }

    /** GW-01：引擎侧 golden —— 允许 / 拒绝 / 待审批 / 非法意图 / 缺字段 / 低置信，逐一复算。 */
    @Test
    void gw01_engineGolden() throws Exception {
        Executor chain = buildChain(tempAudit("golden"));

        SkillResult allow = chain.execute(
                new IntentObject("runCompile", Collections.singletonMap("module", "x"), 0.9, "编译", "产出", "无"), ctx());
        assertEquals(ErrorCodeCatalog.OK, allow.code);
        assertTrue(allow.success);

        SkillResult deny = chain.execute(
                new IntentObject("deleteData", Collections.singletonMap("table", "tmp"), 0.9, "清", "x", "无"), ctx());
        assertEquals("R3_DENIED", deny.code);
        assertFalse(deny.success);

        SkillResult pending = chain.execute(
                new IntentObject("deployService", new HashMap<String,Object>(){{put("target", "p");put("version", "1");}},
                        0.95, "发布", "上线", "回滚"), ctx());
        assertEquals("PENDING_APPROVAL", pending.code);
        assertNotNull(pending.approvalTicketId);

        SkillResult invalid = chain.execute(
                new IntentObject("runCompile", Collections.singletonMap("module", "x"), 0.9, "建议你执行编译", "x", "无"), ctx());
        assertEquals(ErrorCodes.INTENT_FORMAT_INVALID, invalid.code);

        // 注：空 tool 在原始链上先被模式白名单层判为 MODE_TOOL_NOT_ALLOWED；
        // "缺字段 → INTENT_FORMAT_INVALID" 是网关控制器解析层的契约，见 EvaluateControllerTest。
        SkillResult low = chain.execute(
                new IntentObject("runCompile", Collections.singletonMap("module", "x"), 0.1, "不确定", "x", "无"), ctx());
        assertEquals("NEED_MORE_INFO", low.code);
    }

    /** GW-06：只裁决不执行 —— 不触发真实执行、不误发 GovernancePass 令牌。 */
    @Test
    void gw06_verdictOnlyNoExecutionNoToken() throws Exception {
        Executor chain = buildChain(tempAudit("n-exec"));
        GovernancePass.resetThread();

        SkillResult r = chain.execute(
                new IntentObject("runCompile", Collections.singletonMap("module", "x"), 0.9, "编译", "产出", "无"), ctx());
        assertTrue(r.success, "裁决应通过（允许语义），但只是允许、未真正执行");

        // 链跳过了 SandboxedToolExecutor（唯一发牌点），不应残留任何 GovernancePass 令牌
        assertFalse(GovernancePass.hasActivePass(), "不应残留 GovernancePass 令牌");
        assertEquals(0, GovernancePass.depth(), "令牌栈深度应为 0（未经过发牌点）");
    }
}
