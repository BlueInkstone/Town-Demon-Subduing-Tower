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
public final class AnchorReplicaState {
    private final String name;
    private final boolean offHost;
    private final long records;
    private final String hash;
    private final String error;

    public AnchorReplicaState(String name, boolean offHost, long records, String hash, String error) {
        this.name = name;
        this.offHost = offHost;
        this.records = records;
        this.hash = hash;
        this.error = error;
    }

    public String name() { return name; }
    public boolean offHost() { return offHost; }
    public long records() { return records; }
    public String hash() { return hash; }
    public String error() { return error; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AnchorReplicaState that = (AnchorReplicaState) o;
        return java.util.Objects.equals(name, that.name)
                && java.util.Objects.equals(offHost, that.offHost)
                && java.util.Objects.equals(records, that.records)
                && java.util.Objects.equals(hash, that.hash)
                && java.util.Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, offHost, records, hash, error);
    }

    @Override
    public String toString() {
        return "AnchorReplicaState[name=" + name + ", offHost=" + offHost + ", records=" + records
                + ", hash=" + hash + ", error=" + error + "]";
    }

    /** 是否成功读到（不代表与本地一致——一致性由启动校验负责）。 */
    public boolean readable() {
        return error == null;
    }

    /** 是否落后于本地（正常状态，通常是推送失败过或副本刚接入）。 */
    public boolean behind(long localRecords) {
        return readable() && records >= 0 && records < localRecords;
    }
}
