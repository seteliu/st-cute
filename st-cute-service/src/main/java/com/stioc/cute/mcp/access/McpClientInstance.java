package com.stioc.cute.mcp.access;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.stioc.cute.mcp.McpServerConfig;
import com.stioc.cute.tool.McpCuteTool;
import com.stioc.cute.tool.access.CuteTool;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * 托管单个 stdio（本地进程）或原生 SSE（远端 HTTP 服务）MCP 服务器的客户端双向通信实例
 */
@Slf4j
public class McpClientInstance {

    /**
     * MCP 节点名称
     */
    @Getter
    private final String name;

    /**
     * 节点启动参数配置
     */
    @Getter
    private final McpServerConfig config;

    /**
     * 工作目录绝对路径
     */
    @Getter
    private final String cwd;

    /**
     * 是否为原生 SSE 模式连接
     */
    @Getter
    private boolean sse = false;

    /**
     * 原生 SSE 连接时的 HTTP 客户端
     */
    private HttpClient httpClient;

    /**
     * 服务端动态推送的 POST 接收消息端点 URL
     */
    private volatile String ssePostUrl;

    /**
     * 子进程实例（仅限 Stdio 模式）
     */
    private Process process;

    /**
     * 标准输入写入流（仅限 Stdio 模式）
     */
    private BufferedWriter stdinWriter;

    /**
     * 标准输出读取流（仅限 Stdio 模式）
     */
    private BufferedReader stdoutReader;

    /**
     * 标准错误读取流（仅限 Stdio 模式）
     */
    private BufferedReader stderrReader;

    /**
     * 虚拟线程池执行服务
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 挂起的请求回调 Map
     */
    private final Map<String, CompletableFuture<JSONObject>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 自增消息序列号 ID
     */
    private int messageIdSequence = 1;

    /**
     * 客户端连接状态
     */
    @Getter
    private volatile String status = "OFFLINE"; // OFFLINE, CONNECTING, RUNNING

    /**
     * 暴露给本系统的工具列表
     */
    @Getter
    private volatile List<CuteTool> exposedTools = new ArrayList<>();

    /**
     * 工具集发生变更时的回调 Runnable
     */
    @Setter
    private Runnable onToolsChangedCallback;

    /**
     * 构造 MCP 客户端实例
     */
    public McpClientInstance(String name, McpServerConfig config, String cwd) {
        this.name = name;
        this.config = config;
        this.cwd = cwd;
    }

    /**
     * 启动原生 SSE 连接或拉起本地 stdio 子进程并开启 I/O 读取循环
     */
    public synchronized void start() throws Exception {
        status = "CONNECTING";
        String command = config.getCommand();

        if (command != null && (command.startsWith("http://") || command.startsWith("https://"))) {
            this.sse = true;
            this.httpClient = HttpClient.newBuilder().build();
            log.info("[MCP Client {}] 检测到 URL 启动参数，将使用原生 Java SSE 方式连接远端服务: {}", name, command);

            // 1. 发起 SSE GET 连接请求
            HttpRequest sseRequest = HttpRequest.newBuilder()
                    .uri(URI.create(command))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build();

            // 2. 异步处理连接响应流
            CompletableFuture<Void> connectFuture = new CompletableFuture<>();
            httpClient.sendAsync(sseRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(response -> {
                        executor.submit(() -> readSseLoop(response.body(), connectFuture));
                    })
                    .exceptionally(ex -> {
                        connectFuture.completeExceptionally(ex);
                        return null;
                    });

            try {
                // 等待 SSE 的 endpoint 事件上报以确定 POST 传输路径，限时 5 秒
                connectFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                status = "OFFLINE";
                throw new IOException("与原生 MCP SSE 连接建立超时或失败: " + ex.getMessage(), ex);
            }
        } else {
            // 标准 Stdio 子进程拉起逻辑
            log.info("[MCP Client {}] 正在启动本地进程: {} {}", name, command, String.join(" ", config.getArgs() != null ? config.getArgs() : List.of()));

            List<String> cmd = new ArrayList<>();
            cmd.add(command);
            if (config.getArgs() != null) {
                cmd.addAll(config.getArgs());
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (config.getEnv() != null) {
                pb.environment().putAll(config.getEnv());
            }
            if (cwd != null && !cwd.isBlank()) {
                pb.directory(new File(cwd));
            }

            process = pb.start();

            // 绑定标准流
            stdinWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));

            // 启动异步读取 stdout 线程
            executor.submit(this::readLoop);
            // 启动异步读取 stderr 线程
            executor.submit(this::stderrLoop);
        }

        // 执行握手交互
        try {
            initAndHandshake();
            status = "RUNNING";
        } catch (Exception e) {
            status = "OFFLINE";
            shutdown();
            throw new IOException("与 MCP 服务握手失败: " + e.getMessage(), e);
        }
    }

