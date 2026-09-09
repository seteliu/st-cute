package com.stioc.cute.message.types;

import com.stioc.cute.engine.store.types.Message;
import com.stioc.cute.engine.store.types.MessageRole;
import com.stioc.cute.engine.store.types.MessageStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 带有长度截断标识的消息列表封装传输对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LimitMessageDto {

    /**
     * 消息列表
     */
    private List<MessageVo> messages;

    /**
     * 是否被截断/隐藏历史
     */
    private boolean truncated;

}
