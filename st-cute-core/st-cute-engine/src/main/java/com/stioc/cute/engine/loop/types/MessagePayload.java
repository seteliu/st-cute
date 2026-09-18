package com.stioc.cute.engine.loop.types;

import com.stioc.cute.engine.llm.types.CuteAttachment;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.store.types.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 消息拦截器加工载体：承载单条即将发往大模型的消息正文与结构化附件，
 * 由引擎在历史重建各角色分支构建后，依次交由 {@link com.stioc.cute.engine.loop.message.MessageInterceptor} 链加工。
 * <p>
 * 正文与附件为可变字段（拦截器直接改写）；其余为只读字段（final）。
 * 注意：attachments 仅在 USER/TOOL 分支实际生效（协议上仅这两类消息可携带多模态附件）；
 * 消息序位（首/末条等）经 {@link MessageIndexInfo} 按角色查询获得，不再单独冗余字段。
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
public class MessagePayload {

    /**
     * 消息正文（可被拦截器改写）
     */
    private String content;

    /**
     * 结构化多模态附件集合（可被拦截器改写；null 表示不携带附件）
     */
    private List<CuteAttachment> attachments;

    /**
     * 当前消息对应的数据库原始记录（含 createTime、attachments 原始 JSON 等元数据，只读）；
     * 引擎合成的消息（如 SYSTEM 首条系统提示词）为 null
     */
    private final Message thisMsg;

    /**
     * 本次历史重建的完整数据库消息集合（COMPRESSED 前置排序后，含未参与渲染的消息），作为扩展信息只读传入
     */
    private final List<Message> allMsgs;

    /**
     * 本次历史重建的角色索引信息（各角色首/末条消息 ID），供拦截器感知消息序位
     */
    private final MessageIndexInfo indexInfo;

    /**
     * 当前 Provider 是否支持多模态（附件图片装载开关，引擎算好传入）
     */
    private final boolean multimodal;

    /**
     * 当前智能体上下文
     */
    private final AgentContext context;
}
