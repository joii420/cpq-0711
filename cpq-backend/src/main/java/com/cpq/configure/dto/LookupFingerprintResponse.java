package com.cpq.configure.dto;

import java.util.List;
import java.util.Map;

public class LookupFingerprintResponse {
    public boolean matched;

    /**
     * 命中的报价料号 / 销售料号（quote_part_no）。
     * {@code hfPartNo} 与 {@code matchedPartNo} 同值——保留 {@code hfPartNo}
     * 兼容既有字段名（前端 {@code types/configure.ts} 已声明），{@code matchedPartNo}
     * 为 3a 任务约定的字段名，二选一读取皆可。
     */
    public String hfPartNo;
    public String matchedPartNo;

    public Snapshot snapshot;

    /**
     * task-260910 · B-6（api.md §2.4）：<b>只剩 {@code processes} 一个字段</b>。
     *
     * <p>🔴 删掉的两个字段（实测<b>全前端零消费方</b>，查完即丢，各拖一段查库）：
     * <ul>
     *   <li>{@code unitWeightGrams} —— 原查 {@code v_compat_material_master.unit_weight}；</li>
     *   <li>{@code compositeProcesses} —— 原查已废弃的 V6 {@code capacity}。</li>
     * </ul>
     * 🚫 要加回来必须先改 api.md（跨端契约），不要因为「看起来有用」就补字段。
     */
    public static class Snapshot {
        /** 工序列表 {@code [{processCode, seqNo}]}，数据源 {@code ds_quote_self_process_fee}（AC-22 唯一消费方）。 */
        public List<Map<String, Object>> processes;
    }
}
