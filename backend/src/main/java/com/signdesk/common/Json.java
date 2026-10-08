package com.signdesk.common;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

public final class Json {
    private Json() {}

    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String value, Class<T> type) {
        return MAPPER.readValue(value, type);
    }

    public static JsonNode tree(String value) {
        return MAPPER.readTree(value);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(String value) {
        return MAPPER.readValue(value, Map.class);
    }
}
