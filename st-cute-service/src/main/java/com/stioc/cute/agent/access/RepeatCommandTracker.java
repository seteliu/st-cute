package com.stioc.cute.agent.access;

import lombok.Data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同一命令重复执行追踪器。
 * <p>
 * 记录最近一次命令输出摘要与连续相同输出次数，供平台检测"同一命令短时间内重复执行
 * 且输出完全相同"的疑似死循环场景，进而熔断拒绝执行。
 * 仅存内存，服务重启后计数清零可接受：重复调用通常是模型决策问题，重新计数不影响正确性。
 * </p>
 */
@Data
public class RepeatCommandTracker {

    /**
     * 最近一次命令输出的 MD5 摘要（用于与下一次输出比对是否完全相同，避免缓存全文输出占用内存）
     */
    private volatile String lastOutputDigest = null;

    /**
     * 连续产生完全相同输出的次数（输出一旦变化立即重置为 1）
     */
    private final AtomicInteger sameOutputCount = new AtomicInteger(0);

    /**
     * 最近一次执行的时间戳（毫秒），超出判定时间窗口后计数视为失效
     */
    private volatile long lastExecuteAt = 0L;

    /**
     * 计算命令输出的 MD5 摘要，用于等值比对
     */
    public static String digestOf(String output) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest((output == null ? "" : output).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // 摘要算法不可用时退化为 hashCode 字符串，仅影响比对精度不影响功能
            return String.valueOf(output == null ? 0 : output.hashCode());
        }
    }
}
