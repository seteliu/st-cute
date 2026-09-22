package com.stioc.cute.platform.security.types;

import lombok.Data;

/**
 * 用户登录请求体
 * <p>承载登录校验所需的全部客户端提交材料：原文密码的传输摘要与质询-应答协议材料。</p>
 */
@Data
public class LoginRequest {

    /**
     * 原文密码的 SHA-256 十六进制传输摘要（历史明文存储值的兼容校验口径）
     */
    private String password;

    /**
     * 质询接口取得的一次性质询值（摘要形态存储值的登录必传）
     */
    private String nonce;

    /**
     * 证明摘要：SHA-256hex(存储摘要段 Base64 + ":" + nonce)，由前端以本地存储摘要计算
     */
    private String proof;
}
