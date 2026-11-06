package com.towndemontower.examples.apiclient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 中间件注册表：负责"注册"与"执行顺序"，本身不写业务逻辑。
 *
 * <p><b>顺序语义</b>：{@code order} 越小越靠<b>外层</b>（越早拿到请求、越晚拿到响应）。
 * 例如：</p>
 * <pre>
 *   chain.add(0, onError)      // 最外层：统一兜底，任何层抛出都能捕获
 *   chain.add(1, retry)        // 只重试"内层"的失败
 *   chain.add(2, logging)      // 记录每一次真实尝试（放在 retry 内层才有意义）
 *   chain.add(3, token)        // 每次尝试都重新补头
 *   chain.add(4, unifiedEnvelope) // 最内层：把 HTTP/业务错误转成 ApiException
 * </pre>
 *
 * <p>同 order 时按<b>注册先后</b>稳定排序（先注册的先执行），这样链条行为可复现，
 * 不依赖 Map/HashSet 的迭代顺序——那是最常见的"本地好好的，换台机器顺序就变了"来源。</p>
 */
public final class MiddlewareChain {

    private static final class Entry {
        final int order;
        final long seq;
        final Middleware middleware;

        Entry(int order, long seq, Middleware middleware) {
            this.order = order;
            this.seq = seq;
            this.middleware = middleware;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private long seq = 0;

    /** 追加到最内层（order 默认取当前最大值 + 1）。 */
    public synchronized MiddlewareChain add(Middleware middleware) {
        int order = entries.isEmpty() ? 0 : entries.get(entries.size() - 1).order + 1;
        return add(order, middleware);
    }

    public synchronized MiddlewareChain add(int order, Middleware middleware) {
        if (middleware == null) throw new IllegalArgumentException("middleware 不能为 null");
        entries.add(new Entry(order, seq++, middleware));
        entries.sort(Comparator.comparingInt((Entry e) -> e.order).thenComparingLong(e -> e.seq));
        return this;
    }

    public synchronized int size() { return entries.size(); }

    /** 给日志/自检用：实际执行顺序。 */
    public synchronized List<String> names() {
        List<String> out = new ArrayList<>(entries.size());
        for (Entry e : entries) out.add(e.order + ":" + e.middleware.name());
        return out;
    }

    /**
     * 由内向外包裹成链，再交给最外层执行。
     *
     * <p>注意这里是从尾部往前包：最后注册（order 最小）的成为最外层。
     * 用统一的 {@link Middleware.Next} 接口而不是递归，是为了让每个中间件都拿到
     * "内层的一个函数引用"，重试中间件才可以安全地多次调用它。</p>
     */
    public synchronized ApiResponse execute(ApiRequest request, Terminal terminal) throws ApiException {
        Middleware.Next next = terminal::send;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Middleware m = entries.get(i).middleware;
            Middleware.Next inner = next;
            next = r -> m.apply(r, inner);
        }
        return next.proceed(request);
    }

    /** 链条的终点：真正发 HTTP 的那一步（由 {@link ApiClient} 提供）。 */
    public interface Terminal {
        ApiResponse send(ApiRequest request);
    }
}
