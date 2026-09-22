package com.stioc.cute.tool.websearchtool;

import com.stioc.cute.engine.common.JsonKit;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import com.stioc.cute.platform.contract.SearxngProperty;
import com.stioc.cute.tool.ToolNames;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 SearXNG 的联网聚合搜索内置只读工具。
 * <p>
 * 数据源为用户自建的 SearXNG 元搜索服务（聚合 google/bing 等引擎结果），
 * 通过 JSON 接口查询并裁剪为模型友好的精简结果（标题/链接/摘要/引擎命中/排序分），
 * 服务地址与访问令牌走 application.yml 部署级静态配置，不参与系统设置合并。
 * 未配置服务基地址时工具对模型整体不可见。
 * </p>
 */
@Slf4j
@Component
public class SearxngSearchTool implements CuteTool {

    /**
     * 建立连接超时时间（秒）
     */
    private static final long CONNECT_TIMEOUT_SECONDS = 5;

    private final OkHttpClient httpClient;
    private final SearxngProperty property;

    public SearxngSearchTool(SearxngProperty property) {
        this.property = property;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(property.getTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    // ── 1. 标识与域定义 ──

    @Override
    public String getRawName() {
        return ToolNames.WEB_SEARCH;
    }

    // ── 2. 模型元数据定义 ──

    @Override
    public String getDescription() {
        return "基于 SearXNG 的多引擎聚合联网搜索（google/bing 等），返回标题、链接与摘要等精简结果，并附搜索联想词与无响应引擎说明。"
                + "适用于查询时效性信息、外部公开资料等本地代码之外的内容；"
                + "支持 pageno 翻页；当 resultCount 为 0 或结果与查询明显无关时，建议更换关键词重搜。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "搜索关键词"
            },
            "pageno": {
              "type": "integer",
              "description": "结果页码，从 1 开始，可选，默认为 1",
              "default": 1
            }
          },
          "required": ["query"]
        }
        """;
    }

    // ── 3. 生命周期与治理控制 ──

    @Override
    public boolean isAvailable(AgentContext context) {
        // 未配置服务基地址时整体不可用，对模型隐藏该工具
        return StringUtils.isNotBlank(property.getBaseUrl());
    }

    @Override
    public ToolAccessLevel getAccessLevel() {
        // 只读：无外部物理副作用，支持并发调度
        return ToolAccessLevel.READ;
    }

    // ── 4. 并发锁与审计资源 ──

    @Override
    public String getTargetResource(Map<String, Object> arguments, AgentContext context) {
        // 审计关注搜索意图本身，不携带令牌等敏感信息
        return SearxngSearchArgs.from(arguments).query();
    }

    // ── 5. 执行入口 ──

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        SearxngSearchArgs searchArgs = SearxngSearchArgs.from(arguments);
        String query = searchArgs.query();
        if (StringUtils.isBlank(query)) {
            return ToolResult.error("缺少必填参数 query（搜索关键词），请补充后重试");
        }
        int pageno = searchArgs.pageno();

        // 基地址非法时 HttpUrl.parse 返回 null，快速失败给出可行动提示
        HttpUrl base = HttpUrl.parse(property.getBaseUrl());
        if (base == null) {
            return ToolResult.error("SearxngSearchTool 服务基地址配置非法，请检查 application.yml 中 st-cute.search.searxng.base-url");
        }

        HttpUrl.Builder urlBuilder = base.newBuilder()
                .addPathSegment("search")
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("pageno", String.valueOf(pageno));
        if (StringUtils.isNotBlank(property.getToken())) {
            urlBuilder.addQueryParameter("tokens", property.getToken());
        }
        Request request = new Request.Builder().url(urlBuilder.build()).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String bodyText = body != null ? body.string() : null;
            if (!response.isSuccessful() || StringUtils.isBlank(bodyText)) {
                log.warn("SearxngSearchTool 请求失败: code={}, query={}, pageno={}", response.code(), query, pageno);
                return ToolResult.error("搜索服务响应异常（HTTP " + response.code() + "），请稍后重试");
            }

            SearxngSearchResponse resp;
            try {
                resp = JsonKit.parseObject(bodyText, SearxngSearchResponse.class);
            } catch (Exception e) {
                log.warn("SearxngSearchTool 响应解析失败: query={}", query, e);
                return ToolResult.error("搜索服务返回内容无法解析，请稍后重试");
            }

            int count = resp.getResults() != null ? resp.getResults().size() : 0;
            log.info("SearxngSearchTool 搜索完成: query={}, pageno={}, 结果数={}", query, pageno, count);
            return buildSuccessOutput(query, resp);
        } catch (IOException e) {
            log.warn("SearxngSearchTool 请求异常: query={}, pageno={}", query, pageno, e);
            return ToolResult.error("搜索请求失败（网络异常或超时），请稍后重试: " + e.getMessage());
        }
    }

    /**
     * 构建回灌给模型的成功输出（自产 resultCount，空字段整体省略以节省上下文）
     *
     * @param query 原始搜索词
     * @param resp  SearXNG 响应
     * @return 精简结果 JSON 字符串
     */
    private String buildSuccessOutput(String query, SearxngSearchResponse resp) {
        List<SearxngSearchResult> results = resp.getResults();
        int count = results != null ? results.size() : 0;

        JSONObject out = new JSONObject();
        out.put("query", query);
        out.put("resultCount", count);
        out.put("results", results != null ? results : List.of());

        if (resp.getAnswers() != null && !resp.getAnswers().isEmpty()) {
            out.put("answers", resp.getAnswers());
        }
        if (resp.getSuggestions() != null && !resp.getSuggestions().isEmpty()) {
            out.put("suggestions", resp.getSuggestions());
        }
        if (resp.getUnresponsiveEngines() != null && !resp.getUnresponsiveEngines().isEmpty()) {
            // 原始结构 [引擎名, 原因] 数组的数组，拍平为 "引擎名: 原因" 可读形态
            JSONArray flat = new JSONArray();
            for (List<String> pair : resp.getUnresponsiveEngines()) {
                if (pair == null || pair.isEmpty()) {
                    continue;
                }
                flat.add(pair.size() > 1 ? pair.get(0) + ": " + pair.get(1) : pair.get(0));
            }
            if (!flat.isEmpty()) {
                out.put("unresponsiveEngines", flat);
            }
        }
        return out.toJSONString();
    }
}
