package com.stioc.cute.runtime.llm;

import com.stioc.cute.engine.llm.RetryPolicyProvider;
import com.stioc.cute.platform.contract.ContractProperty;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 引擎重试策略供血实现：宿主全局配置（config.json / yml 合并结果）的适配。
 */
@Component
public class RetryPolicyProviderImpl implements RetryPolicyProvider {

    @Resource
    private ContractProperty contractProperty;

    @Override
    public int getRetryCount() {
        return contractProperty.getRetryCount();
    }

    @Override
    public int getRetryIntervalSec() {
        return contractProperty.getRetryIntervalSec();
    }
}
