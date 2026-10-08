package com.signdesk.storage;

import cn.hutool.core.util.StrUtil;

import com.signdesk.common.ApiException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small SQL helper for SQLite-specific atomic queue transitions; platform CRUD uses MyBatis-Plus.
 */
@Component
public class Db {
    private final JdbcTemplate jdbc;

    public Db(JdbcTemplate jdbc, DatabaseMigrator migrator) {
        this.jdbc = jdbc;
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args).stream()
                .map(
                        row -> {
                            Map<String, Object> out = new LinkedHashMap<>();
                            row.forEach((k, v) -> out.put(StrUtil.toCamelCase(k), v));
                            return out;
                        })
                .toList();
    }

    public Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> result = rows(sql, args);
        if (result.isEmpty()) throw new ApiException(404, "对象不存在或已被删除");
        return result.getFirst();
    }

    public long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    public static String text(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : v.toString();
    }

    public static int integer(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    public static boolean flag(Map<String, Object> row, String key) {
        return integer(row, key) != 0;
    }
}
