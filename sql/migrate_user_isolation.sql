USE ai_assistant_db;

-- 对已有数据库先允许 NULL，以便人工将历史会话归属到正确用户；新建会话由应用写入 user_id。
ALTER TABLE chat_session ADD COLUMN user_id BIGINT NULL AFTER id;
ALTER TABLE chat_message ADD COLUMN user_id BIGINT NULL AFTER id;
CREATE INDEX idx_chat_session_user_id ON chat_session (user_id);
CREATE INDEX idx_chat_message_user_session ON chat_message (user_id, session_id);
ALTER TABLE chat_session DROP INDEX session_id;
CREATE UNIQUE INDEX uk_chat_session_user_session ON chat_session (user_id, session_id);

-- 完成历史数据归属后执行：
-- ALTER TABLE chat_session MODIFY user_id BIGINT NOT NULL;
-- ALTER TABLE chat_message MODIFY user_id BIGINT NOT NULL;
-- ALTER TABLE chat_session ADD CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES user(id);
