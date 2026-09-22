package com.stioc.cute.provider;

import com.stioc.cute.engine.common.JsonKit;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.llm.CuteChat;
import com.stioc.cute.engine.llm.CuteChatFactory;
import com.stioc.cute.engine.llm.ProviderResolver;
import com.stioc.cute.engine.llm.types.CuteChatOptions;
import com.stioc.cute.engine.llm.types.CuteChatResponse;
import com.stioc.cute.engine.llm.types.CuteMessage;
import com.stioc.cute.engine.llm.types.CuteMessageRole;
import com.stioc.cute.engine.llm.types.CutePrompt;
import com.stioc.cute.engine.llm.types.CuteToolCall;
import com.stioc.cute.engine.llm.types.CuteToolDefinition;
import com.stioc.cute.engine.llm.types.Provider;
import com.stioc.cute.platform.common.CharsetAwareFileKit;
import com.stioc.cute.platform.contract.ContractFile;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L4 真实大模型供应商联网集成测试。
 * <p>
 * 特性与设计保障：
 * 1. 独立配置源：严格读取测试专属目录下的 {@code ~/.st-cute-test/config.json}，绝不触碰生产目录与数据库；
 * 2. 默认平滑跳过（零污染 CI）：若未配置或 Key 均为占位符，自动触发 {@link Assumptions#assumeTrue} 跳过，保障日常 {@code mvn test} 纯净全绿；
 * 3. 契约复用：复用 L2 契约四件套（非流式冒烟 call / 流式增量拼接 stream / 工具调用 tool_call / 非法 Key 异常响应），反向校准假服务端；
 * 4. 只断言不变量：仅断言正文非空、Token 计量有效与 Schema 结构，不断言思考链必然出现（防模型版本脆断）。
 * </p>
 */
@DisplayName("L4 真实大模型供应商联网集成测试")
class RealProviderIntegrationTest {

    private static final List<Provider> ACTIVE_PROVIDERS = new ArrayList<>();

    @BeforeAll
    static void setUpAll() {
        File configFile = new File(ContractFile.getGlobalDir(), "config.json");
        if (!configFile.exists()) {
            Assumptions.assumeTrue(false,
                    "跳过 L4 联网集成测试：未检测到测试配置文件 [" + configFile.getAbsolutePath()
                            + "]。如需激活，可参考 src/test/resources/config.template.json 复制并填写真实 apiKey。");
            return;
        }

        try {
            String content = CharsetAwareFileKit.readString(configFile.toPath());
            JSONObject root = JsonKit.parseObject(content);
            if (root == null) {
                Assumptions.assumeTrue(false, "配置文件内容为空，跳过 L4 联网测试");
                return;
            }

            // 1. 检查 enabled 测试激活开关
            if (root.containsKey("enabled") && !root.getBooleanValue("enabled")) {
                Assumptions.assumeTrue(false,
                        "配置文件 [" + configFile.getAbsolutePath() + "] 中 enabled 为 false，跳过 L4 联网集成测试。");
                return;
            }

            // 2. 获取 providers 列表（优先顶层平铺，兼容回退 st-cute 包裹）
            JSONArray array = root.getJSONArray("providers");
            if (array == null && root.containsKey("st-cute")) {
                array = root.getJSONObject("st-cute").getJSONArray("providers");
            }
            if (array == null || array.isEmpty()) {
                Assumptions.assumeTrue(false, "配置文件中无 providers 列表，跳过 L4 联网测试");
                return;
            }

            // 3. 解析目标过滤模型（系统属性优先，文件配置兜底）
            String targetGroup = System.getProperty("st-cute.llm.test.group");
            if (StringUtils.isBlank(targetGroup)) {
                targetGroup = root.getString("targetGroup");
            }

            String targetModel = System.getProperty("st-cute.llm.test.model");
            if (StringUtils.isBlank(targetModel)) {
                targetModel = root.getString("targetModel");
            }

            for (int i = 0; i < array.size(); i++) {
                JSONObject obj = array.getJSONObject(i);
                Provider p = obj.toJavaObject(Provider.class);
                if (p == null || StringUtils.isBlank(p.getApiKey()) || StringUtils.isBlank(p.getModelName())) {
                    continue;
                }
                // 排除示例占位符 Key
                String key = p.getApiKey().trim();
                if (key.contains("your-") || key.equals("sk-xxx") || key.length() < 8) {
                    continue;
                }
                if (StringUtils.isNotBlank(targetGroup) && !StringUtils.equalsIgnoreCase(targetGroup, p.getGroup())) {
                    continue;
                }
                if (StringUtils.isNotBlank(targetModel) && !StringUtils.equalsIgnoreCase(targetModel, p.getModelName())) {
                    continue;
                }
                ACTIVE_PROVIDERS.add(p);
            }
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "解析配置文件失败，跳过 L4 联网测试: " + e.getMessage());
            return;
        }

        if (ACTIVE_PROVIDERS.isEmpty()) {
            Assumptions.assumeTrue(false,
                    "配置文件 [" + configFile.getAbsolutePath() + "] 中未包含有效 apiKey 的供应商配置，跳过 L4 联网测试。");
        }
    }

    private static CuteChat createClient(Provider provider) {
        ProviderResolver resolver = context -> provider;
        CuteChatFactory factory = new CuteChatFactory(resolver, Optional.empty(), Optional.empty());
        return factory.getCuteChat(null);
    }

    private static CutePrompt simplePrompt(String userText) {
        List<CuteMessage> messages = List.of(
                CuteMessage.builder().role(CuteMessageRole.SYSTEM).content("你是一个严谨的测试助手。").build(),
                CuteMessage.builder().role(CuteMessageRole.USER).content(userText).build()
        );
        return CutePrompt.builder().messages(messages).build();
    }

    @Test
    @DisplayName("契约一：非流式调用 call 冒烟，返回非空正文与 Token 计量")
    void testCall_SmokeContract() {
        for (Provider provider : ACTIVE_PROVIDERS) {
            CuteChat client = createClient(provider);
            CuteChatResponse response = client.call(simplePrompt("请仅回复一个单词：pong"));

            assertNotNull(response, provider.getGroup() + ":" + provider.getModelName() + " 响应不应为空");
            assertTrue(StringUtils.isNotBlank(response.getContent()),
                    provider.getGroup() + ":" + provider.getModelName() + " 正文内容不应为空");
            assertNotNull(response.getUsage(),
                    provider.getGroup() + ":" + provider.getModelName() + " Token 计量不应为空");
            assertTrue(response.getUsage().getOutputTokens() > 0,
                    provider.getGroup() + ":" + provider.getModelName() + " 输出 Token 应大于 0");
        }
    }

    @Test
    @DisplayName("契约二：流式调用 streamConsume 增量推送与拼接收尾")
    void testStream_IncrementalChunksContract() {
        for (Provider provider : ACTIVE_PROVIDERS) {
            CuteChat client = createClient(provider);

            List<CuteChatResponse> received = new ArrayList<>();
            client.streamConsume(simplePrompt("请从 1 数到 5，每个数字一行"), stream -> stream.forEach(chunk -> {
                if (chunk != null) {
                    received.add(chunk);
                }
            }));

            assertTrue(!received.isEmpty(),
                    provider.getGroup() + ":" + provider.getModelName() + " 流式接收到的 chunk 列表不应为空");

            String fullContent = received.stream()
                    .map(CuteChatResponse::getContent)
                    .filter(StringUtils::isNotBlank)
                    .reduce("", String::concat);

            assertTrue(StringUtils.isNotBlank(fullContent),
                    provider.getGroup() + ":" + provider.getModelName() + " 流式拼接出的完整内容不应为空");

            // 验证流式收尾事件中包含有效的 Token 计量（或在最后几帧携带）
            boolean hasUsage = received.stream().anyMatch(r -> r.getUsage() != null);
            assertTrue(hasUsage, provider.getGroup() + ":" + provider.getModelName() + " 流式序列中必须携带 usage 计量");
        }
    }

    @Test
    @DisplayName("契约三：工具调用 tool_call 模型函数回调与参数反序列化")
    void testToolCall_FunctionCallingContract() {
        CuteToolDefinition toolDef = CuteToolDefinition.builder()
                .name("get_current_time")
                .description("获取指定时区的当前时间")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "timezone": {
                              "type": "string",
                              "description": "时区名称，例如 Asia/Shanghai 或 UTC"
                            }
                          },
                          "required": ["timezone"]
                        }
                        """)
                .build();

        CuteChatOptions options = CuteChatOptions.builder()
                .tools(List.of(toolDef))
                .build();

        CutePrompt prompt = CutePrompt.builder()
                .messages(List.of(
                        CuteMessage.builder().role(CuteMessageRole.USER)
                                .content("请帮我调用工具查询 Asia/Shanghai 时区的当前时间").build()
                ))
                .options(options)
                .build();

        for (Provider provider : ACTIVE_PROVIDERS) {
            CuteChat client = createClient(provider);
            CuteChatResponse response = client.call(prompt);

            assertNotNull(response);
            // 真实大模型若支持 tool_call，应命中工具回调
            List<CuteToolCall> calls = response.getToolCalls();
            if (calls != null && !calls.isEmpty()) {
                CuteToolCall call = calls.get(0);
                assertNotNull(call.getName());
                assertTrue(call.getName().contains("get_current_time"),
                        "工具调用名称应匹配 get_current_time，实际: " + call.getName());
                if (StringUtils.isNotBlank(call.getArguments())) {
                    JSONObject args = JsonKit.parseObject(call.getArguments());
                    assertNotNull(args, "工具参数必须为合法 JSON 字符串");
                }
            } else {
                // 部分模型可能以正文形式答复，不断言硬失败但断言正文非空
                assertTrue(StringUtils.isNotBlank(response.getContent()));
            }
        }
    }

    @Test
    @DisplayName("契约四：非法 Key 鉴权失败与真实网络异常捕获")
    void testErrorHandling_InvalidApiKeyContract() {
        for (Provider provider : ACTIVE_PROVIDERS) {
            Provider invalidProvider = Provider.builder()
                    .group(provider.getGroup())
                    .protocol(provider.getProtocol())
                    .baseUrl(provider.getBaseUrl())
                    .apiKey("sk-invalid-key-for-st-cute-l4-test")
                    .modelName(provider.getModelName())
                    .temperature(0.0)
                    .build();

            CuteChat invalidClient = createClient(invalidProvider);
            assertThrows(Exception.class, () -> invalidClient.call(simplePrompt("测试非法鉴权")),
                    provider.getGroup() + " 使用非法 Key 时必须抛出鉴权失败异常，不得静默成功");
        }
    }
}
