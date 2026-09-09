package com.stioc.cute.engine.llm;

import com.stioc.cute.engine.llm.types.CuteAttachment;
import com.stioc.cute.engine.loop.core.AgentContext;

import java.util.List;

/**
 * 引擎附件内容加载供血接口（宿主实现）。
 * <p>
 * 窗口重建时消息附件的原始数据解析、物理解码（文本提取、图片衍生等）与历史占位符渲染属宿主文件域能力，
 * 引擎通过本接口完成附件装载，不感知宿主文件系统与解码实现。
 * </p>
 */
public interface AttachmentContentLoader {

    /**
     * 加载并解码附件物理数据为大模型附件列表。
     * 宿主负责解析 rawAttachments（如 JSON 字符串、路径等），并转化为统一的 CuteAttachment 列表。
     *
     * @param rawAttachments 原始附件元数据（如存盘的 JSON 字符串）
     * @param context        当前智能体上下文（宿主可据此解析工作区基准目录等）
     * @param allowImage     是否允许产出图片类附件（通常仅多模态模型开启）
     * @return 附件列表（解码异常或文件不存在时宿主返回占位提示或非空错误列表）
     */
    List<CuteAttachment> loadAttachments(String rawAttachments, AgentContext context, boolean allowImage);

    /**
     * 为历史消息中的附件构建轻量 Markdown 占位符文本。
     *
     * @param rawAttachments 原始附件元数据（如存盘的 JSON 字符串）
     * @return 渲染好的占位符文本，无附件时返回 null
     */
    String buildAttachmentPlaceholder(String rawAttachments);
}
