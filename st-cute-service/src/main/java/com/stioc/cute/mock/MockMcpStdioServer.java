package com.stioc.cute.mock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 模拟 MCP 服务的 Stdio 启动类。
 * 提供静态启动入口，专门用于标准输入输出方式的交互。
 */
public class MockMcpStdioServer {

    /**
     * 启动 Stdio 模式的消息循环监听。
     * 该方法会阻塞当前调用线程。
     */
    public static void startStdio() {
        System.err.println("MCP Stdio 模拟服务启动，正在监听标准输入...");
        MockMcpHandler handler = new MockMcpHandler();

        // 强行使用 UTF-8 编码包装 System.out，解决 Windows 默认字符集（如 GBK）导致的输出乱码问题
        PrintStream utf8Out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                handler.handleMessage(line, responseJson -> {
                    // 使用 UTF-8 打印响应包并刷新
                    utf8Out.println(responseJson);
                    utf8Out.flush();
                });
            }
        } catch (IOException e) {
            System.err.println("MCP Stdio 读取异常: " + e.getMessage());
        } finally {
            System.err.println("MCP Stdio 模拟服务已停止。");
        }
    }

    public static void main(String[] args) {
        startStdio();
    }
}
