package com.towndemontower.gateway.core;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.core.contract.ErrorCodes;
import com.towndemontower.core.contract.ExecutionContext;
import com.towndemontower.core.contract.Executor;
import com.towndemontower.core.contract.IntentObject;
import com.towndemontower.core.contract.SkillDescriptor;
import com.towndemontower.core.contract.SkillResult;
import com.towndemontower.core.executor.ExecutorFactory;
import com.towndemontower.core.executor.PermissionEngine;
import com.towndemontower.core.executor.PermissionProvisioner;
import com.towndemontower.core.executor.SkillRegistry;
import com.towndemontower.core.mode.DefaultModeToolPolicy;
import com.towndemontower.core.policy.DefaultRiskGate;
import com.towndemontower.core.policy.SimpleShellAstAnalyzer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * [gateway] 裁决引擎：装配 core 的"只裁决不执行"链，并对服务端配置身份做权限下发。
 *
 * <p>网关是策略决策点：真正的鉴权在 API 令牌层（见 {@code EvaluateHandler}），
 * RBAC 层在网关内被如实执行——服务端把自己的配置身份（agentId）授予已注册工具所需的
 * 全部权限，因此合法 R0/R1/R2 工具能走到风险闸门，而不是在 RBAC 层被无意义地拦下。</p>
 *
 * <p><b>审计的两个写入口</b>：进入裁决链的请求由链最外层的 {@code AuditDecorator} 记录；
 * 连链都没进的入参拒绝（超长 / 控制字符）在这里补记 {@link GatewayAudit}——
 * 否则这类拒绝在审计里完全不可见。</p>
 */
public class GatewayVerdictEngine {

    /** 请求级入参上限（与 core 输入校验 C-12 对齐）：单参数值超过 1MiB 直接拒绝。 */
    public static final int MAX_ARG_LENGTH = 1_048_576;
    /** 控制字符范围（与 core 输入校验 C-10 对齐）：含 \x00–\x1f 直接拒绝。 */
    private static final int CONTROL_LIMIT = 0x20;

    private final Executor chain;
    private final AuditStore audit;

    /**
     * @param auditPath 审计文件路径（构造 {@link FileAuditStore}）
     */
    public GatewayVerdictEngine(String agentId, String auditPath) {
        this(agentId, new FileAuditStore(Paths.get(auditPath)));
    }

    /**
     * @param audit 与 HTTP 入口共用的审计存储。
     *              必须<b>同一个实例</b>：两条各自独立打开同一文件会各写各的段头与外锚，把链弄坏。
     */
    public GatewayVerdictEngine(String agentId, AuditStore audit) {
        SkillRegistry registry = new SkillRegistry();
        PermissionEngine permissionEngine = new PermissionEngine();

        // 服务端配置身份：授予已注册工具所需的全部权限（网关是策略决策点，
        // 真正鉴权在服务端 API 令牌层；此处让 RBAC 层被如实执行，不静默放行）。
        PermissionProvisioner provisioner =
                PermissionProvisioner.of(permissionEngine, null, "gateway-server");
        List<String> allPerms = new ArrayList<>();
        for (String name : registry.names()) {
            SkillDescriptor d = registry.get(name);
            if (d != null && d.requiredPerms != null) {
                for (String p : d.requiredPerms) allPerms.add(p);
            }
        }
        provisioner.grant(agentId, allPerms.toArray(new String[0]));

        this.audit = audit;
        this.chain = ExecutorFactory.buildVerdictOnly(
                registry,
                new DefaultModeToolPolicy(),
                new DefaultRiskGate(new SimpleShellAstAnalyzer()),
                permissionEngine,
                new com.towndemontower.core.approval.HumanApprovalPolicy(),
                null, // 无审批中心：R2 走内存单号（待审批）
                audit);
    }

    /** 供 HTTP 入口写入口层审计：必须与链共用同一个实例，见构造器注释。 */
    public AuditStore audit() {
        return audit;
    }

    /**
     * 裁决一次意图。先做请求级入参校验（超长 / 控制字符），不通过直接拒绝并补记审计；
     * 通过则交给 core 的只裁决链。裁决链内部异常由 AuditDecorator 兜底为明确拒绝 + 审计留痕。
     *
     * @return 一律返回可序列化裁决（success / code / message / output / approvalTicketId）
     */
    public SkillResult evaluate(IntentObject intent, ExecutionContext ctx) {
        SkillResult inputCheck = validateIntent(intent);
        if (inputCheck != null) {
            // 这条路径不进链，链最外层的 AuditDecorator 看不到它 —— 必须在这里补记
            GatewayAudit.record(audit, intent == null ? null : intent.tool, inputCheck.code,
                    GatewayAudit.STAGE_INPUT, ctx);
            return inputCheck;
        }
        return chain.execute(intent, ctx);
    }

    /**
     * 请求级入参校验（网关入口层）。返回非 null 表示应直接拒绝。
     * 与 core 输入校验口径对齐：超长参数 → INPUT_REJECTED；控制字符 → INPUT_REJECTED。
     */
    public SkillResult validateIntent(IntentObject intent) {
        if (intent == null || intent.args == null) {
            return SkillResult.denied(ErrorCodes.INTENT_FORMAT_INVALID, "intent or args missing");
        }
        for (java.util.Map.Entry<String, Object> entry : intent.args.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof String) {
                String s = (String) v;
                if (s.length() > MAX_ARG_LENGTH) {
                    return SkillResult.denied(ErrorCodes.INPUT_REJECTED,
                            "arg '" + entry.getKey() + "' exceeds " + MAX_ARG_LENGTH + " bytes");
                }
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c < CONTROL_LIMIT && c != '\t' && c != '\n' && c != '\r') {
                        return SkillResult.denied(ErrorCodes.INPUT_REJECTED,
                                "arg '" + entry.getKey() + "' contains control character");
                    }
                }
            }
        }
        return null;
    }
}
