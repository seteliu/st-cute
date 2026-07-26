CREATE TABLE IF NOT EXISTS t_conversation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at DATETIME,
    updated_at DATETIME,
    title TEXT,
    project_id INTEGER,
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
    iteration_count INTEGER,
    worktree_path TEXT,
    worktree_branch TEXT,
    unlocked_tool_names TEXT,
    loop_running INTEGER
);

CREATE TABLE IF NOT EXISTS t_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at DATETIME,
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
    before_compact_content TEXT,
    input_tokens INTEGER,
    output_tokens INTEGER,
    cached_tokens INTEGER,
    execution_duration_ms INTEGER
);

CREATE TABLE IF NOT EXISTS t_project (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at DATETIME,
    name TEXT,
    path TEXT
);

-- 高频查询条件索引优化
CREATE INDEX IF NOT EXISTS idx_message_cid ON t_message (cid);
CREATE INDEX IF NOT EXISTS idx_message_cid_callid ON t_message (cid, call_id);
CREATE INDEX IF NOT EXISTS idx_conversation_project_id ON t_conversation (project_id);
CREATE INDEX IF NOT EXISTS idx_conversation_parent_cid ON t_conversation (parent_cid);
CREATE INDEX IF NOT EXISTS idx_project_path ON t_project (path);
