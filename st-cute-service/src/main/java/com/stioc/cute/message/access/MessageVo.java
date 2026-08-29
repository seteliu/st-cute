package com.stioc.cute.message.access;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;

/**
 * 统一会话历史消息视图信息传输对象 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class MessageVo {

    /**
     * 消息主键 ID
     */
    private Long id;

    /**
     * 消息角色
     */
    private MessageRole role;

    /**
     * 消息展示正文
     */
    private String content;

    /**
     * 大模型推理思考过程内容
     */
    private String thought;

    /**
     * 当前是否正在流式生成中
     */
    private boolean isStreaming;

    /**
     * 消息的当前业务状态
     */
    private MessageStatus status;

    /**
     * 父级助手消息 ID
     */
    private Long parentMessageId;

    /**
     * 消息创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 折叠/滑窗压缩前的原始消息备份内容
     */
    private String beforeCompactContent;

    /**
     * TOOL 专属属性：工具调用的唯一 ID
     */
    private String toolId;

    /**
     * TOOL 专属属性：工具的名称
     */
    private String toolName;

    /**
     * TOOL 专属属性：工具入参的 JSON 字符串
     */
    private String toolArguments;

    /**
     * 消息关联的附件列表 JSON 数组（相对路径、文件名、大小、MIME类型等）
     */
    private String attachments;

    /**
     * 统一的格式化映射方法 (系统内唯一出口)
     */
    public static MessageVo fromEntity(MessageEntity entity) {
        return fromEntity(entity, false);
    }

    /**
     * 将消息实体映射为消息传输对象，并控制是否包含折叠前的原始消息内容
     */
    public static MessageVo fromEntity(MessageEntity entity, boolean includeRawContent) {
        if (entity == null) {
            return null;
        }

        MessageVo vo = new MessageVo();
        vo.setId(entity.getId());
        vo.setRole(entity.getRole());

        MessageStatus mStatus = entity.getStatus() != null ? entity.getStatus() : MessageStatus.SUCCESS;
        vo.setStatus(mStatus);

        vo.setContent(entity.getContent());
        vo.setThought(entity.getReasoningContent());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setStreaming(false);
        vo.setAttachments(entity.getAttachments());

        if (includeRawContent) {
            vo.setBeforeCompactContent(entity.getBeforeCompactContent());
        }

        vo.setParentMessageId(entity.getParentMessageId());

        // 仅在 TOOL 类型的消息中解析并展平工具属性
        if (MessageRole.TOOL == entity.getRole() && StringUtils.hasText(entity.getToolCalls())) {
            try {
                JSONObject toolObj = JSON.parseObject(entity.getToolCalls().trim());
                vo.setToolId(toolObj.getString("id"));
                vo.setToolName(toolObj.getString("name"));
                vo.setToolArguments(toolObj.getString("arguments"));
            } catch (Exception e) {
                log.warn("反序列化 TOOL 消息属性失败: id={}", entity.getId(), e);
            }
        }

        return vo;
    }
}
