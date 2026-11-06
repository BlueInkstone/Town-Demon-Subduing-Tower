CREATE TABLE IF NOT EXISTS governance_decision (
  id               VARCHAR(36)  PRIMARY KEY,
  tool             VARCHAR(128) NOT NULL,
  risk             VARCHAR(8),
  code             VARCHAR(64),
  decision_status  VARCHAR(32)  NOT NULL,
  approval_ticket_id VARCHAR(64),
  args_json        VARCHAR(2000),
  created_at       TIMESTAMP     NOT NULL
);

CREATE TABLE IF NOT EXISTS outbox_message (
  id               VARCHAR(36)  PRIMARY KEY,
  decision_id      VARCHAR(36)  NOT NULL,
  event_type       VARCHAR(64)  NOT NULL,
  tool             VARCHAR(128),
  risk             VARCHAR(8),
  code             VARCHAR(64),
  decision_status  VARCHAR(32),
  approval_ticket_id VARCHAR(64),
  publish_status   VARCHAR(16)  NOT NULL,
  retry_count      INT          NOT NULL DEFAULT 0,
  created_at       TIMESTAMP    NOT NULL
);
