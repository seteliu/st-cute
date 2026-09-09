package com.stioc.cute.platform.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

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
        try {
            return LocalDateTime.parse(str.trim(), PARSER);
        } catch (Exception e) {
            String sanitized = str.trim().replace('T', ' ');
            return LocalDateTime.parse(sanitized, PARSER);
        }
    }
}
