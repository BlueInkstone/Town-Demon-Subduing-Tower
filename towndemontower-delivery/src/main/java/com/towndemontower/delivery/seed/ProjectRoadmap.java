package com.towndemontower.delivery.seed;

import com.towndemontower.delivery.service.DeliveryProgressService;

import java.time.LocalDateTime;

/**
 * [delivery/seed] Town Demon-Subduing Tower <b>自身</b>的交付路线图基线。
 *
 * <p>本类把本仓库真实发生的交付里程碑固化为看板初始数据，使
 * {@code /delivery/} 打开即呈现"这个产品做到哪一步了"，而不是通用样例数据。</p>
 *
 * <p><b>时间口径</b>：</p>
 * <ul>
 *   <li><b>已完成项</b>——计划起止与实际完成时间全部取自仓库真实时间戳
 *       （源码文件与交付文档的 mtime），属于事实，不随启动时刻变化；</li>
 *   <li><b>未交付项</b>（已延期 / 进行中 / 未开始）——尚未承诺具体日期，
 *       计划窗口<b>相对启动时刻</b>生成：已延期项落在过去、进行中项横跨当下、
 *       未开始项落在未来。这样看板任何时候打开，状态分布都稳定且语义正确。</li>
 * </ul>
 *
 * <p>进度百分比是<b>人工评估值</b>（对应交付物完成度），不随时间变化；
 * 状态由 {@code DeliveryStatusResolver} 按"当前时间"实时推导，无需刷新。</p>
 */
public final class ProjectRoadmap {

    /**
     * 项目基线日：仓库首个交付物（core 契约层与 PM 文档）落地当日。
     * 已完成项的计划与实际时间均以该日为基准，逐项取自真实文件时间戳。
     */
    private static final LocalDateTime BASELINE = LocalDateTime.of(2026, 11, 2, 0, 0);

    private ProjectRoadmap() {
    }

    /**
     * 播种本产品的真实交付路线图。
     *
     * @param service 交付进度服务（写入走领域层字段校验）
     * @param now     当前时刻，作为未交付项的相对基准
     */
    public static void seed(DeliveryProgressService service, LocalDateTime now) {
        /* ---------- ① 已完成（时间取自仓库真实时间戳）---------- */

        // v1.0 Phase 0：core 契约 + 装饰器链 + 审计哈希链
        //   契约层源码 11:23:22，CoreTest 11:23:23，PM 文档 11:27:53
        service.create("v1.0 Phase 0 · 治理内核与 v1.3 契约", "谢圣",
                at(11, 20), at(11, 30), at(11, 28), 100, null);

        // v1.1 Phase 1：Spring Boot 薄适配层（@AgentSkill AOP + 自动配置）
        //   starter 首个源文件 11:51:23，适配层设计文档 12:10:31
        service.create("v1.1 Phase 1 · Spring Boot 薄适配层", "谢圣",
                at(11, 50), at(12, 15), at(12, 10), 100, null);

        // v1.2：运行时与基础设施增强（凭据代理 / 取消令牌 / 状态持久化 / AST 增强）
        //   CredentialBroker 等 14:24:58，V12FeatureTest 14:33:51，实现文档 14:35:14
        service.create("v1.2 · 运行时与基础设施增强（首批）", "谢圣",
                at(14, 20), at(14, 36), at(14, 35), 100, null);

        // v1.2.1：Docker 容器级沙箱
        //   DockerSandboxStrategy 14:51:02，V121DockerSandboxTest 14:51:59，实现文档 14:58:38
        service.create("v1.2.1 · Docker 容器级沙箱", "谢圣",
                at(14, 50), at(15, 0), at(14, 59), 100, null);

        // v1.2.1：starter 事务一致性（@Transactional + Outbox + WS 总线）
        //   tx 包 15:18:32–15:32:25，实现文档 15:40:23
        service.create("v1.2.1 · starter 事务一致性（Outbox/WS）", "谢圣",
                at(15, 15), at(15, 45), at(15, 40), 100, null);

        // v1.3：交付进度看板模块
        //   模块首个源文件 16:24:43，交付文档 17:28:45
        service.create("v1.3 · 交付进度看板模块", "谢圣",
                at(16, 20), at(17, 35), at(17, 30), 100, null);

        /* ---------- ② 已延期（计划窗口已过，实际未完成）---------- */

        // 审批网关：接口（ApprovalPolicy / HumanApprovalPolicy）与桩已具备，
        // 但真实网关（用户 / IM / CLI / 转人工）始终未接入 —— 计划窗口已过，进度 30%
        service.create("v1.2 · ApprovalPolicy 真实审批网关接入", "谢圣",
                now.minusDays(6), now.minusDays(4), null, 30, null);

        /* ---------- ③ 进行中（刚立项排期，尚未承诺具体日期）---------- */

        service.create("v1.3 · 交付看板增强（多租户 / 变更历史 / 分角色权限）", "谢圣",
                now.minusDays(2), now.plusDays(12), null, 10, null);

        /* ---------- ④ 未开始（计划窗口在未来）---------- */

        service.create("v1.3 · War Room 实时监控面板", "谢圣",
                now.plusDays(15), now.plusDays(45), null, 0, null);

        service.create("v1.3 · 审计看板与质量指标接入", "谢圣",
                now.plusDays(20), now.plusDays(50), null, 0, null);

        service.create("v1.3 · 物理急停控制台 + 配置档位 YAML", "谢圣",
                now.plusDays(25), now.plusDays(55), null, 0, null);

        service.create("v1.3 · 运维文档补全（SLO / Runbook / 回滚）", "谢圣",
                now.plusDays(30), now.plusDays(60), null, 0, null);

        service.create("v2.0 · Python / JS SDK 与多 Agent 编排引擎", "谢圣",
                now.plusDays(40), now.plusDays(75), null, 0, null);
    }

    /** 基线日指定时刻（分钟精度）。 */
    private static LocalDateTime at(int hour, int minute) {
        return BASELINE.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
    }

    /** 路线图的交付项总数（供测试断言）。 */
    public static int expectedItemCount() {
        return 13;
    }

    /** 路线图中"已完成"项数（供测试断言）。 */
    public static int expectedCompletedCount() {
        return 6;
    }

    /** 路线图中"已延期"项数（供测试断言）。 */
    public static int expectedDelayedCount() {
        return 1;
    }

    /** 路线图中"进行中"项数（供测试断言）。 */
    public static int expectedInProgressCount() {
        return 1;
    }

    /** 路线图中"未开始"项数（供测试断言）。 */
    public static int expectedNotStartedCount() {
        return 5;
    }

    /** 路线图中人工状态覆盖的项数（供测试断言；应为 0，即状态全部来自自动推导）。 */
    public static int expectedManualOverrideCount() {
        return 0;
    }
}
