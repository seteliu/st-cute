package com.stioc.cute.engine.prompt;

import com.stioc.cute.engine.loop.core.AgentContext;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 引擎内置默认环境段贡献者：os / 日期。
 * <p>
 * 仅提供跨宿主通用的系统属性级基础环境信息（时间取天级以提升提示词缓存命中率）。
 * 工作目录、Git 分支、权限模式、活动 Shell、模型名等平台相关环境由宿主 EnvironmentContributor 贡献。
 * </p>
 */
public class DefaultEnvPromptContributor implements SystemPromptContributor {

    @Override
    public int order() {
        return 50;
    }

    @Override
    public String contribute(AgentContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前运行系统环境上下文】\n");
        sb.append("- 操作系统平台: ").append(System.getProperty("os.name"))
                .append(" (").append(System.getProperty("os.arch")).append(")\n");
        sb.append("- 系统当前时间: ").append(new SimpleDateFormat("yyyy-MM-dd").format(new Date())).append("\n");
        return sb.toString();
    }
}
