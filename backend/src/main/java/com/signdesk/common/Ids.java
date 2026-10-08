package com.signdesk.common;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;

public final class Ids {
    private Ids() {}

    public static String next() {
        return IdWorker.getIdStr();
    }
}
