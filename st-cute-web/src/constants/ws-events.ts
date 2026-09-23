/**
 * WebSocket 物理传输协议帧类型常量与载荷契约
 *
 * 【对齐锚】后端契约出口（修改任一处必须同步，禁止单侧变更）：
 * - 定向事件：st-cute-core .../websocket/RuntimeEventListenerWebSocket.java -> mapToWsType()
 * - 广播事件：st-cute-core .../websocket/WebSocketBroadcast.java -> EventType 枚举
 * - 心跳协议：服务端 PONG 应答
 *
 * 前端禁止在 wsService.on() 中使用裸字符串，一律引用本常量。
 */

/** 定向推送事件（后端 mapToWsType 映射，携带 cid/parentCid 定向语义） */
export const WS_EVENTS = {
  /** 消息占位创建（payload: MessageVo） */
  MESSAGE_CREATED: 'S2C_MESSAGE_CREATED',
  /** 消息全量快照更新（payload: MessageVo，非空字段即库中真值） */
  MESSAGE_UPDATED: 'S2C_MESSAGE_UPDATED',
  /** 助手思考流增量（payload: StreamChunkPayload） */
  THINKING_STREAM: 'S2C_THINKING_STREAM',
  /** 助手正文流增量（payload: StreamChunkPayload） */
  CONTENT_STREAM: 'S2C_CONTENT_STREAM',
  /** 工具日志流增量（payload: StreamChunkPayload，id 为工具消息 ID） */
  TOOL_LOG_STREAM: 'S2C_TOOL_LOG_STREAM',
  /** 消息物理删除（payload: 被删除的消息 ID） */
  MESSAGE_DELETED: 'S2C_MESSAGE_DELETED',
  /** 会话创建（全局广播，payload: Conversation） */
  CONVERSATION_CREATED: 'S2C_CONVERSATION_CREATED',
  /** 会话更新（全局广播，payload: Conversation） */
  CONVERSATION_UPDATED: 'S2C_CONVERSATION_UPDATED',
  /** 会话物理删除（全局广播，payload: 被删除的 cid 数值） */
  CONVERSATION_DELETED: 'S2C_CONVERSATION_DELETED',
  /** 项目创建（全局广播，payload: Project） */
  PROJECT_CREATED: 'S2C_PROJECT_CREATED',
  /** 项目删除（全局广播，payload: 被删除的项目 ID 数值） */
  PROJECT_DELETED: 'S2C_PROJECT_DELETED',
  /** 系统配置更新（全局广播，payload: 变更字段的部分对象） */
  CONFIG_UPDATED: 'S2C_CONFIG_UPDATED',
  /** 供应商配置更新（全局广播，payload: Provider[] 或 null） */
  PROVIDERS_UPDATED: 'S2C_PROVIDERS_UPDATED',
  /** MCP 状态更新（全局广播，payload: McpStatusVo[]） */
  MCP_UPDATED: 'S2C_MCP_UPDATED'
  // S2C_HOOK_EVENT 无契约：后端 HookService.sendHookEventWs 仅记日志、不推送 WS 帧。
  // 若后端未来恢复推送，在此登记常量并补监听即可
} as const

/** WS 事件名联合类型（wsService.on() 的 type 参数收敛） */
export type WsEventName = (typeof WS_EVENTS)[keyof typeof WS_EVENTS]

/** 前端本地衍生类型（连接生命周期，非后端协议帧） */
export const LOCAL_EVENTS = {
  /** 物理连接建立成功（前端本地触发） */
  OPEN: 'OPEN',
  /** 物理连接断开（前端本地触发） */
  CLOSE: 'CLOSE'
} as const

/** 服务端心跳应答帧类型（onmessage 中直接消费，不经事件分发） */
export const PONG_TYPE = 'PONG'

/** 发送帧类型：会话绑定注册（心跳） */
export const PING_TYPE = 'PING'
