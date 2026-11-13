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
 * <p>动作过滤是<b>精确匹配</b>（忽略大小写），内容过滤是 payload 子串匹配，两者都可省略。</p>
 *
 * <p><b>时间过滤（V2.2 起）</b>：{@code from} / {@code to} 接受 ISO-8601 UTC 毫秒时间
 * （如 {@code 2026-11-13T00:00:00.000Z}），<b>两端都包含</b>。时间戳是定长 UTC 字符串，
 * 因此直接按字符串比较即可，不需要解析时区。</p>
 *
 * <p>注意一个刻意的取舍：一旦带了时间条件，<b>没有时间戳的旧记录会被排除</b>——
 * 它们无法被放到时间轴上，硬塞进结果里只会让人误以为"那段时间确实没有调用"。
 * 运维要知道"有多少条旧记录被排除"，看 {@code items} 里是否出现 {@code ts=null} 即可
 * （不过滤时不带 ts 的旧记录照常返回，字段为 {@code null}）。</p>
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
            @RequestParam(value = "offset", required = false) Integer offset,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        guard.check(token);
        int lim = AdminParams.limit(limit, 100, 500);
        int off = AdminParams.offset(offset);
        String lower = (from == null || from.isBlank()) ? null : from.trim();
        String upper = (to == null || to.isBlank()) ? null : to.trim();

        List<AuditRecord> matched = new ArrayList<>();
        for (AuditRecord r : auditStore.all()) {
            if (action != null && !action.isBlank()
                    && !action.trim().equalsIgnoreCase(r.action)) continue;
            if (q != null && !q.isBlank()
                    && (r.payload == null || !r.payload.contains(q))) continue;
            // 定长 UTC 时间戳：字符串比较即时间比较；旧记录没有 ts，带时间条件时排除
            if (lower != null && (r.ts == null || r.ts.compareTo(lower) < 0)) continue;
            if (upper != null && (r.ts == null || r.ts.compareTo(upper) > 0)) continue;
            matched.add(r);
        }
        // 起始/结束下标用独立变量名：from / to 已经是时间筛选参数，重名会直接编译失败
        int begin = Math.min(off, matched.size());
        int end = Math.min(begin + lim, matched.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (AuditRecord r : matched.subList(begin, end)) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("seq", r.seq);
            one.put("action", r.action);
            one.put("hash", r.hash);
            one.put("ts", r.ts);
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
