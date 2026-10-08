package com.signdesk.storage;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class InstanceLock implements AutoCloseable {
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;

    public InstanceLock(Path directory) throws Exception {
        this.directory = directory;
        channel =
                FileChannel.open(
                        directory.resolve("instance.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = channel.tryLock();
        } catch (Exception e) {
            channel.close();
            throw new IllegalStateException("Data directory is in use");
        }
        if (acquired == null) {
            channel.close();
            throw new IllegalStateException("Data directory is in use");
        }
        lock = acquired;
    }

    public Path directory() {
        return directory;
    }

    @Override
    public void close() throws Exception {
        lock.release();
        channel.close();
    }
}
