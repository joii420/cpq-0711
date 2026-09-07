package com.cpq.quotation.service.dsrecord;

import com.cpq.dataset.registry.SheetDef;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个报价单页签组件 ↔ 一张 {@code ds_quote_*} 带版本主表的绑定（task-260907 第二段 · B-2/B-6）。
 *
 * <p><b>「页签源」不是一个字段，是四跳推导</b>（2026-09-07 勘察实证，全仓无 {@code tab_source} 之类的列）：
 * <pre>
 *   component.data_driver_path  "$xxx"
 *     → component_sql_view (component_id, sql_view_name)
 *     → builder_config JSONB {tabType, variantKey, dialect, columns[]}
 *     → semantic_tab_view (tab_type, variant_key, dialect)   ← 唯一键
 *     → semantic_tab_view.anchor_node_id → semantic_node.physical_table  = ds_quote_*
 *     → QuoteRegistry 里 tableName 相同的那个 SheetDef
 * </pre>
 * 用户口中「在取值编辑器中定义好了页签源」指的就是这条链的起点（取数配置器里选的页签类型 + 变体）。
 *
 * <p><b>列映射</b>：{@code builder_config.columns[]} 每项带 {@code sourceNodeKey} /
 * {@code sourceColumn} / {@code fieldName}。{@code sourceNodeKey} 等于锚点节点的 {@code node_key}
 * ⇒ 这一列就是主表的物理列；否则（LOOKUP 查名列 / 价格函数列 / 其它节点的列）主表对不齐。
 *
 * <p>🚨 <b>本绑定描述的是「投影」，不是「全集」</b>（AP-60）：
 * <ul>
 *   <li><b>行维度</b>：页签可能带判别式 / 变体谓词，只表征该组的部分行；</li>
 *   <li><b>列维度</b>：{@link #fieldToColumn} 只覆盖页签暴露的那几列，主表其余列这个页签根本不知道。</li>
 * </ul>
 * ⇒ 回填必须以<b>主表整组真实行</b>为基底做<b>列级 patch</b>，🚫 不许拿本绑定的行集去重建整组。
 *
 * @param componentId       页签组件 id
 * @param sheet             目标 sheet（带版本；免版本表不会出现在这里，B-13）
 * @param anchorNodeKey     锚点语义节点 key（= sheetKey，留作诊断与日志）
 * @param fieldToColumn     组件字段名 → 主表物理列名（有序，= 页签表征的列集合）
 * @param viewColumnByField 组件字段名 → SQL 视图输出列别名（{@code builder_config.columns[].viewColumn}）。
 *                          {@code driverRow} 的 key 理论上就是字段名（{@code QuoteBackfillCollector:613}
 *                          的「field 名 = alias 约定」），但编译器实际发的是
 *                          {@code AliasGenerator.bareColumn(fieldName)} 且会去重加后缀 ⇒ 两者可能不等，
 *                          取值时按「字段名 → 视图列名」两级兜底，别只认一个
 * @param extendFields      主表对不齐的字段名（自定义列 / 公式列 / 常量列 / 查名列）——
 *                          落 {@code _record.extend_column}，D-5：不回填、不参与升版与比对
 * @param elementPriceField 元素单价字段名（{@code component.element_price_field}），可空；
 *                          仅物料BOM / 物料与元素BOM 两张的 {@code _record} 用它填 {@code element_price}
 * @param rowKeyFieldNames  组件行键字段名（{@code component.row_key_fields}），用于按
 *                          {@code deleted_row_keys} 墓碑剔除用户删掉的行；可空
 */
public record DsSheetBinding(UUID componentId,
                             SheetDef sheet,
                             String anchorNodeKey,
                             Map<String, String> fieldToColumn,
                             Map<String, String> viewColumnByField,
                             List<String> extendFields,
                             String elementPriceField,
                             List<String> rowKeyFieldNames) {
}
