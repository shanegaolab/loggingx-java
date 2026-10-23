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
