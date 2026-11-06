package com.towndemontower.agents.web;

import com.towndemontower.agents.governance.AgentGovernanceException;
import com.towndemontower.agents.service.AgentInactiveException;
import com.towndemontower.agents.service.AgentNotFoundException;
import com.towndemontower.agents.service.AgentValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [agents/web] 接口的异常映射。
 *
 * <ul>
 *   <li>字段校验失败 → 400；</li>
 *   <li>Agent 不存在 → 404；</li>
 *   <li>状态不允许执行 → 409（与权限不足区分开）；</li>
 *   <li>治理链待人工审批 → 202；治理链拒绝 → 403。</li>
 * </ul>
 */
@RestControllerAdvice
public class AgentExceptionHandler {

    @ExceptionHandler(AgentValidationException.class)
    public ResponseEntity<Map<String, Object>> onValidation(AgentValidationException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "VALIDATION_FAILED");
        body.put("field", e.field());
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(AgentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> onNotFound(AgentNotFoundException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "NOT_FOUND");
        body.put("id", e.id());
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(AgentInactiveException.class)
    public ResponseEntity<Map<String, Object>> onInactive(AgentInactiveException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "AGENT_INACTIVE");
        body.put("agentId", e.agentId());
        body.put("status", e.status() == null ? null : e.status().name());
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(AgentGovernanceException.class)
    public ResponseEntity<Map<String, Object>> onGovernance(AgentGovernanceException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code());
        body.put("message", e.getMessage());
        if (e.approvalTicketId() != null) {
            body.put("approvalTicketId", e.approvalTicketId());
        }
        if (e.isPendingApproval()) {
            body.put("message", "该操作需要人工审批，已提交审批单：" + e.approvalTicketId());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
