package com.towndemontower.core.gate;

import java.time.Duration;

/**
 * [core] 门禁脚本契约（语言中立）。compile / unit-test / lint / eslint / schema-check 等。
 * 脚本只读、签名/哈希校验；Agent 不得修改门禁脚本，确需修改走 R2 审批。
 */
public interface GateScript {
    String name();

    String command();

    /** 签名哈希；运行前校验，不匹配直接拒绝执行。 */
    String signedHash();

    Duration timeout();

    int maxRetry();
}