    /**
     * 向服务器端发送 JSON-RPC 2.0 请求，并挂起等待回复
     */
    public CompletableFuture<JSONObject> sendRequest(String method, JSONObject params) {
        String id = String.valueOf(messageIdSequence++);
        JSONObject req = new JSONObject();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) {
            req.put("params", params);
        }

        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            String json = req.toJSONString();
            log.debug("[MCP Client {}] 发送 JSON-RPC: {}", name, json);
            if (sse) {
                if (ssePostUrl == null) {
                    throw new IOException("原生 SSE POST 发送通道尚未建立");
                }
                HttpRequest postRequest = HttpRequest.newBuilder()
                        .uri(URI.create(ssePostUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                httpClient.sendAsync(postRequest, HttpResponse.BodyHandlers.discarding())
                        .exceptionally(ex -> {
                            log.error("[MCP Client {}] 原生 SSE 转发 POST 请求错误", name, ex);
                            pendingRequests.remove(id);
                            future.completeExceptionally(ex);
                            return null;
                        });
            } else {
                synchronized (this) {
                    if (stdinWriter != null) {
                        stdinWriter.write(json);
                        stdinWriter.newLine();
                        stdinWriter.flush();
                    } else {
                        throw new IOException("连接已关闭");
                    }
                }
            }
        } catch (Exception e) {
            pendingRequests.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 发送 JSON-RPC 2.0 通知帧（不需要回复）
     */
    public void sendNotification(String method, JSONObject params) {
        JSONObject notification = new JSONObject();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.put("params", params);
        }
        try {
            String json = notification.toJSONString();
            log.debug("[MCP Client {}] 发送通知: {}", name, json);
            if (sse) {
                if (ssePostUrl != null) {
                    HttpRequest postRequest = HttpRequest.newBuilder()
                            .uri(URI.create(ssePostUrl))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                            .build();
                    httpClient.sendAsync(postRequest, HttpResponse.BodyHandlers.discarding());
                }
            } else {
                synchronized (this) {
                    if (stdinWriter != null) {
                        stdinWriter.write(json);
                        stdinWriter.newLine();
                        stdinWriter.flush();
                    }
                }
            }
        } catch (Exception e) {
            log.error("[MCP Client {}] 发送通知失败", name, e);
        }
    }

    /**
     * 执行 MCP 握手流程
     */
    private void initAndHandshake() throws Exception {
        // 1. 发送 initialize
        JSONObject initParams = new JSONObject();
        initParams.put("protocolVersion", "2024-11-05");

        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "st-cute");
        clientInfo.put("version", "1.0.0");
        initParams.put("clientInfo", clientInfo);
        initParams.put("capabilities", new JSONObject());

        // 10秒握手超时
        JSONObject response = sendRequest("initialize", initParams).get(10, TimeUnit.SECONDS);
        log.info("[MCP Client {}] 初始化成功，响应结果: {}", name, response);

        // 2. 发送 notifications/initialized
        sendNotification("notifications/initialized", null);

        // 3. 初始同步工具列表
        refreshTools();
    }

    /**
     * 刷新拉取 MCP 服务暴露的所有工具列表
     */
    public void refreshTools() throws Exception {
        JSONObject response = sendRequest("tools/list", null).get(10, TimeUnit.SECONDS);
        JSONObject result = response.getJSONObject("result");
        List<CuteTool> list = new ArrayList<>();
        if (result != null && result.containsKey("tools")) {
            JSONArray toolsArr = result.getJSONArray("tools");
            for (int i = 0; i < toolsArr.size(); i++) {
                JSONObject tObj = toolsArr.getJSONObject(i);
                String tName = tObj.getString("name");
                String tDesc = tObj.getString("description");
                JSONObject tSchema = tObj.getJSONObject("inputSchema");

                String schemaStr = tSchema != null ? tSchema.toJSONString() : "{\"type\":\"object\"}";
                // 自动对 MCP 工具加上前缀，防止命名冲突
                list.add(new McpCuteTool(name + "_" + tName, tDesc, schemaStr, tName, this));
            }
        }
        this.exposedTools = Collections.unmodifiableList(list);
        log.info("[MCP Client {}] 已装载外部工具 {} 个", name, list.size());
    }

