package com.towndemontower.spring;

import com.towndemontower.core.approval.ApprovalCenter;
import com.towndemontower.core.approval.ApprovalStatus;
import com.towndemontower.core.audit.AnchorReplicaState;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.core.gate.GateScriptRunner;
import com.towndemontower.core.metrics.GovernanceMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [starter] 治理观测端点（默认关闭）。
 *
 * <p><b>为什么默认关闭</b>：接入方引入一个治理 starter，不该顺便多出两个公开 HTTP 端点。
 * 这与"不用 {@code application.properties} 污染接入方"是同一条原则。</p>
 *
 * <p><b>三个必须为 0 的指标</b>（{@code ungovernedInvocations} / {@code auditChainValid} 为 false /
 * 待审批堆积）任何一项非正常，都应当告警而不是"观察一下"。</p>
 */
@RestController
@RequestMapping("/towndemontower")
public class GovernanceMetricsController {

    private final GovernanceMetrics metrics;
    private final ApprovalCenter approvalCenter;
    private final AuditStore auditStore;
    private final TownDemontowerProperties props;
    private final GovernanceIntegrityChecker checker;

    /**
     * 兼容构造器（V1.5 及更早的手工装配方式）：不注入自检器时，
     * {@code /integrity} 的发现项由 {@link #fallbackFindings(boolean)} 就地计算。
     * 自动配置路径始终注入自检器，保证"启动判定"与"端点展示"同源。
     */
    public GovernanceMetricsController(GovernanceMetrics metrics,
                                       ApprovalCenter approvalCenter,
                                       AuditStore auditStore,
                                       TownDemontowerProperties props) {
        this(metrics, approvalCenter, auditStore, props, null);
    }

    public GovernanceMetricsController(GovernanceMetrics metrics,
                                       ApprovalCenter approvalCenter,
                                       AuditStore auditStore,
                                       TownDemontowerProperties props,
                                       GovernanceIntegrityChecker checker) {
        this.metrics = metrics;
        this.approvalCenter = approvalCenter;
        this.auditStore = auditStore;
        this.props = props;
        this.checker = checker;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(
            @RequestHeader(value = "X-Governance-Token", required = false) String token) {
        requireToken(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counters", metrics.snapshot());
        out.put("denialRate", metrics.denialRate());
        out.put("falseRejectionRate", metrics.falseRejectionRate());
        out.put("overConservatismRate", metrics.overConservatismRate());
        out.put("misExecutionRate", metrics.misExecutionRate());
        out.put("cacheHitRate", metrics.cacheHitRate());
        return out;
    }

    @GetMapping("/integrity")
    public Map<String, Object> integrity(
            @RequestHeader(value = "X-Governance-Token", required = false) String token) {
        requireToken(token);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean chainOk;
        try {
            chainOk = auditStore.verifyChain();
        } catch (RuntimeException e) {
            chainOk = false;
        }
        out.put("auditChainValid", chainOk);
        out.put("auditRecords", auditStore.all().size());
        // 锚点覆盖到第几条、还差几条：批量追平时 lag>0 是正常状态；
        // 持续上涨说明追平逻辑卡住（或写入速率压过了追平阈值）
        if (auditStore instanceof FileAuditStore file) {
            out.put("auditAnchorRecords", file.anchorRecords());
            out.put("auditAnchorLag", file.anchorLag());
            out.put("auditAnchorBatch", file.anchorBatch());
            // 副本状态：这一项是"外部证词"的唯一线索。本地日志与锚点永远自洽，
            // 因此运营侧真正该盯的是副本落后多少、以及失败计数是否在涨。
            List<Map<String, Object>> reps = new ArrayList<>();
            for (AnchorReplicaState st : file.replicaStates()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", st.name());
                m.put("offHost", st.offHost());
                m.put("records", st.records());
                m.put("readable", st.readable());
                if (st.error() != null) m.put("error", st.error());
                reps.add(m);
            }
            out.put("auditAnchorReplicas", reps);
            out.put("auditAnchorReplicaFailures", file.replicaFailures());
            out.put("auditAnchorReplicaErrors", file.replicaErrors());
        }
        out.put("pendingApprovals", approvalCenter.list(ApprovalStatus.PENDING).size());

        // 发现项只从自检器取（单一来源）：控制器自己再算一遍必然与启动时判断漂移
        List<String> findings = checker != null
                ? checker.codes()
                : fallbackFindings(chainOk);
        out.put("findings", findings);
        out.put("compliant", checker != null ? checker.compliant() : findings.isEmpty());
        out.put("mode", props.getMode());
        return out;
    }

    /** 未注入自检器时的降级判断（正常装配下不会走到）。 */
    private List<String> fallbackFindings(boolean chainOk) {
        List<String> out = new java.util.ArrayList<>();
        if (props.isAutoGrantDeclaredPerms()) out.add("AUTO_GRANT_ENABLED");
        GateScriptRunner.KeyStatus gateKey = GateScriptRunner.envStatus();
        if (gateKey == GateScriptRunner.KeyStatus.MISSING) out.add("GATE_KEY_MISSING");
        if (gateKey == GateScriptRunner.KeyStatus.WEAK) out.add("GATE_KEY_WEAK");
        if (!chainOk) out.add("AUDIT_CHAIN_BROKEN");
        if ("memory".equalsIgnoreCase(props.getAuditStorage())) out.add("AUDIT_VOLATILE");
        return out;
    }

    /**
     * 访问控制：观测数据包含工具调用分布、拒绝原因、审批积压，属于内部信息，
     * 因此要求 {@code X-Governance-Token} 与配置一致。
     * 未配置令牌时一律 403（此时启动自检本应已拒绝启动，这里是第二道防线）。
     */
    private void requireToken(String token) {
        String expected = props.getMetricsToken();
        if (expected == null || expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "governance endpoint disabled: token not configured");
        }
        if (token == null || !expected.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid governance token");
        }
    }
}
