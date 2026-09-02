package com.stioc.cute.platform.contract;

import com.google.common.util.concurrent.Striped;
import java.util.concurrent.locks.Lock;

/**
 * 契约锁定义，用于集中持有全局并发排他锁的物理引用，
 * 方便各个具体业务模块直接使用。
 */
public class ContractLock {

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
     * 文件物理排他写锁条带锁
     */
    public static final Striped<Lock> FILE_STRIPED = Striped.lock(256);
}
