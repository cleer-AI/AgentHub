-- 创建数据库
CREATE DATABASE IF NOT EXISTS ai_assistant_db
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

-- 使用数据库
USE ai_assistant_db;

-- 创建会话表
CREATE TABLE chat_session (
                              id BIGINT AUTO_INCREMENT PRIMARY KEY,
                              user_id BIGINT NOT NULL,
                              session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
                              request_id VARCHAR(64) NOT NULL COMMENT '客户端请求幂等键',
                              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                              UNIQUE KEY uk_user_session (user_id, session_id),
                              INDEX idx_chat_session_user_id (user_id)
);

-- 创建消息表
CREATE TABLE chat_message (
                              id BIGINT AUTO_INCREMENT PRIMARY KEY,
                              user_id BIGINT NOT NULL,
                              session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
                              role VARCHAR(20) NOT NULL COMMENT '角色: user/assistant',
                              content TEXT NOT NULL COMMENT '消息内容',
                              message_order INT NOT NULL COMMENT '消息顺序',
                              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

                              INDEX idx_session_id (session_id),
                              INDEX idx_session_order (session_id, message_order),
                              INDEX idx_user_session (user_id, session_id),
                              UNIQUE KEY uk_message_request_role (user_id, session_id, request_id, role)
);

CREATE TABLE user (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      username VARCHAR(50) NOT NULL UNIQUE,
                      password VARCHAR(255) NOT NULL,
                      email VARCHAR(100),
                      phone VARCHAR(20),
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

ALTER TABLE chat_session
    ADD CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES user(id);
