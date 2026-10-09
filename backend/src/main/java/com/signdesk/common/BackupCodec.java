package com.signdesk.common;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;

/** Strict new-file decoding is scoped to backups, not the established management APIs. */
public final class BackupCodec {
    private BackupCodec() {}

    private static final JsonMapper MAPPER =
            JsonMapper.builder()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .withCoercionConfig(
                            String.class,
                            coercion ->
                                    coercion.setCoercion(
                                                    CoercionInputShape.Integer, CoercionAction.Fail)
                                            .setCoercion(
                                                    CoercionInputShape.Float, CoercionAction.Fail)
                                            .setCoercion(
                                                    CoercionInputShape.Boolean,
                                                    CoercionAction.Fail))
                    .build();

    public static <T> T read(InputStream input, Class<T> type) {
        try {
            T value = MAPPER.readValue(input, type);
            if (value == null) throw new IllegalArgumentException();
            return value;
        } catch (Exception e) {
            throw new ApiException("配置文件格式或字段不正确，仅支持 signdesk-plain-v1");
        }
    }
}
