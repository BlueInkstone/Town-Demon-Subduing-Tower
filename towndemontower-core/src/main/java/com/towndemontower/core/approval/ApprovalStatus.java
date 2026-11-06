package com.towndemontower.core.approval;

/**
 * [core] 审批单状态。
 *
 * <p>{@link #EXPIRED} 是"默认拒绝"的落地形态：超过 TTL 未处理即视为拒绝，
 * 而不是无限期挂起——挂起的审批单会让调用方一直等，或被迫加一个"超时就放行"的补丁。</p>
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED
}
