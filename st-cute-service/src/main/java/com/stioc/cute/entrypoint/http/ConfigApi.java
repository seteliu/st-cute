package com.stioc.cute.entrypoint.http;

import com.stioc.cute.platform.common.Result;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.provider.ProviderService;
import com.stioc.cute.provider.BasicConfigDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 系统基础行为配置控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
public class ConfigApi {

    @Resource
    private ContractProperty contractProperty;
    @Resource
    private ProviderService providerService;

    /**
     * 获取系统当前基础运行属性配置
     */
    @GetMapping("/list")
    public Result<BasicConfigDto> getConfig() {
        BasicConfigDto dto = new BasicConfigDto();
        dto.setLanguage(contractProperty.getLanguage());
        dto.setNewlineKey(contractProperty.getNewlineKey());
        dto.setHttpLog(contractProperty.getLlmLog().isHttpLog());
        dto.setHttpLogDays(contractProperty.getLlmLog().getHttpLogDays());
        dto.setPassword(contractProperty.getPassword());
        dto.setMessageAggregation(contractProperty.isMessageAggregation());
        dto.setMaxViewHistoryLimit(contractProperty.getMaxViewHistoryLimit());
        dto.setPathSandboxEnabled(contractProperty.isPathSandboxEnabled());
        return Result.success(dto);
    }

    /**
     * 保存并应用新的系统基础配置
     */
    @PostMapping("/save")
    public Result<Boolean> saveConfig(@RequestBody BasicConfigDto body) {
        log.info("请求保存基础设置: {}", body);
        String language = body.getLanguage();
        String newlineKey = body.getNewlineKey();
        Boolean httpLog = body.getHttpLog();
        Integer httpLogDays = body.getHttpLogDays();
        String password = body.getPassword();
        Boolean messageAggregation = body.getMessageAggregation();
        Boolean pathSandboxEnabled = body.getPathSandboxEnabled();

        String finalLanguage = language != null ? language : "zh-CN";
        String finalNewlineKey = newlineKey != null ? newlineKey : "enter";
        boolean finalHttpLog = httpLog != null ? httpLog : false;
        int finalHttpLogDays = httpLogDays != null ? httpLogDays : 7;
        boolean finalMessageAggregation = messageAggregation != null ? messageAggregation : true;
        boolean finalPathSandboxEnabled = pathSandboxEnabled != null ? pathSandboxEnabled : true;

        providerService.saveSettings(finalLanguage, finalNewlineKey, finalHttpLog, finalHttpLogDays, password, finalMessageAggregation, finalPathSandboxEnabled);
        return Result.success(true);
    }
}
