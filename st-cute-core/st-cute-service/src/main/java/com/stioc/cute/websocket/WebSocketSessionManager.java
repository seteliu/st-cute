package com.stioc.cute.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * 管理当前 WebSocket 对话及智能体生命周期上下文中的会话状态映射
 * <p>
 * 两级结构：
 * <ul>
 *   <li><b>全量连接集 {@code allSessions}</b>：连接级跟踪，覆盖自建立起的全部活跃物理连接
 *       （含未绑定任何 cid 的连接）。全局广播消息统一直接面向它投递——未绑定 cid 的连接
 *       也能天然接收广播（会话列表增删、项目/配置变更等）；且一个物理连接只收一份，
 *       消除旧实现遍历 cid 桶导致多绑定连接重复收广播的问题。</li>
 *   <li><b>业务映射 {@code activeSessions}</b>：cid → 连接集合，用于会话内流式帧的定向推送。
 *       一个连接可同时累积绑定多个 cid（天然支持多会话并行订阅）；切换会话时是否解绑旧 cid
 *       由前端显式决策（PING 帧携带 unbindCid），后端不代为假设"一连接一 cid"。</li>
 * </ul>
 * 历史缺陷警示：旧实现只增不删——切走运行中会话后其高频流式帧继续全量推给本连接，
 * 洪峰触发慢连接清理与心跳判死导致断连重连。故单会话视图切换时须解绑旧 cid。
 * </p>
 * <p>
 * 所有物理连接在注册时统一经 {@link ConcurrentWebSocketSessionDecorator} 包装：
 * 内部提供每连接发送缓冲队列与发送超时上限，使并发调用线程（通知车道线程、
 * 循环线程等）不至于因慢客户端 TCP 缓冲区满而被长时间阻塞；超限的慢连接会被装饰器
 * 自动关闭，配合本类的失败主动移除策略完成死连接清理。
 * </p>
 * <p>
 * 需注意：装饰器仅在「其他线程已持有 flush 权」时把发送动作转为入队；若调用线程自己
 * 抢到 flush 权，它仍会同步写 TCP。故发送方是否会被对端网速阻塞，取决于事件分发模式：
 * 引擎默认的「网络通知事件极速模式」下，思考流/正文流经通知车道异步消化，发送线程不会
 * 被对端阻塞；关闭该模式时二者改由循环线程同步直调，此时循环线程会一直写到对端可接收为止，
 * 形成端到端背压（慢客户端让产出一并变慢，服务端不堆积）。装饰器的超时与缓冲上限
 * 是这两种情形下同步写的兜底保护。
 * </p>
 */
@Slf4j
public class WebSocketSessionManager {

    /**
     * 单条消息发送超时上限（毫秒）：超时即判定为慢连接，装饰器自动关闭该会话
     */
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    /**
     * 每连接发送缓冲区大小上限（字节）：积压超限判定为慢连接，装饰器自动关闭该会话
     */
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;

    /**
     * 装饰器实例在 session attributes 中的存储键：
     * 保证同一物理连接全局唯一装饰器实例（发送缓冲不分裂），心跳 PONG 等直发场景复用同一装饰器
     */
    private static final String DECORATOR_ATTRIBUTE_KEY = "WS_SESSION_DECORATOR";

    /**
     * 全量活跃物理连接集合（集合内为装饰器实例）。
     * <p>连接建立即入集，关闭即出集；全局广播统一面向它投递，未绑定 cid 的连接天然可收广播。</p>
     */
    private static final Set<WebSocketSession> allSessions = new CopyOnWriteArraySet<>();

    /**
     * 业务 cid 对应的长连活跃物理 WebSocket 会话连接集合 Map 映射（集合内为装饰器实例）
     */
    private static final Map<Long, Set<WebSocketSession>> activeSessions = new ConcurrentHashMap<>();

