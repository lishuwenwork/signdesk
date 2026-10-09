package com.signdesk.common;

public final class Checks {
    private Checks() {}

    public static <T> T found(T value) {
        if (value == null) throw new ApiException(404, "对象不存在");
        return value;
    }

    public static void changed(int rows) {
        if (rows != 1) throw new ApiException(409, "配置已被其他页面更新，请刷新后重试");
    }

    public static String name(String name, int max) {
        if (name == null || name.isBlank() || name.length() > max)
            throw new ApiException("名称长度不正确");
        return name.trim();
    }

    public static boolean flag(Integer value) {
        return Integer.valueOf(1).equals(value);
    }
}
