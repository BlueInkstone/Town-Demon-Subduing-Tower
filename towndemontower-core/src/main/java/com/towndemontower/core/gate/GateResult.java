package com.towndemontower.core.gate;

/**
 * [core] 门禁执行结果。
 */
public class GateResult {
    public final boolean passed;
    public final String output;
    public final int attempts;

    private GateResult(boolean passed, String output, int attempts) {
        this.passed = passed;
        this.output = output;
        this.attempts = attempts;
    }

    public static GateResult passed(int attempts) {
        return new GateResult(true, "OK", attempts);
    }

    public static GateResult failed(String output, int attempts) {
        return new GateResult(false, output, attempts);
    }
}
