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
     * 保存并应用新的系统基础配置（不含密码：密码走专用接口 {@link #savePassword}）
     */
    @PostMapping("/save")
    public Result<Boolean> saveConfig(@RequestBody BasicConfigDto body) {
        log.info("请求保存基础设置: language={}, httpLog={}, httpLogDays={}",
                body.getLanguage(), body.getHttpLog(), body.getHttpLogDays());
        String language = body.getLanguage();
        String newlineKey = body.getNewlineKey();
        Boolean httpLog = body.getHttpLog();
        Integer httpLogDays = body.getHttpLogDays();
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

        providerService.saveSettings(finalLanguage, finalNewlineKey, finalHttpLog, finalHttpLogDays,
                finalPathSandboxEnabled, finalMinimalSkillMode, finalLoadAllUserAttachments, body.getMaxViewHistoryLimit());
        return Result.success(true);
    }

    /**
     * 设置 / 修改 / 清除安全访问密码（独立于基础配置保存）。
     * <p>
     * 之所以独立成接口而非并入 /save：密码值一旦与常驻表单同进同出，浏览器自动填充就可能把
     * 存储摘要回填进输入框并被前端二次摘要，导致密码静默失效。独立接口只接受用户现输现算的
     * 传输摘要，从根上杜绝该误伤。
     * </p>
     *
     * @param body password 为 SHA-256(原文) 传输摘要；passwordLength 为原文长度；
     *             passwordClear=true 表示清除密码（优先级高于 password）
     */
    @PostMapping("/password")
    public Result<Boolean> savePassword(@RequestBody BasicConfigDto body) {
        // 日志脱敏：只打印是否携带摘要与清除标记，杜绝摘要值落日志
        log.info("请求更新安全访问码: passwordChanged={}, clear={}",
                StringUtils.hasText(body.getPassword()), Boolean.TRUE.equals(body.getPasswordClear()));
        providerService.savePassword(body.getPassword(),
                body.getPasswordLength(), Boolean.TRUE.equals(body.getPasswordClear()));
        return Result.success(true);
    }
}
