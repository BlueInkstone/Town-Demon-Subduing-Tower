package com.towndemontower.core.audit;

import java.util.Objects;

/**
 * [core] 单条审计记录。追加写、不可篡改，逐条 SHA-256 链式引用前一条哈希。
 */
public class AuditRecord {
    public final long seq;
    public final String action;
    public final String payload;
    public final String prevHash;
    public final String hash;

    public AuditRecord(long seq, String action, String payload, String prevHash, String hash) {
        this.seq = seq;
        this.action = action;
        this.payload = payload;
        this.prevHash = prevHash;
        this.hash = hash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditRecord)) return false;
        AuditRecord r = (AuditRecord) o;
        return seq == r.seq && Objects.equals(action, r.action)
                && Objects.equals(payload, r.payload)
                && Objects.equals(prevHash, r.prevHash) && Objects.equals(hash, r.hash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seq, action, payload, prevHash, hash);
    }
}
