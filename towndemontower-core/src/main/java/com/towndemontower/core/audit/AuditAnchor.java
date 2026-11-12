package com.towndemontower.core.audit;

import java.util.Collections;
import java.util.Arrays;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * [core] 审计外锚（V1.9 / I4），V1.10 起支持<b>副本扇出</b>。
 *
 * <p><b>为什么链内哈希不够</b>：链只能自证"内部一致"。整条链被替换、或尾部被截断
 * （删掉最后几条"不该被看见"的记录）时，链本身依然是自洽的——除非有一个<b>链外</b>的
 * 参照物记得"当时最后一条是什么"。</p>
 *
 * <p>本地锚点文件内容：</p>
 * <pre>
 * records=&lt;总记录数&gt;\thash=&lt;最后一条 hash&gt;\tmac=&lt;HMAC(key, records|hash)&gt;\tat=&lt;epoch 毫秒&gt;
 * </pre>
 *
 * <ul>
 *   <li><b>记录数</b>挡截断：日志只剩 N-k 条而锚点说 N 条，直接判异常；</li>
 *   <li><b>末条哈希</b>挡改写：条数一样但内容被换，比对失败；</li>
 *   <li><b>MAC</b>挡"连锚点一起改"：没有密钥就算不出 mac。</li>
 * </ul>
 *
 * <p><b>V1.10 / I4-c 补的是最后一个洞</b>：本地锚点与日志同盘，"能改日志的人"通常也是
 * "能删锚点的人"。攻击者甚至不用伪造——把整个目录回滚到某个旧快照，日志自洽、锚点自洽、
 * MAC 也对得上，本地一切检查都会通过。现在每次锚定都会把快照推给 {@link AnchorReplica}，
 * 启动时再拉回来对账：</p>
 * <ul>
 *   <li>副本<b>比本地新</b>（条数更多）→ 本地被回滚或删除 → <b>拒绝启动</b>；</li>
 *   <li>条数相同但哈希不同 → 日志被改写（或副本属于另一条链）→ <b>拒绝启动</b>；</li>
 *   <li>副本<b>比本地旧</b> → 正常的落后（推送失败过 / 副本刚接入），照常启动；</li>
 *   <li>副本<b>读不出来</b> → 无法排除回滚；{@code replicaRequired=true} 时拒绝启动，
 *       否则只记进自检与端点。</li>
 * </ul>
 *
 * <p>注意"读得到且与本地矛盾"这条<b>永远</b>致命，不受 {@code replicaRequired} 影响：
 * 那个开关表达的是"副本暂时不可用能不能先起来"，不是"证据对不上能不能先起来"。</p>
 */
final class AuditAnchor {

    /** 锚点快照。 */
    static final class Anchor {
        private final long records;
        private final String hash;

        Anchor(long records, String hash) {
            this.records = records;
            this.hash = hash;
        }

        public long records() { return records; }
        public String hash() { return hash; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Anchor that = (Anchor) o;
            return java.util.Objects.equals(records, that.records)
                    && java.util.Objects.equals(hash, that.hash);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(records, hash);
        }

        @Override
        public String toString() {
            return "Anchor[records=" + records + ", hash=" + hash + "]";
        }
    }

    private final Path path;
    private final byte[] key;
    private final List<AnchorReplica> replicas;
    private final boolean replicaRequired;
    /** 可选：推送失败的快照写进本地暂存区，等下一轮补传（V1.16）。 */
    private final AuditSyncSpool spool;
    /** 副本名 → 最近一次推送失败原因（成功即移除）。仅诊断用。 */
    private final java.util.Map<String, String> pushErrors = new java.util.LinkedHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong pushFailures =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * 副本名 → 最近一次已知状态（启动核对写入，之后每次推送刷新）。
     *
     * <p>不留在"启动那一刻的快照"：副本恢复后 {@code /integrity} 还报着红，
     * 或者新接入副本永远显示空——两种都会让人不再信任这个读数。</p>
     */
    private final java.util.Map<String, AnchorReplicaState> latest =
            new java.util.LinkedHashMap<>();

    AuditAnchor(Path path, byte[] key) {
        this(path, key, Arrays.asList(), false);
    }

