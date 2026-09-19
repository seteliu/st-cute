package com.stioc.cute.controller;

import com.stioc.cute.platform.common.Result;
import com.stioc.cute.platform.contract.ContractProperty;
import com.stioc.cute.platform.util.PasswordDigestKit;
import com.stioc.cute.provider.ProviderService;
import com.stioc.cute.provider.BasicConfigDto;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 系统基础行为配置控制器
 * <p>
 * 安全约定：访问密码全链路不明文——前端提交 SHA-256(原文) 传输摘要，
 * 服务端存储带盐摘要（{@code salt:digest}），查询接口仅回传"是否已设置"布尔标记。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    @Resource
    private ContractProperty contractProperty;
    @Resource
    private ProviderService providerService;

    /**
     * 获取系统当前基础运行属性配置（密码仅回传是否已设置的布尔标记，绝不回传明文/摘要）
     */
    @GetMapping("/list")
    public Result<BasicConfigDto> getConfig() {
        BasicConfigDto dto = new BasicConfigDto();
        dto.setLanguage(contractProperty.getLanguage());
        dto.setNewlineKey(contractProperty.getNewlineKey());
        dto.setHttpLog(contractProperty.getLlmLog().isHttpLog());
        dto.setHttpLogDays(contractProperty.getLlmLog().getHttpLogDays());
        dto.setPasswordSet(StringUtils.hasText(contractProperty.getPassword()));
        dto.setMaxViewHistoryLimit(contractProperty.getMaxViewHistoryLimit());
        dto.setPathSandboxEnabled(contractProperty.isPathSandboxEnabled());
        dto.setMinimalSkillMode(contractProperty.isMinimalSkillMode());
        dto.setLoadAllUserAttachments(contractProperty.isLoadAllUserAttachments());
        return Result.success(dto);
    }

    /**
     * 保存并应用新的系统基础配置。
     * <p>password 字段语义：空/缺省 = 不修改；非空 = 前端已计算的 SHA-256(原文) 传输摘要，加盐后落盘。</p>
     */
    @PostMapping("/save")
    public Result<Boolean> saveConfig(@RequestBody BasicConfigDto body) {
        // 日志脱敏：DTO 含密码传输摘要，只打印非敏感字段的保存摘要，杜绝敏感值落日志
        log.info("请求保存基础设置: language={}, httpLog={}, httpLogDays={}, passwordChanged={}, passwordClear={}",
                body.getLanguage(), body.getHttpLog(), body.getHttpLogDays(), StringUtils.hasText(body.getPassword()), Boolean.TRUE.equals(body.getPasswordClear()));
        String language = body.getLanguage();
        String newlineKey = body.getNewlineKey();
        Boolean httpLog = body.getHttpLog();
        Integer httpLogDays = body.getHttpLogDays();
        String password = body.getPassword();
        Boolean pathSandboxEnabled = body.getPathSandboxEnabled();
        Boolean minimalSkillMode = body.getMinimalSkillMode();
        Boolean loadAllUserAttachments = body.getLoadAllUserAttachments();

        String finalLanguage = language != null ? language : "zh-CN";
        String finalNewlineKey = newlineKey != null ? newlineKey : "enter";
        boolean finalHttpLog = httpLog != null ? httpLog : false;
        int finalHttpLogDays = httpLogDays != null ? httpLogDays : 7;
        boolean finalPathSandboxEnabled = pathSandboxEnabled != null ? pathSandboxEnabled : true;
        boolean finalMinimalSkillMode = minimalSkillMode != null ? minimalSkillMode : false;
        boolean finalLoadAllUserAttachments = loadAllUserAttachments != null ? loadAllUserAttachments : true;

        providerService.saveSettings(finalLanguage, finalNewlineKey, finalHttpLog, finalHttpLogDays, password,
                Boolean.TRUE.equals(body.getPasswordClear()),
                finalPathSandboxEnabled, finalMinimalSkillMode, finalLoadAllUserAttachments, body.getMaxViewHistoryLimit());
        return Result.success(true);
    }
}
