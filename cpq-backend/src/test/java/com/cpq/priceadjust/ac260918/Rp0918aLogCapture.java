package com.cpq.priceadjust.ac260918;

import org.jboss.logmanager.ExtLogRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 进程内日志抓取：往 JUL 根 logger 挂一个 handler（与 {@code SubmitApproveBackfillTest} 同一做法）。
 * AC-9 ④ / AC-10 / AC-13 / AC-18 / AC-22 的可观测量就是后端日志行，本类把它们变成可断言的数据。
 */
public final class Rp0918aLogCapture implements AutoCloseable {

    public record Line(long millis, int levelValue, String level, String logger, String thread,
                       String message, Throwable thrown) {
        public boolean isError() {
            return levelValue >= Level.SEVERE.intValue();
        }

        public String throwableChain() {
            StringBuilder sb = new StringBuilder();
            Throwable t = thrown;
            int guard = 0;
            while (t != null && guard++ < 20) {
                sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" <- ");
                t = t.getCause();
            }
            return sb.toString();
        }
    }

    private final List<Line> lines = Collections.synchronizedList(new ArrayList<>());
    private final SimpleFormatter fallbackFormatter = new SimpleFormatter();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord r) {
            String msg;
            try {
                msg = (r instanceof ExtLogRecord ext) ? ext.getFormattedMessage() : fallbackFormatter.formatMessage(r);
            } catch (RuntimeException e) {
                msg = String.valueOf(r.getMessage());
            }
            lines.add(new Line(r.getMillis(), r.getLevel().intValue(), r.getLevel().getName(), r.getLoggerName(),
                Thread.currentThread().getName(), msg == null ? "" : msg, r.getThrown()));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private Rp0918aLogCapture() {
        handler.setLevel(Level.ALL);
    }

    public static Rp0918aLogCapture start() {
        Rp0918aLogCapture c = new Rp0918aLogCapture();
        Logger.getLogger("").addHandler(c.handler);
        return c;
    }

    @Override
    public void close() {
        Logger.getLogger("").removeHandler(handler);
    }

    public List<Line> snapshot() {
        synchronized (lines) {
            return new ArrayList<>(lines);
        }
    }

    public List<Line> since(long fromMillis) {
        List<Line> out = new ArrayList<>();
        for (Line l : snapshot()) if (l.millis() >= fromMillis) out.add(l);
        return out;
    }

    public List<Matcher> find(Pattern p, long fromMillis) {
        List<Matcher> out = new ArrayList<>();
        for (Line l : since(fromMillis)) {
            Matcher m = p.matcher(l.message());
            if (m.find()) out.add(m);
        }
        return out;
    }

    public List<Line> findLines(Pattern p, long fromMillis) {
        List<Line> out = new ArrayList<>();
        for (Line l : since(fromMillis)) if (p.matcher(l.message()).find()) out.add(l);
        return out;
    }

    // ── 本任务约定的观测点（问题说明 ⑤-15） ─────────────────────────────────────

    /** {@code [perf] upgrade li=%s quotation=%s lines=%d dryRun=%b sql=%d ms=%d} */
    public static final Pattern PERF_UPGRADE = Pattern.compile(
        "\\[perf] upgrade li=(\\S+) quotation=(\\S+) lines=(\\d+) dryRun=(true|false) sql=(\\d+) ms=(\\d+)");

    /** {@code [perf] revision-write quotation=%s kind=INITIAL|CURRENT ms=%d} */
    public static final Pattern PERF_REVISION_WRITE = Pattern.compile(
        "\\[perf] revision-write quotation=(\\S+) kind=(INITIAL|CURRENT) ms=(\\d+)");

    /** AC-18：「版本已作废，停止剩余 N 个料号」 */
    public static final Pattern BUDGET_STOPPED = Pattern.compile("版本已作废，停止剩余\\s*(\\d+)\\s*个料号");

    public record PerfUpgrade(String lineId, String quotationId, int lines, boolean dryRun, long sql, long ms, String raw) {
    }

    public record RevisionWrite(String quotationId, String kind, long ms, String raw) {
    }

    public List<PerfUpgrade> perfUpgrades(long fromMillis) {
        List<PerfUpgrade> out = new ArrayList<>();
        for (Line l : since(fromMillis)) {
            Matcher m = PERF_UPGRADE.matcher(l.message());
            if (m.find()) {
                out.add(new PerfUpgrade(m.group(1), m.group(2), Integer.parseInt(m.group(3)),
                    Boolean.parseBoolean(m.group(4)), Long.parseLong(m.group(5)), Long.parseLong(m.group(6)), l.message()));
            }
        }
        return out;
    }

    public List<RevisionWrite> revisionWrites(long fromMillis) {
        List<RevisionWrite> out = new ArrayList<>();
        for (Line l : since(fromMillis)) {
            Matcher m = PERF_REVISION_WRITE.matcher(l.message());
            if (m.find()) out.add(new RevisionWrite(m.group(1), m.group(2), Long.parseLong(m.group(3)), l.message()));
        }
        return out;
    }
}
