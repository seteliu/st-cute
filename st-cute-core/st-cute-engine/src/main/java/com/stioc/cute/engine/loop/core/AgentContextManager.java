package com.stioc.cute.engine.loop.core;

import com.stioc.cute.engine.event.AgentEventDispatcher;
import com.stioc.cute.engine.event.AgentEventFactory;
import com.stioc.cute.engine.hook.AgentHookDispatcher;
import com.stioc.cute.engine.loop.AgentContextInitializer;

import com.stioc.cute.engine.store.types.Conversation;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.store.types.MessageQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理内存中所有的活动智能体运行上下文环境。
 * <p>
 * 引擎侧仅负责循环运行时状态的恢复（Token 快照、权限模式字符串、供应商、循环轮次等）；
 * 事件监听器与 Hook 监听器清单为引擎级全局共享（AgentEventDispatcher / AgentHookDispatcher
 * 装配一次），创建上下文时仅注入分发器引用，不再逐上下文拷贝监听器清单；
 * 宿主强相关数据（技能、规则、Hook、动态工具源）经
 * {@link AgentContextInitializer} 扩展点由宿主回调装载，引擎不感知文件系统约定。
 * 字段使用 {@link Optional} 显式声明初始化器清单为可选扩展配置。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class AgentContextManager {

    private final AgentEventDispatcher eventDispatcher;
    private final AgentHookDispatcher hookDispatcher;
    private final ConversationStore conversationStore;
    private final MessageStore messageStore;
    private final Optional<List<AgentContextInitializer>> contextInitializers;

    /**
     * 内存活动上下文缓存容器 Map
     */
    private final ConcurrentHashMap<Long, AgentContext> contexts = new ConcurrentHashMap<>();

    /**
     * 获取或创建指定会话的运行上下文
     */
    public AgentContext getOrCreateContext(Long cid) {
        if (cid == null) {
            cid = 0L;
        }
        // 快速路：直接从 Map 中读取，不进行锁或数据库查询
        AgentContext existing = contexts.get(cid);
        if (existing != null) {
            return existing;
        }

        // 慢速路：内存中尚无此上下文，需要初始化创建
        return contexts.computeIfAbsent(cid, c -> {
            // 监听器清单为引擎级全局共享（两分发器装配一次），此处仅注入引用
            AgentContext ctx = new AgentContext(c, eventDispatcher, hookDispatcher);

            // 1. 从数据库恢复已持久化的运行时状态快照
            try {
                if (conversationStore != null) {
                    Conversation conv = conversationStore.getById(c);
                    if (conv != null) {
                        // 恢复 Token 计数
                        if (conv.getInputTokens() != null) {
                            ctx.setInputTokens(conv.getInputTokens());
                        }
                        if (conv.getOutputTokens() != null) {
                            ctx.setOutputTokens(conv.getOutputTokens());
                        }
                        if (conv.getCachedTokens() != null) {
                            ctx.setCachedTokens(conv.getCachedTokens());
                        }

                        // 恢复 权限模式（字符串化，透传库值不设硬编码兜底） 与 供应商信息
                        ctx.setPermissionMode(conv.getPermissionMode());

                        if (conv.getProviderGroup() != null) {
                            ctx.setProviderGroup(conv.getProviderGroup());
                        }
                        if (conv.getProviderModelName() != null) {
                            ctx.setProviderModelName(conv.getProviderModelName());
                        }

                        // 恢复循环轮次，供进程重启后继续执行时判断是否超过上限
                        if (conv.getLoopCount() != null && conv.getLoopCount() > 0) {
                            ctx.setLoopCount(conv.getLoopCount());
                        }
                        if (conv.getParentCid() != null) {
                            ctx.setParentCid(conv.getParentCid());
                        }

                        // 恢复 LoopRunning 运行状态
                        if (conv.getLoopRunning() != null) {
                            ctx.setLoopRunning(conv.getLoopRunning() == 1);
                        }

                        // 恢复工作区标识（宿主 ProjectService 等业务层解析语义，引擎不解释）
                        if (conv.getWorkspaceId() != null) {
                            ctx.setWorkspaceId(conv.getWorkspaceId());
                        }

                        log.info("成功从数据库初始化会话 {} 的窗口 Token 快照和权限与供应商: input={}, output={}, mode={}",
                                c, ctx.getInputTokens(), ctx.getOutputTokens(), ctx.getPermissionMode());
                    }
                }

                // 2. 回调宿主装载扩展点：装载技能、规则、Hook、动态工具源等伴生数据
                triggerContextInitializers(ctx);
            } catch (Exception e) {
                log.error("从数据库初始化会话或回调宿主装载扩展点失败: cid={}", c, e);
            }

            return ctx;
        });
    }

    /**
     * 触发全部宿主上下文装载扩展点（未注册任何 Initializer 时静默跳过）
     */
    private void triggerContextInitializers(AgentContext ctx) {
        if (contextInitializers.isEmpty() || contextInitializers.get().isEmpty()) {
            return;
        }
        for (AgentContextInitializer initializer : contextInitializers.get()) {
            try {
                initializer.onContextRestored(ctx);
            } catch (Exception e) {
                log.error("执行宿主上下文装载扩展点失败: initializer={}, cid={}",
                        initializer.getClass().getSimpleName(), ctx.getCid(), e);
            }
        }
    }
    public AgentContext getActiveContext(Long cid) {
        if (cid == null) {
            return null;
        }
        return contexts.get(cid);
    }

    /**
     * 触发宿主重装载会话专属资产。
     * <p>
     * 引擎不再内置资产装载逻辑，统一转发给宿主装载扩展点。
     * </p>
     */
    public void loadContextAssets(AgentContext context) {
        if (context == null) {
            return;
        }
        log.debug("会话 {} 触发宿主重装载专属资产", context.getCid());
        triggerContextInitializers(context);
    }

    /**
     * 用户发出强行中止信号，标记当前上下文已取消
     */
    public void cancelContext(Long cid) {
        AgentContext context = contexts.get(cid);
        if (context != null) {
            context.setCanceled(true);

            // 1. 强杀此会话的所有外部物理子进程、强退大模型 HTTP 连接（内含进程表与连接表的清理）
            cancelActiveSideEffects(context);

            // 2. 中断执行线程
            Thread activeThread = context.getActiveThread();
            if (activeThread != null && activeThread.isAlive()) {
                log.debug("向活跃执行线程 {} 发送中断信号以取消会话 {}", activeThread.getName(), cid);
                activeThread.interrupt();
            }
            log.debug("运行上下文已被用户标记取消: {}", cid);

            // 3. 级联递归取消名下的并发子代理会话的运行
            contexts.values().forEach(child -> {
                if (cid.equals(child.getParentCid())) {
                    log.debug("级联取消子会话: {}", child.getCid());
                    cancelContext(child.getCid());
                }
            });
        }
    }

    /**
     * 强制取消指定会话当前活跃的物理副作用（委托给 AgentContext 自行清理网络连接并级联伴生扩展）。
     * 可被「用户中断运行」与「删除会话级联清理」等多种链路复用。
     */
    public void cancelActiveSideEffects(AgentContext context) {
        if (context == null) {
            return;
        }
        log.debug("正在清理会话 {} 的活跃副作用", context.getCid());
        context.clear();
    }

    /**
     * 移除会话运行上下文（物理资源回收交由宿主装载扩展点或宿主服务自行处理）
     */
    public void removeContext(Long cid) {
        // 1. 强制清理本会话（以及下属子会话）的所有物理进程、Call 等副作用
        cancelContext(cid);

        // 2. 级联移除所有子会话上下文缓存
        contexts.values().forEach(child -> {
            if (cid.equals(child.getParentCid())) {
                contexts.remove(child.getCid());
                log.debug("移除了子会话运行上下文: {}", child.getCid());
            }
        });

        // 3. 移除本会话上下文
        contexts.remove(cid);
        log.debug("移除了会话运行上下文: {}", cid);
    }

    /**
     * 获取当前在存的所有活跃上下文集合
     */
    public Collection<AgentContext> getAllContexts() {
        return contexts.values();
    }

    /**
     * 级联物理删除指定会话及底层消息（广播 CONVERSATION_DELETE 事件，Direct 层落库删除两表，并在内存活跃时清理上下文与物理副作用）。
     */
    public void deleteConversation(Long cid) {
        log.info("物理删除会话: cid={}", cid);
        AgentContext context = getActiveContext(cid);
        if (context == null) {
            // 会话不在内存（典型：服务重启后删除历史会话）时，删除事件构造依赖 AgentContext 无法走通，
            // 直接走 Store 物理直删兜底，避免异常被吞后静默漏删（会话与消息残留、附件却被先行清理）
            log.info("会话不在内存中，直接经 Store 物理删除兜底: cid={}", cid);
            if (messageStore != null) {
                messageStore.deleteByQuery(MessageQuery.builder().cid(cid).build());
            }
            conversationStore.deleteById(cid);
            return;
        }
        try {
            eventDispatcher.dispatch(cid, AgentEventFactory.createConversationDelete(context, cid));
        } catch (Exception e) {
            log.warn("分发删除会话事件失败, cid={}", cid, e);
        }
        removeContext(cid);
    }

    /**
     * 创建并持久化新会话（广播 CONVERSATION_CREATE 事件，由 Direct 层落库回填自增 ID 并触发事件外推）
     */
    public Conversation createConversation(Conversation conversation) {
        if (conversation == null) {
            throw new IllegalArgumentException("新建会话实体不能为空");
        }
        if (conversation.getCreateTime() == null) {
            conversation.setCreateTime(LocalDateTime.now());
        }
        if (conversation.getUpdateTime() == null) {
            conversation.setUpdateTime(LocalDateTime.now());
        }
        log.info("引擎创建新会话: title={}, parentCid={}", conversation.getTitle(), conversation.getParentCid());

        // 分发 CONVERSATION_CREATE 事件（DirectListener 同步写盘回填自增 ID）
        eventDispatcher.dispatch(0L, AgentEventFactory.createConversationCreate(null, conversation));

        return conversation;
    }
}
