package com.towndemontower.delivery;

import com.towndemontower.delivery.domain.DeliveryItem;
import com.towndemontower.delivery.domain.DeliveryStatus;
import com.towndemontower.delivery.domain.DeliveryStatusResolver;
import com.towndemontower.delivery.domain.DeliverySummary;
import com.towndemontower.delivery.domain.InMemoryDeliveryRepository;
import com.towndemontower.delivery.service.DeliveryNotFoundException;
import com.towndemontower.delivery.service.DeliveryProgressService;
import com.towndemontower.delivery.service.DeliveryValidationException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

/**
 * [delivery/test] 领域层离线验证器（纯 Java，零 Spring 依赖，main 直跑）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>状态推导：未开始 / 进行中 / 已延期 / 已完成 四条规则 + 人工覆盖优先；</li>
 *   <li>交付项 CRUD 与字段级校验；</li>
 *   <li>按状态筛选；</li>
 *   <li>整体进度汇总口径。</li>
 * </ul>
 *
 * <p>时钟通过 {@link Supplier} 注入固定值，因此"到期即延期"这类与时间相关的规则
 * 可被精确断言，不受真实时间影响。</p>
 */
public class DeliveryDomainTest {

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

    /** 固定时钟容器：让"现在"可被测试精确控制。 */
    static final class FixedClock implements Supplier<Instant> {
        private Instant now;

        FixedClock(Instant now) {
            this.now = now;
        }

        void set(Instant t) {
            this.now = t;
        }

        @Override
        public Instant get() {
            return now;
        }
    }

