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

    /** 全部 sheet，按 Excel 顺序（sortOrder 升序）。 */
    List<SheetDef> sheets();

    /** 带版本 sheet（= 抽屉 tab，api.md §2），顺序保持不变。 */
    default List<SheetDef> versionedSheets() {
        List<SheetDef> out = new ArrayList<>();
        for (SheetDef s : sheets()) if (s.versioned) out.add(s);
        return out;
    }

    /**
     * task-260907 第二段 · B-2 —— 本数据集的带版本表是否配 {@code _record} 快照表 +
     * 「来源报价单 id」系统列。<b>只有报价侧 {@code true}</b>。
     *
     * <p>🚨 <b>为什么开关放在 Registry 而不是 {@link SheetDef}</b>：{@code SheetDef} 是三套数据集
     * 共用的类，把「要不要 {@code _record}」写死在它里面（如按表名前缀判断）等于让核价两套
     * 的自检也去要求这些列 —— {@code DatasetSchemaSelfCheck} 要求列集<b>完全相等</b>，
     * 核价侧的库里没有这些列 ⇒ <b>核价侧服务当场起不来</b>。
     * 数据集级的差异就该由数据集自己声明。
     */
    default boolean quoteRecordEnabled() {
        return false;
    }

    /**
     * task-260907 第二段 · B-1（S-3）—— 该 sheet 的 {@code _record} 相对主表的<b>附加业务列</b>，
     * {@code 列名 → pgType}（有序）。默认无。
     *
     * <p>报价侧只有物料BOM / 物料与元素BOM 两张返回 {@code element_price numeric(26,12)}
     * （用户原话「物料BOM元素 表」，D-6：🚫 不进主表）。
     */
    default java.util.Map<String, String> recordExtraColumns(SheetDef sheet) {
        return java.util.Map.of();
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
