package com.towndemontower.core.audit;

/**
 * [core] 审计锚点副本（V1.10 / I4-c）。
 *
 * <p><b>为什么本地锚点还不够</b>：锚点与日志同机同盘（甚至同目录）时，"能改日志的人"
 * 通常也是"能删锚点的人"。最省事的攻击甚至不用伪造——把整目录回滚到某个旧快照即可：
 * 日志自洽、锚点也自洽、MAC 也对得上，<b>链上没有任何一处不一致</b>。
 * 只有一份"当时确实已经写到 N 条"的第三方记录才能识破它。</p>
 *
 * <p>因此副本只有一条硬要求：<b>它与本地日志不在同一个可写域里</b>。
 * 落在另一块盘/另一个挂载点是"权限域隔离"，只有跨进程、跨主机才是真正的"异地"。
 * 框架核不实这件事，所以每个实现都要如实回答 {@link #offHost()}
 * ——运维声明与事实不符时，自检报出来的告警就是给人改配置用的。</p>
 *
 * <p>实现约束：</p>
 * <ul>
 *   <li>{@link #push} 抛异常 = 副本没兜住证据，由 {@link FileAuditStore} 按
 *       {@code replicaRequired} 决定是拒绝启动/调用还是只计数告警；</li>
 *   <li>{@link #pull} 返回 {@code null} 表示"还没有任何锚点"（首次使用），
 *       抛异常表示"读不出来"，两者语义不同，不能混为一谈；</li>
 *   <li>实现必须<b>只追加</b>：副本自己能覆盖自己的旧值，回滚就检不出来了。</li>
 * </ul>
 */
public interface AnchorReplica {

    /** 副本名（日志与端点里显示；配置里的一段就是它的来源）。 */
    String name();

    /** 是否跨主机（由实现如实回答，框架无法核实文件副本是否真的在别的机器上）。 */
    boolean offHost();

    /**
     * 推一条锚点快照。
     *
     * @throws RuntimeException 副本不可写；消息会被原样带进审计/自检，不要放敏感内容
     */
    void push(AnchorSnapshot snapshot);

    /**
     * 读回副本记住的最新锚点。
     *
     * @return {@code null} 表示副本尚未记录任何锚点
     * @throws RuntimeException 副本不可读或内容损坏
     */
    AnchorSnapshot pull();

    /** 最近一次失败的原因；正常时 {@code null}。仅用于诊断，不参与判定。 */
    String lastError();
}
