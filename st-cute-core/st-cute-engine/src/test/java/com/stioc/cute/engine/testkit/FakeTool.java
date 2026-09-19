package com.stioc.cute.engine.testkit;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;

import java.util.Map;

/**
 * 可编程的假工具：供注册中心、Schema 卫兵与执行链路的离线用例使用。
 * <p>
 * 以构造参数而非 Mockito 定制行为，保持引擎测试零字节码增强依赖。
 * </p>
 */
public class FakeTool implements CuteTool {

    private final String rawName;
    private final String domain;
    private final String description;
    private final String argumentSchema;
    private final ToolAccessLevel accessLevel;
    private final boolean available;
    private final String executeResult;
    private final boolean approvalExempt;

    /**
     * 执行次数计数（供并发与批量执行用例断言真实调用次数）
     */
    private int executeCount;

    private FakeTool(Builder builder) {
        this.rawName = builder.rawName;
        this.domain = builder.domain;
        this.description = builder.description;
        this.argumentSchema = builder.argumentSchema;
        this.accessLevel = builder.accessLevel;
        this.available = builder.available;
        this.executeResult = builder.executeResult;
        this.approvalExempt = builder.approvalExempt;
    }

    public static Builder builder(String rawName) {
        return new Builder(rawName);
    }

    /**
     * 快速构造一个 Schema 合法的只读工具
     */
    public static FakeTool readOnly(String rawName) {
        return builder(rawName).build();
    }

    @Override
    public String getDomain() {
        return domain;
    }

    @Override
    public String getRawName() {
        return rawName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getArgumentSchema() {
        return argumentSchema;
    }

    @Override
    public boolean isAvailable(AgentContext context) {
        return available;
    }

    @Override
    public boolean isApprovalExempt() {
        return approvalExempt;
    }

    @Override
    public ToolAccessLevel getAccessLevel() {
        return accessLevel;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        // 记录调用痕迹，供批量执行与并发用例断言
        synchronized (this) {
            executeCount++;
        }
        return executeResult;
    }

    /**
     * 读取当前累计执行次数
     */
    public synchronized int getExecuteCount() {
        return executeCount;
    }

    /**
     * 假工具流式构造器
     */
    public static final class Builder {

        private final String rawName;
        private String domain;
        private String description = "假工具（测试用）";
        private String argumentSchema = """
                {"type":"object","properties":{"text":{"type":"string"}}}
                """;
        private ToolAccessLevel accessLevel = ToolAccessLevel.READ;
        private boolean available = true;
        private String executeResult = "ok";
        private boolean approvalExempt;

        private Builder(String rawName) {
            this.rawName = rawName;
        }

        public Builder domain(String domain) {
            this.domain = domain;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder argumentSchema(String argumentSchema) {
            this.argumentSchema = argumentSchema;
            return this;
        }

        public Builder accessLevel(ToolAccessLevel accessLevel) {
            this.accessLevel = accessLevel;
            return this;
        }

        public Builder available(boolean available) {
            this.available = available;
            return this;
        }

        public Builder executeResult(String executeResult) {
            this.executeResult = executeResult;
            return this;
        }

        public Builder approvalExempt(boolean approvalExempt) {
            this.approvalExempt = approvalExempt;
            return this;
        }

        public FakeTool build() {
            return new FakeTool(this);
        }
    }
}
