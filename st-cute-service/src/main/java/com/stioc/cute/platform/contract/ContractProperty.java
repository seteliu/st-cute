package com.stioc.cute.platform.contract;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.platform.util.ConfigMergeUtils;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一管理 st-cute 的自定义 YML 配置项并自动与全局 config.json 进行合并
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "st-cute")
public class ContractProperty {

    /**
     * 大模型接口调用物理日志记录配置项
     */
    private LlmLog llmLog = new LlmLog();

    /**
     * 大模型供应商配置列表（如 OpenRouter, DeepSeek 等）
     */
    private List<Provider> providers = new ArrayList<>();

    /**
     * 发送消息的触发换行按键名（如 enter 或 ctrl+enter）
     */
    private String newlineKey = "enter";

    /**
     * 系统语言设置，默认为 zh-CN
     */
    private String language = "zh-CN";

    /**
     * 接口调用或任务重试最大次数
     */
    private int retryCount = 3;

    /**
     * 失败重试的物理等待时间间隔（秒）
     */
    private int retryIntervalSec = 5;

    /**
     * 安全保护访问密码，留空表示不开启
     */
    private String password;

    /**
     * 是否开启消息聚合展示
     */
    private boolean messageAggregation = true;

    /**
     * 会话历史消息最大保留数量限制
     */
    private int maxViewHistoryLimit = 2000;

    /**
     * 是否开启路径沙箱保护，默认开启
     */
    private boolean pathSandboxEnabled = true;


    /**
     * 初始化方法：惰性读取物理用户目录下的全局配置文件并与内置的默认 YML 配置进行深度反射合并
     */
    @PostConstruct
    public void initAndMergeConfigs() {
        File globalJsonFile = new File(System.getProperty("user.home"), ".st-cute/config.json");
        if (!globalJsonFile.exists()) {
            log.info("未检测到全局配置文件 {}，将直接使用系统内置配置", globalJsonFile.getAbsolutePath());
            return;
        }
        log.info("检测到全局配置文件 {}，开始读取并与内置默认配置合并...", globalJsonFile.getAbsolutePath());
        try {
            String jsonContent = Files.readString(globalJsonFile.toPath(), StandardCharsets.UTF_8);
            JSONObject root = JSON.parseObject(jsonContent);
            if (root == null) {
                return;
            }
            JSONObject configToMerge = root.containsKey("st-cute") ? root.getJSONObject("st-cute") : root;

            // 1. 使用反射工具，深度合并基础设置项与默认供应商配置
            ConfigMergeUtils.merge(this, configToMerge);

            // 2. 特殊合并 providers 列表，以 group + ":" + modelName 联合键进行覆盖/去重
            List<Provider> globalProviders = parseProvidersFromJsonObject(configToMerge);
            if (globalProviders != null && !globalProviders.isEmpty()) {
                Map<String, Provider> mergedMap = new LinkedHashMap<>();
                if (this.providers != null) {
                    for (Provider p : this.providers) {
                        String key = (p.getGroup() != null ? p.getGroup() : "") + ":" + (p.getModelName() != null ? p.getModelName() : "");
                        mergedMap.put(key, p);
                    }
                }
                for (Provider p : globalProviders) {
                    String key = (p.getGroup() != null ? p.getGroup() : "") + ":" + (p.getModelName() != null ? p.getModelName() : "");
                    mergedMap.put(key, p);
                }
                this.providers = new ArrayList<>(mergedMap.values());
            }

            log.info("全局配置已成功与默认配置合并，当前共加载 {} 个供应商，系统语言: {}, 换行键: {}, 响应体日志: {}, 路径沙箱保护: {}",
                    this.providers.size(), this.language, this.newlineKey, this.llmLog.isHttpLog(), this.pathSandboxEnabled);
        } catch (Exception e) {
            log.error("读取或合并全局配置文件 config.json 异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 解析全局 JSON 配置对象中的大模型提供商列表
     *
     * @param obj 全局配置 JSONObject
     * @return 提供商配置对象列表，若解析失败或不存在则返回 null
     */
    private List<Provider> parseProvidersFromJsonObject(JSONObject obj) {
        try {
            if (obj.containsKey("providers")) {
                return obj.getJSONArray("providers").toJavaList(Provider.class);
            }
        } catch (Exception e) {
            log.error("解析配置对象中的提供商列表异常: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * 大模型 API 请求与响应日志持久化留存配置静态内部类
     */
    @Data
    public static class LlmLog {
        /**
         * 是否开启大模型接口调用的详细 HTTP 日志记录（包含 Payload 入参和出参）
         */
        private boolean httpLog = false;

        /**
         * 大模型物理 HTTP 原始日志的最长保留天数
         */
        private int httpLogDays = 7;
    }
}

