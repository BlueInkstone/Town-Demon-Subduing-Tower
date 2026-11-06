package com.towndemontower.delivery.web;

import com.towndemontower.delivery.governance.DeliveryGovernanceException;
import com.towndemontower.delivery.service.DeliveryNotFoundException;
import com.towndemontower.delivery.service.DeliveryValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [delivery/web] 看板接口的异常映射。
 *
 * <ul>
 *   <li>字段校验失败 → 400；</li>
 *   <li>交付项不存在 → 404；</li>
 *   <li>治理链待人工审批 → 202（返回审批单号，前端提示"已提交审批"）；</li>
 *   <li>治理链拒绝（权限不足 / 模式不允许 / 风险闸门拒绝） → 403。</li>
 * </ul>
 */
@RestControllerAdvice
public class DeliveryExceptionHandler {

    @ExceptionHandler(DeliveryValidationException.class)
    public ResponseEntity<Map<String, Object>> onValidation(DeliveryValidationException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "VALIDATION_FAILED");
        body.put("field", e.field());
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(DeliveryNotFoundException.class)
    public ResponseEntity<Map<String, Object>> onNotFound(DeliveryNotFoundException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "NOT_FOUND");
        body.put("id", e.id());
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(DeliveryGovernanceException.class)
    public ResponseEntity<Map<String, Object>> onGovernance(DeliveryGovernanceException e) {
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
