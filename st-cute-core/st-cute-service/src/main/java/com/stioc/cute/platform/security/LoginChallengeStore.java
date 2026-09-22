package com.stioc.cute.platform.security;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 登录质询值存储（内存态，单次有效）
 * <p>
 * 质询-应答登录协议的服务端侧材料管理：质询接口签发随机一次性 nonce，
 * 登录接口消费 nonce。freshness 由服务端签发时间保证——nonce 自签发起
 * {@link #CHALLENGE_TTL} 内有效、仅可消费一次，超时或重用一律拒绝，
 * 从机制上杜绝登录证明材料的抓包重放（替代此前可被伪造的明文客户端时间戳请求头）。
 * </p>
 * <p>
 * 实现说明：内存态由 Guava {@link Cache} 承载，条目按写入后 {@link #CHALLENGE_TTL}
 * 自动过期，过期清理无需任何手工代码；{@code maximumSize} 兜底限制条目总量，
 * 防止高频刷取撑爆内存。消费采用 {@code asMap().remove()} 的原子移除语义保证单次有效。
 * </p>
 */
@Component
public class LoginChallengeStore {

    /**
     * 质询值有效时长：自服务端签发起算，超时未消费即自动作废
     */
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);

    /**
     * 在存质询值数量上限：Guava 缓存按 LRU 自动淘汰，防止高频刷取撑爆内存
     */
    private static final int MAX_CHALLENGES = 10000;

    /**
     * 质询值随机字节数（256 位安全强度）
     */
    private static final int NONCE_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /**
     * 在存质询值缓存：nonce → 签发时间戳（毫秒，服务器时钟），写入后自动过期
     */
    private final Cache<String, Long> challenges = CacheBuilder.newBuilder()
            .expireAfterWrite(CHALLENGE_TTL)
            .maximumSize(MAX_CHALLENGES)
            .build();

    /**
     * 签发一次性质询值
     *
     * @return 随机 nonce（64 位十六进制字符串）
     */
    public String issue() {
        byte[] nonceBytes = new byte[NONCE_BYTES];
        random.nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);
        challenges.put(nonce, System.currentTimeMillis());
        return nonce;
    }

    /**
     * 消费质询值（单次有效）：存在且未被移除方可通过，无论成败均立即移除，杜绝重用。
     * <p>超时过期的条目已由缓存自动移出，本次必然取不到值，与"未签发/已消费"同判为失败。</p>
     *
     * @param nonce 客户端提交的质询值
     * @return true 表示质询值真实存在且在有效期内
     */
    public boolean consume(String nonce) {
        if (!StringUtils.hasText(nonce)) {
            return false;
        }
        // 原子移除：并发重复提交时仅有一个请求能取到值，保证严格单次有效
        return challenges.asMap().remove(nonce) != null;
    }
}
