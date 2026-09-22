package com.stioc.cute.engine.llm.types;

import com.stioc.cute.engine.llm.CuteChat;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 大模型客户端缓存条目：客户端实例 + 组装时的供应商配置快照（指纹比对基准）。
 * <p>
 * 宿主改配置后 {@code ProviderResolver} 解析出的新快照与旧快照 equals 比对不一致，
 * 即触发客户端重建替换，无需缓存失效回调。
 * </p>
 */
@Getter
@RequiredArgsConstructor
public class CachedClient {

    /**
     * 组装完成的客户端实例
     */
    private final CuteChat client;

    /**
     * 组装时的供应商配置快照
     */
    private final Provider configSnapshot;
}
