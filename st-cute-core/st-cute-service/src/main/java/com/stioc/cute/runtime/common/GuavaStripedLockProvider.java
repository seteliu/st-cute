package com.stioc.cute.runtime.common;

import com.google.common.util.concurrent.Striped;
import com.stioc.cute.engine.common.EngineLock;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.Lock;

/**
 * 宿主锁供血实现：基于 Guava Striped 的进程内条带锁（单机部署形态）。
 * <p>
 * 自引擎 AgentEngineLock 随锁抽象下放迁入宿主，条带数与锁语义完全一致（可重入、同 key 同锁）。
 * 云化多实例部署时替换为分布式锁实现（如 Redisson）即可，引擎无需感知。
 * 宿主侧需要与引擎共享同源锁的场合（如存储层差量更新临界区），
 * 统一经 {@code AgentEngine.getEngineLock()} 获取，禁止直接引用本类静态条带。
 * </p>
 */
@Component
public class GuavaStripedLockProvider implements EngineLock {

    /**
     * 会话数据写操作排他条带锁（包含会话与消息的读写加锁）。
     * key 直接使用 cid，覆盖会话实体的事件链写盘/内存回填与触发判定等所有会话数据临界区
     */
    private static final Striped<Lock> CID_DATA_STRIPED = Striped.lock(512);

    /**
     * 会话 ReAct 对话循环执行串行化条带锁。
     * key 直接使用 cid
     */
    private static final Striped<Lock> CID_LOOP_STRIPED = Striped.lock(128);

    /**
     * 会话自动命名排他条带锁。
     * key 直接使用 cid
     */
    private static final Striped<Lock> CID_NAMING_STRIPED = Striped.lock(64);

    /**
     * 写工具执行并发排他条带锁
     */
    private static final Striped<Lock> WRITE_TOOL_STRIPED = Striped.lock(256);

    @Override
    public Lock getConversationDataLock(long cid) {
        return CID_DATA_STRIPED.get(cid);
    }

    @Override
    public Lock getConversationLoopLock(long cid) {
        return CID_LOOP_STRIPED.get(cid);
    }

    @Override
    public Lock getConversationNamingLock(long cid) {
        return CID_NAMING_STRIPED.get(cid);
    }

    @Override
    public Lock getWriteToolLock(String lockKey) {
        return WRITE_TOOL_STRIPED.get(lockKey);
    }
}
