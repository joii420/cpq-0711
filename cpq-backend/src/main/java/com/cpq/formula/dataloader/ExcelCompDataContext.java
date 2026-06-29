package com.cpq.formula.dataloader;

import com.cpq.quotation.entity.QuotationLineComponentData;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 当前请求线程的「整单 component_data 预取」上下文(ThreadLocal,仿 {@link PartVersionContext})。
 *
 * <p>首存/刷新的逐行 Excel 构建里,{@code ExcelViewService.buildRowData} 默认每行(甚至每 spine 节点)
 * 各发一次 {@code QuotationLineComponentData.list("lineItemId=?")}。本上下文由 saveDraft 编排在构建循环外
 * <b>整单一次 IN 查</b>后设入(componentId→该行 component_data 列表,按 lineItemId 分组),buildRowData
 * 命中则读内存、零往返;未设(其它路径/单卡编辑)→ 回落逐行查(零破坏)。
 *
 * <p>务必 finally {@link #clear()},避免线程池下个请求误用旧值。值为只读(各 build 只读不 mutate)。
 */
public final class ExcelCompDataContext {

    private static final ThreadLocal<Map<UUID, List<QuotationLineComponentData>>> CURRENT = new ThreadLocal<>();

    private ExcelCompDataContext() {}

    public static void set(Map<UUID, List<QuotationLineComponentData>> byLine) {
        if (byLine == null) CURRENT.remove();
        else CURRENT.set(byLine);
    }

    /** 返回该行预取的 component_data 列表;上下文未设或本行无预取 → null(调用方回落逐行查)。 */
    public static List<QuotationLineComponentData> get(UUID lineItemId) {
        Map<UUID, List<QuotationLineComponentData>> m = CURRENT.get();
        return (m == null || lineItemId == null) ? null : m.get(lineItemId);
    }

    public static boolean isSet() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
