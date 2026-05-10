package com.cpq.common.dto;

/**
 * Pagination clamping helper — prevents DoS via huge {@code size} requests
 * and rejects negative {@code page} indices uniformly across services.
 *
 * <p>Hard cap is 200; default size is 20. Both are applied in service layer
 * just before Panache's {@code .page(...)} call.
 */
public final class Pagination {

    public static final int MAX_PAGE_SIZE = 200;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private Pagination() {}

    public static int clampSize(int size) {
        if (size <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    public static int clampPage(int page) {
        return Math.max(page, 0);
    }
}
