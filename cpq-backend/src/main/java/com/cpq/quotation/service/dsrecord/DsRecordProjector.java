package com.cpq.quotation.service.dsrecord;

import com.cpq.dataset.fingerprint.RowFingerprints;
import com.cpq.dataset.fingerprint.ValueNormalizer;
import com.cpq.dataset.registry.ColumnDef;
import com.cpq.quotation.rowkey.DeletedRowKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一个页签组件的 {@code snapshot_rows} / {@code row_data} 投影成 {@link DsRecordRow}
 * 并锚到主表基底行（task-260907 第二段 · B-5 / B-6 / B-7 · A0-1 双锚的<b>写入侧</b>）。
 *
 * <h3>为什么锚点要在「拍快照」这一刻算，而不是回填时再算</h3>
 * A0-1 裁决的两个锚（{@code origin_id} / {@code base_row_fingerprint}）语义都是
 * 「<b>拍快照时</b>主表那一行」。回填时主表可能已经被别的单升过版（{@code id} 全换、指纹全变），
 * 那时再想「这条 {@code _record} 当初对应哪一行」就没有依据了。
 *
 * <h3>行主轴 = 两路位置化取数（照 {@code RowKeyUniquenessService} 的既有口径）</h3>
 * <pre>
 *   ① driver 展开行 ← snapshot_rows[i]，按下标 overlay row_data 的**非 manual 子序列**[i]
 *   ② 只活在 row_data 的行（下标 ≥ snapshot_rows.size()）—— 纯 INPUT 页签的正常形态
 *   ③ 手动新增行（`_origin='manual'`）追加末尾
 * </pre>
 * 🚨 <b>①②③ 缺一不可</b>。曾经只做 ① 并把 row_data 当成「回种用户编辑值」的补充，
 * 于是纯 INPUT 页签（{@code snapshot_rows} 本来就是空的）产出恒 0 行，
 * <b>且不抛异常、日志无告警、完全静默</b> —— AC-2（输入值）与 AC-3（自定义列）在那类页签上
 * 永远不可满足。实证：{@code row_data} 有 1 行、{@code snapshot_rows} 为 NULL ⇒ {@code rows=0}；
 * 把 row_data 原样拷进 snapshot_rows 后立刻变 {@code rows=1}。
 * <p>🚫 <b>不许把「有没有这一行」和「能不能回种」混成一个判据</b> —— 那正是上面那个缺陷的形状。
 * 回种闸（长度不等则不覆盖）保留，但它只管覆盖，不再决定行的存在。
 *
 * <h3>🚨 为什么不能靠行序对位</h3>
 * 主表读出无 {@code ORDER BY} 保证，行数一变即整体错位且<b>错位后无法自我发现</b> ——
 * 这正是 {@code AP-54} / {@code repair-0727} 反复出事的形状（写错行 / 删错行）。
 * 本类改用<b>内容对位</b>：拿页签表征列的 <b>driver 原值</b>（= 展开那一刻从主表读出来的值，
 * 尚未被用户编辑）去匹配基底行的同名列，命中即消费（同内容多行时按出现序逐个消费）。
 *
 * <h3>🚨 已知缺口（必须写在这里，别让下一个人以为它已解决）</h3>
 * 取数配置器编译出的 SQL <b>不输出主表行 id</b>（{@code SemanticCompiler} 只发 {@code hf_part_no}
 * + 业务列），而 {@code QuotePendingRewriter.WHITELIST_TABLES} 里<b>零个</b> {@code ds_quote_*} 表
 * ⇒ {@code __v6_id} 锚点对新链路根本不会注入。所以 {@code origin_id} 只能靠本类的内容对位反推。
 * 完全同内容的重复行（{@code 需求文档} §⑥ 实测：{@code VS-FG01} 有两行连
 * {@code input_material_no} 都一样）之间的分配是任意的 —— 但它们的<b>表征列内容相同</b>，
 * patch 结果对这些列等价；差异只可能出现在页签未表征的列上，而那些列本来就<b>原样保留</b>。
 */
