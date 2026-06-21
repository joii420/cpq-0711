package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * 写时算齐（materialize-at-write）：给定一个组件的 snapshot 行（driver 原始值）+ 该组件的
 * 公式定义，用<b>真实生产公式引擎 {@link FormulaCalculator}</b> 计算 FORMULA 叶子列，
 * 输出<b>扁平 row_data</b>（{@code [{字段名:值, …, row_index:N}]}）。
 *
 * <p><b>背景</b>：配置阶段只把 driver 整行（{@code [{driverRow, basicDataValues}]}）写入
 * {@code snapshot_rows}，从不计算 FORMULA 叶子列；而 Excel 视图读 {@code row_data}
 * （经 {@link com.cpq.quotation.service.card.ComponentDataEffectiveRows#columnSums} 对扁平顶层数值键求和），
 * 缺失叶子列求和为 0 → 产品小计错误（仅统计来料），直到用户编辑一次卡片才修正。
 * 本服务在配置时把叶子列算齐写回 {@code row_data}，无需用户手动编辑。
 *
 * <p><b>实现 = 1:1 复刻 {@link CardSnapshotService#buildResolvedRows} 的逐行解析</b>：
 * <ol>
 *   <li>定位目标组件 tab（按 {@code componentCode}）→ 取其 {@code fields / formulas / formula_assignments}。</li>
 *   <li>{@code snapshot_rows}（{@code [{driverRow, basicDataValues}]}）直接作 baseRows 喂引擎。</li>
 *   <li>{@link FormulaCalculator#calculate} 逐行求 FORMULA 字段（editRows 恒空，配置态无编辑）。</li>
 *   <li>{@link FormulaCalculator#resolveRowByFieldName} 把每行解析成扁平 {@code {字段名→值}}
 *       （FORMULA 取已算 formulaResults，driver/INPUT/BASIC_DATA/DATA_SOURCE 取各自来源），
 *       再补 {@code row_index}。</li>
 * </ol>
 *
 * <p><b>跨组件小计</b>：{@code crossComponentSubtotals} 键约定 = {@code code#col} 与 {@code name#col}
 * （与 {@link FormulaCalculator} 的 {@code component_subtotal} token 取值键
 * {@code component_code + "#" + value} / {@code tab_name + "#" + value} 一致，
 * 也与 {@link com.cpq.quotation.service.card.ComponentDataEffectiveRows} 的 {@code SUBTOTAL_KEY_SEP} 一致）。
 *
 * <p><b>AP-51 行数权威</b>：行数 = {@code snapshot_rows} 行数（driver 展开权威），绝不 Math.max。
 * <p><b>SUBTOTAL 组件</b>：不在此物化（其值由读时 {@link com.cpq.quotation.service.card.ComponentDataEffectiveRows}
 * 按公式重算），调用方应跳过。
 *
 * <p>无可变状态的纯计算 bean（{@code @ApplicationScoped} 便于注入；同时支持
 * {@code new RowDataMaterializer(new FormulaCalculator())} 直接单测）。
 */
@ApplicationScoped
public class RowDataMaterializer {

    private static final Logger LOG = Logger.getLogger(RowDataMaterializer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FormulaCalculator formulaCalculator;

    @Inject
    public RowDataMaterializer(FormulaCalculator formulaCalculator) {
        this.formulaCalculator = formulaCalculator;
    }

    /**
     * 计算一个组件的 FORMULA 叶子列并输出扁平 row_data。
     *
     * @param componentsSnapshot      模板 {@code components_snapshot} 数组（含各 tab 的
     *                                {@code componentCode / fields / formulas / formula_assignments}）
     * @param componentCode           目标组件 code（匹配 snapshot tab 的 {@code componentCode}）
     * @param snapshotRows            该组件的 driver 整行数组（{@code [{driverRow, basicDataValues}]}）
     * @param crossComponentSubtotals 跨组件小计（键 = {@code code#col} / {@code name#col}），可空
     * @return 扁平 row_data 数组（FORMULA 叶子列已算齐 + {@code row_index}）；
     *         snapshotRows 空/null 或找不到组件 → 空数组
     */
    public ArrayNode materializeComponentRows(JsonNode componentsSnapshot,
                                              String componentCode,
                                              JsonNode snapshotRows,
                                              Map<String, Double> crossComponentSubtotals) {
        return materializeComponentRows(componentsSnapshot, componentCode, snapshotRows,
                crossComponentSubtotals, Map.of());
    }

    /**
     * 重载：额外透传 cross_tab_ref 兄弟组件已算行（{@code source(componentId/componentCode) → 行表}）。
     * 配置态 2-pass：Pass1 各组件先用空 cross 物化并把其扁平行存入 crossTabRows，
     * Pass2 引用方再带 crossTabRows 复算（与 {@link CardSnapshotService#assembleTabsWithFormulaResults} 同款）。
     *
     * @param crossTabRows source 标识 → 已算扁平行表（行=字段名→值）；null 视作空。
     */
    public ArrayNode materializeComponentRows(JsonNode componentsSnapshot,
                                              String componentCode,
                                              JsonNode snapshotRows,
                                              Map<String, Double> crossComponentSubtotals,
                                              Map<String, List<Map<String, Object>>> crossTabRows) {
        ArrayNode out = MAPPER.createArrayNode();
        if (snapshotRows == null || !snapshotRows.isArray() || snapshotRows.isEmpty()) {
            return out;
        }
        JsonNode tab = findTab(componentsSnapshot, componentCode);
        if (tab == null) {
            LOG.debugf("[materialize] component code=%s not found in snapshot; skip", componentCode);
            return out;
        }

        JsonNode fields = tab.path("fields");
        JsonNode formulas = tab.path("formulas");
        JsonNode formulaAssignments = tab.path("formula_assignments");

        // baseRows = snapshot_rows（已是 [{driverRow, basicDataValues}]）；editRows 恒空（配置态无编辑）。
        // rowKeyFields 传 null：无 editRows → 行键仅用于唯一化对齐，无影响（按行序兜底）。
        ArrayNode baseRows = (ArrayNode) snapshotRows;
        ArrayNode emptyEdit = MAPPER.createArrayNode();

        Map<String, Double> cross = crossComponentSubtotals != null
                ? crossComponentSubtotals : Map.of();
        Map<String, List<Map<String, Object>>> xtab = crossTabRows != null
                ? crossTabRows : Map.of();

        // 逐行 FORMULA 求值（与 CardSnapshotService PASS2 同款引擎调用）。
        ArrayNode formulaResults = formulaCalculator.calculate(
                fields, formulas, formulaAssignments,
                /* rowKeyFields */ null, baseRows, emptyEdit,
                cross, Map.of(), Map.of(),
                xtab);

        // formulaResults: [{rowKey, values:{字段名→数值}}]；行键唯一化口径与 calculate 内部一致。
        // 无 editRows 时各行 rowKey 即按行序兜底（uniquify 不改单次出现键），按下标取 values 即可。
        for (int i = 0; i < baseRows.size(); i++) {
            JsonNode br = baseRows.get(i);
            JsonNode driverRow = br.path("driverRow");
            JsonNode basicDataValues = br.path("basicDataValues");
            JsonNode frValues = (i < formulaResults.size())
                    ? formulaResults.get(i).path("values") : null;

            // 扁平解析（FORMULA 取已算 values；其余按字段定义来源解析）—— 复用通用引擎。
            Map<String, Object> resolved = formulaCalculator.resolveRowByFieldName(
                    fields, driverRow, basicDataValues, /* editValues */ null, frValues);

            ObjectNode rowNode = MAPPER.valueToTree(resolved);
            rowNode.put("row_index", i);
            out.add(rowNode);
        }
        return out;
    }

    /** 按 componentCode 在 components_snapshot 数组里定位 tab；找不到返回 null。 */
    private JsonNode findTab(JsonNode componentsSnapshot, String componentCode) {
        if (componentsSnapshot == null || !componentsSnapshot.isArray() || componentCode == null) {
            return null;
        }
        for (JsonNode tab : componentsSnapshot) {
            if (componentCode.equals(tab.path("componentCode").asText(null))) {
                return tab;
            }
        }
        return null;
    }
}
