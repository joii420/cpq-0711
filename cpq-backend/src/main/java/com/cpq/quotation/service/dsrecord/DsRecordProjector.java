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
 *
 * <h3>🔴 D-45：树页签的<b>合成根行</b>不进 {@code _record}（2026-09-08 主线亲验抓到，用户裁决甲）</h3>
 * 树页签的行主轴是 <b>spine 节点</b>而不是业务行（{@code BomTreeRenderService#render} ⑤：
 * 「树页签：以卡片的 spine 节点为行主轴，缺数据补空行」）。其中<b>根节点</b>是渲染构件 ——
 * 它代表的是<b>成品本身</b>，而不是一条 BOM 边，主表里根本没有与之对应的行
 * ⇒ 四层锚定全部落空 ⇒ 回填按「新增行」追加 ⇒ <b>该组每回填一次就多一行</b>。
 * <p>🔬 实证（{@code QT-20260908-0615}，用户一个字没改）：{@code _record(material_bom)} 只有 1 行、
 * {@code input_material_no=NULL}、{@code origin_id=NULL}、{@code base_row_fingerprint=NULL}，
 * 而主表 {@code T260907T-FG01} 组有 3 行（{@code RM01/RM02/SC01}）；
 * {@code ds_quote_material_bom} 全表 103 行里 9 行 {@code input_material_no IS NULL}，
 * 其中 <b>8 行 {@code source=QUOTE_BACKFILL}</b>（就是这么长出来的）。
 * <p>⇒ 挂点在<b>投影侧</b>（本类），🚫 不在回填侧过滤 —— 乙案（回填侧丢弃）被用户否掉，
 * 理由是「{@code _record} 本身仍脏，别的消费者照样中招」。判定见 {@link #representsNoBaseRow}。
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
        List<String> syntheticNodeIds = new ArrayList<>();   // D-45：本次跳过的合成根行（日志留痕用）

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
            // ── 🔴 D-45（用户裁决甲）：树页签的合成根行不表征任何主表行 ⇒ 不进 _record ──
            //    🚨 这是 representsNoBaseRow 的**唯一**调用点（🚫 不许在别的分支再判一次）。
            //    ②③ 两路（只活在 row_data 的行 / 手动行）没有 snapshot 侧节点，结构上不可能是
            //    合成根行，所以那里不判也不会漏。日志在下面统一打 —— 谓词本身零副作用。
            if (representsNoBaseRow(node)) {
                syntheticNodeIds.add(String.valueOf(text(node, "__nodeId")));
                continue;
            }
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
        // D-45 留痕：🚫 不许静默跳过 —— 静默会让「这行为什么没进 _record」变成查不出原因的哑谜。
        // 口径与 DsQuoteRecordService 的 crossCardDeduped=%d 对齐（都是「本次少了几行、为什么」）。
        if (!syntheticNodeIds.isEmpty()) {
            LOG.infof("[ds-record] component=%s axis=%s syntheticRootSkipped=%d nodeIds=%s"
                            + " —— 树页签合成根行（spine 根 = 成品本身，主表无对应边行），"
                            + "🚫 这不是数据丢失，是不该写进去的渲染构件",
                    binding.componentId(), fallbackAxis, syntheticNodeIds.size(), syntheticNodeIds);
        }
        return out;
    }

    /**
     * <b>这一行是不是「不表征任何主表行」的渲染构件</b>（D-45 · 甲）。
     *
     * <h3>判据（两个<b>结构</b>信号的合取，🚫 不看任何业务列的值）</h3>
     * <ol>
     *   <li><b>它是树页签的行</b> —— 携带 spine 节点身份 {@code __nodeId}。
     *       这是本工程判「树行」的既有权威口径：{@code TreeRelations#isTreeRows}、
     *       {@code CardSnapshotService} 的剪枝、{@code injectTreeAttrsForCrossTab} 全都只认它；</li>
     *   <li><b>它是 spine 的根</b> —— 没有父边（{@code __parentId} 与 {@code __parentNo} 皆空）。
     *       两个都要求空是<b>刻意从严</b>：{@code TreeRelations} 按 {@code __parentId} 连边，
     *       {@code ConfigureSnapshotService}（「无父 = 该行树的根 = 成品」）按 {@code __parentNo} 判根，
     *       两个权威口径都说是根，才跳过。有分歧 → 不跳过（宁可留一行脏，也不误伤真实行）。</li>
     * </ol>
     *
     * <h3>为什么「根节点」⇒「主表里没有这一行」</h3>
     * 树页签的行主轴是 spine 节点，每个节点的业务行按<b>边键</b>取
     * （{@code BomTreeRenderService#edgeKey(parentNo, materialNo)}）；主表存的就是这些<b>边</b>
     * ——实测 {@code ds_quote_material_bom} 的 {@code (material_no, input_material_no)}
     * 即 {@code (父件, 子件)}（证据行：{@code id=12007} 的 {@code material_no=T260907T-RM01} /
     * {@code input_material_no=T260907T-RM03}，可见 {@code material_no} 是<b>父件</b>而非成品）。
     * 根节点<b>没有父</b> ⇒ 它不是任何一条边 ⇒ 主表不可能有它。
     *
     * <h3>🚨 为什么<b>不能</b>写成「粒度列为 NULL」（用户裁决的硬约束）</h3>
     * 两者在 {@code MATERIAL_BOM}（{@code grain_columns={input_material_no}}）上<b>恰好重合</b>，
     * 但它们不是同一件事，字面判据有两个方向的错：
     * <ul>
     *   <li><b>误杀</b>：某张表的粒度列若合法可空，真实行会被当成构件丢掉；</li>
     *   <li><b>更隐蔽的误杀</b>：用户在界面上把一条<b>真实边行</b>的「投入料号」清空，
     *       按字面判据它当场变成「构件」被跳过 ⇒ <b>静默吞掉用户的编辑</b>。
     *       结构信号不看值：用户改什么都不会让一行变成/不再是 spine 根。</li>
     * </ul>
     * 另外两者<b>同源性</b>也不同：{@code __nodeId/__parentId} 由唯一产出点
     * {@code BomTreeRenderService#treeRowNode} 写死；粒度列来自 {@code semantic_node.grain_columns}，
     * 是<b>可被取数配置器改的配置数据</b>，拿它当行身份判据等于把判据交给配置。
     *
     * <h3>🔑 升级路径（本期迁移冻结，只留形状）</h3>
     * 甲的代价是<b>静默的结构性丢失</b>：事后从 {@code _record} 看不出「这里本来有一行根行」。
     * 将来 {@code DsRecordRow.Provenance} 落到 {@code _record} 的列上
     * （{@code DsBackfillCollector#grainFallbackEligible} 的 Javadoc 记的那个已知残留），
     * 应当把<b>唯一调用点</b>从「跳过不写」改成「写入并标记 {@code SYNTHETIC}」，
     * 回填侧改按该标记过滤。一个列同时解决三件事：
     * ① 本缺陷不再污染主表；② {@code _record} 将来若当渲染源，拿得到根行；
     * ③ 读侧 {@code grainFallbackEligible} 不必再靠「两个锚是否都空」<b>推断</b>行来源。
     * <p>⚠️ 改的时候只动本谓词的<b>那一个</b>调用点即可 —— 这正是它被抽成单一谓词的原因。
     *
     * @param snapshotRow {@code snapshot_rows[i]} 的<b>顶层</b>节点（系统列挂在顶层，
     *                    <b>不</b>在 {@code driverRow} 里 —— 见 {@code TreeRelations} 类注释）
     * @return true = 渲染构件，不表征任何主表行
     */
    static boolean representsNoBaseRow(JsonNode snapshotRow) {
        if (snapshotRow == null || !snapshotRow.isObject()) return false;
        if (text(snapshotRow, "__nodeId") == null) return false;              // 信号①：不是树页签行
        return text(snapshotRow, "__parentId") == null                        // 信号②：没有父边 = spine 根
                && text(snapshotRow, "__parentNo") == null;
    }

    /**
     * 取文本；缺失 / JSON null / 空白 一律返 null。
     * <p>对齐 {@code TreeRelations#text}（该方法判的是 {@code isEmpty}）——本方法把<b>纯空白串</b>
     * 也算空，比它<b>略严</b>，方向与 {@code ConfigureSnapshotService} 判根用的
     * {@code parentNo != null && !parentNo.isBlank()} 一致。
     */
    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 组装一行 {@link DsRecordRow}。{@code driverRow} 可为 null（只活在 {@code row_data} 里的行：
     * 纯 INPUT 页签行 / 手动新增行）。
     *
     * <p>轴值取值顺序见 {@link #resolveAxis}（D-46 后：<b>本 sheet 轴列的实际值优先</b>）。
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

    /**
     * 这一行落在哪个<b>轴值组</b>（{@code _record} 的组粒度 = {@code (quotation_id, 客户号, 轴值)}）。
     *
     * <h3>🔴 D-46：优先级原本是反的（2026-09-08 主线亲验抓到，第三个洞）</h3>
     * 原顺序是 {@code driverRow.hf_part_no} → {@code row_data.hf_part_no} → <b>轴列实际值</b> → 兜底。
     * <p>🔑 权威的是「<b>这一行在本 sheet 的轴列上填的是什么</b>」——
     * {@code _record} 要落进 {@code sheet.axisColumn} 的就是这个值（见
     * {@code DsQuoteRecordService#insertRows}：轴列一律写 {@code row.axisValue}）。
     * {@code hf_part_no} 只是 driver 视图的<b>通用</b>列，它跟轴列是不是同一回事，<b>由页签语义决定</b>。
     *
     * <h3>实证：两者在 13 个页签上恒等，只有树页签分叉</h3>
     * <pre>
     *   QT-20260908-0615 全部组件 snapshot_rows 逐行比对 hf_part_no vs 「*_销售料号」：
     *     物料BOM(树)      同 0 / 异 14   ← 唯一分叉点
     *     物料与元素BOM     同 8 / 异 0
     *     其余 11 个页签    同 5 / 异 0（每个）
     * </pre>
     * 树页签的 driver 语义是 {@code (parent_no=父件, hf_part_no=子件)}，而主表
     * {@code ds_quote_material_bom} 的轴列 {@code material_no} 是<b>父件</b>
     * （实测边行 {@code (material_no=T260907T-RM01, input_material_no=T260907T-RM03)}）
     * ⇒ 老顺序把每条 BOM 边行的轴值算成<b>子件</b>，全部落在别的组里
     * ⇒ 被 {@code DsQuoteRecordService} 的组过滤丢掉
     * ⇒ {@code ds_quote_material_bom_record} 实测 <b>64 行 100% 是合成根行</b>
     * （{@code input_material_no} 全 NULL，涉 30 张单），<b>一条真实 BOM 边行都没有</b>
     * ⇒ 物料BOM 从来没有被报价单回填过。D-45 堵掉根行之后它会恒为 0 行 ——
     * <b>不是 D-45 造成的，是 D-45 掩盖了它</b>。
     *
     * <h3>为什么取 {@code columnValues} 而不是 {@code anchorValues}</h3>
     * {@code anchorValues}（driver 原值）是<b>行身份</b>键（见类注释「锚点键不能用叠加编辑的整行内容」）；
     * 「这一行属于哪个组」问的是<b>当前状态</b>，与 {@code insertRows} 真正写进轴列的值必须是同一个。
     * 🚫 两者不许混用：身份键用当前值会让改数的行锚不上（已实证的翻倍事故），
     * 组键用原值则会让行写进 A 组、轴列却显示 B 值。
     *
     * <p>后两级（{@code hf_part_no} / {@code fallbackAxis}）现在是<b>纯兜底</b>：
     * 页签没表征轴列时（{@code fieldToColumn} 里没有 {@code axisColumn}）才会走到，
     * 「只活在 {@code row_data} 的行」也靠它们。
     */
    private static String resolveAxis(JsonNode driverRow, JsonNode flatRow, DsRecordRow row,
                                      String axisColumn, String fallbackAxis) {
        // 🔑 D-46：本 sheet 轴列在这一行上的实际值 = 权威。🚫 不许再把 hf_part_no 提到它前面。
        Object axis = axisColumn == null ? null : row.columnValues.get(axisColumn);
        if (axis != null && !String.valueOf(axis).isBlank()) return String.valueOf(axis);
        String hf = driverRow == null ? null : driverRow.path("hf_part_no").asText(null);
        if (hf != null && !hf.isBlank()) return hf;
        hf = flatRow == null ? null : flatRow.path("hf_part_no").asText(null);
        if (hf != null && !hf.isBlank()) return hf;
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
