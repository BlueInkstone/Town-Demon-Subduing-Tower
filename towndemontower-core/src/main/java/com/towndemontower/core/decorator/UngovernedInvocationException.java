package com.towndemontower.core.decorator;

import com.towndemontower.core.contract.ErrorCodes;

/**
 * [core] 非受治理调用异常（V1.11 / D2）。
 *
 * <p>受治理方法的实现首行调用 {@link GovernancePass#require(String)}，但当前线程栈上没有
 * 对应的通行令牌——要么方法被自调用（{@code this.x()}，AOP 不生效），要么被别的代码直接
 * 调用（绕过切面直调下层 Service）。这两种情况以前是<b>静默失效</b>：方法照常执行，
 * 治理链一步没走、审计一条没有。现在它们变成一次显式失败。</p>
 *
 * <p>unchecked 而非 checked：受治理方法的签名不能被治理设施污染；调用方通常也不该
 * catch 它——它表示"代码写错了"，不是"业务被拒绝了"。需要统一契约（如 REST 层）时，
 * starter 的切面会把它翻译成 {@code GovernanceDeniedException}，错误码一致。</p>
 */
public class UngovernedInvocationException extends RuntimeException {

    private final String code = ErrorCodes.UNGOVERNED_INVOCATION;
    private final String tool;

    public UngovernedInvocationException(String tool, String reason) {
        super(ErrorCodes.UNGOVERNED_INVOCATION + ": tool '" + tool + "' " + reason);
        this.tool = tool;
    }

    /** 固定为 {@link ErrorCodes#UNGOVERNED_INVOCATION}，便于调用方按码分流。 */
    public String getCode() {
        return code;
    }

    /** 本次调用声称要执行的工具名（即 {@code require(...)} 的实参）。 */
    public String getTool() {
        return tool;
    }
}
