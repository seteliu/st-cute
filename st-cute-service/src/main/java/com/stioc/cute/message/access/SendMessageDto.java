package com.stioc.cute.message.access;

import lombok.Data;

/**
 * 客户端提问发送消息请求 DTO
 */
@Data
public class SendMessageDto {

    /**
     * 发送的文本消息内容
     */
    private String text;

    /**
     * 关联的附件列表 JSON 字符串
     */
    private String attachments;
}
