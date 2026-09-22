package com.stioc.cute.engine.testkit;

import com.stioc.cute.engine.assembly.EngineInfra;
import com.stioc.cute.engine.common.EngineExecutor;
import com.stioc.cute.engine.common.EngineLock;
import com.stioc.cute.engine.common.NotifyExecutor;
import com.stioc.cute.engine.event.AgentEventDispatcher;
import com.stioc.cute.engine.event.AgentEventListener;
import com.stioc.cute.engine.hook.AgentHookDispatcher;
import com.stioc.cute.engine.hook.HookListener;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.ConversationStore;
import com.stioc.cute.engine.store.MessageStore;
import com.stioc.cute.engine.tool.ToolGuard;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 引擎供血接口的测试替身工厂。
 * <p>
 * 引擎的全部供血均为纯接口，故一律用 JDK 动态代理生成替身，不引入 Mockito——
 * 保持引擎测试零字节码增强依赖，与「engine 零 Spring 依赖」的架构红线一致。
 * </p>
 * <p>
 * 默认行为约定：布尔返 false、数值返 0、{@link Lock} 返真实可重入锁、其余返 null。
 * 需要定制返回值时用 {@link #stub(Class, Map)} 指定方法名到返回值的映射。
 * </p>
 */
public final class EngineStubs {

    private EngineStubs() {
        // 工具类禁止实例化
    }

    /**
     * 生成指定接口的默认替身（无定制返回值）
     */
    public static <T> T stub(Class<T> type) {
        return stub(type, Map.of());
    }

    /**
     * 生成指定接口的替身，并按键定制部分方法的返回值。
     *
     * @param type      供血接口类型
     * @param overrides 方法名 → 返回值（未命中的方法走默认行为）
     */
    @SuppressWarnings("unchecked")
    public static <T> T stub(Class<T> type, Map<String, Object> overrides) {
        InvocationHandler handler = (proxy, method, args) -> {
            // 默认方法直调（如 CuteTool.getAccessLevel() 的默认实现）
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            // 身份语义三件套不得走业务替身，否则同实例自等也会失败
            if ("equals".equals(method.getName()) && args != null && args.length == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(method.getName()) && args == null) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(method.getName()) && args == null) {
                return type.getSimpleName() + "Stub@" + Integer.toHexString(System.identityHashCode(proxy));
            }
            // 定制返回值优先
            if (overrides.containsKey(method.getName())) {
                return overrides.get(method.getName());
            }
            return defaultReturn(method.getReturnType());
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /**
     * 按返回类型给出安全默认值：布尔 false、数值 0、字符 '0'、锁为真实可重入锁，其余 null
     */
    private static Object defaultReturn(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return '0';
        }
        if (Lock.class.isAssignableFrom(returnType)) {
            return new ReentrantLock();
        }
        return null;
    }

    /**
     * 引擎锁替身：按 key 缓存锁实例，满足契约要求的「同 key 同锁 + 可重入」语义。
     * <p>
     * 若每次调用都返回新锁，引擎的临界区将完全失效，且同线程重入会直接死锁——
     * 这是引擎测试替身最容易踩的坑，故此处显式按 key 复用。
     * </p>
     */
    public static EngineLock engineLock() {
        Map<String, Lock> dataLocks = new ConcurrentHashMap<>();
        Map<String, Lock> loopLocks = new ConcurrentHashMap<>();
        Map<String, Lock> namingLocks = new ConcurrentHashMap<>();
        Map<String, Lock> writeLocks = new ConcurrentHashMap<>();

        return new EngineLock() {
            @Override
            public Lock getConversationDataLock(long cid) {
                return dataLocks.computeIfAbsent("data:" + cid, k -> new ReentrantLock());
            }

            @Override
            public Lock getConversationLoopLock(long cid) {
                return loopLocks.computeIfAbsent("loop:" + cid, k -> new ReentrantLock());
            }

            @Override
            public Lock getConversationNamingLock(long cid) {
                return namingLocks.computeIfAbsent("naming:" + cid, k -> new ReentrantLock());
            }

            @Override
            public Lock getWriteToolLock(String lockKey) {
                return writeLocks.computeIfAbsent("write:" + lockKey, k -> new ReentrantLock());
            }
        };
    }

    /**
     * 引擎执行器替身：返回真实可用的执行器，供闭环测试真实调度任务
     */
    public static EngineExecutor engineExecutor() {
        ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "engine-test-executor");
            t.setDaemon(true);
            return t;
        });
        return () -> executor;
    }

    /**
     * 会话存储替身（默认全空实现，供仅校验装配完整性的用例使用）
     */
    public static ConversationStore conversationStore() {
        return stub(ConversationStore.class);
    }

    /**
     * 消息存储替身（默认全空实现，供仅校验装配完整性的用例使用）
     */
    public static MessageStore messageStore() {
        return stub(MessageStore.class);
    }

    /**
     * 工具安全守卫替身（默认放行）
     */
    public static ToolGuard toolGuard() {
        return stub(ToolGuard.class);
    }

    /**
     * 构造真实的会话上下文实例。
     * <p>
     * {@link AgentContext} 是具体类而非接口，动态代理不适用于它，
     * 故此处直接装配真实的上下文——其依赖的两个分发器均为可空清单 + 引擎锁，
     * 真实构造的开销可忽略，且比任何替身都更贴近生产装配形态。
     * </p>
     *
     * @param cid 会话 ID
     */
    public static AgentContext agentContext(Long cid) {
        EngineLock lock = engineLock();
        AgentEventDispatcher eventDispatcher = new AgentEventDispatcher(
                List.of(), new EngineInfra(lock, engineExecutor()), new NotifyExecutor(), true);
        AgentHookDispatcher hookDispatcher = new AgentHookDispatcher(Optional.of(List.of()), lock);
        return new AgentContext(cid, eventDispatcher, hookDispatcher);
    }

    /**
     * 构造上下文，并指定事件监听器与 Hook 监听器（供事件/Hook 相关用例断言真实分发）
     * <p>极速模式取引擎默认值（开启）。</p>
     */
    public static AgentContext agentContext(Long cid,
                                            List<AgentEventListener> eventListeners,
                                            List<HookListener> hookListeners,
                                            EngineLock lock) {
        return agentContext(cid, eventListeners, hookListeners, lock, true);
    }

    /**
     * 构造上下文，显式指定通知层极速模式（供断言两种分发模式的用例使用）
     */
    public static AgentContext agentContext(Long cid,
                                            List<AgentEventListener> eventListeners,
                                            List<HookListener> hookListeners,
                                            EngineLock lock,
                                            boolean notifyFastMode) {
        AgentEventDispatcher eventDispatcher = new AgentEventDispatcher(
                eventListeners, new EngineInfra(lock, engineExecutor()), new NotifyExecutor(), notifyFastMode);
        AgentHookDispatcher hookDispatcher = new AgentHookDispatcher(Optional.of(hookListeners), lock);
        return new AgentContext(cid, eventDispatcher, hookDispatcher);
    }
}