    AuditAnchor(Path path, byte[] key, List<AnchorReplica> replicas, boolean replicaRequired) {
        this(path, key, replicas, replicaRequired, null);
    }

    AuditAnchor(Path path, byte[] key, List<AnchorReplica> replicas, boolean replicaRequired,
                AuditSyncSpool spool) {
        this.path = path;
        this.key = key;
        this.replicas = replicas == null ? Arrays.asList() : new ArrayList<>(replicas);
        this.replicaRequired = replicaRequired;
        this.spool = spool;
    }

    boolean exists() {
        return Files.exists(path);
    }

    Path path() {
        return path;
    }

    List<AnchorReplica> replicas() {
        return replicas;
    }

    /**
     * 读取并校验锚点。
     *
     * @throws IllegalStateException 锚点被改（mac 不匹配）或格式损坏
     */
    Anchor read() {
        if (!exists()) return null;
        String text;
        try {
            text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("审计锚点读取失败：" + path, e);
        }
        if (text.isEmpty()) return null;
        AnchorSnapshot snap = AnchorSnapshot.parse(text, path.toString());
        // 旧锚点（无密钥时代写入）mac 为 "-"，本身就不受保护；先接受，
        // 启动末尾会以当前密钥重写一份带 MAC 的锚点（升级路径，不是放水）
        if ("-".equals(snap.mac())) {
            return new Anchor(snap.records(), snap.hash());
        }
        String expected = AuditCrypto.mac(key, snap.records() + "|" + snap.hash());
        if (!expected.equals(snap.mac())) {
            throw new IllegalStateException("审计锚点被篡改（mac 不匹配）：" + path);
        }
        return new Anchor(snap.records(), snap.hash());
    }

