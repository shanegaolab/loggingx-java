-- Applied at every startup (spring.sql.init.mode=always), so every statement must be idempotent.
-- utf8mb4_bin keeps usernames, role codes and route names case-sensitive, as the application expects.

CREATE TABLE IF NOT EXISTS sys_user (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    username         VARCHAR(64)  NOT NULL,
    encoded_password VARCHAR(255),
    token_version    INT          NOT NULL DEFAULT 0,
    nick_name        VARCHAR(64),
    gender           VARCHAR(8),
    phone            VARCHAR(32),
    email            VARCHAR(255),
    avatar           VARCHAR(1024),
    status           VARCHAR(8),
    created_at       DATETIME(6),
    created_by       VARCHAR(64),
    updated_at       DATETIME(6),
    updated_by       VARCHAR(64),
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_user_username UNIQUE (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(64),
    description VARCHAR(255),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6),
    updated_at  DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_role_code UNIQUE (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- Only MENU entries have a unique route name; route_name is NULL for buttons, and unique keys allow many NULLs.
CREATE TABLE IF NOT EXISTS sys_menu (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    parent_id   BIGINT        NOT NULL DEFAULT 0,
    type        VARCHAR(16)   NOT NULL,
    name        VARCHAR(64),
    path        VARCHAR(255),
    component   VARCHAR(255),
    title       VARCHAR(64),
    icon        VARCHAR(128),
    sort        INT           NOT NULL DEFAULT 0,
    enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    keep_alive  BOOLEAN       NOT NULL DEFAULT FALSE,
    hidden      BOOLEAN       NOT NULL DEFAULT FALSE,
    hide_tab    BOOLEAN       NOT NULL DEFAULT FALSE,
    link        VARCHAR(1024),
    iframe      BOOLEAN       NOT NULL DEFAULT FALSE,
    show_badge  BOOLEAN       NOT NULL DEFAULT FALSE,
    text_badge  VARCHAR(32),
    fixed_tab   BOOLEAN       NOT NULL DEFAULT FALSE,
    active_path VARCHAR(255),
    full_page   BOOLEAN       NOT NULL DEFAULT FALSE,
    auth_mark   VARCHAR(64),
    updated_at  DATETIME(6),
    route_name  VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN type = 'MENU' THEN name END),
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_menu_route_name UNIQUE (route_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- Grants reference roles and menus by id only; the services remove grants when a role or menu is deleted.
CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY idx_sys_user_role_role (role_id),
    CONSTRAINT fk_sys_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS sys_role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, menu_id),
    KEY idx_sys_role_menu_menu (menu_id),
    CONSTRAINT fk_sys_role_menu_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Departments form a tree through parent_id (0 for top-level ones); sibling departments have distinct names.
CREATE TABLE IF NOT EXISTS sys_dept (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    parent_id  BIGINT       NOT NULL DEFAULT 0,
    name       VARCHAR(64)  NOT NULL,
    sort       INT          NOT NULL DEFAULT 0,
    leader     VARCHAR(64),
    phone      VARCHAR(32),
    email      VARCHAR(255),
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_dept_parent_name UNIQUE (parent_id, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS sys_post (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(64)  NOT NULL,
    name       VARCHAR(64)  NOT NULL,
    sort       INT          NOT NULL DEFAULT 0,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    remark     VARCHAR(255),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_post_code UNIQUE (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- A user belongs to at most one department. It is a separate table rather than a sys_user column so this script
-- stays idempotent on existing databases. Departments and posts still held by users cannot be deleted.
CREATE TABLE IF NOT EXISTS sys_user_dept (
    user_id BIGINT NOT NULL,
    dept_id BIGINT NOT NULL,
    PRIMARY KEY (user_id),
    KEY idx_sys_user_dept_dept (dept_id),
    CONSTRAINT fk_sys_user_dept_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_user_dept_dept FOREIGN KEY (dept_id) REFERENCES sys_dept (id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS sys_user_post (
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, post_id),
    KEY idx_sys_user_post_post (post_id),
    CONSTRAINT fk_sys_user_post_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_user_post_post FOREIGN KEY (post_id) REFERENCES sys_post (id)
) ENGINE = InnoDB;

-- The departments a post applies to; a post without rows here applies to every department. A post applying to a
-- department also applies to its descendants. Departments that posts still apply to cannot be deleted.
CREATE TABLE IF NOT EXISTS sys_post_dept (
    post_id BIGINT NOT NULL,
    dept_id BIGINT NOT NULL,
    PRIMARY KEY (post_id, dept_id),
    KEY idx_sys_post_dept_dept (dept_id),
    CONSTRAINT fk_sys_post_dept_post FOREIGN KEY (post_id) REFERENCES sys_post (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_post_dept_dept FOREIGN KEY (dept_id) REFERENCES sys_dept (id)
) ENGINE = InnoDB;

-- A log store collects the logs of one application or environment. Producers write to it over HTTP with its ingest
-- token, or through Kafka with its code. The log lines themselves are kept in ClickHouse (db/clickhouse-schema.sql).
CREATE TABLE IF NOT EXISTS log_store (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    code           VARCHAR(64)  NOT NULL,
    name           VARCHAR(64)  NOT NULL,
    description    VARCHAR(255),
    retention_days INT          NOT NULL DEFAULT 7,
    ingest_token   VARCHAR(64)  NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     DATETIME(6),
    updated_at     DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_log_store_code UNIQUE (code),
    CONSTRAINT uk_log_store_ingest_token UNIQUE (ingest_token)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- What the collection agents of a log store collect. Agents fetch their config from LoggingX, so a change here
-- reaches every agent of the store on its next sync. A store without a row uses the defaults.
CREATE TABLE IF NOT EXISTS log_collect_config (
    store_id          BIGINT        NOT NULL,
    paths             VARCHAR(4000),
    windows_paths     VARCHAR(4000),
    containers        BOOLEAN       NOT NULL DEFAULT TRUE,
    multiline         VARCHAR(16)   NOT NULL,
    multiline_pattern VARCHAR(255),
    transport         VARCHAR(16)   NOT NULL,
    kafka_servers     VARCHAR(500),
    kafka_topic       VARCHAR(255),
    updated_at        DATETIME(6),
    PRIMARY KEY (store_id),
    CONSTRAINT fk_log_collect_config_store FOREIGN KEY (store_id) REFERENCES log_store (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- A collection agent installed on a host (or a Kubernetes node), registered on its first sync with the store's ingest
-- token. Agents then authenticate with their own secret, of which only the SHA-256 hash is kept, so they keep syncing
-- after the store's token is regenerated. last_seen_at is the time of the latest sync, which agents send every
-- 30-60 seconds.
CREATE TABLE IF NOT EXISTS log_agent (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    agent_key       VARCHAR(64)  NOT NULL,
    secret_hash     CHAR(64)     NOT NULL,
    store_id        BIGINT       NOT NULL,
    hostname        VARCHAR(255) NOT NULL,
    platform        VARCHAR(16)  NOT NULL,
    version         VARCHAR(32),
    ip              VARCHAR(64),
    server_url      VARCHAR(255),
    config_revision VARCHAR(32),
    registered_at   DATETIME(6),
    last_seen_at    DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_log_agent_key UNIQUE (agent_key),
    KEY idx_log_agent_store (store_id),
    CONSTRAINT fk_log_agent_store FOREIGN KEY (store_id) REFERENCES log_store (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

-- Host resource monitoring of a log store's agents: whether they report metrics, how often, and whether they include
-- busy processes and containers. A store without a row uses the defaults (enabled). Part of the collect config agents
-- sync, so a change reaches them on their next sync.
CREATE TABLE IF NOT EXISTS host_monitor_config (
    store_id         BIGINT      NOT NULL,
    enabled          BOOLEAN     NOT NULL DEFAULT TRUE,
    interval_seconds INT         NOT NULL DEFAULT 15,
    processes        BOOLEAN     NOT NULL DEFAULT TRUE,
    containers       BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at       DATETIME(6),
    PRIMARY KEY (store_id),
    CONSTRAINT fk_host_monitor_config_store FOREIGN KEY (store_id) REFERENCES log_store (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Threshold alert rules on host metrics, evaluated every minute. metric is cpu, memory, disk, load or offline; a rule
-- fires for a host when the metric stays above threshold for duration_minutes (disk: on the latest sample; offline:
-- when the agent has not synced for duration_minutes). A rule without store_id applies to every store.
CREATE TABLE IF NOT EXISTS host_alert_rule (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(64)  NOT NULL,
    metric           VARCHAR(16)  NOT NULL,
    threshold        DOUBLE       NOT NULL,
    duration_minutes INT          NOT NULL,
    severity         VARCHAR(16)  NOT NULL,
    store_id         BIGINT,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       DATETIME(6),
    updated_at       DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_host_alert_rule_store FOREIGN KEY (store_id) REFERENCES log_store (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Alerts raised by the rules. A firing alert of a rule and host is updated while the condition holds and resolved when
-- it no longer does; rule and store columns are copied so the history survives their deletion.
CREATE TABLE IF NOT EXISTS host_alert_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    rule_id       BIGINT       NOT NULL,
    rule_name     VARCHAR(64)  NOT NULL,
    metric        VARCHAR(16)  NOT NULL,
    severity      VARCHAR(16)  NOT NULL,
    threshold     DOUBLE       NOT NULL,
    store_id      BIGINT       NOT NULL,
    hostname      VARCHAR(255) NOT NULL,
    current_value DOUBLE,
    peak_value    DOUBLE,
    status        VARCHAR(16)  NOT NULL,
    started_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    resolved_at   DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_host_alert_event_status (status, started_at),
    KEY idx_host_alert_event_host (store_id, hostname, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Where log archives are kept: one S3-compatible bucket (MinIO, Alibaba Cloud OSS, Tencent Cloud COS, AWS S3, ...).
-- A single row (id 1). The secret key is encrypted by the application (AES-GCM).
CREATE TABLE IF NOT EXISTS backup_storage (
    id         INT           NOT NULL,
    endpoint   VARCHAR(255)  NOT NULL,
    region     VARCHAR(64)   NOT NULL,
    bucket     VARCHAR(128)  NOT NULL,
    prefix     VARCHAR(255),
    access_key VARCHAR(255)  NOT NULL,
    secret_key VARCHAR(1024) NOT NULL,
    path_style BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_at DATETIME(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Archiving of a log store: each day older than archive_after_days is written to the bucket as one compressed file,
-- which is deleted keep_days after that day. A store without a row is not archived.
CREATE TABLE IF NOT EXISTS backup_policy (
    store_id           BIGINT  NOT NULL,
    enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    archive_after_days INT     NOT NULL,
    keep_days          INT     NOT NULL,
    updated_at         DATETIME(6),
    PRIMARY KEY (store_id),
    CONSTRAINT fk_backup_policy_store FOREIGN KEY (store_id) REFERENCES log_store (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- The archives: one per store and day, with what it holds and where. Archives outlive their store (store_code is
-- kept), so they can still be downloaded. A failed archive is retried on the next run. A restored archive's logs are
-- back in the store, and kept from the retention cleanup, until restored_until.
CREATE TABLE IF NOT EXISTS backup_archive (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    store_id       BIGINT        NOT NULL,
    store_code     VARCHAR(64)   NOT NULL,
    log_day        DATE          NOT NULL,
    object_key     VARCHAR(512)  NOT NULL,
    record_count   BIGINT        NOT NULL DEFAULT 0,
    raw_bytes      BIGINT        NOT NULL DEFAULT 0,
    size_bytes     BIGINT        NOT NULL DEFAULT 0,
    sha256         CHAR(64),
    status         VARCHAR(16)   NOT NULL,
    error          VARCHAR(1000),
    archived_at    DATETIME(6)   NOT NULL,
    restore_status VARCHAR(16),
    restore_error  VARCHAR(1000),
    restored_at    DATETIME(6),
    restored_until DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_backup_archive_day UNIQUE (store_id, log_day),
    KEY idx_backup_archive_day (log_day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Notification channels of the alerts: email (SMTP) and SMS (Alibaba Cloud or Tencent Cloud). config holds the settings
-- as JSON; secret (the SMTP password or the SMS access key secret) is encrypted by the application.
CREATE TABLE IF NOT EXISTS notify_channel (
    channel    VARCHAR(16)   NOT NULL,
    enabled    BOOLEAN       NOT NULL DEFAULT TRUE,
    config     TEXT          NOT NULL,
    secret     VARCHAR(2048),
    updated_at DATETIME(6),
    PRIMARY KEY (channel)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Groups of alert recipients: system users (user_ids, a JSON array) and other contacts (contacts, a JSON array of
-- {name, email, phone}).
CREATE TABLE IF NOT EXISTS notify_group (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255),
    user_ids    TEXT,
    contacts    TEXT,
    created_at  DATETIME(6),
    updated_at  DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_notify_group_name UNIQUE (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- How an alert rule notifies: rule_type is host (host_alert_rule) or log (log_alert_rule); channels is a comma-separated
-- list of email and sms; user_ids and group_ids are JSON arrays. A rule without a row does not notify.
CREATE TABLE IF NOT EXISTS alert_notify (
    rule_type       VARCHAR(8)  NOT NULL,
    rule_id         BIGINT      NOT NULL,
    channels        VARCHAR(32) NOT NULL,
    user_ids        TEXT,
    group_ids       TEXT,
    notify_resolved BOOLEAN     NOT NULL DEFAULT TRUE,
    repeat_minutes  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (rule_type, rule_id)
) ENGINE = InnoDB;

-- Log alert rules, evaluated every minute: the store's logs of the last window_minutes matching the levels
-- (comma-separated, empty for all), the keyword (substring of the message, ignoring case) and the host fire the rule
-- when there are more than threshold of them.
CREATE TABLE IF NOT EXISTS log_alert_rule (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(64)  NOT NULL,
    store_id       BIGINT       NOT NULL,
    levels         VARCHAR(64),
    keyword        VARCHAR(255),
    host           VARCHAR(255),
    window_minutes INT          NOT NULL,
    threshold      INT          NOT NULL,
    severity       VARCHAR(16)  NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     DATETIME(6),
    updated_at     DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_log_alert_rule_store FOREIGN KEY (store_id) REFERENCES log_store (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Alerts raised by the log rules, like host_alert_event: current_value is the number of matching logs in the window,
-- sample the newest matching message.
CREATE TABLE IF NOT EXISTS log_alert_event (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    rule_id        BIGINT        NOT NULL,
    rule_name      VARCHAR(64)   NOT NULL,
    store_id       BIGINT        NOT NULL,
    severity       VARCHAR(16)   NOT NULL,
    threshold      INT           NOT NULL,
    window_minutes INT           NOT NULL,
    current_value  BIGINT,
    peak_value     BIGINT,
    sample         VARCHAR(1000),
    status         VARCHAR(16)   NOT NULL,
    started_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    resolved_at    DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_log_alert_event_status (status, started_at),
    KEY idx_log_alert_event_rule (rule_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Every notification sent (or failed): event_type host or log, kind firing, resolved, repeat or test.
CREATE TABLE IF NOT EXISTS notify_record (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(8)    NOT NULL,
    event_id   BIGINT,
    rule_name  VARCHAR(64),
    kind       VARCHAR(16)   NOT NULL,
    channel    VARCHAR(16)   NOT NULL,
    recipient  VARCHAR(255)  NOT NULL,
    status     VARCHAR(16)   NOT NULL,
    error      VARCHAR(1000),
    sent_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notify_record_event (event_type, event_id, sent_at),
    KEY idx_notify_record_time (sent_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Which log stores a role may access. A role without a row may access every store (as before stores were scoped).
CREATE TABLE IF NOT EXISTS sys_role_log_store (
    role_id    BIGINT  NOT NULL,
    all_stores BOOLEAN NOT NULL DEFAULT TRUE,
    store_ids  TEXT,
    updated_at DATETIME(6),
    PRIMARY KEY (role_id),
    CONSTRAINT fk_role_log_store_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- AI diagnosis: global settings (one row, id 1).
CREATE TABLE IF NOT EXISTS ai_settings (
    id          INT          NOT NULL,
    provider    VARCHAR(32),
    model       VARCHAR(128),
    daily_limit INT          NOT NULL DEFAULT 50,
    updated_at  DATETIME(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- AI diagnosis per log store: its code repository and what triggers an analysis.
CREATE TABLE IF NOT EXISTS ai_store_config (
    store_id       BIGINT        NOT NULL,
    repo_url       VARCHAR(512),
    repo_branch    VARCHAR(128),
    repo_username  VARCHAR(128),
    repo_secret    VARCHAR(2048),
    auto_new       BOOLEAN       NOT NULL DEFAULT FALSE,
    on_alert       BOOLEAN       NOT NULL DEFAULT FALSE,
    scanned_until  DATETIME(6),
    repo_commit    VARCHAR(64),
    repo_synced_at DATETIME(6),
    repo_error     VARCHAR(1000),
    updated_at     DATETIME(6),
    PRIMARY KEY (store_id),
    CONSTRAINT fk_ai_store_config_store FOREIGN KEY (store_id) REFERENCES log_store (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Errors of a store grouped by fingerprint (exception type and first application frame, or normalized message).
CREATE TABLE IF NOT EXISTS error_group (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    store_id     BIGINT       NOT NULL,
    fingerprint  VARCHAR(40)  NOT NULL,
    error_type   VARCHAR(255),
    location     VARCHAR(512),
    sample       TEXT,
    host         VARCHAR(128),
    occurrences  BIGINT       NOT NULL DEFAULT 0,
    first_seen   DATETIME(6),
    last_seen    DATETIME(6),
    status       VARCHAR(16)  NOT NULL DEFAULT 'open',
    diagnosis_id BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_error_group UNIQUE (store_id, fingerprint),
    CONSTRAINT fk_error_group_store FOREIGN KEY (store_id) REFERENCES log_store (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- An AI analysis of an error with the code it points to.
CREATE TABLE IF NOT EXISTS ai_diagnosis (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    store_id          BIGINT        NOT NULL,
    group_id          BIGINT,
    trigger_type      VARCHAR(16)   NOT NULL,
    alert_event_id    BIGINT,
    status            VARCHAR(16)   NOT NULL,
    review_state      VARCHAR(16)   NOT NULL DEFAULT 'open',
    error_type        VARCHAR(255),
    log_text          TEXT,
    host              VARCHAR(128),
    source            VARCHAR(255),
    log_time          DATETIME(6),
    repo_commit       VARCHAR(64),
    code_refs         MEDIUMTEXT,
    provider          VARCHAR(32),
    model             VARCHAR(128),
    summary           VARCHAR(500),
    root_cause        TEXT,
    suggestion        MEDIUMTEXT,
    diff              MEDIUMTEXT,
    confidence        VARCHAR(16),
    prompt_tokens     INT,
    completion_tokens INT,
    error             VARCHAR(1000),
    created_by        VARCHAR(64),
    created_at        DATETIME(6),
    finished_at       DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_ai_diagnosis_created (created_at),
    CONSTRAINT fk_ai_diagnosis_store FOREIGN KEY (store_id) REFERENCES log_store (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- When each user last read their notices (the header bell).
CREATE TABLE IF NOT EXISTS user_notice_seen (
    username VARCHAR(64) NOT NULL,
    seen_at  DATETIME(6),
    PRIMARY KEY (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Operations knowledge (runbooks, postmortems, FAQs) the AI assistant answers from.
CREATE TABLE IF NOT EXISTS ai_knowledge (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    title      VARCHAR(200) NOT NULL,
    content    MEDIUMTEXT   NOT NULL,
    tags       VARCHAR(255),
    store_id   BIGINT,
    created_by VARCHAR(64),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_ai_knowledge_store FOREIGN KEY (store_id) REFERENCES log_store (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