    /**
     * 远程调用 MCP 具体的某个工具
     */
    public String executeTool(String originalToolName, Map<String, Object> arguments) {
        JSONObject params = new JSONObject();
        params.put("name", originalToolName);
        params.put("arguments", arguments != null ? arguments : new HashMap<>());

        try {
            // 设置 60秒工具执行超时限制
            JSONObject response = sendRequest("tools/call", params).get(60, TimeUnit.SECONDS);
            if (response.containsKey("error")) {
                return "{\"error\": \"MCP服务端返回错误: " + response.getJSONObject("error").toJSONString() + "\"}";
            }
            JSONObject result = response.getJSONObject("result");
            if (result != null) {
                boolean isError = result.getBooleanValue("isError");
                JSONArray content = result.getJSONArray("content");
                StringBuilder sb = new StringBuilder();
                if (content != null) {
                    for (int i = 0; i < content.size(); i++) {
                        JSONObject item = content.getJSONObject(i);
                        if (item != null && "text".equals(item.getString("type"))) {
                            sb.append(item.getString("text"));
                        }
                    }
                }
                if (isError) {
                    return "{\"error\": \"" + sb.toString() + "\"}";
                }
                return sb.toString();
            }
            return "{\"success\": false, \"message\": \"没有返回有效result内容\"}";
        } catch (Exception e) {
            log.error("[MCP Client {}] 执行工具 {} 失败", name, originalToolName, e);
            return "{\"error\": \"MCP客户端通信或执行发生异常: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 处理读取到的任意一行 JSON-RPC 回复报文，并完成对应的 Future
     */
    private void processIncomingJson(String line) {
        try {
            JSONObject obj = JSON.parseObject(line);
            if (obj == null) return;

            if (obj.containsKey("id")) {
                String id = obj.getString("id");
                CompletableFuture<JSONObject> future = pendingRequests.remove(id);
                if (future != null) {
                    future.complete(obj);
                }
            } else if (obj.containsKey("method")) {
                String method = obj.getString("method");
                log.debug("[MCP Client {}] 收到服务端通知事件: {}", name, method);
                if ("notifications/tools/list-changed".equals(method)) {
                    if (onToolsChangedCallback != null) {
                        onToolsChangedCallback.run();
                    }
                }
            }
        } catch (Exception e) {
            log.error("[MCP Client {}] 解析 JSON-RPC 数据帧错误: {}", name, line, e);
        }
    }

    /**
     * 阻塞读取 Stdio 标准输出循环
     */
    private void readLoop() {
        try {
            String line;
            while ((line = stdoutReader.readLine()) != null) {
                if (line.isBlank()) continue;
                log.debug("[MCP 客户端 {} 标准输出] {}", name, line);
                processIncomingJson(line);
            }
        } catch (IOException e) {
            log.warn("[MCP 客户端 {}] 标准输出读取线程已关闭", name);
        } finally {
            status = "OFFLINE";
        }
    }

    /**
     * 阻塞读取 SSE 输入流消息循环
     */
    private void readSseLoop(InputStream sseStream, CompletableFuture<Void> connectFuture) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(sseStream, StandardCharsets.UTF_8))) {
            String line;
            String currentEvent = "";
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    currentEvent = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if ("endpoint".equals(currentEvent)) {
                        this.ssePostUrl = data;
                        log.info("[MCP Client {}] 原生 SSE 连接成功解析 POST 接收消息端点: {}", name, data);
                        connectFuture.complete(null);
                    } else if ("message".equals(currentEvent)) {
                        log.debug("[MCP 客户端 {} 原生 SSE 标准输出] {}", name, data);
                        processIncomingJson(data);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[MCP 客户端 {}] 原生 SSE 接收线程读取关闭: {}", name, e.getMessage());
            connectFuture.completeExceptionally(e);
        } finally {
            status = "OFFLINE";
        }
    }

    /**
     * 阻塞读取 Stdio 标准错误循环
     */
    private void stderrLoop() {
        try {
            String line;
            while ((line = stderrReader.readLine()) != null) {
                log.warn("[MCP 服务端 {} 标准错误] {}", name, line);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 强行销毁与关闭本地进程或释放客户端资源
     */
    public synchronized void shutdown() {
        status = "OFFLINE";
        log.info("[MCP Client {}] 正在销毁与关闭客户端...", name);
        try {
            if (stdinWriter != null) {
                try {
                    stdinWriter.close();
                } catch (Exception e) {}
                stdinWriter = null;
            }
            if (stdoutReader != null) {
                try {
                    stdoutReader.close();
                } catch (Exception e) {}
                stdoutReader = null;
            }
            if (stderrReader != null) {
                try {
                    stderrReader.close();
                } catch (Exception e) {}
                stderrReader = null;
            }
            if (process != null && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                process = null;
            }
        } catch (Exception e) {
            log.error("[MCP Client {}] 关闭进程发生异常", name, e);
        } finally {
            executor.shutdownNow();
            pendingRequests.clear();
            httpClient = null;
            ssePostUrl = null;
        }
    }
}
