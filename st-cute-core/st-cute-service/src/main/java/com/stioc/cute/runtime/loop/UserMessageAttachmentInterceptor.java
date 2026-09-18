package com.stioc.cute.runtime.loop;

import com.stioc.cute.engine.loop.message.MessageInterceptor;
import com.stioc.cute.engine.loop.types.MessagePayload;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.platform.contract.ContractProperty;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 用户消息附件拦截器：
 * <ul>
 *   <li>开启「加载全部用户消息附件」配置：所有用户消息附件均完整装载物理数据（图片/文本）挂载至 attachments；</li>
 *   <li>关闭该配置：仅最后一条有效用户消息完整装载，历史用户消息以轻量 Markdown 占位符追加在正文末尾（节省上下文）；</li>
 *   <li>无附件：直接透传。</li>
 * </ul>
 */
@Slf4j
@Component
public class UserMessageAttachmentInterceptor implements MessageInterceptor {

    @Resource
    private AttachmentContentLoader attachmentContentLoader;
    @Resource
    private ContractProperty contractProperty;

    @Override
    public int order() {
        return 200;
    }

    @Override
    public void intercept(MessagePayload payload) {
        Message thisMsg = payload.getThisMsg();
        if (thisMsg == null || MessageRole.USER != thisMsg.getRole()) {
            return;
        }
        if (StringUtils.isBlank(thisMsg.getAttachments())) {
            return;
        }
        // 经角色索引判定是否为最后一条有效用户消息（lastIndexMap 以消息 ID 标识末条）
        boolean isLastUserMessage = payload.getIndexInfo() != null
                && thisMsg.getId().equals(payload.getIndexInfo().getLastIndexMap().get(MessageRole.USER));
        // 配置开启时全部完整装载，关闭时仅末条完整装载
        if (contractProperty.isLoadAllUserAttachments() || isLastUserMessage) {
            // 完整加载附件 Payload
            payload.setAttachments(attachmentContentLoader.loadAttachments(
                    thisMsg.getAttachments(), payload.getContext(), payload.isMultimodal()));
        } else {
            // 历史用户消息：生成轻量 Markdown 占位符追加在文本末尾
            String placeholder = attachmentContentLoader.buildAttachmentPlaceholder(thisMsg.getAttachments());
            if (StringUtils.isNotBlank(placeholder)) {
                String content = payload.getContent() != null ? payload.getContent() : "";
                payload.setContent(content + "\n\n" + placeholder);
            }
        }
    }
}
