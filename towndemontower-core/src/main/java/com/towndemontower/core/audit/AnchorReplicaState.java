package com.towndemontower.core.audit;

/**
 * [core] 一个锚点副本的核对结果（V1.10 / I4-c）。
 *
 * <p>启动时由 {@link FileAuditStore} 拉取每个副本生成，供启动日志、自检与
 * {@code /integrity} 共用——三处显示同一份数据，避免"日志说 A、端点说 B"。</p>
 *
 * @param name    副本名（配置里的那一段）
 * @param offHost 副本是否声称跨主机
 * @param records 副本记住的条数；{@code -1} 表示副本为空或读不出来
 * @param hash    副本记住的哈希；读不出来时为 {@code null}
 * @param error   读取失败原因；正常为 {@code null}
 */
public record AnchorReplicaState(String name, boolean offHost, long records, String hash, String error) {

    /** 是否成功读到（不代表与本地一致——一致性由启动校验负责）。 */
    public boolean readable() {
        return error == null;
    }

    /** 是否落后于本地（正常状态，通常是推送失败过或副本刚接入）。 */
    public boolean behind(long localRecords) {
        return readable() && records >= 0 && records < localRecords;
    }
}
