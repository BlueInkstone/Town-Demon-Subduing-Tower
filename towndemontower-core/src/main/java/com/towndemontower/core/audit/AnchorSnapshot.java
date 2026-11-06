package com.towndemontower.core.audit;

/**
 * [core] 一条锚点快照（V1.10 / I4-c）。
 *
 * <p>同一份内容既写本地 {@code .anchor} 文件，也推到各个副本，所以格式必须统一且可解析——
 * 副本实现（文件账本、HTTP 收集器）只需要搬运一行文本，不需要理解审计链。</p>
 *
 * <pre>
 * records=&lt;总条数&gt;\thash=&lt;第 records 条的哈希&gt;\tmac=&lt;MAC&gt;\tat=&lt;epoch 毫秒&gt;
 * </pre>
 *
 * <p>{@code at} 只是给人看的时间线索（"最后一次锚定发生在什么时候"），
 * <b>不参与任何判定</b>——本机时钟可回拨，把它当安全属性等于给自己挖坑。</p>
 */
public record AnchorSnapshot(long records, String hash, String mac, long at) {

    /** 空链的占位哈希，与 {@code HashChainAuditStore} 的 GENESIS 保持一致。 */
    public static final String GENESIS_HASH = "GENESIS";

    /** 渲染成单行（副本的传输格式）。 */
    public String line() {
        return "records=" + records + "\thash=" + hash + "\tmac=" + mac + "\tat=" + at;
    }

    /**
     * 解析一行。字段数少于 3 视为损坏；{@code at=} 缺失时记 0
     * （兼容 v1.9.2 之前的本地锚点文件，那种文件本来就没有时间字段）。
     *
     * @throws IllegalStateException 内容不可解析
     */
    public static AnchorSnapshot parse(String text, String origin) {
        if (text == null || text.isBlank()) return null;
        String[] parts = text.trim().split("\t");
        if (parts.length < 3) {
            throw new IllegalStateException("锚点格式损坏（" + origin + "）：" + abbreviate(text));
        }
        long records;
        try {
            records = Long.parseLong(strip(parts[0], "records="));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("锚点条数不可解析（" + origin + "）：" + parts[0], e);
        }
        String hash = strip(parts[1], "hash=");
        String mac = strip(parts[2], "mac=");
        long at = 0L;
        if (parts.length >= 4) {
            try {
                at = Long.parseLong(strip(parts[3], "at="));
            } catch (NumberFormatException ignored) {
                // 时间字段纯展示，坏了不影响判定，不当成损坏
            }
        }
        return new AnchorSnapshot(records, hash, mac, at);
    }

    /** 同一位置（条数相同）的两个锚点是否指向同一条记录。 */
    public boolean sameAt(AnchorSnapshot other) {
        return other != null && records == other.records
                && (hash == null ? other.hash == null : hash.equals(other.hash));
    }

    private static String strip(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }

    private static String abbreviate(String s) {
        String oneLine = s.replace('\n', ' ').replace('\t', ' ');
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine;
    }
}
