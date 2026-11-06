package com.towndemontower.spring;

import com.towndemontower.core.audit.AuditStore;
import com.towndemontower.core.audit.HashChainAuditStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * [starter/test] 离线可运行验证器：手动装配自动配置 + 示例 Bean + AOP 开关，跑通四条链路，
 * 验证「AOP 拦截 → core 装饰器链默认策略 → 结果映射」端到端成立。
 *
 * <p>绕开 surefire（JDK 21 下本地 surefire 3.2.5 有 LinkageError），直接以 main 验证装配与 AOP 真实生效。</p>
 */
public class StarterDemo {

    public static void main(String[] args) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(TownDemontowerAutoConfiguration.class, AgentSkillDemo.class, TestAopConfig.class);
        ctx.refresh();

        // V1.6 起默认关闭"注解自授权"：权限必须显式授予。
        // V1.9 起引擎只读，显式授予必须走 PermissionProvisioner 端口。
        String agentId = ctx.getBean(TownDemontowerProperties.class).getAgentId();
        ctx.getBean(com.towndemontower.core.executor.PermissionProvisioner.class)
                .grant(agentId, "ops:restart", "ops:deploy", "data:delete");

        AgentSkillDemo demo = ctx.getBean(AgentSkillDemo.class);
        int pass = 0, fail = 0;

        // ① R1 重启：模式白名单 + 权限 + 风险闸门全通过 → 执行真实方法
        try {
            String r = demo.restartService("svc-1");
            if ("restarted:svc-1".equals(r)) { pass++; System.out.println("[PASS] R1 restartService -> " + r); }
            else { fail++; System.out.println("[FAIL] R1 wrong output: " + r); }
        } catch (Throwable t) {
            fail++; System.out.println("[FAIL] R1 threw: " + t);
        }

        // ② R3 删除：风险闸门硬性拒绝
        try {
            demo.deleteData("orders");
            fail++; System.out.println("[FAIL] R3 not denied");
        } catch (GovernanceDeniedException e) {
            if ("R3_DENIED".equals(e.getCode())) { pass++; System.out.println("[PASS] R3 deleteData -> " + e.getCode()); }
            else { fail++; System.out.println("[FAIL] R3 wrong code: " + e.getCode()); }
        }

        // ③ R2 部署：进审批网关 → 待审批
        try {
            demo.deployService("web", "1.0");
            fail++; System.out.println("[FAIL] R2 not pending");
        } catch (GovernanceDeniedException e) {
            if ("PENDING_APPROVAL".equals(e.getCode()) && e.getApprovalTicketId() != null) {
                pass++; System.out.println("[PASS] R2 deployService -> " + e.getCode() + " ticket=" + e.getApprovalTicketId());
            } else { fail++; System.out.println("[FAIL] R2 wrong: " + e.getCode() + "/" + e.getApprovalTicketId()); }
        }

        // ④ 模式白名单：splitTask 仅 PLANNING 允许，DEV 模式下拦截
        try {
            demo.splitTask("do X");
            fail++; System.out.println("[FAIL] mode-not-allowed not denied");
        } catch (GovernanceDeniedException e) {
            if ("MODE_TOOL_NOT_ALLOWED".equals(e.getCode())) { pass++; System.out.println("[PASS] splitTask(DEV) -> " + e.getCode()); }
            else { fail++; System.out.println("[FAIL] mode wrong code: " + e.getCode()); }
        }

        // 审计：4 次调用均经审计装饰器落链
        AuditStore audit = ctx.getBean(HashChainAuditStore.class);
        int audits = audit.all().size();
        if (audits >= 4) { pass++; System.out.println("[PASS] audit records = " + audits); }
        else { fail++; System.out.println("[FAIL] audit records = " + audits); }

        ctx.close();
        System.out.println("\nRESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
