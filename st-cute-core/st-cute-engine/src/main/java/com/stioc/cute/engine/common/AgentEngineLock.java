package com.stioc.cute.engine.common;

import com.google.common.util.concurrent.Striped;
import java.util.concurrent.locks.Lock;

/**
 * 引擎契约锁定义，用于集中持有引擎全局并发排他锁的物理引用。
 * <p>自 platform/contract/ContractLock 随行迁入引擎，锁语义与条带数完全一致，旧引用统一改指本类。</p>
 */
public class AgentEngineLock {

    /**
     * 会话数据写操作排他条带锁（包含会话与消息的读写加锁）。
     * key 直接使用 cid，覆盖会话实体的事件链写盘/内存回填与触发判定等所有会话数据临界区
     */
    public static final Striped<Lock> CID_DATA_STRIPED = Striped.lock(512);

    /**
     * 会话 ReAct 对话循环执行串行化条带锁。
     * key 直接使用 cid
     */
    public static final Striped<Lock> CID_LOOP_STRIPED = Striped.lock(128);

    /**
     * 会话自动命名排他条带锁。
     * key 直接使用 cid
     */
    public static final Striped<Lock> CID_NAMING_STRIPED = Striped.lock(64);

    /**
     * 写工具执行并发排他条带锁
     */
    public static final Striped<Lock> WRITE_TOOL_STRIPED = Striped.lock(256);
}
