package com.stioc.cute.platform.security.types;

import lombok.Data;

/**
 * 登录质询响应体
 * <p>登录质询接口下发给客户端的证明材料：一次性 nonce 与重算存储摘要所需的盐。</p>
 */
@Data
public class ChallengeDto {

    /**
     * 一次性质询值（64 位十六进制随机数，5 分钟内单次有效）
     */
    private String nonce;

    /**
     * 存储值的盐段（Base64）：客户端重算存储摘要 D 以进一步计算证明摘要所必需
     */
    private String salt;
}
