-- ST-Cute 数据库全量初始化脚本 (v0.2.0)
-- 包含 t_conversation, t_message, t_project 全量表结构与索引定义

-- ==================== 1. 会话表 (t_conversation) ====================
DROP TABLE IF EXISTS t_conversation;
CREATE TABLE IF NOT EXISTS t_conversation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    create_time DATETIME,
    update_time DATETIME,
    title TEXT,
    workspace_id TEXT,
    provider_group TEXT,
    provider_model_name TEXT,
    permission_mode TEXT,
    parent_cid INTEGER,
    input_tokens INTEGER,
    output_tokens INTEGER,
    cached_tokens INTEGER,
    call_tool_count INTEGER,
    waiting_tool_ids TEXT,
    waiting_sub_cids TEXT,
    loop_count INTEGER,
    loop_running INTEGER
);
CREATE INDEX IF NOT EXISTS idx_conversation_workspace_id ON t_conversation (workspace_id);
CREATE INDEX IF NOT EXISTS idx_conversation_parent_cid ON t_conversation (parent_cid);

-- ==================== 2. 消息表 (t_message) ====================
DROP TABLE IF EXISTS t_message;
CREATE TABLE IF NOT EXISTS t_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    create_time DATETIME,
    update_time DATETIME,
    cid INTEGER,
    parent_message_id INTEGER,
    role TEXT,
    content TEXT,
    reasoning_content TEXT,
    tool_calls TEXT,
    call_id TEXT,
    status TEXT,
    visible_to_user INTEGER NOT NULL DEFAULT 1,
    visible_to_model INTEGER NOT NULL DEFAULT 1,
    input_tokens INTEGER,
    output_tokens INTEGER,
    cached_tokens INTEGER,
    execution_duration_ms INTEGER,
    attachments TEXT
);
CREATE INDEX IF NOT EXISTS idx_message_cid ON t_message (cid);
CREATE INDEX IF NOT EXISTS idx_message_cid_callid ON t_message (cid, call_id);

-- ==================== 3. 项目表 (t_project) ====================
DROP TABLE IF EXISTS t_project;
CREATE TABLE IF NOT EXISTS t_project (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    create_time DATETIME,
    update_time DATETIME,
    name TEXT,
    path TEXT,
    expanded INTEGER NOT NULL DEFAULT 1,
    active INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_project_path ON t_project (path);
