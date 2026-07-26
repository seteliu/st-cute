package com.stioc.cute.entrypoint.http;

import com.stioc.cute.platform.contract.Provider;
import com.stioc.cute.provider.ProviderService;
import com.stioc.cute.platform.common.Result;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 大模型供应商及模型配置控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/provider")
public class ProviderApi {

    @Resource
    private ProviderService providerService;

    /**
     * 获取全部已配置注册的大模型供应商列表
     */
    @GetMapping("/list")
    public Result<List<Provider>> getProviders() {
        List<Provider> list = providerService.getAllProviders();
        return Result.success(list);
    }

    /**
     * 保存或更新大模型供应商的属性配置与可用模型列表
     */
    @PostMapping("/save")
    public Result<Provider> saveProvider(
            @RequestBody Provider config,
            @RequestParam(required = false) String originalModelName) {
        log.debug("收到保存 Provider 请求: {}, originalModelName: {}", config, originalModelName);
        Provider saved = providerService.saveProvider(config, originalModelName);
        return Result.success(saved);
    }

    /**
     * 根据模型名称物理删除其配置
     */
    @DeleteMapping("/delete")
    public Result<Boolean> deleteProvider(@RequestParam String group, @RequestParam String modelName) {
        providerService.deleteProvider(group, modelName);
        return Result.success(true);
    }
}

