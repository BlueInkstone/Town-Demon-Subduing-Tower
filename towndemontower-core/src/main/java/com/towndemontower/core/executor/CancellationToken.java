package com.towndemontower.core.executor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * [core] 取消令牌（NFR-5）。贯穿 SkillExecutor / 沙箱 / Broker，用于在途 Tool 调用的急停。
 * 线程安全，可被 Ops 急停控制台或取消请求触发。
 */
public class CancellationToken {
    private final String id;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public CancellationToken(String id) {
        this.id = id;
    }

    public CancellationToken() {
        this("anon");
    }

    public String id() {
        return id;
    }

    /** 触发取消（急停）。 */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
