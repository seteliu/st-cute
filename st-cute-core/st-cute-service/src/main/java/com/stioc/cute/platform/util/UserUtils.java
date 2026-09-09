package com.stioc.cute.platform.util;

import com.stioc.cute.platform.common.UserInfo;

/**
 * 线程上下文用户工具类
 */
public class UserUtils {

    private static final ThreadLocal<UserInfo> USER_THREAD_LOCAL = new ThreadLocal<>();

    /**
     * 设置当前线程的用户信息
     *
     * @param userInfo 用户信息
     */
    public static void setUser(UserInfo userInfo) {
        USER_THREAD_LOCAL.set(userInfo);
    }

    /**
     * 获取当前线程的用户信息
     *
     * @return 用户信息
     */
    public static UserInfo getUser() {
        return USER_THREAD_LOCAL.get();
    }

    /**
     * 清理当前线程的用户信息，防止内存泄漏
     */
    public static void clear() {
        USER_THREAD_LOCAL.remove();
    }
}
