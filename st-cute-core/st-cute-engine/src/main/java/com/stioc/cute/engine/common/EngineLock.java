package com.stioc.cute.engine.common;

import java.util.concurrent.locks.Lock;

/**
 * 引擎锁供血接口：引擎全部并发排他锁的物理实现由宿主注入，引擎只面向本接口与 JDK {@link Lock} 编程。
 * <p>
 * 宿主可按部署形态自由选型：单机宿主（桌面端）可提供进程内条带锁（如 Guava Striped），
 * 云化多实例宿主可提供分布式锁（如 Redisson）；条带数、锁粒度与续期策略均为宿主实现细节。
 * </p>
 * <p>
 * 实现契约（违反将导致引擎临界区失效甚至死锁，务必遵守）：
 * <ul>
 *   <li><b>可重入</b>：同线程持锁期间，引擎会在事件链内重入同 key 数据锁
 *       （如 submitUserMessage 持锁写消息时，经事件分发再次进入同 cid 数据锁），实现必须可重入；</li>
 *   <li><b>同 key 同锁</b>：同一进程内同 key 的多次获取必须返回同一把锁（或语义等价的互斥体），
 *       宿主存储层与引擎内部临界区必须共享同一实现实例；</li>
 *   <li><b>支持 tryLock</b>：会话自动命名与僵死自愈路径依赖 tryLock 非阻塞语义；</li>
 *   <li><b>长持锁容忍</b>：循环锁（{@link #getConversationLoopLock(long)}）在 ReAct 全程持有
 *       （含 LLM 推理与工具执行，分钟级起步），租约型分布式实现必须带自动续期，
 *       禁止短租约硬超时强制释放；</li>
 *   <li><b>加锁顺序</b>：引擎存在双锁嵌套场景（子会话与父会话数据锁同持），
 *       固定按「子会话 → 父会话」顺序加锁，实现方不得引入新的嵌套加锁顺序，避免分布式死锁。</li>
 * </ul>
 * </p>
 */
public interface EngineLock {

    /**
     * 获取会话数据写临界区锁：覆盖会话与消息实体的事件链写盘、内存回填与触发判定等所有会话数据临界区
     *
     * @param cid 会话 ID
     * @return 可重入排他锁
     */
    Lock getConversationDataLock(long cid);

    /**
     * 获取会话 ReAct 对话循环串行化锁：整个循环期间持有（分钟级长锁，见类契约「长持锁容忍」）
     *
     * @param cid 会话 ID
     * @return 可重入排他锁
     */
    Lock getConversationLoopLock(long cid);

    /**
     * 获取会话自动命名排他锁：调用侧以 tryLock 非阻塞语义使用，拿不到即放弃本次命名
     *
     * @param cid 会话 ID
     * @return 可重入排他锁
     */
    Lock getConversationNamingLock(long cid);

    /**
     * 获取写工具执行并发排他锁：WRITE 与 SENSITIVE 等有外部副作用的工具执行期间持有
     *
     * @param lockKey 工具声明的锁键（如文件路径、会话 ID 等业务键）
     * @return 可重入排他锁
     */
    Lock getWriteToolLock(String lockKey);
}
