package com.stioc.cute.conversation.types;

import com.stioc.cute.engine.store.types.Conversation;

import lombok.Data;

/**
 * 客户端请求更新会话配置参数 DTO
 */
@Data
public class UpdateConfigDto {

    /**
     * 目标权限兜底模式级别名
     */
    private String permissionMode;
}
