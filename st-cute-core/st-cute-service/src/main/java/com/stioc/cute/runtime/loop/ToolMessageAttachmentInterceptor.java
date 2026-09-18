package com.stioc.cute.runtime.loop;

import com.stioc.cute.engine.loop.message.MessageInterceptor;
import com.stioc.cute.engine.loop.types.MessagePayload;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 工具消息附件拦截器：
 * 工具执行落库的附件元数据（如 load_attachment 工具）在历史重建时重新物理解码，
 * 挂载至 TOOL 消息的 attachments 结构化字段（多模态图片与完整文本，突破 content 内 preview 的截断限制）。
 */
@Slf4j
@Component
public class ToolMessageAttachmentInterceptor implements MessageInterceptor {

    @Resource
    private AttachmentContentLoader attachmentContentLoader;

    @Override
    public int order() {
        return 200;
    }

    @Override
    public void intercept(MessagePayload payload) {
        Message thisMsg = payload.getThisMsg();
        if (thisMsg == null || MessageRole.TOOL != thisMsg.getRole()) {
            return;
        }
        if (StringUtils.isBlank(thisMsg.getAttachments())) {
            return;
        }
        payload.setAttachments(attachmentContentLoader.loadAttachments(
                thisMsg.getAttachments(), payload.getContext(), payload.isMultimodal()));
    }
}
