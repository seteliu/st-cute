package com.stioc.cute.message.types;

import com.stioc.cute.engine.llm.types.CuteToolCall;
import com.stioc.cute.engine.tool.ToolCallCodec;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 统一会话历史消息视图信息传输对象 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
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
    private LocalDateTime createTime;

    /**
     * 消息更新时间
     */
    private LocalDateTime updateTime;

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
     * FOLDED 专属属性：折叠区间最小消息 ID（闭区间）
     */
    private Long foldedMinId;

    /**
     * FOLDED 专属属性：折叠区间最大消息 ID（闭区间）
     */
    private Long foldedMaxId;

    /**
     * FOLDED 专属属性：折叠的助手消息（含 BRANCH/COMPRESSED 角色折算）数量
     */
    private Integer assistantCount;

    /**
     * FOLDED 专属属性：折叠的工具消息数量
     */
    private Integer toolCount;

    /**
     * FOLDED 专属属性：折叠明细中的失败/异常结构化列表 [{kind: "tool"|"assistant", toolName: "工具名"}]。
     * 非空即代表折叠内容存在异常，前端据此渲染警告图标；由后端折叠时扫描生成，避免前端拼接文案。
     */
    private List<ErrorDetail> errorDetails;

    /**
     * FOLDED 折叠明细中的异常结构化条目
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorDetail {

        /**
         * 异常来源类型：tool=工具执行失败；assistant=助手消息失败
         */
        private String kind;

        /**
         * 工具名（仅 kind=tool 时有值，前端映射 i18n 展示）
         */
        private String toolName;
    }

    /**
     * 统一的格式化映射方法 (系统内唯一出口)。
     * 将消息实体映射为消息传输对象（原 includeRawContent 大日志回带参数随
     * before_compact_content 机制废弃删除，映射逻辑收归本单参方法）
     */
    public static MessageVo fromEntity(Message entity) {
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
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setAttachments(entity.getAttachments());

        vo.setParentMessageId(entity.getParentMessageId());

        // 仅在 TOOL 类型的消息中解析并展平工具属性
        if (MessageRole.TOOL == entity.getRole() && StringUtils.hasText(entity.getToolCalls())) {
            CuteToolCall call = ToolCallCodec.parseSingle(entity.getToolCalls());
            vo.setToolId(StringUtils.hasText(call.getId()) ? call.getId() : null);
            vo.setToolName(StringUtils.hasText(call.getName()) ? call.getName() : null);
            vo.setToolArguments(StringUtils.hasText(call.getArguments()) ? call.getArguments() : null);
        }

        return vo;
    }
}
