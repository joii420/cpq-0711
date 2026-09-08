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
 * <h3>🚨 锚点键<b>不能</b>用「叠加了用户编辑的整行内容」（实测教训）</h3>
 * 原实现拿 {@code columnValues}（driver 值 ⊕ {@code row_data} 覆盖）当身份键，后果是：
 * <b>用户改任意一个数值 → 那一行就锚不上 → 回填把它当新增追加，原行原样保留 ⇒ 该组翻倍。</b>
 * 财务在界面上看到的却是「本次覆盖 0 / 本次不动 2」—— 与 {@code AP-60} 是镜像形态
 * （那次静默删行，这次静默翻倍）。⇒ 第 1 趟锚点键一律取 {@link DsRecordRow#anchorValues}
 * （拍快照那一刻的 driver 原值），第 2 趟用粒度列兜底。
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
        // 🔑 D-38 修法甲：NULL/空串 ⇒ **从未物化**（driver 还没展开，row_data 里就是 driver 行）；
        //    `[]` ⇒ **已物化、driver 确实返 0 行**（AP-38 形态），那时 row_data 里的行是用户自己录的。
        //    两者在 parseArray 之后只差「返回 null」与「返回 size==0 的数组」，🚫 不许再合并处理。
        final boolean snapshotMaterialized = snap != null;
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
            addRow(out, binding, sheetAxisColumn, driverRow, flatRow, fallbackAxis, sortOrder,
                    DsRecordRow.Provenance.DRIVER);
        }

        // ── ② 只存在于 row_data 的行 —— 🔴 本轮修的就是这一段 ────────────────────────────
        //    纯 INPUT 页签（无 driver 展开）的 snapshot_rows **本来就该是空的**，
        //    它的行只活在 row_data 里。原实现把 row_data 只当「回种用户编辑值」的补充、
        //    主轴仍是 snapshot_rows ⇒ snapshot 为空时产出恒 0，且**不抛异常、完全静默**
        //    ⇒ AC-2（输入值）/ AC-3（自定义列）在这类页签上永远不可满足。
        //    这里不存在错位风险：这些下标在 snapshot 侧根本没有对应行，不是「配错」而是「只有一侧」。
        for (int i = snapSize; i < driverDataRows.size(); i++) {
            // D-38 甲：来源取决于 snapshot **有没有物化过**，🚫 不是「有没有 driver 值」。
            addRow(out, binding, sheetAxisColumn, null, driverDataRows.get(i), fallbackAxis, sortOrder,
                    snapshotMaterialized ? DsRecordRow.Provenance.ROW_DATA_TAIL
                                         : DsRecordRow.Provenance.DRIVER_NOT_MATERIALIZED);
        }

        // ── ③ 手动新增行（`_origin='manual'`，先例：追加末尾）────────────────────────────
        for (JsonNode mr : manualRows) {
            addRow(out, binding, sheetAxisColumn, null, mr, fallbackAxis, sortOrder,
                    DsRecordRow.Provenance.MANUAL);
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
                               JsonNode driverRow, JsonNode flatRow, String fallbackAxis, int sortOrder,
                               DsRecordRow.Provenance provenance) {
        DsRecordRow row = new DsRecordRow();
        row.componentId = binding.componentId();
        row.sortOrder = sortOrder;
        row.provenance = provenance;

        for (Map.Entry<String, String> e : binding.fieldToColumn().entrySet()) {
            JsonNode v = pick(binding, driverRow, flatRow, e.getKey());
            if (v != null && !v.isMissingNode()) {
                row.columnValues.put(e.getValue(), v.isNull() ? null : nodeToJava(v));
            }
            // 🔑 锚点键只取 driver 原值：叠加了用户编辑的值不能当行身份（见 DsRecordRow#anchorValues）
            if (driverRow != null) {
                JsonNode dv = pick(binding, driverRow, null, e.getKey());
                if (dv != null && !dv.isMissingNode()) {
                    row.anchorValues.put(e.getValue(), dv.isNull() ? null : nodeToJava(dv));
                }
            }
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
                              Map<String, ColumnDef> colDefs, java.util.Collection<String> matchColumns,
                              java.util.Collection<String> grainColumns) {
        int version = base == null ? 0 : base.versionNo;
        for (DsRecordRow r : rows) r.baseVersionNo = version;
        if (base == null || base.rows.isEmpty()) return;

        java.util.Set<DsMainTableReader.BaseRow> usedBase =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        // ── 第 1 趟：整行内容精确对位 ────────────────────────────────────────────────
        //    键取「拍快照那一刻的 driver 原值」（anchorValues）；没有 driver 侧的行退回 columnValues
        //    —— 那类行本来就只活在 row_data 里，它的当前值就是它唯一的内容。
        Map<String, Deque<DsMainTableReader.BaseRow>> byContent = new LinkedHashMap<>();
        for (DsMainTableReader.BaseRow br : base.rows) {
            byContent.computeIfAbsent(contentKey(br.values, colDefs, matchColumns),
                    k -> new ArrayDeque<>()).add(br);
        }
        for (DsRecordRow r : rows) {
            Map<String, Object> key = r.anchorValues.isEmpty() ? r.columnValues : r.anchorValues;
            Deque<DsMainTableReader.BaseRow> q = byContent.get(contentKey(key, colDefs, matchColumns));
            DsMainTableReader.BaseRow hit = null;
            while (q != null && !q.isEmpty()) {
                DsMainTableReader.BaseRow c = q.pollFirst();
                if (!usedBase.contains(c)) { hit = c; break; }
            }
            if (hit != null) {
                usedBase.add(hit);
                r.originId = hit.id;
                r.baseRowFingerprint = hit.rowFingerprint;
            }
        }

        // ── 第 2 趟：粒度列兜底 ──────────────────────────────────────────────────────
        // 🔑 为什么必须有这一趟：第 1 趟拿**整行内容**当身份键，于是「用户改了一个数值」的行
        //    必然对不上 ⇒ 回填会把它当新增追加，而原行原样保留 ⇒ **组每回填一次就翻一倍**。
        //    那是 AP-60 的镜像形态（那次静默删行，这次静默翻倍），且生产上改一个数就会触发。
        // 🚫 但**不放宽内容比对**（例如忽略数值精度 / 全走 toRawString）—— 那会让内容真的不同的行
        //    也锚上，后果更重：变成「覆盖到错的行上」，正是 AP-60 的原始形态。
        // ⇒ 改用**独立于值的行身份**：语义节点声明的粒度列（semantic_node.grain_columns）。
        // ⚠️ 需求文档 §⑥ 实测这些键**不保证唯一**，所以只在「两侧该键都唯一」时才认；
        //    有歧义 → 不认，让它进 unanchoredRows 被财务看见（宁可显式上报，也不写到错的行上）。
        if (grainColumns == null || grainColumns.isEmpty()) return;
        List<DsRecordRow> pending = new ArrayList<>();
        for (DsRecordRow r : rows) {
            if (r.originId != null) continue;
            // ── D-36 的闸 + D-38 修法甲（用户 2026-09-07 裁决）─────────────────────────
            // D-36 要防的是：用户在**已存在的组**里新增一行，其粒度键与某既有行相同且基底恰好一条
            //   ⇒ 兜底把它认成那条既有行的 patch ⇒ 新增行消失、既有行被覆盖（AP-60 原始形态）。
            // 🚨 D-38：原判据写的是 `anchorValues.isEmpty()`，而它**同时命中 driver 未物化的行** ——
            //    `_record` 唯一写点是 saveDraft，saveDraft 只保留、不生成 snapshot_rows
            //    ⇒ 「新建产品行 → 首存 → 提交」这条常规路径上，整张单的行都没有 driver 侧。
            //    后果：用户改过值的行第 1 趟必 miss、第 2 趟又被这道闸拦掉 ⇒ 双 null
            //    ⇒ 回填按新增追加 ⇒ **组每回填一次就变大**（A/B/C 三态实测：2→3 行 vs 2→2 行）。
            // ⇒ 改按**行来源**判定（见 DsRecordRow.Provenance）：只关掉真·用户新增行。
            // ⚠️ 组不存在（version == 0）时不设限：那时整组行都还没有，任何行都谈不上「新增覆盖既有」。
            if (r.provenance.userAdded() && version > 0) continue;
            pending.add(r);
        }
        if (pending.isEmpty()) return;

        Map<String, List<DsMainTableReader.BaseRow>> baseByGrain = new LinkedHashMap<>();
        for (DsMainTableReader.BaseRow br : base.rows) {
            if (usedBase.contains(br)) continue;
            baseByGrain.computeIfAbsent(contentKey(br.values, colDefs, grainColumns),
                    k -> new ArrayList<>()).add(br);
        }
        Map<String, List<DsRecordRow>> recByGrain = new LinkedHashMap<>();
        for (DsRecordRow r : pending) {
            Map<String, Object> key = r.anchorValues.isEmpty() ? r.columnValues : r.anchorValues;
            recByGrain.computeIfAbsent(contentKey(key, colDefs, grainColumns), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<DsRecordRow>> e : recByGrain.entrySet()) {
            List<DsMainTableReader.BaseRow> cands = baseByGrain.get(e.getKey());
            // 🚨 只认「两侧都恰好一条」——任一侧有歧义就整组不认
            if (cands == null || cands.size() != 1 || e.getValue().size() != 1) continue;
            DsMainTableReader.BaseRow br = cands.get(0);
            if (usedBase.contains(br)) continue;
            usedBase.add(br);
            DsRecordRow r = e.getValue().get(0);
            r.originId = br.id;
            r.baseRowFingerprint = br.rowFingerprint;
            LOG.debugf("[ds-record][anchor] 粒度列兜底命中 baseRowId=%d grainKey=%s（整行内容不同 = 用户改过值）",
                    br.id, e.getKey().replace('\u001F', '|'));
        }

        logAnchorMiss(rows, base, colDefs, matchColumns);
    }

    /**
     * 两趟都没锚上时，把两侧<b>逐列</b>打出来（DEBUG）。
     *
     * <h3>🚨 别删这段（有两次实证）</h3>
     * 「锚不上」这三个字<b>本身不解释原因</b>。不打这一枪就只能靠猜 —— 实测中主线与我
     * <b>各误判过一次</b>：都把「主表某几列本来就是 NULL」当成了别的原因
     * （一次猜「{@code colDefs} 取不到 def 退化成 {@code toRawString}」，
     * 一次猜「夹具只带部分表征列」）。这段日志把两次误读当场证伪。
     * <p>标记：{@code ≠} = 该列两侧不同；{@code ⚠️} = {@code colDefs} 里没有这一列
     * （那才是真的会退化成 {@code toRawString} 的情形）。
     */
    private static void logAnchorMiss(List<DsRecordRow> rows, DsMainTableReader.BaseGroup base,
                                      Map<String, ColumnDef> colDefs,
                                      java.util.Collection<String> matchColumns) {
        if (!LOG.isDebugEnabled() || base == null) return;
        List<String> cols = new ArrayList<>(matchColumns);
        java.util.Collections.sort(cols);
        for (DsRecordRow r : rows) {
            if (r.originId != null) continue;
            Map<String, Object> key = r.anchorValues.isEmpty() ? r.columnValues : r.anchorValues;
            for (DsMainTableReader.BaseRow br : base.rows) {
                StringBuilder sb = new StringBuilder();
                for (String c : cols) {
                    ColumnDef def = colDefs.get(c);
                    String bv = def == null ? ValueNormalizer.toRawString(br.values.get(c))
                            : ValueNormalizer.normalize(br.values.get(c), def.type, def.scale);
                    String rv = def == null ? ValueNormalizer.toRawString(key.get(c))
                            : ValueNormalizer.normalize(key.get(c), def.type, def.scale);
                    sb.append("\n      ").append(bv.equals(rv) ? "  " : "\u2260 ").append(c)
                      .append(" : base=[").append(bv).append("] record=[").append(rv).append(']')
                      .append(def == null ? "   \u26a0\ufe0f colDefs 无此列 \u2192 退化 toRawString" : "");
                }
                LOG.debugf("[ds-record][anchor-miss] recordAxis=%s baseRowId=%d 逐列对照：%s",
                        r.axisValue, br.id, sb);
            }
        }
    }

    /** 内容键：只取页签表征的列，按列名字典序、走与行指纹同一套 {@link ValueNormalizer} 归一。 */
    public static String contentKey(Map<String, Object> values, Map<String, ColumnDef> colDefs,
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
