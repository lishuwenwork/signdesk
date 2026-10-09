package com.signdesk.common;

/** Existing paging bounds; long offsets prevent overflow on large page numbers. */
public record PageQuery(int page, int size) {
    public PageQuery {
        page = Math.max(1, page);
        size = Math.max(1, Math.min(100, size));
    }

    public long offset() {
        return ((long) page - 1) * size;
    }
}
