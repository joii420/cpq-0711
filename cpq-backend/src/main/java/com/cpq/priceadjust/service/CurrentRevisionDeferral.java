package com.cpq.priceadjust.service;

import java.util.function.Supplier;

/**
 * repair-260918 B-4③ · "defer the current-period revision snapshot" option of
 * {@link MaterialVersionUpgradeService#upgrade}, scoped to the current thread.
 *
 * <p>Only the grouped job path ({@link PriceAdjustJobExecutionService#executeJob}) turns it on, around each
 * item of a quotation group; the group's snapshot is then written once, after every row of that quotation
 * has committed. Everywhere else (single retry, admin endpoint, dry runs) it is off ⇒ default = legacy
 * behaviour (S9 inside {@code upgrade()}'s own transaction).
 *
 * <p>Why a thread-scoped flag instead of another {@code upgrade(...)} overload: the job path keeps calling the
 * long-standing 4-argument overload, so any stub / spy placed on any {@code upgrade} overload (the test seam
 * named in test.md §3.2) still intercepts the grouped path. Static on purpose — it must keep working when the
 * upgrade bean itself is replaced by a mock.
 */
public final class CurrentRevisionDeferral {

    private static final ThreadLocal<Boolean> DEFERRED = new ThreadLocal<>();

    private CurrentRevisionDeferral() { }

    /** Runs {@code body} with the current-period snapshot deferred; always restores the previous state. */
    public static <T> T callDeferred(Supplier<T> body) {
        Boolean previous = DEFERRED.get();
        DEFERRED.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            if (previous == null) DEFERRED.remove(); else DEFERRED.set(previous);
        }
    }

    /** {@code true} while inside {@link #callDeferred}. */
    public static boolean isDeferred() {
        return Boolean.TRUE.equals(DEFERRED.get());
    }
}
