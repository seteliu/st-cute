import { WS_EVENTS, LOCAL_EVENTS, PONG_TYPE, PING_TYPE } from '@/constants/ws-events'

export interface WebSocketEvent<T = any> {
  eventId: string;
  cid: number | null;
  parentCid?: number | null;
  /**
   * 本次绑定时请求后端顺带解绑的旧会话 ID（可选，前端决策）。
   * 后端天然支持一个连接同时绑定多个 cid（多会话并行订阅）；
   * 单会话视图切换会话时默认传旧 cid 解绑，防止切走会话的流式帧继续推给本连接。
   */
  unbindCid?: number | null;
  timestamp: number;
  type: string;
  payload: T;
}

export type EventCallback<T = any> = (event: WebSocketEvent<T>) => void;

/** 前端可监听的全部事件名（后端协议帧 + 本地连接生命周期），供 on() 收敛类型并获得补全 */
export type ListenableEventName = (typeof WS_EVENTS)[keyof typeof WS_EVENTS] | (typeof LOCAL_EVENTS)[keyof typeof LOCAL_EVENTS]

class WebSocketService {
  private socket: WebSocket | null = null;
  private url: string = '';
  private cid: number | null = null;
  private callbacks: Map<string, Set<EventCallback>> = new Map();
  private reconnectAttempts: number = 0;
  private maxReconnectDelay: number = 30000; // 最大 30 秒重连延迟
  private pingIntervalId: ReturnType<typeof setInterval> | null = null;
  private pongTimeoutId: ReturnType<typeof setTimeout> | null = null;
  private reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;
  private isConnected: boolean = false;

  public connect(url?: string) {
    this.stopReconnect();
    if (this.socket) {
      this.close();
    }
    const defaultProto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const defaultUrl = `${defaultProto}//${window.location.host}/ws`;
    const targetUrl = url || defaultUrl;
    this.url = targetUrl;
    console.log(`[WS] 正在连接: ${targetUrl}, 当前会话ID: ${this.cid ?? '未绑定'}`);

    try {
      this.socket = new WebSocket(targetUrl);
      this.socket.onopen = () => this.handleOpen();
      this.socket.onmessage = (event) => this.handleMessage(event);
      this.socket.onerror = (error) => this.handleError(error);
      this.socket.onclose = (event) => this.handleClose(event);
    } catch (e) {
      console.error('[WS] 实例化失败', e);
      this.triggerReconnect();
    }
  }

  private handleOpen() {
    console.log('[WS] 连接成功建立');
    this.isConnected = true;
    this.reconnectAttempts = 0;
    this.stopReconnect();

    // 物理连接建立瞬间，若已持有会话 ID，立即向后端发送 PING 握手包完成 0 延迟会话绑定注册
    if (this.cid !== null) {
      console.log(`[WS] 物理连接建立成功，立即向后端注册当前会话 ID: ${this.cid}`);
      this.send(PING_TYPE, {});
    }

    this.startHeartbeat();
    this.triggerCallbacks(LOCAL_EVENTS.OPEN, {
      eventId: '',
      cid: this.cid,
      timestamp: Date.now(),
      type: LOCAL_EVENTS.OPEN,
      payload: {}
    });
  }

  private handleMessage(event: MessageEvent) {
    try {
      const data = JSON.parse(event.data) as WebSocketEvent;
      console.debug('[WS] 收到消息:', data);

      if (data.type === PONG_TYPE) {
        console.debug('[WS] 收到心跳 PONG');
        this.resetPongTimeout();
        return;
      }

      this.triggerCallbacks(data.type, data);
      this.triggerCallbacks('*', data); // 通配符监听
    } catch (e) {
      console.warn('[WS] 解析消息 JSON 失败:', event.data, e);
    }
  }

  private handleError(error: Event) {
    console.error('[WS] 连接异常:', error);
  }

  private handleClose(event: CloseEvent) {
    console.warn(`[WS] 连接断开, 代码: ${event.code}, 原因: ${event.reason}`);
    this.isConnected = false;
    this.stopHeartbeat();
    this.triggerCallbacks(LOCAL_EVENTS.CLOSE, {
      eventId: '',
      cid: this.cid,
      timestamp: Date.now(),
      type: LOCAL_EVENTS.CLOSE,
      payload: {}
    });
    this.triggerReconnect();
  }

