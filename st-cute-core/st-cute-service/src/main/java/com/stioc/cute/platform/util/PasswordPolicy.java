package com.stioc.cute.platform.util;

import com.stioc.cute.platform.common.BusinessException;

import java.util.regex.Pattern;

/**
 * 安全访问码复杂度策略
 * <p>
 * 统一口径：原文经 trim 后长度须不小于 {@link #MIN_LENGTH} 位，必须同时包含英文字母与数字，
 * 且仅允许出现常见密码字符（英文字母、数字、键盘常见半角符号与空格）。
 * 前端设置入口、后端保存兜底与登录校验共用本策略，防止经配置文件直改弱密码绕过前端校验。
 * </p>
 */
public final class PasswordPolicy {

    /**
     * 最小长度要求（针对 trim 后的原文）
     */
    public static final int MIN_LENGTH = 8;

    /**
     * 最大长度要求（针对 trim 后的原文）：与前端输入框 maxlength 保持同一口径，
     * 防止经配置文件写入超长访问码造成存储与比较链路的边界问题。
     * <p>
     * 说明：后端全程无法获取原文（前端仅上传摘要），故字符集校验只能在
     * 前端保存链路与后端「启动明文迁移」链路（此时能读到 config.json 中的明文）执行，
     * 长度约束则可由前端随摘要附带的 passwordLength 字段做服务端兜底
     * </p>
     */
    public static final int MAX_LENGTH = 32;

    /**
     * 允许的字符集正则：英文字母、数字、空格、斜杠与键盘常见半角符号等 ASCII 可见字符常规子集。
     * 与前端 stores/app.ts 中的 PASSWORD_ALLOWED_RE 保持字符范围一致
     */
    private static final Pattern ALLOWED_PATTERN =
            Pattern.compile("^[A-Za-z0-9 !\"#$%&'()*+,.:;<=>?@\\[\\\\\\]^_`{|}~/-]+$");

    private PasswordPolicy() {
    }

    /**
     * 判断原文是否满足安全策略
     *
     * @param rawPassword 访问码原文（不做 trim 修改，仅以 trim 后口径参与判定）
     * @return 满足策略返回 true
     */
    public static boolean matches(String rawPassword) {
        if (rawPassword == null) {
            return false;
        }
        String trimmed = rawPassword.trim();
        // 长度区间校验
        if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) {
            return false;
        }
        // 组成校验：必须同时包含 ASCII 英文字母与数字（刻意不用 Character.isLetter，避免中文等放行）
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                hasLetter = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            return false;
        }
        // 字符白名单校验：仅允许常见密码字符
        return ALLOWED_PATTERN.matcher(trimmed).matches();
    }

    /**
     * 校验失败时抛出业务异常（含完整策略描述），供设置访问码的保存链路使用；
     * 登录链路请使用 {@link #matches}，避免向攻击者泄露策略差异细节
     *
     * @param rawPassword 访问码原文
     */
    public static void validate(String rawPassword) {
        if (matches(rawPassword)) {
            return;
        }
        throw new BusinessException("安全访问码不符合安全策略：" + describe());
    }

    /**
     * 策略要求描述文本（用于拼装用户可见的错误提示）
     */
    public static String describe() {
        return "长度 " + MIN_LENGTH + "~" + MAX_LENGTH + " 位，须同时包含英文字母与数字，且仅可使用常见字符（字母、数字与常见半角符号）";
    }
}
