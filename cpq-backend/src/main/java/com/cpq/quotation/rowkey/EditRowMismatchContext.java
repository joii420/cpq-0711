package com.cpq.quotation.rowkey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 线程级「{@code editRows} 行键失配」收集器（repair-260908 B-8，AC-18）。
 *
 * <p><b>它存在的唯一理由：让一个本来完全静默的数据损失变得响亮。</b>
 *
 * <p>{@code CardSnapshotService#filterEditRowsToNewBaseRows} 的现行行为是
 * 「旧 {@code editRows} 的 {@code rowKey} 不在新 {@code baseRows} 的键集合里 ⇒ <b>直接丢弃</b>」——
 * 不记日志、不计数、不报错、HTTP 200。⇒ 用户手工填的值消失了，界面上看不出来。
 *
 * <p><b>本次为什么非管不可</b>：缺陷① 修好之后<b>行键必然会变</b> ——
 * 跨客户重复行消失 ⇒ 撞键消失 ⇒ {@code uniquifyRowKeys} 的 {@code #N} 消歧后缀跟着消失
 * （实测 {@code QT-20260908-0624} 一单就有 196 条带后缀的 {@code editRows}，
 * {@code #0} 与 {@code #1} 各 96 条严丝合缝成对 = 恰好两个客户）。
 * 这些键在刷新后一条都对不上，会被上面那行 {@code if} 静默吞掉。
 *
 * <p>🚫 <b>刻意不做「按内容迁移」</b>（用户 2026-09-08 裁决 D-14）：{@code #0} / {@code #1}
 * 本来就是<b>两个客户的两行</b>，合并成一行时「保留哪一条」<b>没有唯一正确答案</b>；
 * 猜错的后果是把别家客户的编辑值写进本家 —— 比丢值更坏，而且同样看不出来。
 * ⇒ 本类只<b>收集并报出</b>，不改变任何一行的去留。
 *
 * <p><b>用法</b>（与 {@code SqlDebugContext} 同款）：调用方 {@link #begin()} → 业务执行 →
 * {@link #drainAll()}。未 {@code begin()} 时 {@link #record} 是空操作、零开销，生产路径不受影响。
 * 基于 {@code ThreadLocal}，要求三者<b>同线程</b>成对使用（刷新端点在请求线程内同步调用，满足）。
 *
 * <p>⚠️ 收集窗口只影响「要不要放进 HTTP 响应」。{@code CardSnapshotService} 里那条
 * {@code LOG.warn} <b>不受窗口约束、恒生效</b> —— 别的调用路径（保存草稿、打开单据）上的失配
 * 同样会留在服务端日志里，只是不进响应体。
 */
public final class EditRowMismatchContext {

    /** 一条失配记录：哪个组件的哪个行键没对上，以及它原本承载的值（摘要）。 */
    public static final class Mismatch {
        public final String componentId;
        public final String rowKey;
        /** 原值摘要：字段名 → 值的字符串形式，长值已截断。AC-18 要求「给出原值摘要」。 */
        public final Map<String, String> values;

        public Mismatch(String componentId, String rowKey, Map<String, String> values) {
            this.componentId = componentId;
            this.rowKey = rowKey;
            this.values = values == null ? Map.of() : values;
        }

        @Override
        public String toString() {
            return componentId + " :: " + rowKey + " " + values;
        }
    }

    /** 单次收集的上限 —— 一单实测可达 196 条，全量进响应体没有意义，计数才是判据。 */
    private static final int MAX_DETAIL = 200;
    /** 单个值的截断长度：摘要用来认人，不是用来还原数据。 */
    private static final int MAX_VALUE_LEN = 120;

    private static final ThreadLocal<List<Mismatch>> BUFFER = new ThreadLocal<>();
    /** 命中总数（可能 > 明细条数，见 {@link #MAX_DETAIL}）。 */
    private static final ThreadLocal<int[]> COUNTER = new ThreadLocal<>();

    private EditRowMismatchContext() {}

    /** 开启当前线程的收集（清空旧缓冲）。 */
    public static void begin() {
        BUFFER.set(new ArrayList<>());
        COUNTER.set(new int[]{0});
    }

    public static boolean isActive() {
        return BUFFER.get() != null;
    }

    /** 记录一条失配。未 {@code begin()} 时为空操作。 */
    public static void record(String componentId, String rowKey, Map<String, String> values) {
        List<Mismatch> buf = BUFFER.get();
        if (buf == null) return;
        int[] c = COUNTER.get();
        if (c != null) c[0]++;
        if (buf.size() >= MAX_DETAIL) return;
        buf.add(new Mismatch(componentId, rowKey, values));
    }

    /** 值摘要的统一截断口径（调用方拼 map 时用，避免各处各截各的）。 */
    public static String abbreviate(String v) {
        if (v == null) return null;
        return v.length() <= MAX_VALUE_LEN ? v : v.substring(0, MAX_VALUE_LEN) + "…(截断)";
    }

    /**
     * 一次性取走「总数 + 明细」并清理。
     *
     * <p>🔑 <b>刻意只提供这一个出口</b>：如果拆成 {@code drain()} + {@code count()} 两个方法，
     * 调用顺序写反（先 drain 清了 ThreadLocal 再取数）就会<b>恒得 0</b> —— 而 0 正好长得像
     * 「没有失配」，于是本类费劲要消灭的那类「恒真信号」会在它自己身上重现一次。
     */
    public static Map<String, Object> drainAll() {
        List<Mismatch> buf = BUFFER.get();
        int[] c = COUNTER.get();
        int total = (c == null) ? 0 : c[0];
        BUFFER.remove();
        COUNTER.remove();
        List<Mismatch> detail = (buf == null) ? List.of() : buf;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", total);
        out.put("truncated", total > detail.size());
        out.put("items", detail);
        return out;
    }
}
