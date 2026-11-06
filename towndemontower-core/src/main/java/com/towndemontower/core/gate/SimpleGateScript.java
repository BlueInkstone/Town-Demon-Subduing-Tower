package com.towndemontower.core.gate;

import java.time.Duration;

/**
 * [core] 简单门禁脚本实现。
 *
 * <p>四参构造器用<b>环境变量</b>密钥自签名，因此密钥不可用时会在构造期直接抛异常——
 * 宁可起不来，也不要带着一个假签名进入运行期。单测/内嵌场景请用五参构造器显式传入密钥。</p>
 */
public class SimpleGateScript implements GateScript {
    private final String name;
    private final String command;
    private final String signedHash;
    private final Duration timeout;
    private final int maxRetry;

    /** 生产路径：签名密钥只能来自环境变量 {@link GateScriptRunner#KEY_ENV}。 */
    public SimpleGateScript(String name, String command, Duration timeout, int maxRetry) {
        this(name, command, timeout, maxRetry, GateScriptRunner.requireEnvKey());
    }

    /** 显式注入密钥（单测 / 内嵌场景），密钥不落源码。 */
    public SimpleGateScript(String name, String command, Duration timeout, int maxRetry, byte[] signingKey) {
        this.name = name;
        this.command = command;
        this.signedHash = (signingKey == null || signingKey.length == 0)
                ? null : GateScriptRunner.sign(signingKey, this);
        this.timeout = timeout;
        this.maxRetry = maxRetry;
    }

    @Override public String name() { return name; }
    @Override public String command() { return command; }
    @Override public String signedHash() { return signedHash; }
    @Override public Duration timeout() { return timeout; }
    @Override public int maxRetry() { return maxRetry; }
}
