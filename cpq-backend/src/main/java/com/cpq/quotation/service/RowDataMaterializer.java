package com.cpq.quotation.service;

import com.cpq.quotation.rowkey.DeletedRowKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
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
        // 配置态：editRows 恒空、无行键覆盖、无墓碑过滤 → 委派全参重载传空/ null。
        return materializeComponentRows(componentsSnapshot, componentCode, snapshotRows,
                /* editRows */ null, /* rowKeyFields */ null,
                crossComponentSubtotals, crossTabRows,
                /* deleted */ null, /* rowKeyFieldNames */ null);
    }

    /**
     * 全参重载（报价侧失焦同步用）：在配置态物化基础上额外接受
     * <b>editRows（用户编辑）+ rowKeyFields（行键对齐）+ deleted（永久删除墓碑）</b>，
     * 把编辑同时算进 <b>INPUT 列扁平值</b> 与 <b>FORMULA 叶子列</b>，并按墓碑剔除已删除行。
     *
     * <p><b>editRows → 双落点（AP-54 行键对齐）</b>：
     * <ol>
     *   <li>FORMULA 叶子：editRows 透传 {@link FormulaCalculator#calculate}，引擎内部按 effKey
     *       合并 editValues 到 driverRow 后求值（与生产 assembleTabsWithFormulaResults 同款）。</li>
     *   <li>INPUT 扁平值：本方法用<b>同款 effKey 口径</b>（{@code computeRowKey + uniquifyRowKeys}，
     *       与 calculate/前端 buildUniqueRowKeys 逐字节一致）重算每行 effKey，按 effKey 取本行
     *       editValues 喂 {@link FormulaCalculator#resolveRowByFieldName}（INPUT 取 editValues 优先），
     *       使用户手填的 料号/单价 也反映在 row_data，而非仅 FORMULA 叶子。</li>
     * </ol>
     *
     * <p><b>行对齐</b>：calculate 的 formulaResults 跳过墓碑行且其 {@code rowKey}=effKey，故按 rowKey
     * 建索引取 values（不靠下标，避免删除行错位）。flat 输出迭代完整 baseRows、按 keep 掩码 continue
     * 跳过删除行（AP-51 行数权威=baseRows；不 Math.max）。
     *
     * @param editRows         该组件 editRows（{@code [{rowKey, values:{字段名→值}}]}），可空
     * @param rowKeyFields     行键字段节点（{@code ["料号",…]}），<b>必须传真实值</b>——null 退化为按行号
     *                         对齐致 editRows 错位（AP-54）；无编辑时可传 null。
     * @param deleted          永久删除墓碑列表（null/空 → 不过滤）
     * @param rowKeyFieldNames rowKeyFields 解出的字段名列表（与 deleted 配套；null 则不过滤）
     */
    public ArrayNode materializeComponentRows(JsonNode componentsSnapshot,
                                              String componentCode,
                                              JsonNode snapshotRows,
                                              JsonNode editRows,
                                              JsonNode rowKeyFields,
                                              Map<String, Double> crossComponentSubtotals,
                                              Map<String, List<Map<String, Object>>> crossTabRows,
                                              List<DeletedRowKeys.Tombstone> deleted,
                                              List<String> rowKeyFieldNames) {
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

        // baseRows = snapshot_rows（已是 [{driverRow, basicDataValues}]）。
        ArrayNode baseRows = (ArrayNode) snapshotRows;
        JsonNode edits = (editRows != null && editRows.isArray()) ? editRows : MAPPER.createArrayNode();

        Map<String, Double> cross = crossComponentSubtotals != null
                ? crossComponentSubtotals : Map.of();
        Map<String, List<Map<String, Object>>> xtab = crossTabRows != null
                ? crossTabRows : Map.of();

        // ① 逐行 FORMULA 求值（带 editRows + 墓碑过滤；与 CardSnapshotService PASS2 同款引擎调用）。
        //    formulaResults: [{rowKey(=effKey), values:{字段名→数值}}]，已跳过墓碑行。
        ArrayNode formulaResults = formulaCalculator.calculate(
                fields, formulas, formulaAssignments,
                rowKeyFields, baseRows, edits,
                cross, Map.of(), Map.of(),
                xtab, deleted, rowKeyFieldNames);

        // ② 按 rowKey(=effKey) 建 formulaResults 索引（删除行被跳过 → 不靠下标对齐）。
        Map<String, JsonNode> frByKey = new HashMap<>();
        for (JsonNode fr : formulaResults) {
            String rk = fr.path("rowKey").asText(null);
            if (rk != null) frByKey.put(rk, fr.path("values"));
        }

        // ③ 复算与 calculate 内部逐字节一致的 effKeys（rawKey → uniquify），用于：
        //    (a) 按 effKey 取本行 editValues 喂 resolveRowByFieldName（INPUT 列反映编辑）；
        //    (b) 按 effKey 命中 frByKey 取 FORMULA 叶子值。
        List<String> rawKeys = new ArrayList<>(baseRows.size());
        for (int i = 0; i < baseRows.size(); i++) {
            JsonNode br = baseRows.get(i);
            String rk = formulaCalculator.computeRowKey(
                    rowKeyFields, fields, br.path("driverRow"), br.path("basicDataValues"));
            rawKeys.add((rk != null && !rk.isEmpty()) ? rk : String.valueOf(i));
        }
        List<String> effKeys = FormulaCalculator.uniquifyRowKeys(rawKeys);

        // editRows 按 rowKey 索引（取本行 editValues）。
        Map<String, JsonNode> editByKey = new HashMap<>();
        for (JsonNode er : edits) {
            String rk = er.path("rowKey").asText(null);
            if (rk != null) editByKey.put(rk, er.path("values"));
        }

        // ④ 墓碑过滤掩码（与 calculate 内部口径一致：唯一化后按指纹双命中剔除整行；fps 用完整 baseRows）。
        boolean[] keep = null;
        if (deleted != null && !deleted.isEmpty()) {
            List<String> fps = new ArrayList<>(baseRows.size());
            for (JsonNode br : baseRows) {
                fps.add(DeletedRowKeys.rowFingerprint(rowKeyFieldNames, br.path("driverRow")));
            }
            keep = DeletedRowKeys.keepMask(effKeys, fps, deleted);
        }

        // ⑤ 扁平解析输出。迭代完整 baseRows（行数权威，AP-51），按 keep 跳过删除行；
        //    row_index 随保留行重新连续编号（与 ConfigureSnapshotService 物化输出口径一致）。
        int rowIndex = 0;
        for (int i = 0; i < baseRows.size(); i++) {
            if (keep != null && !keep[i]) continue; // 删除行不写入 row_data
            JsonNode br = baseRows.get(i);
            JsonNode driverRow = br.path("driverRow");
            JsonNode basicDataValues = br.path("basicDataValues");
            String effKey = effKeys.get(i);
            JsonNode editValues = editByKey.get(effKey);   // 本行编辑（INPUT 列覆盖），可空
            JsonNode frValues = frByKey.get(effKey);       // 本行已算 FORMULA 叶子，可空

            // 扁平解析：FORMULA 取已算 values；INPUT 取 editValues 优先（→ driverRow → default_source）。
            Map<String, Object> resolved = formulaCalculator.resolveRowByFieldName(
                    fields, driverRow, basicDataValues, editValues, frValues);

            ObjectNode rowNode = MAPPER.valueToTree(resolved);
            rowNode.put("row_index", rowIndex++);
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
