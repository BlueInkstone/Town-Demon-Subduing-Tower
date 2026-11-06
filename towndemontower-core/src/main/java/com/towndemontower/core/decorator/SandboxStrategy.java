package com.towndemontower.core.decorator;

import com.towndemontower.core.executor.CancellationToken;

/**
 * [V1.2] 沙箱策略接口。将"如何隔离执行命令"从装饰器链解耦，
 * 便于后续接入进程级隔离（ProcessSandboxStrategy）乃至 Docker 容器沙箱（V1.2.1）。
 */
public interface SandboxStrategy {
    /** 在隔离环境执行命令，返回标准输出；取消令牌触发则尽快终止并抛异常。 */
    String execute(String command, CancellationToken token) throws Exception;
}
