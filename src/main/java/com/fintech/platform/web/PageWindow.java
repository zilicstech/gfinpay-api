package com.fintech.platform.web;

/** Bounds list endpoints so a missing filter cannot return an entire table. */
public final class PageWindow {

    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 500;

    private PageWindow() {}

    public static int limit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    public static int offset(Integer requested) {
        if (requested == null || requested < 0) {
            return 0;
        }
        return requested;
    }
}
