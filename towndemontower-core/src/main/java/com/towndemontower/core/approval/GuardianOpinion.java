package com.towndemontower.core.approval;

/**
 * [core] Guardian 预审意见（仅建议，不可信来源，不进入可执行意图）。
 */
public class GuardianOpinion {
    public final String summary;
    public final boolean risky;
    public final String advice;

    public GuardianOpinion(String summary, boolean risky, String advice) {
        this.summary = summary;
        this.risky = risky;
        this.advice = advice;
    }
}
