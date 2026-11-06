package com.towndemontower.spring.tx;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * [starter/tx/test] 捕获型审批事件发布器：记录所有收到的事件，并可在下一次发布时模拟失败，
 * 用于验证 Outbox 的"失败重试 / 崩溃恢复"路径。
 */
public class CapturingPublisher implements ApprovalEventPublisher {

    public final List<ApprovalEvent> received = new CopyOnWriteArrayList<>();
    /** 下一次 publish 抛异常（模拟 WS 不可用），抛出后置为 false（仅失败一次）。 */
    public volatile boolean failNext = false;

    @Override
    public void publish(ApprovalEvent event) {
        if (failNext) {
            failNext = false;
            throw new RuntimeException("simulated WS broker down");
        }
        received.add(event);
    }

    public List<ApprovalEvent> received() {
        return List.copyOf(received);
    }
}
