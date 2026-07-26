package com.stioc.cute.platform.util;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;

@Slf4j
public class ConfigMergeUtils {

    /**
     * 将从 JSONObject 解析出的覆盖值，合并到已有的 target 对象中
     */
    public static void merge(Object target, JSONObject source) {
        if (target == null || source == null) {
            return;
        }

        Class<?> clazz = target.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }

            String name = field.getName();
            if (!source.containsKey(name)) {
                continue;
            }

            Object value = source.get(name);
            field.setAccessible(true);
            try {
                Class<?> fieldType = field.getType();

                // 如果是 Collection（如 List）
                if (Collection.class.isAssignableFrom(fieldType)) {
                    Object parsedVal = source.getObject(name, field.getGenericType());
                    if (parsedVal != null) {
                        field.set(target, parsedVal);
                    }
                    continue;
                }

                // 如果是 Map
                if (Map.class.isAssignableFrom(fieldType)) {
                    Map targetMap = (Map) field.get(target);
                    if (targetMap != null && value instanceof Map) {
                        targetMap.putAll((Map) value);
                    } else {
                        Object parsedVal = source.getObject(name, field.getGenericType());
                        if (parsedVal != null) {
                            field.set(target, parsedVal);
                        }
                    }
                    continue;
                }

                // 如果是嵌套自定义配置对象
                if (isCustomConfigObject(fieldType)) {
                    Object targetSubObj = field.get(target);
                    if (targetSubObj == null) {
                        Object subVal = source.getObject(name, fieldType);
                        field.set(target, subVal);
                    } else if (value instanceof JSONObject) {
                        merge(targetSubObj, (JSONObject) value);
                    }
                    continue;
                }

                // 标量字段（String, Number, Boolean, Enum 等）
                Object parsedVal = source.getObject(name, fieldType);
                if (parsedVal != null) {
                    field.set(target, parsedVal);
                }

            } catch (Exception e) {
                log.error("Merge field '{}' in class '{}' failed: {}", name, clazz.getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 将一个普通Java对象的所有属性，转为清洁的 JSONObject
     * 只转换声明的非 static/final 属性，避免引入 Spring 框架注入的其他 Bean
     */
    public static JSONObject toJsonObject(Object source) {
        if (source == null) {
            return null;
        }

        JSONObject result = new JSONObject();
        Class<?> clazz = source.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }

            String name = field.getName();
            field.setAccessible(true);
            try {
                Object value = field.get(source);
                if (value == null) {
                    continue;
                }

                Class<?> fieldType = field.getType();

                // 嵌套自定义类（这里指定只要是 contract 包下的自定义非标量配置对象，就进行递归转换）
                if (isCustomConfigObject(fieldType)) {
                    result.put(name, toJsonObject(value));
                } else {
                    // 其他直接交给 FastJSON
                    result.put(name, value);
                }
            } catch (Exception e) {
                log.error("Serialize field '{}' in class '{}' failed: {}", name, clazz.getSimpleName(), e.getMessage(), e);
            }
        }
        return result;
    }

    private static boolean isCustomConfigObject(Class<?> clazz) {
        if (clazz.isPrimitive()) return false;
        String name = clazz.getName();
        // 凡是 contract 包下的嵌套配置类才递归，这样可以完美避开其他包（例如 provider）的普通数据载体
        return name.startsWith("com.stioc.cute.platform.contract");
    }
}
