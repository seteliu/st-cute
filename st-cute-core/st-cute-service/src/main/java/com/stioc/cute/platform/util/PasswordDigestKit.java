package com.stioc.cute.platform.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 安全访问密码摘要工具：密码不明文落盘、不明文回传前端。
 * <p>
 * 存储形态：{@code SHA-256(salt + password)}，摘要与盐均经 Base64 编码，
 * 组合为 {@code salt:digest} 单字符串持久化（如 config.json 的 password 字段）。
 * 校验侧使用 {@link #matches} 恒定时间比较，避免时序侧信道。
 * </p>
 * <p>兼容设计：历史明文密码在首次校验时自动识别（不包含分隔符的值视为明文），
 * 校验通过后由调用方决定是否升级为摘要存储。</p>
 */
public final class PasswordDigestKit {

    /**
     * 盐字节长度
     */
    private static final int SALT_LENGTH = 16;

    /**
     * SHA-256 摘要字节长度
     */
    private static final int SHA256_LENGTH = 32;

    /**
     * 原文最大长度（与访问码安全策略 {@link PasswordPolicy#MAX_LENGTH} 同口径）。
     * <p>保留该常量作为「存储值原文长度」语义的单点出口，供需要按原文口径做长度判断的
     * 调用方引用（如 {@link #isDigested} 的格式识别说明）</p>
     */
    public static final int MAX_RAW_LENGTH = PasswordPolicy.MAX_LENGTH;

    /**
     * 摘要与盐的拼接分隔符
     */
    private static final String SEPARATOR = ":";

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordDigestKit() {
    }

    /**
     * 生成随机盐并计算密码摘要
     *
     * @param rawPassword 明文密码
     * @return {@code salt:digest} 形态的存储字符串
     */
    public static String hash(String rawPassword) {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        byte[] digest = sha256(salt, rawPassword);
        return Base64.getEncoder().encodeToString(salt) + SEPARATOR
                + Base64.getEncoder().encodeToString(digest);
    }

    /**
     * 校验明文密码与存储值是否匹配。
     * <p>存储值含分隔符时按摘要格式校验（恒定时间比较）；不含分隔符时视为历史明文直接 equals。
     * 空密码（未设置）场景由调用方先行判空，本方法不处理空语义。</p>
     *
     * @param rawPassword  待校验的明文密码
     * @param storedStored 存储的密码值（摘要形态或历史明文）
     * @return 匹配返回 true
     */
    public static boolean matches(String rawPassword, String storedStored) {
        if (rawPassword == null || storedStored == null) {
            return false;
        }
        int idx = storedStored.indexOf(SEPARATOR);
        if (idx <= 0) {
            // 历史明文兼容：不包含分隔符的存储值按明文比较
            return MessageDigest.isEqual(
                    rawPassword.getBytes(StandardCharsets.UTF_8),
                    storedStored.getBytes(StandardCharsets.UTF_8));
        }
        try {
            byte[] salt = Base64.getDecoder().decode(storedStored.substring(0, idx));
            byte[] expected = Base64.getDecoder().decode(storedStored.substring(idx + 1));
            byte[] actual = sha256(salt, rawPassword);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判定存储值是否为摘要形态
     * <p>
     * 按本工具自身的生成格式做严格校验：盐段须为 {@link #SALT_LENGTH} 字节 Base64（24 字符）、
     * 摘要段须为 SHA-256 的 32 字节 Base64（44 字符）。刻意不做「含冒号 + 可 Base64 解码」的宽松判定——
     * 用户在 config.json 手写形如 {@code abcd:efgh} 的明文密码时，
     * 宽松判定会把它误认为摘要形态而走质询链路，导致登录失败且提示语义错乱
     * </p>
     *
     * @param storedValue 存储的密码值
     * @return 摘要形态返回 true；历史明文或空值返回 false
     */
    public static boolean isDigested(String storedValue) {
        if (storedValue == null || storedValue.isEmpty()) {
            return false;
        }
        int idx = storedValue.indexOf(SEPARATOR);
        if (idx <= 0) {
            return false;
        }
        String saltSegment = storedValue.substring(0, idx);
        String digestSegment = storedValue.substring(idx + 1);
        return isExpectedSegment(saltSegment, SALT_LENGTH) && isExpectedSegment(digestSegment, SHA256_LENGTH);
    }

    /**
     * 判断 Base64 段解码后的字节长度是否为期望值（长度不符或解码失败均返回 false）
     *
     * @param segment        Base64 编码段
     * @param expectedLength 期望的原始字节长度
     * @return 符合期望格式返回 true
     */
    private static boolean isExpectedSegment(String segment, int expectedLength) {
        if (segment == null || segment.isEmpty()) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(segment).length == expectedLength;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 登录质询应答校验：使用存储摘要作为密钥材料，重算期望证明值并与客户端应答做恒定时间比对。
     * <p>
     * 协议约定：客户端先经质询接口取得服务端签发的一次性质询值 nonce 与盐，本地计算
     * {@code D = Base64(SHA-256(salt + X))}（X 为 SHA-256(原文) 十六进制摘要，D 应与存储摘要一致），
     * 再计算证明 {@code proof = SHA-256hex(D + ":" + nonce)} 提交；服务端以自身存储的
     * 摘要段与 nonce 重算同一表达式比对。证明值将 freshness 材料（nonce）绑定进摘要，
     * 抓包重放或伪造任意 nonce 均无法通过校验。
     * </p>
     *
     * @param storedStored 存储的密码值（须为摘要形态 salt:digest）
     * @param nonce        服务端签发并被消费的一次性质询值
     * @param proof        客户端提交的证明摘要（小写十六进制）
     * @return 校验通过返回 true
     */
    public static boolean verifyChallengeProof(String storedStored, String nonce, String proof) {
        if (nonce == null || proof == null || storedStored == null) {
            return false;
        }
        int idx = storedStored.indexOf(SEPARATOR);
        if (idx <= 0) {
            return false;
        }
        try {
            // 盐段与摘要段均须可 Base64 解码，畸形存储值直接按校验失败处理
            Base64.getDecoder().decode(storedStored.substring(0, idx));
            String digestBase64 = storedStored.substring(idx + 1);
            Base64.getDecoder().decode(digestBase64);
            String expected = sha256Hex(digestBase64 + SEPARATOR + nonce);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    proof.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 计算文本的 SHA-256 十六进制摘要（小写）。
     * <p>与前端登录/保存密码前计算的传输摘要为同一算法：历史明文密码迁移时，
     * 按前端会发送的摘要材料形态重新入库，保证升级后仍可正常登录。</p>
     *
     * @param text 原文
     * @return 64 位小写十六进制摘要字符串
     */
    public static String sha256Hex(String text) {
        if (text == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算 SHA-256 摘要失败", e);
        }
    }

    private static byte[] sha256(byte[] salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(password.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException("计算密码摘要失败", e);
        }
    }
}
