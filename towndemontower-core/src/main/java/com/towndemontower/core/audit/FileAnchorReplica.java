package com.towndemontower.core.audit;



import java.util.HashSet;
import java.util.Arrays;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * [core] 文件型锚点副本：一个<b>只追加</b>的账本（V1.10 / I4-c）。
 *
 * <p>与本地 {@code .anchor} 的关键差别是<b>不覆盖</b>：本地锚点是"当前状态"，
 * 每条覆盖一次；这里每次锚定追加一行，历史不可抹掉。于是两类攻击留下痕迹：</p>
 *
 * <ul>
 *   <li><b>删中间某行</b>：每行的 MAC 覆盖了上一行的 MAC（{@code mac(key, "ledger|prevMac|records|hash")}），
 *       中间少一行，链路立刻断——这一点比本地锚点强，本地锚点被删就只能靠 {@code .anchor} 自己，
 *       而它和日志同盘；</li>
 *   <li><b>回滚整文件</b>：账本里记录的条数必须<b>单调不减</b>，回滚到旧版本会让"当前条数"变小，
 *       与本地日志一对就能发现。真正的异地价值仍然取决于这个路径是否在另一个失败域里
 *       （{@link #offHost()}）。</li>
 * </ul>
 *
 * <p><b>代价</b>：账本行数 = 锚定次数。默认 {@code anchorBatch=1} 时等于审计条数，
 * 高写入量下需要配批量（{@code audit-anchor-batch}）或改用 HTTP 收集器，否则账本长得很快。
 * 本类<b>不做轮转</b>：轮转等于删除历史，与本类的存在理由冲突，需要清理时应该换存储。</p>
 *
 * <p>无审计密钥时 MAC 恒为 {@code -}，链路校验退化为"条数单调"这一条——
 * 与整条审计链退化到 SHA-256 是同一个前提，自检会一并报出。</p>
 */
public final class FileAnchorReplica implements AnchorReplica {

    private final Path ledger;
    private final byte[] key;
    private final boolean offHost;
    private final ReentrantLock lock = new ReentrantLock();
    /** 末行的 MAC（链的下一个 prev）；{@code null} 表示尚未从磁盘读过。 */
    private String lastMac;
    private volatile String lastError;

    public FileAnchorReplica(Path ledger, byte[] key) {
        this(ledger, key, false);
    }

    /**
     * @param offHost 运维声明"这个路径在另一个失败域"（例如另一块盘、NFS 挂载点）。
     *                框架无法核实，只用于自检提示与端点展示
     */
    public FileAnchorReplica(Path ledger, byte[] key, boolean offHost) {
        this.ledger = ledger;
        this.key = (key == null || key.length == 0) ? null : key.clone();
        this.offHost = offHost;
    }

    public Path ledgerFile() {
        return ledger;
    }

    @Override
    public String name() {
        return "file:" + ledger;
    }

    @Override
    public boolean offHost() {
        return offHost;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public void push(AnchorSnapshot snapshot) {
        lock.lock();
        try {
            String prev = prevMac();
            String mac = AuditCrypto.mac(key, "ledger|" + prev + "|"
                    + snapshot.records() + "|" + snapshot.hash());
            String line = "records=" + snapshot.records()
                    + "\thash=" + snapshot.hash()
                    + "\tmac=" + mac
                    + "\tat=" + snapshot.at() + "\n";
            Path parent = ledger.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(ledger, (line).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            restrictPermissions(ledger);
            lastMac = mac;
            lastError = null;
        } catch (IOException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw new IllegalStateException("锚点副本写入失败：" + ledger, e);
        } catch (RuntimeException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw e;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AnchorSnapshot pull() {
        lock.lock();
        try {
            if (!Files.exists(ledger)) {
                lastMac = "-";
                lastError = null;
                return null;
            }
            List<String> lines = Files.readAllLines(ledger, StandardCharsets.UTF_8);
            String prev = "-";
            long prevRecords = -1;
            AnchorSnapshot last = null;
            int index = 0;
            for (String line : lines) {
                index++;
                if (line == null || line.trim().isEmpty()) continue;
                AnchorSnapshot s = AnchorSnapshot.parse(line, "副本 " + ledger + " 第 " + index + " 行");
                if (s.records() < prevRecords) {
                    throw new IllegalStateException("锚点副本被回滚：" + ledger
                            + " 第 " + index + " 行的条数 " + s.records()
                            + " 小于前一行的 " + prevRecords + "（账本只能追加，不能变小）");
                }
                if (key != null) {
                    String expected = AuditCrypto.mac(key, "ledger|" + prev + "|"
                            + s.records() + "|" + s.hash());
                    if (!expected.equals(s.mac())) {
                        throw new IllegalStateException("锚点副本链路断裂：" + ledger
                                + " 第 " + index + " 行 MAC 不匹配（中间有行被删除或改写）");
                    }
                }
                prev = s.mac();
                prevRecords = s.records();
                last = s;
            }
            lastMac = prev;
            lastError = null;
            return last;
        } catch (IOException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw new IllegalStateException("锚点副本读取失败：" + ledger, e);
        } catch (RuntimeException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw e;
        } finally {
            lock.unlock();
        }
    }

    /** 末行 MAC；首次调用时从磁盘恢复，读不出来就当空账本（后续 pull/push 会给出真正的错误）。 */
    private String prevMac() {
        if (lastMac != null) return lastMac;
        try {
            AnchorSnapshot s = pull();
            lastMac = s == null ? "-" : s.mac();
        } catch (RuntimeException e) {
            // pull 已经设了 lastError；这里选择抛出而不是悄悄用 "-" 续写，
            // 否则会在断链之后接出一条"看起来连续"的新链，把断裂处掩盖掉
            throw e;
        }
        return lastMac;
    }

    private static void restrictPermissions(Path f) {
        try {
            Set<PosixFilePermission> perms = new HashSet<>(Arrays.asList(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            Files.setPosixFilePermissions(f, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 不支持 POSIX 权限，交由目录 ACL
        }
    }
}