    static LocalDateTime local(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneId.systemDefault());
    }

    static Instant instant(String isoLocal) {
        return LocalDateTime.parse(isoLocal).atZone(ZoneId.systemDefault()).toInstant();
    }

    public static void main(String[] args) {
        statusResolverTests();
        crudTests();
        validationTests();
        filterTests();
        summaryTests();
        clockDrivenTests();

        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /* ---------------- 状态推导规则 ---------------- */

    static void statusResolverTests() {
        Instant now = instant("2026-11-10T12:00:00");

        // ① 未开始：进度 0，且未到计划开始时间
        DeliveryItem notStarted = item(null, "未开始阶段", "张三",
                "2026-12-01T09:00:00", "2026-12-20T09:00:00", null, 0, null);
        eq("状态推导-未开始", DeliveryStatusResolver.resolve(notStarted, now), DeliveryStatus.NOT_STARTED);

        // ② 进行中：有进度但未到期
        DeliveryItem doing = item(null, "进行中阶段", "李四",
                "2026-11-01T09:00:00", "2026-11-30T09:00:00", null, 40, null);
        eq("状态推导-进行中(有进度)", DeliveryStatusResolver.resolve(doing, now), DeliveryStatus.IN_PROGRESS);

        // ③ 进行中：进度为 0 但已到计划开始时间
        DeliveryItem justStarted = item(null, "刚开工", "王五",
                "2026-11-05T09:00:00", "2026-11-30T09:00:00", null, 0, null);
        eq("状态推导-进行中(已到开始时间)", DeliveryStatusResolver.resolve(justStarted, now), DeliveryStatus.IN_PROGRESS);

        // ④ 已延期：未完成且已过计划完成时间
        DeliveryItem late = item(null, "延期阶段", "赵六",
                "2026-10-01T09:00:00", "2026-11-01T09:00:00", null, 30, null);
        eq("状态推导-已延期", DeliveryStatusResolver.resolve(late, now), DeliveryStatus.DELAYED);

        // ⑤ 已完成：进度 100%
        DeliveryItem done = item(null, "完成阶段", "孙七",
                "2026-10-01T09:00:00", "2026-11-01T09:00:00", null, 100, null);
        eq("状态推导-已完成(满进度)", DeliveryStatusResolver.resolve(done, now), DeliveryStatus.COMPLETED);

        // ⑥ 已完成：填了实际完成时间（即使进度未满）
        DeliveryItem doneByActual = item(null, "已交付阶段", "周八",
                "2026-10-01T09:00:00", "2026-11-01T09:00:00", "2026-10-28T18:00:00", 80, null);
        eq("状态推导-已完成(有实际完成时间)", DeliveryStatusResolver.resolve(doneByActual, now), DeliveryStatus.COMPLETED);

        // ⑦ 人工覆盖优先于一切推导
        DeliveryItem overridden = item(null, "挂起阶段", "吴九",
                "2026-10-01T09:00:00", "2026-11-01T09:00:00", null, 30, DeliveryStatus.NOT_STARTED);
        eq("状态推导-人工覆盖优先", DeliveryStatusResolver.resolve(overridden, now), DeliveryStatus.NOT_STARTED);

        // ⑧ 状态标签与解析
        eq("状态标签-未开始", DeliveryStatus.NOT_STARTED.label(), "未开始");
        eq("状态标签-进行中", DeliveryStatus.IN_PROGRESS.label(), "进行中");
        eq("状态标签-已延期", DeliveryStatus.DELAYED.label(), "已延期");
        eq("状态标签-已完成", DeliveryStatus.COMPLETED.label(), "已完成");
        eq("状态解析-枚举名", DeliveryStatus.parse("IN_PROGRESS"), DeliveryStatus.IN_PROGRESS);
        eq("状态解析-中文标签", DeliveryStatus.parse("已延期"), DeliveryStatus.DELAYED);
        eq("状态解析-未知返回null", DeliveryStatus.parse("不存在的状态"), null);
        eq("状态解析-空返回null", DeliveryStatus.parse("  "), null);
        check("状态终态判定", DeliveryStatus.COMPLETED.isFinal() && !DeliveryStatus.DELAYED.isFinal());
    }

    /* ---------------- CRUD 与校验 ---------------- */

    static void crudTests() {
        FixedClock clock = new FixedClock(instant("2026-11-10T12:00:00"));
        InMemoryDeliveryRepository repo = new InMemoryDeliveryRepository();
        DeliveryProgressService svc = new DeliveryProgressService(repo, clock);

        // 新增
        DeliveryItem created = svc.create("Phase 1 核心功能", "张三",
                LocalDateTime.parse("2026-11-01T09:00:00"),
                LocalDateTime.parse("2026-11-30T09:00:00"), null, 10, null);
        check("新增-生成 id", created.id() != null && created.id().startsWith("DLV-"));
        eq("新增-阶段名", created.phaseName(), "Phase 1 核心功能");
        eq("新增-负责人", created.owner(), "张三");
        eq("新增-进度", created.progressPercent(), 10);
        eq("新增-状态自动推导", created.status(clock.get()), DeliveryStatus.IN_PROGRESS);

        // 第二个交付项：验证 id 递增
        DeliveryItem second = svc.create("Phase 2 联调", "李四",
                LocalDateTime.parse("2026-11-10T09:00:00"),
                LocalDateTime.parse("2026-12-10T09:00:00"), null, 0, null);
        check("新增-第二个 id 递增", !second.id().equals(created.id()));

        // 部分更新：只改进度
        DeliveryItem updated = svc.update(created.id(), null, null, null, null, null, 55, null);
        eq("更新-进度已改", updated.progressPercent(), 55);
        eq("更新-阶段名保持原值", updated.phaseName(), "Phase 1 核心功能");
        eq("更新-计划时间保持原值", updated.plannedEnd(), LocalDateTime.parse("2026-11-30T09:00:00"));

        // 人工指定状态
        DeliveryItem manual = svc.update(second.id(), null, null, null, null, null, null,
                DeliveryStatus.IN_PROGRESS);
        eq("更新-人工指定状态", manual.statusOverride(), DeliveryStatus.IN_PROGRESS);
        check("更新-人工指定后状态生效", manual.status(clock.get()) == DeliveryStatus.IN_PROGRESS);

        // 清除人工指定 → 恢复自动推导
        DeliveryItem cleared = svc.update(second.id(), null, null, null, null, null, null, null, true);
        eq("更新-清除人工指定", cleared.statusOverride(), null);
        eq("更新-清除后回到自动推导结果", cleared.status(clock.get()), DeliveryStatus.IN_PROGRESS);

        // 按 id 查询
        check("查询-命中", svc.findById(created.id()).isPresent());
        check("查询-未命中", svc.findById("DLV-9999").isEmpty());

        // 删除
        svc.delete(second.id());
        check("删除-已移除", svc.findById(second.id()).isEmpty());
        check("删除-总数减少", svc.count() == 1);

        // 删除不存在的项 → 404 语义
        try {
            svc.delete("DLV-9999");
            check("删除不存在-应抛 NotFound", false);
        } catch (DeliveryNotFoundException e) {
            check("删除不存在-抛 NotFound", "DLV-9999".equals(e.id()));
        }

        // 更新不存在的项 → 404 语义
        try {
            svc.update("DLV-9999", "x", "y", null, null, null, 10, null);
            check("更新不存在-应抛 NotFound", false);
        } catch (DeliveryNotFoundException e) {
            check("更新不存在-抛 NotFound", "DLV-9999".equals(e.id()));
        }
    }

    /* ---------------- 字段级校验 ---------------- */

    static void validationTests() {
        InMemoryDeliveryRepository repo = new InMemoryDeliveryRepository();
        FixedClock clock = new FixedClock(instant("2026-11-10T12:00:00"));
        DeliveryProgressService svc = new DeliveryProgressService(repo, clock);

        expectValidation("校验-阶段名为空", "phaseName",
                () -> svc.create("", "张三", LocalDateTime.parse("2026-11-01T09:00:00"),
                        LocalDateTime.parse("2026-11-30T09:00:00"), null, 10, null));
        expectValidation("校验-负责人为空", "owner",
                () -> svc.create("阶段", "  ", LocalDateTime.parse("2026-11-01T09:00:00"),
                        LocalDateTime.parse("2026-11-30T09:00:00"), null, 10, null));
        expectValidation("校验-计划开始为空", "plannedStart",
                () -> svc.create("阶段", "张三", null,
                        LocalDateTime.parse("2026-11-30T09:00:00"), null, 10, null));
        expectValidation("校验-计划完成为空", "plannedEnd",
                () -> svc.create("阶段", "张三", LocalDateTime.parse("2026-11-01T09:00:00"),
                        null, null, 10, null));
        expectValidation("校验-计划完成早于开始", "plannedEnd",
                () -> svc.create("阶段", "张三", LocalDateTime.parse("2026-11-30T09:00:00"),
                        LocalDateTime.parse("2026-11-01T09:00:00"), null, 10, null));
        expectValidation("校验-进度为负", "progressPercent",
                () -> svc.create("阶段", "张三", LocalDateTime.parse("2026-11-01T09:00:00"),
                        LocalDateTime.parse("2026-11-30T09:00:00"), null, -1, null));
        expectValidation("校验-进度超 100", "progressPercent",
                () -> svc.create("阶段", "张三", LocalDateTime.parse("2026-11-01T09:00:00"),
                        LocalDateTime.parse("2026-11-30T09:00:00"), null, 101, null));

        // 更新导致计划起止倒挂 → 也要被拦
        DeliveryItem ok = svc.create("阶段", "张三", LocalDateTime.parse("2026-11-01T09:00:00"),
                LocalDateTime.parse("2026-11-30T09:00:00"), null, 10, null);
        expectValidation("校验-更新造成起止倒挂", "plannedEnd",
                () -> svc.update(ok.id(), null, null, null, LocalDateTime.parse("2026-10-01T09:00:00"),
                        null, null, null));
    }

    /* ---------------- 按状态筛选 ---------------- */

    static void filterTests() {
        FixedClock clock = new FixedClock(instant("2026-11-10T12:00:00"));
        InMemoryDeliveryRepository repo = new InMemoryDeliveryRepository();
        DeliveryProgressService svc = new DeliveryProgressService(repo, clock);

        // 未开始：计划开始在未来
        svc.create("未来阶段", "A", LocalDateTime.parse("2026-12-01T09:00:00"),
                LocalDateTime.parse("2026-12-20T09:00:00"), null, 0, null);
        // 进行中：有进度且未到期
        svc.create("进行阶段", "B", LocalDateTime.parse("2026-11-01T09:00:00"),
                LocalDateTime.parse("2026-11-30T09:00:00"), null, 40, null);
        // 已延期：已过计划完成时间
        svc.create("延期阶段", "C", LocalDateTime.parse("2026-10-01T09:00:00"),
                LocalDateTime.parse("2026-11-01T09:00:00"), null, 30, null);
        // 已完成：进度 100%
        svc.create("完成阶段", "D", LocalDateTime.parse("2026-10-01T09:00:00"),
                LocalDateTime.parse("2026-11-01T09:00:00"), null, 100, null);

        eq("筛选-全部", svc.list(null).size(), 4);
        eq("筛选-未开始", svc.list(DeliveryStatus.NOT_STARTED).size(), 1);
        eq("筛选-进行中", svc.list(DeliveryStatus.IN_PROGRESS).size(), 1);
        eq("筛选-已延期", svc.list(DeliveryStatus.DELAYED).size(), 1);
        eq("筛选-已完成", svc.list(DeliveryStatus.COMPLETED).size(), 1);

        check("筛选-未开始项正确",
                svc.list(DeliveryStatus.NOT_STARTED).get(0).phaseName().equals("未来阶段"));
        check("筛选-已延期项正确",
                svc.list(DeliveryStatus.DELAYED).get(0).phaseName().equals("延期阶段"));

        // 时钟推进 → "进行中"到期后自动变"已延期"，无需任何写操作
        clock.set(instant("2026-12-02T12:00:00"));
        // 进行阶段（计划完成 11-30）已过期 → 转入已延期；未来阶段（12-01 开始）→ 转为进行中
        eq("筛选-时钟推进后已延期增加", svc.list(DeliveryStatus.DELAYED).size(), 2);
        eq("筛选-时钟推进后未来阶段变进行中", svc.list(DeliveryStatus.IN_PROGRESS).size(), 1);
        eq("筛选-时钟推进后进行中原项归零", svc.list(DeliveryStatus.IN_PROGRESS).stream()
                .filter(i -> i.phaseName().equals("进行阶段")).count(), 0L);
    }

    /* ---------------- 汇总 ---------------- */

    static void summaryTests() {
        FixedClock clock = new FixedClock(instant("2026-11-10T12:00:00"));
        InMemoryDeliveryRepository repo = new InMemoryDeliveryRepository();
        DeliveryProgressService svc = new DeliveryProgressService(repo, clock);

        // 空汇总
        DeliverySummary empty = svc.summary();
        eq("汇总-空库总数", empty.totalCount(), 0);
        eq("汇总-空库整体进度", empty.overallProgressPercent(), 0);
        eq("汇总-空库完成率", empty.completedRatioPercent(), 0);
        check("汇总-空库无交付节点", empty.earliestPlannedEnd() == null);
        check("汇总-空库无下一个交付节点", empty.nextUpcomingPlannedEnd() == null);

        svc.create("A", "甲", LocalDateTime.parse("2026-11-01T09:00:00"),
                LocalDateTime.parse("2026-11-05T09:00:00"), null, 100, null);   // 已完成
        svc.create("B", "乙", LocalDateTime.parse("2026-11-01T09:00:00"),
                LocalDateTime.parse("2026-11-30T09:00:00"), null, 50, null);   // 进行中（未到期）
        svc.create("C", "丙", LocalDateTime.parse("2026-10-01T09:00:00"),
                LocalDateTime.parse("2026-11-02T09:00:00"), null, 20, null);   // 已延期
        svc.create("D", "丁", LocalDateTime.parse("2026-12-01T09:00:00"),
                LocalDateTime.parse("2026-12-10T09:00:00"), null, 0, null);    // 未开始

        DeliverySummary s = svc.summary();
        eq("汇总-总数", s.totalCount(), 4);
        eq("汇总-已完成数", s.completedCount(), 1);
        eq("汇总-进行中数", s.inProgressCount(), 1);
        eq("汇总-已延期数", s.delayedCount(), 1);
        eq("汇总-未开始数", s.notStartedCount(), 1);
        // 整体进度 = (100+50+20+0)/4 = 42（向下取整）
        eq("汇总-整体进度(等权平均)", s.overallProgressPercent(), 42);
        // 完成率 = 1/4 = 25
        eq("汇总-完成率", s.completedRatioPercent(), 25);
        // 最近交付节点 = 未完成项中最早的计划完成时间 = 2026-11-02
        eq("汇总-最近交付节点", s.earliestPlannedEnd(), LocalDateTime.parse("2026-11-02T09:00:00"));
        // 下一个交付节点 = 未完成且尚未到期项中最早的计划完成时间 = 2026-11-30
        // （2026-11-02 那项已延期、计划完成时间在过去，故不计入）
        eq("汇总-下一个交付节点", s.nextUpcomingPlannedEnd(), LocalDateTime.parse("2026-11-30T09:00:00"));
        check("汇总-生成时刻非空", s.updatedAt() != null);

        // 全部完成 → 无待交付节点
        List<DeliveryItem> all = svc.list(null);
        for (DeliveryItem it : all) {
            svc.update(it.id(), null, null, null, null, null, 100, null);
        }
        DeliverySummary allDone = svc.summary();
        eq("汇总-全部完成总数", allDone.totalCount(), 4);
        eq("汇总-全部完成完成数", allDone.completedCount(), 4);
        eq("汇总-全部完成整体进度", allDone.overallProgressPercent(), 100);
        eq("汇总-全部完成完成率", allDone.completedRatioPercent(), 100);
        check("汇总-全部完成无待交付节点", allDone.earliestPlannedEnd() == null);
        check("汇总-全部完成无下一个交付节点", allDone.nextUpcomingPlannedEnd() == null);

        // 全部未完成项均已延期 → 有"最早未完成"，但没有"下一个未到期节点"
        InMemoryDeliveryRepository lateRepo = new InMemoryDeliveryRepository();
        DeliveryProgressService lateSvc = new DeliveryProgressService(lateRepo, clock);
        lateSvc.create("E", "戊", LocalDateTime.parse("2026-10-01T09:00:00"),
                LocalDateTime.parse("2026-10-20T09:00:00"), null, 20, null);
        lateSvc.create("F", "己", LocalDateTime.parse("2026-09-01T09:00:00"),
                LocalDateTime.parse("2026-09-20T09:00:00"), null, 10, null);
        DeliverySummary allLate = lateSvc.summary();
        eq("汇总-全部延期最早未完成", allLate.earliestPlannedEnd(),
                LocalDateTime.parse("2026-09-20T09:00:00"));
        check("汇总-全部延期无下一个未到期节点", allLate.nextUpcomingPlannedEnd() == null);
    }

    /* ---------------- 时钟驱动的自动延期 ---------------- */

    static void clockDrivenTests() {
        // 初始时钟设在计划完成时间（11-05）之前
        FixedClock clock = new FixedClock(instant("2026-11-03T12:00:00"));
        InMemoryDeliveryRepository repo = new InMemoryDeliveryRepository();
        DeliveryProgressService svc = new DeliveryProgressService(repo, clock);

        DeliveryItem it = svc.create("阶段", "甲", LocalDateTime.parse("2026-11-01T09:00:00"),
                LocalDateTime.parse("2026-11-05T09:00:00"), null, 30, null);
        eq("时钟-到期前为进行中", it.status(clock.get()), DeliveryStatus.IN_PROGRESS);

        // 推进到计划完成时间之后：不改任何数据，状态自动变"已延期"
        clock.set(instant("2026-11-06T00:00:00"));
        DeliveryItem reloaded = svc.findById(it.id()).orElseThrow();
        eq("时钟-到期后自动已延期", reloaded.status(clock.get()), DeliveryStatus.DELAYED);

        // 人工覆盖可以把已延期改回进行中
        DeliveryItem forced = svc.update(it.id(), null, null, null, null, null, null,
                DeliveryStatus.IN_PROGRESS);
        eq("时钟-人工覆盖可改回", forced.status(clock.get()), DeliveryStatus.IN_PROGRESS);
    }

    /* ---------------- 辅助 ---------------- */

    static DeliveryItem item(String id, String phase, String owner,
                              String start, String end, String actual,
                              int progress, DeliveryStatus override) {
        return new DeliveryItem(id, phase, owner,
                LocalDateTime.parse(start), LocalDateTime.parse(end),
                actual == null ? null : LocalDateTime.parse(actual),
                progress, override);
    }

    interface Action {
        void run();
    }

    static void expectValidation(String name, String expectedField, Action action) {
        try {
            action.run();
            check(name + "（应抛校验异常）", false);
        } catch (DeliveryValidationException e) {
            check(name, expectedField.equals(e.field()));
        }
    }
}
