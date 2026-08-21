package com.stioc.cute.tool;

import com.alibaba.fastjson2.JSONObject;
import com.stioc.cute.tool.access.CuteTool;
import com.stioc.cute.tool.access.ToolExecutionContext;
import com.stioc.cute.tool.access.ToolNames;
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
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getName() {
        return ToolNames.GET_TIME;
    }

    @Override
    public String getDescription() {
        return "【安全核心工具】获取当前系统的实时真实时间与日期。默认返回 'yyyy-MM-dd HH:mm:ss' 格式的当前时间纯文本；支持通过 format 参数自定义格式模板（如 'yyyy-MM-dd'、'HH:mm:ss'）；当 detailed 为 true 时返回包含格式化时间、时间戳、时区、星期等完整信息的 JSON 对象。";
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
    public String execute(Map<String, Object> arguments, ToolExecutionContext context) {
        String format = DEFAULT_FORMAT;
        boolean detailed = false;

        if (arguments != null) {
            Object formatObj = arguments.get("format");
            if (formatObj instanceof String str && !str.isBlank()) {
                format = str.trim();
            }
            Object detailedObj = arguments.get("detailed");
            if (detailedObj instanceof Boolean b) {
                detailed = b;
            } else if (detailedObj instanceof String s) {
                detailed = Boolean.parseBoolean(s.trim());
            }
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(format);
        } catch (IllegalArgumentException e) {
            log.warn("GetTimeTool 格式化模板非法: {}", format, e);
            return new JSONObject()
                    .fluentPut("error", "非法的格式化模板: '" + format + "'，原因: " + e.getMessage())
                    .toJSONString();
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
