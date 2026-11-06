package com.towndemontower.spring.tx;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * [starter/tx] 治理决策落库（JDBC，无 ORM 依赖）。与 Outbox 在同一事务内写入，保证一致性。
 */
public class GovernanceDecisionRepository {

    private final JdbcTemplate jdbc;

    public GovernanceDecisionRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void insert(String decisionId, String tool, String risk, String code,
                       String decisionStatus, String approvalTicketId, String argsJson, Instant createdAt) {
        jdbc.update(
                "INSERT INTO governance_decision(id, tool, risk, code, decision_status, approval_ticket_id, args_json, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                decisionId, tool, risk, code, decisionStatus, approvalTicketId, argsJson,
                Timestamp.from(createdAt));
    }

    public int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM governance_decision", Integer.class);
    }
}
