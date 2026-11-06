package com.towndemontower.delivery;

import com.towndemontower.delivery.domain.DeliveryItem;
import com.towndemontower.delivery.domain.DeliveryStatus;
import com.towndemontower.delivery.domain.InMemoryDeliveryRepository;
import com.towndemontower.delivery.service.DeliveryProgressService;
import com.towndemontower.delivery.seed.ProjectRoadmap;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

/**
 * [delivery/test] 真实交付路线图验证器（纯 Java，零 Spring 依赖，main 直跑）。
 *
 * <p>验证 {@link ProjectRoadmap} 播种的路线图确实反映了本仓库的真实交付历史：
 * <ul>
 *   <li>条目总数与各状态计数符合预期；</li>
 *   <li>已完成的版本里程碑，其"实际完成时间"落在仓库真实时间戳附近；</li>
 *   <li>已完成项带实际完成时间，未交付项不带；</li>
 *   <li>路线图整体进度与完成率可被汇总正确算出；</li>
 *   <li>路线图不含任何人工状态覆盖（状态全部来自自动推导）。</li>
 * </ul>
 */
public class DeliveryRoadmapTest {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("[PASS] " + name);
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }

    static void eq(String name, Object actual, Object expected) {
        boolean ok = (expected == null) ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
            System.out.println("[PASS] " + name + " = " + actual);
        } else {
            fail++;
            System.out.println("[FAIL] " + name + " expected=" + expected + " actual=" + actual);
        }
    }

    /** 固定时钟：让"现在"可被测试精确控制。 */
    static final class FixedClock implements Supplier<Instant> {
        private final Instant now;

        FixedClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant get() {
            return now;
        }
    }

    static Instant instant(String isoLocal) {
        return LocalDateTime.parse(isoLocal).atZone(ZoneId.systemDefault()).toInstant();
    }

    public static void main(String[] args) {
        // 固定"现在" = 2026-11-02 18:00（仓库交付看板文档完成时刻之后）
        FixedClock clock = new FixedClock(instant("2026-11-02T18:00:00"));
        InMemoryDeliveryRepository repo = new InMemoryDeliveryRepository();
        DeliveryProgressService svc = new DeliveryProgressService(repo, clock);

        ProjectRoadmap.seed(svc, LocalDateTime.of(2026, 11, 2, 18, 0));

        /* ---------- ① 条目总数 ---------- */
        List<DeliveryItem> all = svc.list(null);
        eq("路线图-条目总数", all.size(), ProjectRoadmap.expectedItemCount());

        /* ---------- ② 各状态计数 ---------- */
        eq("路线图-已完成数", svc.list(DeliveryStatus.COMPLETED).size(),
                ProjectRoadmap.expectedCompletedCount());
        eq("路线图-已延期数", svc.list(DeliveryStatus.DELAYED).size(),
                ProjectRoadmap.expectedDelayedCount());
        eq("路线图-进行中数", svc.list(DeliveryStatus.IN_PROGRESS).size(),
                ProjectRoadmap.expectedInProgressCount());
        eq("路线图-未开始数", svc.list(DeliveryStatus.NOT_STARTED).size(),
                ProjectRoadmap.expectedNotStartedCount());

        /* ---------- ③ 已完成项带实际完成时间，且落在基线日 ---------- */
        int completedWithActual = 0;
        for (DeliveryItem it : svc.list(DeliveryStatus.COMPLETED)) {
            if (it.actualEnd() != null) {
                completedWithActual++;
                check("路线图-已完成项有实际完成时间：" + it.phaseName(),
                        it.actualEnd().getYear() == 2026 && it.actualEnd().getMonthValue() == 11);
            }
        }
        eq("路线图-已完成项均带实际完成时间", completedWithActual,
                ProjectRoadmap.expectedCompletedCount());

        /* ---------- ④ 未交付项均无实际完成时间 ---------- */
        int unfinishedWithActual = 0;
        for (DeliveryItem it : all) {
            if (it.status(clock.get()) != DeliveryStatus.COMPLETED && it.actualEnd() != null) {
                unfinishedWithActual++;
            }
        }
        eq("路线图-未完成项无实际完成时间", unfinishedWithActual, 0);

        /* ---------- ⑤ 已完成项进度均为 100 ---------- */
        for (DeliveryItem it : svc.list(DeliveryStatus.COMPLETED)) {
            check("路线图-已完成项进度 100：" + it.phaseName(), it.progressPercent() == 100);
        }

        /* ---------- ⑥ 汇总口径 ---------- */
        // 整体进度 = (100*6 + 30 + 10 + 0*5) / 13 = 640/13 = 49
        eq("路线图-整体进度", svc.summary().overallProgressPercent(), 49);
        // 完成率 = 6/13 = 46
        eq("路线图-完成率", svc.summary().completedRatioPercent(), 46);
        check("路线图-最近交付节点非空", svc.summary().earliestPlannedEnd() != null);

        /* ---------- ⑦ 无人工状态覆盖（状态全部自动推导）---------- */
        int manual = 0;
        for (DeliveryItem it : all) {
            if (it.statusOverride() != null) {
                manual++;
            }
        }
        eq("路线图-无人工状态覆盖", manual, ProjectRoadmap.expectedManualOverrideCount());

        /* ---------- ⑧ 时钟推进：未开始项到期后自动变已延期 ---------- */
        FixedClock future = new FixedClock(instant("2026-12-20T12:00:00"));
        DeliveryProgressService svc2 = new DeliveryProgressService(repo, future);
        eq("路线图-时钟推进后未开始项归零", svc2.list(DeliveryStatus.NOT_STARTED).size(), 0);
        check("路线图-时钟推进后已延期项增加",
                svc2.list(DeliveryStatus.DELAYED).size() > ProjectRoadmap.expectedDelayedCount());

        /* ---------- ⑨ 关键里程碑命名可追溯 ---------- */
        boolean hasCore = all.stream().anyMatch(i -> i.phaseName().contains("治理内核"));
        boolean hasStarter = all.stream().anyMatch(i -> i.phaseName().contains("Spring Boot"));
        boolean hasDocker = all.stream().anyMatch(i -> i.phaseName().contains("Docker"));
        boolean hasTx = all.stream().anyMatch(i -> i.phaseName().contains("事务一致性"));
        boolean hasBoard = all.stream().anyMatch(i -> i.phaseName().contains("交付进度看板"));
        check("路线图-含 v1.0 治理内核里程碑", hasCore);
        check("路线图-含 v1.1 Spring 适配层里程碑", hasStarter);
        check("路线图-含 v1.2.1 Docker 沙箱里程碑", hasDocker);
        check("路线图-含 v1.2.1 事务一致性里程碑", hasTx);
        check("路线图-含 v1.3 交付看板里程碑", hasBoard);

        /* ---------- ⑩ 全部里程碑负责人非空 ---------- */
        for (DeliveryItem it : all) {
            check("路线图-负责人非空：" + it.phaseName(),
                    it.owner() != null && !it.owner().trim().isEmpty());
        }

        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
