package com.stioc.cute.runtime.tool;

import com.stioc.cute.engine.loop.core.AgentContext;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.ToolGuard;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolPermissionVerdict;
import com.stioc.cute.permission.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎 ToolGuard SPI 宿主实现桥接测试。
 * 验证 ToolGuardImpl 正确包装并向 PermissionService 委托安全裁决。
 */
class ToolGuardImplTest {

    @Test
    @DisplayName("ToolGuardImpl 将 evaluate 调用精准委托给 PermissionService.evaluateVerdict")
    void delegatesToPermissionService() throws Exception {
        ToolGuardImpl toolGuard = new ToolGuardImpl();

        AtomicBoolean delegated = new AtomicBoolean(false);
        ToolPermissionVerdict mockVerdict = ToolPermissionVerdict.allow();

        PermissionService spyService = new PermissionService() {
            @Override
            public ToolPermissionVerdict evaluateVerdict(CuteTool tool, Map<String, Object> arguments, AgentContext context) {
                delegated.set(true);
                return mockVerdict;
            }
        };

        Field field = ToolGuardImpl.class.getDeclaredField("permissionService");
        field.setAccessible(true);
        field.set(toolGuard, spyService);

        CuteTool dummyTool = new CuteTool() {
            @Override
            public String getRawName() {
                return "test_tool";
            }

            @Override
            public String getDescription() {
                return "test";
            }

            @Override
            public String getArgumentSchema() {
                return "{}";
            }

            @Override
            public ToolAccessLevel getAccessLevel() {
                return ToolAccessLevel.READ;
            }

            @Override
            public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
                return "";
            }
        };

        AgentContext context = new AgentContext(123L, null, null);
        ToolPermissionVerdict result = toolGuard.evaluate(dummyTool, Map.of("k", "v"), context);

        assertTrue(delegated.get(), "应当调用 PermissionService.evaluateVerdict");
        assertNotNull(result);
        assertTrue(result.isAllow());
    }
}
