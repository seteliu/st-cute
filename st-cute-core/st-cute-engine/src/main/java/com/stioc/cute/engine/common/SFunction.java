package com.stioc.cute.engine.common;

import java.io.Serializable;

/**
 * 字段getter方法引用契约（引擎内自研，零第三方依赖）。
 * <p>
 * 继承 Serializable 以使 lambda 方法引用具备序列化能力——这是运行期反解出
 * 目标方法/字段名的前提（JVM 为可序列化 lambda 生成的 writeReplace 钩子）。
 * 仅限接受 {@code 实体::getXxx} 形态的方法引用，普通 lambda 或匿名实现会在
 * {@link LambdaFieldResolver} 解析时快速失败。
 * </p>
 *
 * @param <T> 实体类型
 * @param <R> 字段类型
 */
@FunctionalInterface
public interface SFunction<T, R> extends Serializable {

    /**
     * 从实体上读取字段值（方法引用形态下由编译器生成，指向实体的 getter）
     */
    R apply(T source);
}
