package com.stioc.cute.platform.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * SQLite LocalDateTime 统一格式化类型处理器。
 * <p>
 * 1. 写入 SQLite: 统一输出固定无 'T' 的微秒格式 (yyyy-MM-dd HH:mm:ss.SSSSSS)，保证库内时间规范整齐。<br>
 * 2. 读取 SQLite: 自适应兼容解析包含 'T'、空格、1~9位纳秒/微秒/毫秒或无小数位的各类历史时间字符串。
 * </p>
 */
@Slf4j
@Component
@MappedTypes(LocalDateTime.class)
public class SqliteLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

    /**
     * 落库统一格式化器：标准空格分隔、严格 6 位微秒
     */
    private static final DateTimeFormatter PRINTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /**
     * 读取自适应解析器：容错支持 'T' 或空格、支持 1~9 位小数秒或无小数秒
     */
    private static final DateTimeFormatter PARSER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd['T'][ ]HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.format(PRINTER));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private LocalDateTime parse(String str) {
        if (str == null || str.isBlank()) {
            return null;
        }
        String trimmed = str.trim();
        try {
            return LocalDateTime.parse(trimmed, PARSER);
        } catch (Exception first) {
            // 二次尝试：把 'T' 归一为空格后再解析（PARSER 已含可选 'T' 分支，
            // 但历史数据可能存在 'T' 与小数位混排等形态，归一后命中率更高）。
            // 若二次仍失败，返回 null 而非继续抛异常：单个字段格式异常不应让整个查询中断
            // （列表类查询一行坏数据即导致整个接口 500，代价远大于该字段为空）
            try {
                String sanitized = trimmed.replace('T', ' ');
                return LocalDateTime.parse(sanitized, PARSER);
            } catch (Exception second) {
                log.warn("时间字段解析失败，已按 null 返回（不影响本次查询）: 原值={}", str);
                return null;
            }
        }
    }
}
