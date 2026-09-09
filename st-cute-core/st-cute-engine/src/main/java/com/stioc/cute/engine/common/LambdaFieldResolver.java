package com.stioc.cute.engine.common;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * lambda 方法引用 → 字段名 解析器。
 * <p>
 * 机制：JVM 为「可序列化的方法引用」生成的 lambda 类带有私有合成方法 writeReplace，
 * 调用后得到 {@link SerializedLambda}，其中携带被引用方法的实现类与方法名；
 * 按约定 getter（getXxx/isXxx，直接字段访问形态）剥离前缀即得字段名。
 * 解析结果以弱引用缓存（键为 lambda 合成类，JVM 按调用点缓存其实例），避免重复反射。
 * </p>
 * <p>
 * 快速失败：非方法引用（手写 lambda 体/匿名实现）、非 getter 形态、实现类上不存在
 * 对应真实字段（方法调用链伪装 getter）等一律抛 {@link IllegalStateException}，
 * 保证键的推导错误在首次使用时即暴露，绝不静默。
 * </p>
 */
public final class LambdaFieldResolver {

    /**
     * 解析结果缓存（键：lambda 合成类；弱键防类加载器泄漏；同步包装防并发扩容问题）
     */
    private static final Map<Class<?>, String> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private LambdaFieldResolver() {
        // 纯静态工具类，禁止实例化
    }

    /**
     * 解析字段 getter 方法引用对应的字段名。
     *
     * @param fn 实体::getXxx 形态的方法引用
     * @return 字段名（如 {@code Conversation::getTitle} → "title"）
     * @throws IllegalStateException 方法引用形态非法或解析失败（快速失败，不静默降级）
     */
    public static <T, R> String resolve(SFunction<T, R> fn) {
        if (fn == null) {
            throw new IllegalStateException("解析字段名失败：方法引用为 null");
        }
        Class<?> lambdaClass = fn.getClass();

        // 1. 缓存命中直接返回（lambda 实例按调用点单例，缓存粒度为合成类）
        String cached = CACHE.get(lambdaClass);
        if (cached != null) {
            return cached;
        }

        // 2. 经 writeReplace 取出 SerializedLambda（JVM 对可序列化方法引用的标准实现）
        try {
            Method writeReplace = lambdaClass.getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            Object serialized = writeReplace.invoke(fn);
            if (!(serialized instanceof SerializedLambda sl)) {
                throw new IllegalStateException("非标准可序列化方法引用: " + lambdaClass.getName());
            }

            // 3. 从实现方法名推导字段名（仅接受 getter 形态：方法名剥离 get/is 前缀后首字母小写）
            String fieldName = propertyName(sl.getImplMethodName());

            // 4. 校验实现类上真实存在该字段（拦截方法调用链伪装 getter 的非常规形态）
            Class<?> implClass = Class.forName(sl.getImplClass().replace('/', '.'));
            implClass.getDeclaredField(fieldName);

            CACHE.put(lambdaClass, fieldName);
            return fieldName;
        } catch (Exception e) {
            throw new IllegalStateException("解析 lambda 方法引用字段名失败: " + lambdaClass.getName()
                    + "（仅支持 实体::getXxx 直接字段访问形态）", e);
        }
    }

    /**
     * getter 方法名 → 字段名（getXxx/isXxx 前缀剥离 + 首字母小写）
     */
    private static String propertyName(String accessorName) {
        for (String prefix : new String[]{"get", "is"}) {
            if (accessorName.startsWith(prefix) && accessorName.length() > prefix.length()) {
                return Character.toLowerCase(accessorName.charAt(prefix.length()))
                        + accessorName.substring(prefix.length() + 1);
            }
        }
        throw new IllegalStateException("非 getter 形态方法引用: " + accessorName);
    }
}
