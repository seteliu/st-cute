package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.engine.tool.CuteTool;
import com.stioc.cute.engine.tool.types.ToolAccessLevel;
import com.stioc.cute.engine.tool.types.ToolArgs;
import com.stioc.cute.engine.tool.types.ToolExecutionContext;
import com.stioc.cute.engine.tool.types.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 获取系统当前真实时间与日期信息的内置只读工具。
 * 解决大模型无自带时钟导致的当前时间感知幻觉问题。
 */
@Slf4j
@Component
public class GetTimeTool implements CuteTool {

    private static final String DEFAULT_FORMAT = "yyyy-MM-dd HH:mm:ss";

    @Override
    public String getRawName() {
        return ToolNames.GET_TIME;
    }

    @Override
    public String getDescription() {
        return "【安全核心工具】获取当前系统的实时真实时间与日期。默认返回 'yyyy-MM-dd HH:mm:ss' 格式的当前时间纯文本；支持 format 参数自定义格式、detailed 参数返回结构化详情（时间戳、时区、星期等）。";
    }

    @Override
    public String getArgumentSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "format": {
              "type": "string",
              "description": "时间日期格式化模板，遵循标准 Java DateTimeFormatter 语法（例如 'yyyy-MM-dd HH:mm:ss'、'yyyy-MM-dd'、'HH:mm:ss'），可选，默认为 'yyyy-MM-dd HH:mm:ss'",
              "default": "yyyy-MM-dd HH:mm:ss"
            },
            "detailed": {
              "type": "boolean",
              "description": "是否返回详细的结构化信息（包含时间戳、时区、星期等），可选，默认为 false",
              "default": false
            }
          }
        }
        """;
    }

    @Override
    public ToolAccessLevel getAccessLevel() {
        // 只读：无外部副作用，支持并发调度
        return ToolAccessLevel.READ;
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        ToolArgs args = ToolArgs.of(arguments);
        String format = args.getStringTrimmed("format", DEFAULT_FORMAT);
        Boolean detailedVal = args.getBoolean("detailed");
        boolean detailed = detailedVal != null && detailedVal;

        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(format);
        } catch (IllegalArgumentException e) {
            log.warn("GetTimeTool 格式化模板非法: {}", format, e);
            return ToolResult.error("非法的格式化模板: '" + format + "'，原因: " + e.getMessage());
        }

        String formattedText = now.format(formatter);
        if (!detailed) {
            log.info("GetTimeTool 纯文本输出当前时间: {}", formattedText);
            return formattedText;
        }

        JSONObject result = new JSONObject();
        result.put("formatted", formattedText);
        result.put("pattern", format);
        result.put("timestamp", now.toInstant().toEpochMilli());
        result.put("timezone", now.getZone().getId());
        result.put("dayOfWeek", now.getDayOfWeek().name());
        result.put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        log.info("GetTimeTool JSON 结构化输出当前时间: {}", formattedText);
        return result.toJSONString();
    }
}
