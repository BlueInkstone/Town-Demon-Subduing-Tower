package com.towndemontower.spring.admin;

import com.towndemontower.core.audit.AuditRecord;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.FileAuditStore;
import com.towndemontower.spring.TownDemontowerProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [starter/admin] 审计查询接口。
 *
 * <p><b>只读</b>：审计链的接口层没有写入方法，这里也不提供"补一条""改一条"。
 * 查询结果按链内顺序（seq 升序）返回，偏移与总数都基于当前内存视图，
 * 用于排障与取证，不作为分账依据。</p>
 *
 * <p>动作过滤是<b>精确匹配</b>（忽略大小写），内容过滤是 payload 子串匹配；
 * 两者都可省略。没有时间字段可供过滤——审计记录只存"序号 + 动作 + 哈希 + 内容"，
 * 时间信息在内容里，由写入方决定是否记录。</p>
 */
@RestController
@RequestMapping("/towndemontower/admin/audit")
public class AuditAdminController {

    private final AuditStore auditStore;
    private final AdminGuard guard;

    public AuditAdminController(AuditStore auditStore, TownDemontowerProperties props) {
        this.auditStore = auditStore;
        this.guard = new AdminGuard(props);
    }

    @GetMapping
    public ApiResponse list(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        guard.check(token);
        int lim = AdminParams.limit(limit, 100, 500);
        int off = AdminParams.offset(offset);

        List<AuditRecord> matched = new ArrayList<>();
        for (AuditRecord r : auditStore.all()) {
            if (action != null && !action.isBlank()
                    && !action.trim().equalsIgnoreCase(r.action)) continue;
            if (q != null && !q.isBlank()
                    && (r.payload == null || !r.payload.contains(q))) continue;
            matched.add(r);
        }
        int from = Math.min(off, matched.size());
        int to = Math.min(from + lim, matched.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (AuditRecord r : matched.subList(from, to)) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("seq", r.seq);
            one.put("action", r.action);
            one.put("hash", r.hash);
            one.put("payload", r.payload);
            items.add(one);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", matched.size());
        data.put("limit", lim);
        data.put("offset", off);
        data.put("items", items);
        return ApiResponse.ok(data);
    }

    /** 整链重算校验 + 锚点覆盖情况（批量追平时 anchorLag>0 属正常）。 */
    @GetMapping("/verify")
    public ApiResponse verify(
            @RequestHeader(value = AdminGuard.HEADER, required = false) String token) {
        guard.check(token);
        boolean valid;
        try {
            valid = auditStore.verifyChain();
        } catch (RuntimeException e) {
            valid = false;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", valid);
        data.put("records", auditStore.all().size());
        if (auditStore instanceof FileAuditStore file) {
            data.put("anchorRecords", file.anchorRecords());
            data.put("anchorLag", file.anchorLag());
            data.put("replicaFailures", file.replicaFailures());
        }
        return ApiResponse.ok(data);
    }
}
