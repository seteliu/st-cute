package com.stioc.cute.platform.security;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;

/**
 * 登录接口防爆破守卫（内存态，按来源 IP 维度计数）
 * <p>
 * 滑动窗口策略：统计每个来源最近 {@link #FAIL_WINDOW} 窗口内的连续登录失败次数，
 * 达到 {@link #MAX_FAILS} 次即封禁该来源 {@link #BAN_DURATION} 时长；登录成功立即清零失败计数。
 * </p>
 * <p>
 * 实现说明：内存态由 Guava {@link Cache} 承载，条目按写入后 {@link #FAIL_WINDOW} 自动过期，
 * 失败记录无需手工惰性清理；{@code maximumSize} 兜底限制条目总量，防止伪造来源 IP 撑爆内存。
 * 计数维度取 TCP 对端地址（登录前 X-Forwarded-For 可被伪造，不可作为依据）。
 * </p>
 */
@Slf4j
@Component
public class LoginBruteForceGuard {

    /**
     * 触发封禁的窗口内最大连续失败次数
     */
    private static final int MAX_FAILS = 5;

    /**
     * 失败计数滑动窗口时长：条目自写入起算，超过该时长未再失败即自动移出缓存
     */
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(10);

    /**
     * 触发封禁后的封禁时长
     */
    private static final Duration BAN_DURATION = Duration.ofMinutes(15);

    /**
     * 状态表条目数上限：Guava 缓存按 LRU 自动淘汰，防止海量伪造来源 IP 撑爆内存
     */
    private static final int MAX_STATES = 10000;

    /**
     * 单个来源 IP 的失败记录状态（含封禁截止时间与窗口内失败时间戳队列）
     */
    private static final class State {
        /**
         * 窗口内的失败时间戳队列（队首最旧）
         */
        final ArrayDeque<Long> failTimes = new ArrayDeque<>();

        /**
         * 封禁截止时间戳（毫秒），0 表示未处于封禁期
         */
        volatile long banUntil;
    }

    /**
     * 失败状态缓存：写入后 FAIL_WINDOW 未再失败即自动过期移除；
     * 封禁状态的生命周期（BAN_DURATION）长于该窗口，故封禁期内持续失败会不断续期条目
     */
    private final Cache<String, State> states = CacheBuilder.newBuilder()
            .expireAfterWrite(FAIL_WINDOW)
            .maximumSize(MAX_STATES)
            .build();

    /**
     * 判断指定来源是否处于防爆破封禁期
     *
     * @param clientIp 来源 IP（TCP 对端地址）
     * @return 处于封禁期返回 true，应直接拒绝登录
     */
    public boolean isBanned(String clientIp) {
        State state = states.getIfPresent(clientIp);
        if (state == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (state.banUntil > 0) {
            if (now < state.banUntil) {
                return true;
            }
            // 封禁已到期，解除封禁标记
            state.banUntil = 0;
        }
        return false;
    }

    /**
     * 记录一次登录失败：窗口内失败次数达到阈值即触发封禁
     *
     * @param clientIp 来源 IP（TCP 对端地址）
     */
    public void recordFailure(String clientIp) {
        long now = System.currentTimeMillis();
        State state = states.asMap().computeIfAbsent(clientIp, k -> new State());
        synchronized (state) {
            // 惰性清理滑动窗口之外的过期失败记录（缓存过期粒度与单次失败粒度不同，仍需按时间戳精确裁剪）
            evictExpired(state, now);
            state.failTimes.addLast(now);
            // 主动重写条目以刷新写入时间：封禁期内持续失败时条目不得提前过期，否则封禁会被绕过
            states.put(clientIp, state);
            if (state.failTimes.size() >= MAX_FAILS) {
                state.banUntil = now + BAN_DURATION.toMillis();
                state.failTimes.clear();
                log.warn("来源 IP = {} 登录失败次数达到 {} 次，已触发防爆破封禁 {} 分钟",
                        clientIp, MAX_FAILS, BAN_DURATION.toMinutes());
            }
        }
    }

    /**
     * 记录登录成功：清除该来源的全部失败记录
     *
     * @param clientIp 来源 IP（TCP 对端地址）
     */
    public void recordSuccess(String clientIp) {
        states.invalidate(clientIp);
    }

    /**
     * 裁剪指定状态中滑动窗口外的过期失败记录（须在持有 state 锁时调用）
     *
     * @param state 待裁剪的状态
     * @param now   当前时间戳（毫秒）
     */
    private void evictExpired(State state, long now) {
        long windowMillis = FAIL_WINDOW.toMillis();
        while (!state.failTimes.isEmpty() && now - state.failTimes.peekFirst() > windowMillis) {
            state.failTimes.pollFirst();
        }
    }
}
