package com.signdesk.domain.vo;

import java.util.List;

/** API envelope only; do not serialize MP Page or include detail bodies in list items. */
public record PageVo<T>(List<T> items, long total, int page, int size) {
    @Override
    public String toString() {
        return "PageVo[total=" + total + ", page=" + page + ", size=" + size + "]";
    }
}
