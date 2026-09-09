package com.stioc.cute.engine.llm;

import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.engine.loop.core.AgentContext;

/**
 * 引擎供应商配置解析供血接口（宿主实现）。
 * <p>
 * 引擎不感知供应商配置的存储与选择逻辑（宿主从全局配置/会话属性推导），
 * 仅通过本接口获取当前会话对应的供应商配置快照——快照即引擎内部
 * 客户端组装与模型选择的唯一事实源，配置变更后快照变化由引擎自感知。
 * </p>
 */
public interface ProviderResolver {

    /**
     * 获取指定会话对应的供应商属性配置快照（协议、模型名、窗口大小等；无可用配置时返回 null）
     */
    Provider getProviderConfigForContext(AgentContext context);
}
