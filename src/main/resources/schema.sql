CREATE TABLE IF NOT EXISTS agent_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(256) NOT NULL DEFAULT '',
    display_name VARCHAR(128),
    role VARCHAR(32) NOT NULL DEFAULT 'user',
    department VARCHAR(128),
    phone VARCHAR(32),
    email VARCHAR(128),
    status TINYINT NOT NULL DEFAULT 1,
    last_login DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS session_index (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(256),
    status TINYINT NOT NULL DEFAULT 1,
    message_count INT NOT NULL DEFAULT 0,
    summary TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_user_id (user_id),
    INDEX idx_session_session_id (session_id)
);

CREATE TABLE IF NOT EXISTS conversation_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(64) NOT NULL,
    msg_id VARCHAR(64) NOT NULL UNIQUE,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    msg_index INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_cv_session_id (session_id),
    INDEX idx_cv_session_seq (session_id, msg_index)
);