  private triggerReconnect() {
    if (this.isConnected) return;
    this.stopReconnect();

    this.reconnectAttempts++;
    // 指数退避算法计算下一次重连的延时时间
    const delay = Math.min(
      Math.pow(2, this.reconnectAttempts) * 1000 + Math.random() * 1000,
      this.maxReconnectDelay
    );

    console.log(`[WS] 将在 ${(delay / 1000).toFixed(1)} 秒后尝试第 ${this.reconnectAttempts} 次重连...`);
    this.reconnectTimeoutId = setTimeout(() => {
      this.connect(this.url);
    }, delay);
  }

  private stopReconnect() {
    if (this.reconnectTimeoutId) {
      clearTimeout(this.reconnectTimeoutId);
      this.reconnectTimeoutId = null;
    }
  }

  private startHeartbeat() {
    this.stopHeartbeat();

    // 30秒发一次 PING
    this.pingIntervalId = setInterval(() => {
      if (this.cid === null) {
        console.debug('[WS] 心跳跳过: 当前未绑定后端会话ID');
        return;
      }

      this.send(PING_TYPE, {});

      // 启动 30秒 PONG 响应超时检测（若 30秒内未回 PONG，判定心跳丢失）
      this.pongTimeoutId = setTimeout(() => {
        console.error('[WS] 心跳检测超时, 准备断开重连');
        this.close();
      }, 30000);
    }, 30000);
  }

  private stopHeartbeat() {
    if (this.pingIntervalId) {
      clearInterval(this.pingIntervalId);
      this.pingIntervalId = null;
    }
    if (this.pongTimeoutId) {
      clearTimeout(this.pongTimeoutId);
      this.pongTimeoutId = null;
    }
  }

  private resetPongTimeout() {
    if (this.pongTimeoutId) {
      clearTimeout(this.pongTimeoutId);
      this.pongTimeoutId = null;
    }
  }

  public send(type: string, payload: any = {}, parentCid?: number | null, customCid?: number | null, unbindCid?: number | null) {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      console.warn('[WS] 连接未开启, 放弃发送消息:', type);
      return;
    }

    const targetCid = customCid ?? this.cid;
    if (targetCid === null) {
      console.warn('[WS] 当前未绑定后端会话ID, 放弃发送消息:', type);
      return;
    }

    const event: WebSocketEvent = {
      eventId: this.generateUUID(),
      cid: targetCid,
      parentCid,
      unbindCid: unbindCid ?? null,
      timestamp: Date.now(),
      type,
      payload
    };

    this.socket.send(JSON.stringify(event));
    console.debug('[WS] 发送消息:', event);
  }

  /**
   * 注册事件监听。type 优先使用 ws-events 常量（可获得字面量联合类型约束），
   * 保留 string 宽容度以兼容通配符 '*' 与未来扩展
   */
  public on(type: ListenableEventName | (string & {}), callback: EventCallback) {
    if (!this.callbacks.has(type)) {
      this.callbacks.set(type, new Set());
    }
    this.callbacks.get(type)!.add(callback);
  }

  public off(type: string, callback: EventCallback) {
    if (this.callbacks.has(type)) {
      this.callbacks.get(type)!.delete(callback);
    }
  }

  private triggerCallbacks(type: string, event: WebSocketEvent) {
    const list = this.callbacks.get(type);
    if (list) {
      list.forEach(cb => {
        try {
          cb(event);
        } catch (e) {
          console.error(`[WS] 触发回调 [${type}] 时捕获异常:`, e);
        }
      });
    }
  }

  public close() {
    this.stopReconnect();
    this.reconnectAttempts = 0;
    if (this.socket) {
      this.socket.close();
      this.socket = null;
    }
    this.isConnected = false;
    this.stopHeartbeat();
  }

  public getCid(): number | null {
    return this.cid;
  }

  public setCid(id: number) {
    // 切换绑定：记住旧 cid，绑定新 cid 的 PING 默认携带 unbindCid 解绑旧会话
    // （后端天然支持多会话并行订阅；单会话视图切换时解绑旧 cid，防止切走会话的
    // 流式帧继续推给本连接形成洪峰。未来多会话并行场景显式传参解除即可保留绑定）
    const prevCid = this.cid;
    this.cid = id;
    console.log(`[WS] 切换当前会话ID为: ${id}${prevCid !== null && prevCid !== id ? ` (解绑旧会话: ${prevCid})` : ''}`);
    if (this.isConnected) {
      this.send(PING_TYPE, {}, undefined, undefined, prevCid !== null && prevCid !== id ? prevCid : undefined);
    }
  }

  public clearAllCallbacks() {
    this.callbacks.clear();
    console.log('[WS] 全局回调监听器已重置清空');
  }

  private generateUUID(): string {
    return 'uuid_' + Math.random().toString(36).substring(2, 15) + Math.random().toString(36).substring(2, 15);
  }
}

export const wsService = new WebSocketService();
