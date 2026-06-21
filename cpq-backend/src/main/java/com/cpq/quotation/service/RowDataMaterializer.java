package com.cpq.quotation.service;

import com.cpq.engine.formula.FormulaCalculationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 写时算齐（materialize-at-write）: 给定组件 snapshot 行 + 组件公式定义，计算 FORMULA
 * 叶子列（如 材料成本 / 费用）写回 row_data。
 *
 * <p>背景：导入/配置阶段只把 driver 原始值写入 {@code snapshot_rows}，从不计算 FORMULA
 * 叶子列；而 Excel 视图读 {@code row_data}（经 {@link com.cpq.quotation.service.card.ComponentDataEffectiveRows}），
 * 缺失叶子列求和为 0 → 产品小计错误（仅统计来料），直到用户编辑一次卡片才修正。
 *
 * <p>本服务复用既有公式引擎 {@link FormulaCalculationService#calculateRowFormulas}
 * （逐行求值，仅返回 FORMULA 字段结果），本类只做数据形状适配：JsonNode ↔ Map，
 * 逐行调用引擎、把结果合并回行、组装为 row_data 数组。
 *
 * <p>跨组件小计键约定 = 裸 component_code（与引擎 {@code component_subtotal} token 的
 * {@code component_code} 取值键一致）；列级引用的 {@code code#col} 约定见
 * {@link com.cpq.quotation.service.card.ComponentDataEffectiveRows} 的 SUBTOTAL_KEY_SEP。
 */
@ApplicationScoped
public class RowDataMaterializer {

    private static final Logger LOG = Logger.getLogger(RowDataMaterializer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    FormulaCalculationService formulaCalculationService;

    /**
     * 计算 FORMULA 叶子列写回 row_data。
     *
     * @param componentsSnapshot       组件 snapshot 数组（含 columns / FORMULA expression token）
     * @param componentCode            目标组件 code（匹配 snapshot 中的 code）
     * @param snapshotRows             driver 原始行数组（含 driver 列值，缺 FORMULA 叶子列）
     * @param crossComponentSubtotals  跨组件小计 component_code -> 值（可空）；键为裸 code
     * @return materialized row_data 数组（FORMULA 叶子列已填齐）；snapshotRows 空/null → 空数组
     */
    public JsonNode materializeComponentRows(JsonNode componentsSnapshot,
                                             String componentCode,
                                             JsonNode snapshotRows,
                                             Map<String, Double> crossComponentSubtotals) {
        ArrayNode out = MAPPER.createArrayNode();
        if (snapshotRows == null || !snapshotRows.isArray() || snapshotRows.isEmpty()) {
            return out;
        }

        // 引擎需要 snapshot JSON 字符串 + 每行 Map<String,Object>。
        String snapshotJson;
        try {
            snapshotJson = componentsSnapshot == null ? null : MAPPER.writeValueAsString(componentsSnapshot);
        } catch (Exception e) {
            LOG.error("Failed to serialize components snapshot for materialize: " + e.getMessage(), e);
            return out;
        }

        Map<String, BigDecimal> crossBig = toBigDecimalMap(crossComponentSubtotals);

        for (JsonNode rowNode : snapshotRows) {
            if (rowNode == null || !rowNode.isObject()) {
                // 非对象行：原样保留（不参与公式求值），避免丢数据。
                out.add(rowNode == null ? MAPPER.nullNode() : rowNode);
                continue;
            }

            Map<String, Object> rowData = MAPPER.convertValue(rowNode, new TypeReference<>() {});

            // 委托引擎逐行求 FORMULA 叶子列；引擎只返回 FORMULA 字段结果。
            Map<String, BigDecimal> formulaResults = formulaCalculationService.calculateRowFormulas(
                    snapshotJson, componentCode, rowData, crossBig);

            // 合并：保留原 driver 列 + 写入新算出的 FORMULA 叶子列。
            ObjectNode merged = ((ObjectNode) rowNode).deepCopy();
            if (formulaResults != null) {
                for (Map.Entry<String, BigDecimal> e : formulaResults.entrySet()) {
                    if (e.getValue() == null) {
                        merged.putNull(e.getKey());
                    } else {
                        merged.put(e.getKey(), e.getValue());
                    }
                }
            }
            out.add(merged);
        }

        return out;
    }

    private static Map<String, BigDecimal> toBigDecimalMap(Map<String, Double> in) {
        if (in == null || in.isEmpty()) {
            return null;
        }
        Map<String, BigDecimal> r = new HashMap<>();
        for (Map.Entry<String, Double> e : in.entrySet()) {
            r.put(e.getKey(), e.getValue() == null ? null : BigDecimal.valueOf(e.getValue()));
        }
        return r;
    }
}
