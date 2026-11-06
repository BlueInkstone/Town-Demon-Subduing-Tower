package com.towndemontower.spring.tx;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * [starter/tx] Outbox 表访问。写入在业务事务内完成；发布与状态更新由 {@link OutboxRelay} 在提交后执行。
 */
public class OutboxRepository {

    private final JdbcTemplate jdbc;
    private static final RowMapper<OutboxMessage> MAP = OutboxRepository::map;

    public OutboxRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void insert(String decisionId, String eventType, String tool, String risk,
                       String code, String decisionStatus, String approvalTicketId, Instant createdAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO outbox_message(id, decision_id, event_type, tool, risk, code, decision_status, "
                        + "approval_ticket_id, publish_status, retry_count, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, decisionId, eventType, tool, risk, code, decisionStatus, approvalTicketId,
                "PENDING", 0, Timestamp.from(createdAt));
    }

    public List<OutboxMessage> findPendingByDecision(String decisionId) {
        return jdbc.query(
                "SELECT * FROM outbox_message WHERE decision_id=? AND publish_status='PENDING' ORDER BY created_at",
                MAP, decisionId);
    }

    public List<OutboxMessage> findPending() {
        return jdbc.query(
                "SELECT * FROM outbox_message WHERE publish_status='PENDING' ORDER BY created_at", MAP);
    }

    public void markPublished(String id) {
        jdbc.update("UPDATE outbox_message SET publish_status='PUBLISHED' WHERE id=?", id);
    }

    public void incrementRetry(String id) {
        jdbc.update("UPDATE outbox_message SET retry_count=retry_count+1 WHERE id=?", id);
    }

    public int countPending() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE publish_status='PENDING'", Integer.class);
    }

    private static OutboxMessage map(ResultSet rs, int row) throws SQLException {
        return new OutboxMessage(
                rs.getString("id"),
                rs.getString("decision_id"),
                rs.getString("event_type"),
                rs.getString("tool"),
                rs.getString("risk"),
                rs.getString("code"),
                rs.getString("decision_status"),
                rs.getString("approval_ticket_id"),
                rs.getString("publish_status"),
                rs.getInt("retry_count"),
                rs.getTimestamp("created_at").toInstant());
    }
}
