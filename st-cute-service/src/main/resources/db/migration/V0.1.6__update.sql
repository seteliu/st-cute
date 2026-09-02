-- loop_count 语义改造：删除旧迭代计数字段，新增循环轮次字段
-- iteration_count 旧语义：LLM 因工具调用自循环的次数（每轮推理前 +1，用户发新消息置 0）
-- loop_count 新语义：当前循环轮次（第几轮），初始 0；用户发消息置 0 归零重开，每次循环启动 +1。
-- 存库仅用于监控展示，历史数据无迁移价值，重启后自然归零重开。
-- 注意：列必须可空——SQLite 的 DEFAULT 仅在 INSERT 省略列时生效，MyBatis-Flex 对实体 null 字段
-- 会显式写入 NULL，NOT NULL 约束会导致新建会话报 SQLITE_CONSTRAINT_NOTNULL（null 语义等同 0）
ALTER TABLE t_conversation DROP COLUMN iteration_count;
ALTER TABLE t_conversation DROP COLUMN loop_running;
ALTER TABLE t_conversation ADD COLUMN loop_count INTEGER;
ALTER TABLE t_conversation ADD COLUMN loop_running INTEGER;
