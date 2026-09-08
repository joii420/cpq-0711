package com.cpq.dataset.registry;

import java.util.ArrayList;
import java.util.List;

/**
 * 一套数据集（报价 / 基础核价 / 详细核价）的元数据登记（task-260902 · backtask B-3）。
 *
 * <p>三套互相独立：无外键、无共享行、无相互引用（需求文档 §① 目标）。
 *
 * <p>🚨 <b>闸门 A0 裁决 D-13</b>：本包为新建独立包，与当时的核价基础数据维护端登记表
 * 及 {@code Q01~Q19} / {@code P01~P24} 导入器 <b>并行双轨、零耦合</b>，现有代码一行不改。
 * <p>（核价维护端整包已于 2026-09-07 随 task-260907 移除；本包与 {@code Q01~Q19} /
 * {@code P01~P24} 导入器之间的零耦合关系不变。）
 */
public interface DatasetRegistry {

    /** 路径参数值：{@code quote} / {@code cost-basic} / {@code cost-detail}（api.md §0）。 */
    String datasetKey();

    /** 中文名，用于错误报告文案 {@code sheet「X」不属于{数据集中文名}数据集}（AC-34）。 */
    String datasetLabel();

    /** 表名前缀：{@code ds_quote_} / {@code ds_cost_basic_} / {@code ds_cost_detail_}。 */
    String tablePrefix();

    /** 轴字段：报价 = {@code material_no}（销售料号），核价两套 = {@code production_no}（生产料号）。 */
    String axisColumn();

    String axisLabel();

    /** 该数据集的<b>物料表</b>（免版本，列表页数据源，api.md §3）。 */
    String materialTable();

    /**
     * task-260907 · B-1：本数据集的表是否带 {@code customer_no} 维度。
     *
     * <p>报价侧 = {@code true}（用户 2026-09-07 裁决「将所有报价侧的表都增加 customer_no 字段」）；
     * 核价两套 = {@code false}（裁决只针对报价侧）。
     *
     * <p>它同时决定三件事：① 启动自检期望列集要不要含 {@code customer_no}；
     * ② 版本化写入的轴是不是复合轴；③ 导入端点是否<b>强制</b>要求客户号。
     */
    default boolean customerScoped() { return false; }

    /** 全部 sheet，按 Excel 顺序（sortOrder 升序）。 */
    List<SheetDef> sheets();

    /** 带版本 sheet（= 抽屉 tab，api.md §2），顺序保持不变。 */
    default List<SheetDef> versionedSheets() {
        List<SheetDef> out = new ArrayList<>();
        for (SheetDef s : sheets()) if (s.versioned) out.add(s);
        return out;
    }

    default SheetDef byKey(String sheetKey) {
        for (SheetDef s : sheets()) if (s.sheetKey.equals(sheetKey)) return s;
        return null;
    }

    /** 按 Excel sheet 名匹配（导入 Phase 1 用；不匹配 = AC-34 报错）。 */
    default SheetDef bySheetName(String sheetName) {
        for (SheetDef s : sheets()) if (s.sheetName.equals(sheetName)) return s;
        return null;
    }
}
