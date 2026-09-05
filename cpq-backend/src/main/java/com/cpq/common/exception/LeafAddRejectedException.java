package com.cpq.common.exception;

import java.util.List;

/**
 * task-260904 B-6/B-7（api.md §3.3）：报价单 BOM 树「加叶子」的两个新增准入拒绝态。
 *
 * <p>HTTP 400 + 结构化 {@code code}，前端按 {@code code} 分支，🚫 不要按 message 文本匹配
 * （与 {@code FormulaCycleException.errorType} / {@code TemplateNotFrozenException.CODE} 同一约定）。
 *
 * <table>
 *   <tr><th>code</th><th>触发</th></tr>
 *   <tr><td>{@code LEAF_PART_NOT_IN_MASTER}</td>
 *       <td>料号在 {@code ds_quote_material} 与 {@code material_recipe} 中都不存在</td></tr>
 *   <tr><td>{@code LEAF_CYCLE_DETECTED}</td>
 *       <td>挂上后会形成环（含自环：宿主 = 料号）</td></tr>
 * </table>
 */
public class LeafAddRejectedException extends BusinessException {

    public static final String CODE_PART_NOT_IN_MASTER = "LEAF_PART_NOT_IN_MASTER";
    public static final String CODE_CYCLE_DETECTED = "LEAF_CYCLE_DETECTED";

    private final String errorCode;
    private final String partNo;
    /** 仅 {@link #CODE_CYCLE_DETECTED} 有值：环路径的料号序列（首尾同一料号），供前端展示。 */
    private final List<String> cyclePath;

    private LeafAddRejectedException(String errorCode, String message, String partNo, List<String> cyclePath) {
        super(400, message);
        this.errorCode = errorCode;
        this.partNo = partNo;
        this.cyclePath = cyclePath == null ? List.of() : List.copyOf(cyclePath);
    }

    /** 文案必须点名该料号，并说明需先在物料表或材质库建档（api.md §3.3）。 */
    public static LeafAddRejectedException partNotInMaster(String partNo) {
        return new LeafAddRejectedException(CODE_PART_NOT_IN_MASTER,
                "料号「" + partNo + "」在物料表(ds_quote_material)与材质库(material_recipe)中都不存在，"
                        + "不能作为叶子挂入。请先在物料表或材质库中为该料号建档后重试。",
                partNo, null);
    }

    /** 文案必须给出环路径（如 {@code A → B → A}），不能只说"会成环"（api.md §3.3）。 */
    public static LeafAddRejectedException cycleDetected(String partNo, List<String> cyclePath) {
        String path = (cyclePath == null || cyclePath.isEmpty()) ? partNo : String.join(" → ", cyclePath);
        return new LeafAddRejectedException(CODE_CYCLE_DETECTED,
                "料号「" + partNo + "」挂到该节点下会在 BOM 树上形成环：" + path + "。请改选其它宿主节点或其它料号。",
                partNo, cyclePath);
    }

    public String getErrorCode() { return errorCode; }
    public String getPartNo() { return partNo; }
    public List<String> getCyclePath() { return cyclePath; }
}