    /** 写入本地锚点，并推给所有副本（原子替换，避免半截文件）。 */
    void write(long records, String hash) {
        AnchorSnapshot snap = new AnchorSnapshot(records, hash,
                AuditCrypto.mac(key, records + "|" + hash), System.currentTimeMillis());
        String line = snap.line() + "\n";
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, (line).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("审计锚点写入失败：" + path, e);
        }
        pushReplicas(snap);
    }

    /**
     * 把快照推给全部副本。
     *
     * <p>先把所有副本都试一遍再决定是否抛：一个副本挂了不该让后面的副本饿死——
     * 那样"部分可用"会退化成"全都不可用"。</p>
     *
     * @throws IllegalStateException {@code replicaRequired=true} 且至少一个副本没写成
     */
    private void pushReplicas(AnchorSnapshot snap) {
        if (replicas.isEmpty()) return;
        List<String> errors = new ArrayList<>();
        for (AnchorReplica r : replicas) {
            try {
                r.push(snap);
                synchronized (pushErrors) {
                    pushErrors.remove(r.name());
                    latest.put(r.name(), new AnchorReplicaState(
                            r.name(), r.offHost(), snap.records(), snap.hash(), null));
                }
            } catch (RuntimeException e) {
                String err = describe(e);
                errors.add("[" + r.name() + "] " + err);
                synchronized (pushErrors) {
                    pushErrors.put(r.name(), err);
                    // 条数保持上次已知值：推送失败不等于副本退回 0 条
                    AnchorReplicaState prev = latest.get(r.name());
                    latest.put(r.name(), new AnchorReplicaState(r.name(), r.offHost(),
                            prev == null ? -1 : prev.records(),
                            prev == null ? null : prev.hash(), err));
                }
                pushFailures.incrementAndGet();
                if (spool != null) {
                    try {
                        spool.capture(r.name(), snap.line(), err);
                    } catch (RuntimeException captureFailed) {
                        // 暂存写失败不能反过来影响治理主链：这里只丢一条待补传记录，
                        // 失败计数已由 spool 自己递增，日志也已在那边打过。
                    }
                }
            }
        }
        if (!errors.isEmpty() && replicaRequired) {
            throw new IllegalStateException("审计锚点副本未能持久化，拒绝继续"
                    + "（确需降级请设 audit-anchor-replica-required=false）："
                    + String.join("；", errors));
        }
    }

    /** 当前仍处于失败状态的副本（推送成功会自行清除）。 */
    java.util.Map<String, String> replicaErrors() {
        synchronized (pushErrors) {
            return java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(pushErrors));
        }
    }

    /** 累计推送失败次数（含已恢复的）。 */
    long replicaFailures() {
        return pushFailures.get();
    }

    /** 副本的最近一次已知状态（启动核对 + 之后的每次推送）；未核对过则为空表。 */
    List<AnchorReplicaState> replicaStates() {
        synchronized (pushErrors) {
            return new ArrayList<>(latest.values());
        }
    }

    /**
     * 启动时用副本反查"本地是否被回滚"。
     *
     * @param localRecords 本地日志实际条数
     * @param localTail    本地日志末条哈希
     * @return 每个副本的核对结果（用于启动日志与端点）
     * @throws IllegalStateException 读到副本且与本地矛盾，或 {@code replicaRequired} 下副本不可读
     */
    List<AnchorReplicaState> verifyReplicas(long localRecords, String localTail) {
        List<AnchorReplicaState> out = new ArrayList<>();
        if (replicas.isEmpty()) return out;
        List<String> fatal = new ArrayList<>();
        for (AnchorReplica r : replicas) {
            AnchorSnapshot s;
            try {
                s = r.pull();
            } catch (RuntimeException e) {
                String err = "不可读：" + describe(e);
                out.add(new AnchorReplicaState(r.name(), r.offHost(), -1, null, err));
                if (replicaRequired) fatal.add("[" + r.name() + "] " + err);
                continue;
            }
            if (s == null) {
                out.add(new AnchorReplicaState(r.name(), r.offHost(), -1, null, null));
                continue;
            }
            out.add(new AnchorReplicaState(r.name(), r.offHost(), s.records(), s.hash(), null));
            if (s.records() > localRecords) {
                // 读得到且矛盾：这不是"副本暂时不可用"，是证据表明本地少了东西，永远致命
                fatal.add("[" + r.name() + "] 副本已记录 " + s.records() + " 条，本地日志只有 "
                        + localRecords + " 条：本地审计被回滚或删除");
            } else if (s.records() == localRecords && localRecords > 0
                    && s.hash() != null && !s.hash().equals(localTail)) {
                fatal.add("[" + r.name() + "] 副本第 " + s.records() + " 条的哈希与本地不一致："
                        + "日志被改写，或副本属于另一条审计链");
            }
        }
        if (!fatal.isEmpty()) {
            throw new IllegalStateException("审计锚点副本与本地不符，拒绝启动：" + String.join("；", fatal));
        }
        synchronized (pushErrors) {
            for (AnchorReplicaState st : out) latest.put(st.name(), st);
        }
        return out;
    }

    /**
     * 锚点与日志是否一致。
     *
     * <p><b>V1.9.2 加了"锚点位置哈希"这一手</b>：锚点记的是第 N 条，锚点若只比"总条数与
     * 末条"，出现 {@code records > anchor.records()}（锚点落后，批量追平的正常状态）时
     * 就<b>什么都不检查</b>——前 N 条可以整段重写而没人发现。现在按位置取回第 N 条的哈希
     * 直接比对，落后期间前缀依旧受保护，落后的只有尾巴那几条。</p>
     *
     * @param anchor       锚点快照；{@code null} 表示没有锚点（首次受保护点之前）
     * @param records      日志实际条数
     * @param hashAtAnchor 第 {@code anchor.records()} 条记录的哈希；不足该条数时 {@code null}
     * @return 不一致的描述；一致返回 {@code null}
     */
    static String mismatch(Anchor anchor, long records, String hashAtAnchor) {
        if (anchor == null) return null;
        // 空基线（首次启动写下的 records=0）不构成任何约束
        if (anchor.records() <= 0) return null;
        if (records < anchor.records()) {
            return "日志被截断：锚点记录 " + anchor.records() + " 条，实际只剩 " + records + " 条";
        }
        if (hashAtAnchor == null || !anchor.hash().equals(hashAtAnchor)) {
            return "锚点位置（第 " + anchor.records() + " 条）的记录与锚点不一致（日志被改写）";
        }
        return null;
    }

    private static String describe(RuntimeException e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}
