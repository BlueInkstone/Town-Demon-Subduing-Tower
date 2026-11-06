package com.towndemontower.spring.tx;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * [starter/tx] 默认的审批事件发布器：进程内发布/订阅总线，等价演示 WebSocket 推送语义。
 *
 * <p>真实环境应替换为基于 spring-websocket(STOMP) 的实现（将事件 {@code convertAndSend} 到
 * {@code /topic/approvals} 等目标），仅实现 {@link ApprovalEventPublisher} 即可，Outbox 中继不变。
 * 本类不依赖 spring-websocket（离线环境缺失），保证纯 core/starter 可编译。</p>
 */
public class InMemoryWebSocketBroker implements ApprovalEventPublisher {

    private final List<ApprovalEvent> sent = new CopyOnWriteArrayList<>();
    private final List<Consumer<ApprovalEvent>> subscribers = new CopyOnWriteArrayList<>();

    @Override
    public void publish(ApprovalEvent event) {
        sent.add(event);
        for (Consumer<ApprovalEvent> s : subscribers) {
            s.accept(event);
        }
    }

    /** 订阅事件（生产环境即"连上 WebSocket 的审批者客户端"）。 */
    public void subscribe(Consumer<ApprovalEvent> subscriber) {
        subscribers.add(subscriber);
    }

    /** 已发布事件快照（测试断言用）。 */
    public List<ApprovalEvent> sentEvents() {
        return List.copyOf(sent);
    }
}
