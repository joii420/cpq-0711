package com.cpq.priceadjust.service;

import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * repair-260918 B-6 · turns an exception thrown by an update-job item / a budget dry run into something a person
 * can read, instead of the CDI wrapper text {@code "Error invoking subclass method"}.
 *
 * <p>Walks the {@link Throwable#getCause()} chain:
 * <ul>
 *   <li>transaction timeout / statement cancel — any link is a {@code jakarta.transaction.RollbackException} whose
 *       message contains {@code ARJUNA016102}, or a {@link SQLException} with SQLState {@code 57014}, or any
 *       message contains {@code transaction is not active} / {@code Transaction timeout}
 *       ⇒ {@code EXECUTION_TIMEOUT};</li>
 *   <li>anything else ⇒ {@code UNEXPECTED_ERROR} with {@code "<root cause simple class name>: <root cause message>"}.</li>
 * </ul>
 */
public final class PriceAdjustFailureTranslator {

    public static final String EXECUTION_TIMEOUT = "EXECUTION_TIMEOUT";
    public static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";
    public static final String REVISION_WRITE_FAILED = "REVISION_WRITE_FAILED";
    public static final String EXECUTION_INTERRUPTED = "EXECUTION_INTERRUPTED";

    public static final String MSG_EXECUTION_TIMEOUT = "执行超时（超过 60 秒）被中止，本行未更新，可重试";
    public static final String MSG_REVISION_WRITE_FAILED = "价格已更新，但版本记录写入失败，请重试";
    public static final String MSG_EXECUTION_INTERRUPTED = "执行中断（服务重启），本行未更新，可重试";
    public static final String MSG_BUDGET_TIMEOUT = "预算试算超时（超过 60 秒）";

    private PriceAdjustFailureTranslator() { }

    /** {@code [errorCode, errorMessage]} for a job item. */
    public static String[] forJobItem(Throwable t) {
        if (isTimeout(t)) return new String[]{EXECUTION_TIMEOUT, MSG_EXECUTION_TIMEOUT};
        return new String[]{UNEXPECTED_ERROR, rootCauseText(t)};
    }

    /** {@code budget_error} text for a failed budget dry run. */
    public static String forBudget(Throwable t) {
        return isTimeout(t) ? MSG_BUDGET_TIMEOUT : rootCauseText(t);
    }

    public static boolean isTimeout(Throwable t) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable c = t; c != null && seen.put(c, Boolean.TRUE) == null; c = c.getCause()) {
            String msg = c.getMessage();
            if (c instanceof jakarta.transaction.RollbackException && msg != null && msg.contains("ARJUNA016102")) {
                return true;
            }
            if (c instanceof SQLException && "57014".equals(((SQLException) c).getSQLState())) {
                return true;
            }
            if (msg != null) {
                String lower = msg.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("transaction is not active") || lower.contains("transaction timeout")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code "<root cause simple class name>: <root cause message>"}. */
    public static String rootCauseText(Throwable t) {
        if (t == null) return "未知错误";
        Throwable root = t;
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        seen.put(root, Boolean.TRUE);
        while (root.getCause() != null && seen.put(root.getCause(), Boolean.TRUE) == null) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + ": " + (msg != null ? msg : "(无详细信息)");
    }
}
