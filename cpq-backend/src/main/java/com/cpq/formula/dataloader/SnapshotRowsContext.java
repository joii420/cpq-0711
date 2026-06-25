package com.cpq.formula.dataloader;

import java.util.Map;
import java.util.UUID;

/**
 * 当前请求线程的「整单 snapshot_rows 预取」上下文(ThreadLocal,仿 {@link ExcelCompDataContext})。
 *
 * <p>背景:批量渲染 {@code /batch-expand} 的 Phase 1 对每个 task 各发一次
 * {@code SELECT snapshot_rows ... WHERE line_item_id=? AND component_id=?}(命中快照直返)。
 * 一单 600+ task 全有快照时 = 600+ 次远程往返,直接撑出 20s。
 *
 * <p>本上下文由 {@code ComponentResource.batchExpand} 在 Phase 1 之前<b>整单一次 IN 查</b>所有
 * (line_item_id, component_id) 的 snapshot_rows 后设入(key = {@code lineItemId|componentId} → snapshot_rows 文本)。
 * {@code ComponentDriverService.expand} 的 snapshot-read 命中上下文则读内存、零往返;
 * 上下文已设但该对不在 map 内 = 该对无快照 → 落到实时 expand(<b>不再逐 task 查库</b>);
 * 上下文未设(单卡/其它路径)→ 回落逐 task 查(零破坏)。
 *
 * <p>务必 finally {@link #clear()},避免线程池下个请求误用旧值。值为只读。
 */
public final class SnapshotRowsContext {

    private static final ThreadLocal<Map<String, String>> CURRENT = new ThreadLocal<>();

    private SnapshotRowsContext() {}

    public static String key(UUID lineItemId, UUID componentId) {
        return lineItemId + "|" + componentId;
    }

    public static void set(Map<String, String> byPair) {
        if (byPair == null) CURRENT.remove();
        else CURRENT.set(byPair);
    }

    /** 上下文是否已设(批量预载模式)。设了就不再逐 task 查库。 */
    public static boolean isSet() {
        return CURRENT.get() != null;
    }

    /** 返回该 (lineItemId, componentId) 预取的 snapshot_rows 文本;无该对(=无快照)→ null。 */
    public static String get(UUID lineItemId, UUID componentId) {
        Map<String, String> m = CURRENT.get();
        return (m == null || lineItemId == null || componentId == null) ? null : m.get(key(lineItemId, componentId));
    }

    public static void clear() {
        CURRENT.remove();
    }
}
