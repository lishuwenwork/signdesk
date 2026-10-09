package com.signdesk.service.impl;

import com.signdesk.common.ApiException;
import com.signdesk.mapper.QueueMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
final class CatalogGuard {
    private final QueueMapper queue;

    void platform(String id) {
        check(queue.activeForPlatform(id));
    }

    void account(String id) {
        check(queue.activeForAccount(id));
    }

    void request(String id) {
        check(queue.activeForRequest(id));
    }

    private void check(long count) {
        if (count > 0) throw new ApiException(409, "存在排队或运行中的任务，请先取消并等待结束");
    }
}
