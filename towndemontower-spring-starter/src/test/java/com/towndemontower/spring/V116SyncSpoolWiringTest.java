package com.towndemontower.spring;

import com.sun.net.httpserver.HttpServer;
import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.FileAuditStore;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [starter/test] V1.16 审计同步暂存区接线。
 *
 * <p>用本地假收集端制造"远端 503"，验证：失败落盘、默认目录、重启不丢、恢复后补传删除、
 * 审计链不受影响、配置项绑定。补传成功 = 远端真收到且本地不再保留。</p>
 */
public class V116SyncSpoolWiringTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
    }

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger status = new AtomicInteger(503);
        AtomicInteger posts = new AtomicInteger();
        server.createContext("/anchor", ex -> {
            if ("POST".equals(ex.getRequestMethod())) posts.incrementAndGet();
            ex.sendResponseHeaders(status.get(), -1);
            ex.close();
        });
        server.start();

        String tag = Long.toString(System.nanoTime());
        String auditFile = "target/v116-wire-" + tag + ".log";
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/anchor";

        try (ConfigurableApplicationContext ctx = boot(
                "towndemontower.audit-storage=file",
                "towndemontower.audit-file=" + auditFile,
                "towndemontower.audit-anchor-replicas=" + url,
                "towndemontower.audit-anchor-replica-required=false",
                "towndemontower.audit-sync-retry-interval-ms=60000",
                "towndemontower.audit-sync-max-attempts=3")) {

            TownDemontowerProperties props = ctx.getBean(TownDemontowerProperties.class);
            AuditStore store = ctx.getBean(AuditStore.class);
            AuditSyncRelay relay = ctx.getBean(AuditSyncRelay.class);

            check("配置-暂存默认启用", props.isAuditSyncSpoolEnabled());
            check("配置-默认保留 168 小时", props.getAuditSyncRetentionHours() == 168L);
            check("配置-默认上限 64 MiB", props.getAuditSyncMaxBytes() == 64L * 1024 * 1024);
            check("配置-重试次数绑定为 3", props.getAuditSyncMaxAttempts() == 3);
            check("装配-文件审计存储", store instanceof FileAuditStore);

            FileAuditStore file = (FileAuditStore) store;
            check("装配-暂存区已创建", file.syncSpool() != null);
            check("装配-默认目录在审计文件旁",
                    file.syncSpool().dir().equals(Path.of(auditFile + ".spool")));

            // 远端 503：启动锚点 + 本次追加的锚点都推不出去，各落一条暂存
            file.appendAuto("TEST_ACTION", "payload");
            int before = file.syncSpool().pending().size();
            check("失败-产生暂存", before >= 1);
            check("失败-每次失败都落一条", file.syncSpool().stats().captured() == before);
            check("失败-记录副本名", before >= 1
                    && file.syncSpool().pending().get(0).replica().startsWith("http:"));
            check("失败-审计链仍完整", file.verifyChain());

            // 模拟进程重启：同一目录重建暂存区，待补传记录必须还在
            com.towndemontower.core.audit.AuditSyncSpool reopened =
                    new com.towndemontower.core.audit.AuditSyncSpool(
                            file.syncSpool().dir(), file.replicas());
            check("重启-暂存不丢", reopened.pending().size() == before);

            // 远端恢复，手动触发一轮补传
            status.set(200);
            relay.runOnce();
            check("补传-暂存清空", file.syncSpool().pending().isEmpty());
            check("补传-远端每条收到两次（失败 + 补传）", posts.get() == before * 2);
            check("补传-stats 已送达", file.syncSpool().stats().delivered() == before);
            check("补传-审计链仍完整", file.verifyChain());
        } finally {
            server.stop(0);
        }

        System.out.println("\nRESULT: passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    static ConfigurableApplicationContext boot(String... props) {
        return new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(props)
                .run();
    }
}
