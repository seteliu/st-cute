package com.stioc.cute.mock;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模拟 MCP 协议的消息处理器，完全无状态。
 * 解析 JSON-RPC 2.0 请求，并执行对应的初始化、工具列表获取与工具调用。
 * 采用纯 JDK 正则解析方式实现，无外部第三方 JSON 库依赖，避免在命令行 Stdio 极简环境中出现 ClassNotFoundException。
 */
public class MockMcpHandler {

    /**
     * 响应发送器的回调接口
     */
    @FunctionalInterface
    public interface ResponseSender {
        /**
         * 发送 JSON 字符串响应
         *
         * @param message 响应内容
         */
        void send(String message);
    }

    /**
     * 处理单条 JSON-RPC 请求消息
     *
     * @param input  客户端输入的 JSON 字符串
     * @param sender 用于发送响应的回调
     */
    public void handleMessage(String input, ResponseSender sender) {
        try {
            if (input == null || input.trim().isEmpty()) {
                return;
            }

            // 提取 jsonrpc
            String jsonrpc = extractJsonrpc(input);
            if (!"2.0".equals(jsonrpc)) {
                return;
            }

            // 提取 id 与 method
            String id = extractId(input);
            String method = extractMethod(input);

            if (method == null) {
                return;
            }

            switch (method) {
                case "initialize":
                    handleInitialize(id, sender);
                    break;
                case "notifications/initialized":
                    // 客户端初始化完成通知，无需应答
                    break;
                case "tools/list":
                    handleToolsList(id, sender);
                    break;
                case "tools/call":
                    handleToolsCall(id, input, sender);
                    break;
                default:
                    if (!"null".equals(id)) {
                        sendError(id, -32601, "Method not found: " + method, sender);
                    }
                    break;
            }
        } catch (Exception e) {
            System.err.println("处理 MCP 协议消息时发生异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleInitialize(String id, ResponseSender sender) {
        String response = "{" +
                "\"jsonrpc\":\"2.0\"," +
                "\"id\":" + id + "," +
                "\"result\":{" +
                "\"protocolVersion\":\"2024-11-05\"," +
                "\"capabilities\":{\"tools\":{}}," +
                "\"serverInfo\":{\"name\":\"mock-mcp-server\",\"version\":\"1.0.0\"}" +
                "}" +
                "}";
        sender.send(response);
    }

    private void handleToolsList(String id, ResponseSender sender) {
        String response = "{" +
                "\"jsonrpc\":\"2.0\"," +
                "\"id\":" + id + "," +
                "\"result\":{" +
                "\"tools\":[" +
                "{" +
                "\"name\":\"mock_greet\"," +
                "\"description\":\"向指定的人问好并返回问候语\"," +
                "\"inputSchema\":{" +
                "\"type\":\"object\"," +
                "\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"被问候人的姓名\"}}," +
                "\"required\":[\"name\"]" +
                "}" +
                "}," +
                "{" +
                "\"name\":\"get_system_time\"," +
                "\"description\":\"获取当前服务器系统时间\"," +
                "\"inputSchema\":{" +
                "\"type\":\"object\"," +
                "\"properties\":{}," +
                "\"required\":[]" +
                "}" +
                "}" +
                "]" +
                "}" +
                "}";
        sender.send(response);
    }

    private void handleToolsCall(String id, String input, ResponseSender sender) {
        String toolName = extractToolName(input);
        String response;

        if ("mock_greet".equals(toolName)) {
            String name = extractArgumentName(input);
            if (name == null) {
                name = "陌生人";
            }
            response = "{" +
                    "\"jsonrpc\":\"2.0\"," +
                    "\"id\":" + id + "," +
                    "\"result\":{" +
                    "\"content\":[{\"type\":\"text\",\"text\":\"你好，" + name + "！这是一个来自模拟 MCP 服务的问候。\"}]," +
                    "\"isError\":false" +
                    "}" +
                    "}";
        } else if ("get_system_time".equals(toolName)) {
            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            response = "{" +
                    "\"jsonrpc\":\"2.0\"," +
                    "\"id\":" + id + "," +
                    "\"result\":{" +
                    "\"content\":[{\"type\":\"text\",\"text\":\"当前服务器系统时间为: " + timeStr + "\"}]," +
                    "\"isError\":false" +
                    "}" +
                    "}";
        } else {
            response = "{" +
                    "\"jsonrpc\":\"2.0\"," +
                    "\"id\":" + id + "," +
                    "\"error\":{" +
                    "\"code\":-32602," +
                    "\"message\":\"Unknown tool: " + toolName + "\"" +
                    "}" +
                    "}";
        }

        sender.send(response);
    }

    private void sendError(String id, int code, String message, ResponseSender sender) {
        String response = "{" +
                "\"jsonrpc\":\"2.0\"," +
                "\"id\":" + id + "," +
                "\"error\":{" +
                "\"code\":" + code + "," +
                "\"message\":\"" + message + "\"" +
                "}" +
                "}";
        sender.send(response);
    }

    // ==========================================
    // 正则辅助解析方法
    // ==========================================

    private String extractJsonrpc(String input) {
        Pattern pattern = Pattern.compile("\"jsonrpc\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractId(String input) {
        // id 可以是数字或者带双引号的字符串
        Pattern pattern = Pattern.compile("\"id\"\\s*:\\s*([0-9]+|\"[^\"]*\")");
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "null";
    }

    private String extractMethod(String input) {
        Pattern pattern = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractToolName(String input) {
        // 在 tools/call 请求中，name 处于 params 对象内部
        int paramsIndex = input.indexOf("\"params\"");
        if (paramsIndex != -1) {
            String paramsSubstring = input.substring(paramsIndex);
            Pattern pattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(paramsSubstring);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private String extractArgumentName(String input) {
        // 匹配 arguments 属性对象中 name 参数的值，排除括号外其他可能的字段
        Pattern pattern = Pattern.compile("\"arguments\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
