package com.stioc.cute.runtime.loop;

import com.stioc.cute.engine.loop.message.MessageInterceptor;
import com.stioc.cute.engine.loop.types.MessagePayload;
import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 用户消息时间戳拦截器：在真实用户消息（USER 角色）正文末尾追加消息创建时间，
 * 格式为换行 + 圆括号包裹的 yyyy-MM-dd HH:mm:ss（省 Token 写法），
 * 让大模型感知每条用户消息的真实时间，替代已移除的 get_time 工具。
 */
@Slf4j
@Component
public class UserMessageTimestampInterceptor implements MessageInterceptor {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public int order() {
        return 100;
    }

    @Override
    public void intercept(MessagePayload payload) {
        Message thisMsg = payload.getThisMsg();
        // 仅处理真实用户消息（thisMsg 为 null 的引擎合成消息与 BRANCH/COMPRESSED 等同以 USER 角色
        // 发送的非用户消息均跳过，避免时间戳拼到子代理汇报等场景）
        if (thisMsg == null || MessageRole.USER != thisMsg.getRole()) {
            return;
        }
        LocalDateTime createTime = thisMsg.getCreateTime();
        if (createTime == null) {
            return;
        }
        String content = payload.getContent() != null ? payload.getContent() : "";
        payload.setContent(content + "\n\n[消息时间 " + createTime.format(TIME_FORMATTER) + "]");
    }
}
