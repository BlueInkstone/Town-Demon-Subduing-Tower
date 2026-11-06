package com.towndemontower.spring.tx;

/**
 * [starter/tx] 审批事件发布抽象。默认实现为内存消息总线（{@link InMemoryWebSocketBroker}），
 * 等价演示 WebSocket 推送语义；生产环境可替换为基于 spring-websocket(STOMP) 的实现，
 * 仅实现本接口即可，Outbox 中继无需改动。
 */
public interface ApprovalEventPublisher {
    /** 将治理事件推送给订阅者（审批者 / Ops 面板）。 */
    void publish(ApprovalEvent event);
}
