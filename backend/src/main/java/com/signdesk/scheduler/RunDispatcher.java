package com.signdesk.scheduler;

import com.signdesk.service.*;

import jakarta.annotation.PreDestroy;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

@Component
public class RunDispatcher {
    private final IRunService runs;
    private final ISettingsService settings;
    private final RunCoordinator coordinator;
    private final PlatformWorker worker;
    private final ExecutorService workers =
            Executors.newFixedThreadPool(
                    8,
                    r -> {
                        var thread = new Thread(r, "platform-worker");
                        thread.setDaemon(true);
                        return thread;
                    });

    public RunDispatcher(
            IRunService runs,
            ISettingsService settings,
            RunCoordinator coordinator,
            PlatformWorker worker) {
        this.runs = runs;
        this.settings = settings;
        this.coordinator = coordinator;
        this.worker = worker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        runs.recover();
        coordinator.recovered();
    }

    @Scheduled(fixedDelay = 500)
    public void dispatch() {
        if (!coordinator.ready()) return;
        int cap = settings.query().concurrency();
        for (var batch : runs.queuedBatches()) {
            if (!coordinator.claim(
                    batch.getPlatformId(), cap, () -> runs.claimBatch(batch.getId()))) continue;
            workers.submit(
                    () -> {
                        try {
                            worker.execute(batch);
                        } catch (Exception e) {
                            org.slf4j.LoggerFactory.getLogger(RunDispatcher.class)
                                    .error(
                                            "Queue operation failed ({})",
                                            e.getClass().getSimpleName());
                        } finally {
                            try {
                                runs.finishBatch(batch);
                            } catch (RuntimeException e) {
                                coordinator.unavailable();
                            }
                            coordinator.release(batch.getPlatformId());
                        }
                    });
        }
    }

    @PreDestroy
    public void close() throws InterruptedException {
        coordinator.stop();
        workers.shutdown();
        if (!workers.awaitTermination(25, TimeUnit.SECONDS)) workers.shutdownNow();
    }
}