public final class DsRecordProjector {

    private static final Logger LOG = Logger.getLogger(DsRecordProjector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DsRecordProjector() {}

    /**
     * @param binding      组件 ↔ sheet 绑定
     * @param sheetAxisColumn 目标 sheet 的轴列名（只活在 {@code row_data} 的行靠它兜底定轴值）
     * @param snapshotRows {@code quotation_line_component_data.snapshot_rows} 原文（可空）
     * @param rowDataJson  {@code row_data} 原文（可空）
     * @param deletedKeys  {@code deleted_row_keys} 原文（可空）
     * @param fallbackAxis {@code driverRow.hf_part_no} 缺失时的兜底轴值（= 产品卡片销售料号）
     * @param sortOrder    组件排序
     * @return 该页签投影出的行（尚未锚定；锚定由 {@link #anchor} 在拿到基底后做）
     */
    public static List<DsRecordRow> project(DsSheetBinding binding, String sheetAxisColumn,
                                            String snapshotRows, String rowDataJson,
                                            String deletedKeys, String fallbackAxis, int sortOrder) {
        List<DsRecordRow> out = new ArrayList<>();
        JsonNode snap = parseArray(snapshotRows);
        JsonNode flat = parseArray(rowDataJson);
        if ((snap == null || snap.size() == 0) && (flat == null || flat.size() == 0)) return out;
        List<DeletedRowKeys.Tombstone> tombstones = DeletedRowKeys.parse(deletedKeys);
        List<String> rkfNames = binding.rowKeyFieldNames();

        // ── 两路位置化取数（照 RowKeyUniquenessService 的既有口径，🚫 不另造一套）──────────
        //    row_data 先按 `_origin` 拆两路：非 manual 的按下标与 snapshot_rows 对位；
        //    manual 的追加末尾（它们没有 driver 侧对应行）。
        List<JsonNode> driverDataRows = new ArrayList<>();
        List<JsonNode> manualRows = new ArrayList<>();
        if (flat != null) {
            for (JsonNode r : flat) {
                if (!r.isObject()) continue;
                if ("manual".equals(r.path("_origin").asText(""))) manualRows.add(r);
                else driverDataRows.add(r);
            }
        }
        int snapSize = snap == null ? 0 : snap.size();

        // ── 回种闸（AP-54）：只在能安全对位时才用 row_data 覆盖 driver 值。
        //    🚫 长度不等时不回种，宁可退化成 driver 原值，也不能把 A 行的编辑值写到 B 行头上。
        //    ⚠️ 这个闸只管「覆盖」，🚫 不再兼任「有没有这一行」—— 两者混在一起正是本轮修的缺陷。
        boolean overlaySafe = snapSize == 0 || driverDataRows.size() == snapSize;
        if (snapSize > 0 && !overlaySafe && !driverDataRows.isEmpty()) {
            LOG.debugf("[ds-record] component=%s row_data 非手动行(%d) 与 snapshot_rows(%d) 不等长，"
                    + "本次不回种用户编辑值（行仍照出）", binding.componentId(), driverDataRows.size(), snapSize);
        }

        // ── ① driver 展开行：snapshot_rows 为主轴，按下标 overlay row_data ────────────────
        for (int i = 0; i < snapSize; i++) {
            JsonNode node = snap.get(i);
            JsonNode driverRow = node.path("driverRow");
            if (!tombstones.isEmpty()) {
                String fp = DeletedRowKeys.rowFingerprint(rkfNames, driverRow);
                JsonNode nid = node.get("__nodeId");
                String nodeId = (nid != null && !nid.isNull()) ? nid.asText(null) : null;
                if (DeletedRowKeys.isDeleted(fp, nodeId, tombstones)) continue;   // 用户删掉的行不进 _record
            }
            JsonNode flatRow = (overlaySafe && i < driverDataRows.size()) ? driverDataRows.get(i) : null;
            addRow(out, binding, sheetAxisColumn, driverRow, flatRow, fallbackAxis, sortOrder);
        }

        // ── ② 只存在于 row_data 的行 —— 🔴 本轮修的就是这一段 ────────────────────────────
        //    纯 INPUT 页签（无 driver 展开）的 snapshot_rows **本来就该是空的**，
        //    它的行只活在 row_data 里。原实现把 row_data 只当「回种用户编辑值」的补充、
        //    主轴仍是 snapshot_rows ⇒ snapshot 为空时产出恒 0，且**不抛异常、完全静默**
        //    ⇒ AC-2（输入值）/ AC-3（自定义列）在这类页签上永远不可满足。
        //    这里不存在错位风险：这些下标在 snapshot 侧根本没有对应行，不是「配错」而是「只有一侧」。
        for (int i = snapSize; i < driverDataRows.size(); i++) {
            addRow(out, binding, sheetAxisColumn, null, driverDataRows.get(i), fallbackAxis, sortOrder);
        }

        // ── ③ 手动新增行（`_origin='manual'`，先例：追加末尾）────────────────────────────
        for (JsonNode mr : manualRows) {
            addRow(out, binding, sheetAxisColumn, null, mr, fallbackAxis, sortOrder);
        }
        return out;
    }

    /**
     * 组装一行 {@link DsRecordRow}。{@code driverRow} 可为 null（只活在 {@code row_data} 里的行：
     * 纯 INPUT 页签行 / 手动新增行）。
     *
     * <p>轴值取值顺序：{@code driverRow.hf_part_no} → {@code row_data.hf_part_no}
     * → 页签表征的轴列值 → 产品卡片销售料号。
     * <b>后两级是给「只活在 row_data 的行」准备的</b> —— 它们没有 driver 侧，拿不到 {@code hf_part_no}。
     */
    private static void addRow(List<DsRecordRow> out, DsSheetBinding binding, String axisColumn,
                               JsonNode driverRow, JsonNode flatRow, String fallbackAxis, int sortOrder) {
        DsRecordRow row = new DsRecordRow();
        row.componentId = binding.componentId();
        row.sortOrder = sortOrder;

        for (Map.Entry<String, String> e : binding.fieldToColumn().entrySet()) {
            JsonNode v = pick(binding, driverRow, flatRow, e.getKey());
            if (v == null || v.isMissingNode()) continue;
            row.columnValues.put(e.getValue(), v.isNull() ? null : nodeToJava(v));
        }
        // B-6：主表对不齐的字段一律进 extend_column（自定义列 / 公式列 / 常量列 / 查名列）。
        // 现网 1257 个字段里 396 个无 default_source + 64 个 FORMULA ⇒ 这是常态不是异常。
        for (String fn : binding.extendFields()) {
            JsonNode v = pick(binding, driverRow, flatRow, fn);
            if (v == null || v.isMissingNode() || v.isNull()) continue;
            row.extendValues.put(fn, nodeToJava(v));
        }
        // B-7 / S-3：元素实时价快照。只在两张 BOM 表上落库，由调用方按 sheet 决定是否写。
        String epf = binding.elementPriceField();
        if (epf != null && !epf.isBlank()) {
            row.elementPrice = toDecimal(pick(binding, driverRow, flatRow, epf));
        }

        row.axisValue = resolveAxis(driverRow, flatRow, row, axisColumn, fallbackAxis);
        if (row.axisValue == null || row.axisValue.isBlank()) return;   // 定不出轴值的行不落 _record
        out.add(row);
    }

    private static String resolveAxis(JsonNode driverRow, JsonNode flatRow, DsRecordRow row,
                                      String axisColumn, String fallbackAxis) {
        String hf = driverRow == null ? null : driverRow.path("hf_part_no").asText(null);
        if (hf != null && !hf.isBlank()) return hf;
        hf = flatRow == null ? null : flatRow.path("hf_part_no").asText(null);
        if (hf != null && !hf.isBlank()) return hf;
        Object axis = axisColumn == null ? null : row.columnValues.get(axisColumn);
        if (axis != null && !String.valueOf(axis).isBlank()) return String.valueOf(axis);
        return fallbackAxis;
    }

    /**
     * A0-1 写入侧：把投影行锚到基底行，填 {@code origin_id} / {@code base_row_fingerprint} /
     * {@code base_version_no}。<b>纯内存</b>，🚫 不查库。
     *
     * <p>锚不上的行（手工新增行、或页签值与主表对不上的行）两个锚都留 null ——
     * 回填时它们会被显式列进 {@code unanchoredRows}，🚫 不静默丢弃、也不静默当新增行插进去。
     */
    public static void anchor(List<DsRecordRow> rows, DsMainTableReader.BaseGroup base,
                              Map<String, ColumnDef> colDefs, java.util.Collection<String> matchColumns) {
        int version = base == null ? 0 : base.versionNo;
        Map<String, Deque<DsMainTableReader.BaseRow>> byKey = new LinkedHashMap<>();
        if (base != null) {
            for (DsMainTableReader.BaseRow br : base.rows) {
                byKey.computeIfAbsent(contentKey(br.values, colDefs, matchColumns), k -> new ArrayDeque<>()).add(br);
            }
        }
        for (DsRecordRow r : rows) {
            r.baseVersionNo = version;
            Deque<DsMainTableReader.BaseRow> q = byKey.get(contentKey(r.columnValues, colDefs, matchColumns));
            DsMainTableReader.BaseRow hit = (q == null || q.isEmpty()) ? null : q.pollFirst();
            if (hit != null) {
                r.originId = hit.id;
                r.baseRowFingerprint = hit.rowFingerprint;
            }
        }
    }

    /** 内容键：只取页签表征的列，按列名字典序、走与行指纹同一套 {@link ValueNormalizer} 归一。 */
    static String contentKey(Map<String, Object> values, Map<String, ColumnDef> colDefs,
                             java.util.Collection<String> matchColumns) {
        List<String> cols = new ArrayList<>(matchColumns);
        java.util.Collections.sort(cols);
        StringBuilder sb = new StringBuilder();
        for (String c : cols) {
            ColumnDef def = colDefs.get(c);
            Object raw = values == null ? null : values.get(c);
            sb.append(def == null ? ValueNormalizer.toRawString(raw)
                                  : ValueNormalizer.normalize(raw, def.type, def.scale));
            sb.append(RowFingerprints.SEPARATOR);
        }
        return sb.toString();
    }

    /** 取值：row_data（用户编辑值）优先 → driverRow[字段名] → driverRow[视图列名]。 */
    private static JsonNode pick(DsSheetBinding binding, JsonNode driverRow, JsonNode flatRow, String fieldName) {
        if (flatRow != null && flatRow.isObject() && flatRow.has(fieldName)) return flatRow.get(fieldName);
        if (driverRow != null && driverRow.has(fieldName)) return driverRow.get(fieldName);
        String viewCol = binding.viewColumnByField().get(fieldName);
        if (viewCol != null && driverRow != null && driverRow.has(viewCol)) return driverRow.get(viewCol);
        return null;
    }

    private static JsonNode parseArray(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode n = MAPPER.readTree(json);
            return (n != null && n.isArray()) ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Object nodeToJava(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.booleanValue();
        if (v.isNumber()) return v.decimalValue();
        return v.asText();
    }

    private static BigDecimal toDecimal(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        try {
            if (v.isNumber()) return v.decimalValue();
            String s = v.asText();
            return (s == null || s.isBlank()) ? null : new BigDecimal(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
