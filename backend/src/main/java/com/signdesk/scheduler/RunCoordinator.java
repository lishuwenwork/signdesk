package com.signdesk.scheduler;

import com.signdesk.common.ApiException;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.*;

/**
 * One in-process exclusion boundary for enqueue, dispatch and maintenance. Never holds network I/O.
 */
@Component
public class RunCoordinator {
    private final Set<String> platforms = new HashSet<>();
    private volatile boolean ready, closed;
    private boolean maintenance;
    private long maintenanceGeneration;

    public synchronized <T> T enqueue(Supplier<T> operation) {
        if (maintenance) throw new ApiException(409, "正在维护配置，请稍后再执行");
        if (closed || !ready) throw new ApiException(409, "执行队列尚未就绪");
        return operation.get();
    }

    /** Capture before reading any plan; a configuration replacement invalidates the whole scan. */
    public synchronized OptionalLong scanGeneration() {
        return maintenance || !ready || closed
                ? OptionalLong.empty()
                : OptionalLong.of(maintenanceGeneration);
    }

    public synchronized void automatic(long scanGeneration, Runnable operation) {
        if (!maintenance && ready && !closed && scanGeneration == maintenanceGeneration)
            operation.run();
    }

    public synchronized boolean claim(String platform, int limit, BooleanSupplier claim) {
        if (!ready
                || closed
                || maintenance
                || platforms.size() >= limit
                || platforms.contains(platform)) return false;
        if (!claim.getAsBoolean()) return false;
        platforms.add(platform);
        return true;
    }

    public synchronized void release(String platform) {
        platforms.remove(platform);
    }

    public synchronized void beginMaintenance(BooleanSupplier hasActiveItems) {
        if (maintenance || !platforms.isEmpty() || hasActiveItems.getAsBoolean())
            throw new ApiException(409, "请等待队列空闲后再导入或恢复");
        // Do not reuse a scan that crossed replacement, even after an immediate unpause and
        // even when import rebuilt the same plan revision/effective time. Failed imports also
        // invalidate scans conservatively. No token can be captured while maintenance is active.
        maintenanceGeneration++;
        maintenance = true;
    }

    public synchronized void endMaintenance() {
        maintenance = false;
    }

    public boolean ready() {
        return ready && !closed;
    }

    public boolean closed() {
        return closed;
    }

    public void recovered() {
        ready = true;
    }

    public void unavailable() {
        ready = false;
    }

    public void stop() {
        closed = true;
    }
}