    /**
     * 连接级跟踪：将新建立的物理连接登记进全量连接集。
     * <p>登记即包装发送装饰器（幂等复用）。未绑定任何 cid 的连接也在此集合中，
     * 故能天然接收全局广播。</p>
     */
    public static void trackConnection(WebSocketSession session) {
        if (session != null) {
            allSessions.add(wrapSession(session));
            log.debug("登记 WebSocket 物理连接进全量集合: wsSessionId -> {}, 当前全量连接数: {}",
                    session.getId(), allSessions.size());
        }
    }

    /**
     * 注册/关联业务会话与物理 WebSocketSession（累积绑定）
     * <p>注册时对物理连接做发送装饰器包装（幂等：同一连接重复注册复用既有装饰器）。
     * 一个连接可同时出现在多个 cid 的集合中；解绑由 {@link #unbindSession} 显式执行。</p>
     * <p>取桶与 add 融合在 compute 原子块内，防止与并发清理的「清空桶即移除桶」竞态：
     * 若先取桶再 add，清理线程可能在两步之间把仅含本连接的桶清空移除，add 落在
     * 已脱离 Map 的孤儿桶上，绑定静默丢失。</p>
     */
    public static void registerSession(Long cid, WebSocketSession session) {
        if (cid != null && session != null) {
            WebSocketSession decorated = wrapSession(session);
            activeSessions.compute(cid, (k, sessionSet) -> {
                Set<WebSocketSession> set = sessionSet != null ? sessionSet : new CopyOnWriteArraySet<>();
                set.add(decorated);
                return set;
            });
            log.debug("注册 WebSocket 业务对话会话映射: cid -> {}, wsSessionId -> {}, 当前连接数: {}",
                    cid, session.getId(), activeSessions.get(cid).size());
        }
    }

    /**
     * 解绑物理连接对指定业务会话的关联（不影响该连接的其他 cid 绑定与其在全量连接集的跟踪）。
     * <p>
     * 由前端 PING 帧携带 unbindCid 显式触发：单会话视图切换会话时传旧 cid，
     * 防止切走会话的流式帧继续推给本连接形成洪峰；多会话并行订阅场景不传即可保留多绑定。
     * 解绑后切走会话的新消息不再实时送达本连接，用户切回时前端全量拉取消息列表自愈。
     * </p>
     */
    public static void unbindSession(Long cid, WebSocketSession session) {
        if (cid == null || session == null) {
            return;
        }
        String wsSessionId = session.getId();
        // 移除与空桶回收收敛到 compute 原子块内，与 registerSession 的建桶互斥，
        // 防止并发场景下新建桶被误回收导致绑定丢失
        activeSessions.computeIfPresent(cid, (k, sessions) -> {
            sessions.removeIf(s -> wsSessionId.equals(s.getId()));
            return sessions.isEmpty() ? null : sessions;
        });
        log.debug("解绑 WebSocket 业务对话会话映射: cid -> {}, wsSessionId -> {}", cid, wsSessionId);
    }

    /**
     * 根据物理 WebSocketSession 注销已建立的映射（连接关闭时调用，全量清除该连接的一切登记）
     */
    public static void unregisterSession(WebSocketSession session) {
        if (session != null) {
            purgeSessionEverywhere(session);
            log.info("WebSocket 物理连接关闭，已清除其全部登记: wsSessionId -> {}", session.getId());
        }
    }

    /**
     * 获取业务 cid 对应的所有活跃长连 WebSocketSession 集合
     */
    public static Set<WebSocketSession> getSessions(Long cid) {
        if (cid == null) {
            return Collections.emptySet();
        }
        Set<WebSocketSession> sessions = activeSessions.get(cid);
        return sessions != null ? sessions : Collections.emptySet();
    }

    /**
     * 获取全量连接集中的活跃物理连接数（仅用于日志观测）
     */
    public static int allSessionsSize() {
        return allSessions.size();
    }

    /**
     * 定向发送原始 JSON 消息至对应 cid 的所有活跃物理 WebSocketSession 连接。
     * <p>发送经装饰器缓冲队列异步化：发送失败或慢连接超限被关闭的会话，立即全量移除，
     * 防止死连接残留累积。</p>
     */
    public static void sendEvent(Long cid, String textJson) {
        if (cid == null || cid == 0L || textJson == null) {
            return;
        }
        Set<WebSocketSession> sessions = getSessions(cid);
        if (sessions.isEmpty()) {
            return;
        }
        for (WebSocketSession s : sessions) {
            sendAndPruneOnFailure(s, textJson, "定向推送 cid=" + cid);
        }
    }

