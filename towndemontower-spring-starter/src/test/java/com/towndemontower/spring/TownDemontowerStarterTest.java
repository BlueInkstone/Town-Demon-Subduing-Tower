package com.towndemontower.spring;

import com.towndemontower.core.audit.HashChainAuditStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [starter/test] Spring Boot 集成测试（真实 CI 用；使用 JDK 25 编译运行）。
 */
@SpringBootTest(
        classes = {TownDemontowerAutoConfiguration.class, AgentSkillDemo.class, TestAopConfig.class},
        properties = "towndemontower.permissions-bootstrap=ops:restart,data:delete,ops:deploy,plan:write")
class TownDemontowerStarterTest {

    @Autowired
    AgentSkillDemo demo;

    @Autowired
    HashChainAuditStore audit;

    @Test
    void r1_restart_allowed() {
        assertEquals("restarted:svc-1", demo.restartService("svc-1"));
    }

    @Test
    void r3_delete_denied() {
        GovernanceDeniedException ex = assertThrows(GovernanceDeniedException.class,
                () -> demo.deleteData("orders"));
        assertEquals("R3_DENIED", ex.getCode());
    }

    @Test
    void r2_deploy_pending() {
        GovernanceDeniedException ex = assertThrows(GovernanceDeniedException.class,
                () -> demo.deployService("web", "1.0"));
        assertEquals("PENDING_APPROVAL", ex.getCode());
    }

    @Test
    void mode_not_allowed() {
        GovernanceDeniedException ex = assertThrows(GovernanceDeniedException.class,
                () -> demo.splitTask("x"));
        assertEquals("MODE_TOOL_NOT_ALLOWED", ex.getCode());
    }
}
