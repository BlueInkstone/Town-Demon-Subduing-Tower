package com.towndemontower.examples.apiclient;

/**
 * 中间件：包在"发送请求"外面的一层可组合逻辑。
 *
 * <p>实现上是<b>洋葱模型</b>（也叫责任链 / around 模式）：</p>
 * <pre>
 *   onError( logging( retry( token( 统一信封( 真实发送 ) ) ) ) )
 *         └─ 每层只负责一件事，调用 next.proceed(request) 才把控制权交给内层
 * </pre>
 *
 * <p>由此得到三种能力，而不需要任何框架：
 * <ul>
 *   <li><b>请求拦截</b>：调 {@code proceed} 之前可以改请求（{@link ApiRequest#withHeader}），
 *       甚至直接返回一个假响应（短路，例如命中缓存）；</li>
 *   <li><b>响应处理</b>：拿到内层返回的 {@link ApiResponse} 后可以改写、补字段、统一格式；</li>
 *   <li><b>异常控制</b>：内层抛 {@link ApiException} 时，本层可以重试、降级、上报，或者原样抛出。</li>
 * </ul>
 *
 * <p>约定：<b>中间件必须"透明"</b>——不改变请求语义（除非它本身就是语义的一部分，如补 token），
 * 不吞掉异常（可以处理后再抛，但不能假装成功），不缓存可变对象。</p>
 */
public interface Middleware {

    /** 名字进日志与 {@link MiddlewareChain#names()}，排错时能直接看出链条顺序。 */
    String name();

    /**
     * @param request 进入本层的请求（不可变，需要改就返回新对象）
     * @param next    内层；调 {@code next.proceed(...)} 才继续往下走
     */
    ApiResponse apply(ApiRequest request, Next next);

    /** 内层引用。实现类只应"调用一次"，重试中间件除外。 */
    interface Next {
        ApiResponse proceed(ApiRequest request);
    }
}