    /**
     * 广播发送原始 JSON 消息至全量集合中的所有活跃物理 WebSocketSession 连接
     * <p>直接面向全量连接集（含未绑定 cid 的连接），一个连接只收一份。</p>
     */
    public static void broadcast(String textJson) {
        if (textJson == null) {
            return;
        }
        for (WebSocketSession s : allSessions) {
            sendAndPruneOnFailure(s, textJson, "全局广播");
        }
    }

    /**
     * 经装饰器发送单条消息；发送失败（连接异常/发送超时被关闭）意味着连接已死，
     * 全量移除该连接的一切登记（全量连接集 + 全部 cid 绑定集合）
     *
     * @param session      装饰器包装的会话实例
     * @param textJson     消息 JSON 文本
     * @param sceneDesc    日志场景描述（定向推送/全局广播）
     */
    private static void sendAndPruneOnFailure(WebSocketSession session, String textJson, String sceneDesc) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(textJson));
        } catch (Exception e) {
            log.warn("通过 WebSocket {}消息到物理会话失败，主动移除该连接: sessionId={}, 异常={}",
                    sceneDesc, session.getId(), e.getMessage());
            purgeSessionEverywhere(session);
        }
    }

    /**
     * 全量清除指定物理连接的一切登记：全量连接集 + 各 cid 绑定集合（按连接 ID 匹配，
     * 装饰器与原始连接的 getId() 一致；CopyOnWriteArraySet 支持并发安全删除）。
     * <p>桶移除与 add 一样收敛到 compute 原子块内：清完桶内连接后桶为空才移除，
     * 与并发 {@link #registerSession} 的 compute 互斥，根治「新建桶被并发清理误删」竞态。</p>
     */
    private static void purgeSessionEverywhere(WebSocketSession session) {
        String wsSessionId = session.getId();
        allSessions.removeIf(s -> wsSessionId.equals(s.getId()));
        activeSessions.forEach((cid, sessionSet) -> sessionSet.removeIf(s -> wsSessionId.equals(s.getId())));
        activeSessions.forEach((cid, sessionSet) -> {
            if (sessionSet.isEmpty()) {
                activeSessions.computeIfPresent(cid, (k, set) -> set.isEmpty() ? null : set);
            }
        });
    }

    /**
     * 获取（或惰性创建并缓存）物理连接对应的发送装饰器实例。
     * <p>装饰器缓存在 session attributes 中，保证同一物理连接全生命周期内复用同一实例，
     * 所有写入路径（事件推送、心跳 PONG）共享同一条发送缓冲队列，避免并发写连接。</p>
     * <p>用 synchronized 保护「检查-创建-写入」三步整体：并发首次调用若各自创建装饰器，
     * 两个装饰器各持独立发送队列，同一连接的帧会交错写入而破坏顺序（且后写入者覆盖缓存，
     * 先返回的调用方持有的是已失效实例）。attributes 本身虽为线程安全 Map，
     * 但复合操作不具原子性，故在此显式加锁。</p>
     */
    public static WebSocketSession wrapSession(WebSocketSession session) {
        Object cached = session.getAttributes().get(DECORATOR_ATTRIBUTE_KEY);
        if (cached instanceof WebSocketSession decorator) {
            return decorator;
        }
        synchronized (session) {
            // 双重检查：可能在获取锁期间已由其他线程完成创建
            Object recheck = session.getAttributes().get(DECORATOR_ATTRIBUTE_KEY);
            if (recheck instanceof WebSocketSession decorator) {
                return decorator;
            }
            WebSocketSession created = new ConcurrentWebSocketSessionDecorator(
                    session, SEND_TIME_LIMIT_MS, SEND_BUFFER_SIZE_LIMIT);
            session.getAttributes().put(DECORATOR_ATTRIBUTE_KEY, created);
            return created;
        }
    }
}
