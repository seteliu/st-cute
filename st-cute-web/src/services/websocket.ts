export interface WebSocketEvent {
  eventId: string;
  cid: number | null;
  parentCid?: number | null;
  timestamp: number;
  type: string;
  payload: any;
}

export type EventCallback = (event: WebSocketEvent) => void;

class WebSocketService {
  private socket: WebSocket | null = null;
  private url: string = '';
  private cid: number | null = null;
  private callbacks: Map<string, Set<EventCallback>> = new Map();
  private reconnectAttempts: number = 0;
  private maxReconnectDelay: number = 30000; // 最大 30 秒重连延迟
  private pingIntervalId: any = null;
  private pongTimeoutId: any = null;
  private reconnectTimeoutId: any = null;
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
    this.startHeartbeat();
    this.triggerCallbacks('OPEN', {
      eventId: '',
      cid: this.cid,
      timestamp: Date.now(),
      type: 'OPEN',
      payload: {}
    });
  }

  private handleMessage(event: MessageEvent) {
    try {
      const data = JSON.parse(event.data) as WebSocketEvent;
      console.debug('[WS] 收到消息:', data);

      if (data.type === 'PONG') {
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
    this.triggerCallbacks('CLOSE', {
      eventId: '',
      cid: this.cid,
      timestamp: Date.now(),
      type: 'CLOSE',
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

      this.send('PING', {});
      
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

  public send(type: string, payload: any = {}, parentCid?: number | null, customCid?: number | null) {
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
      timestamp: Date.now(),
      type,
      payload
    };

    this.socket.send(JSON.stringify(event));
    console.debug('[WS] 发送消息:', event);
  }

  public on(type: string, callback: EventCallback) {
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
    this.cid = id;
    console.log(`[WS] 切换当前会话ID为: ${id}`);
    if (this.isConnected) {
      this.send('PING', {});
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
