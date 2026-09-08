package com.cpq.quotation.service.dsrecord;

import com.cpq.dataset.registry.ColumnDef;
import com.cpq.dataset.registry.SheetDef;
import com.cpq.dataset.support.SqlIdent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 按「表 × 轴值」把 {@code _record} 组装成回填 patch（task-260907 第二段 · B-9）。
 *
 * <h3>服务的 AC</h3>
 * AC-6（patch 语义升版）· AC-10（跨版以库当前版为基底）· AC-13（AP-60 守卫）· AC-20（双锚三支路）
 *
 * <h3>🚨 形状（逐条对照 AP-60 的「正确形状」，改本类前先读那一节）</h3>
 * <ol>
 *   <li><b>基底 = 主表该组的真实整组行</b>（{@link DsMainTableReader#readGroups}），
 *       🚫 <b>不是</b> {@code _record} 的行集 —— {@code _record} 是页签投影，
 *       一个页签往往只表征组内部分行、部分列；</li>
 *   <li><b>遍历主轴 = 基底行</b>，🚫 严禁「遍历 {@code _record} 生成行」；</li>
 *   <li><b>双锚对位</b>（A0-1）：同版按 {@code origin_id}；跨版按 {@code base_row_fingerprint}
 *       在当前组内重锚；都锚不上 → 进 {@code unanchoredRows}<b>显式上报</b>；</li>
 *   <li><b>列级 patch</b>：只覆盖该页签表征的列（{@code columnScope}），
 *       页签没表征的列<b>原样保留</b>，🚫 不许写 NULL；</li>
 *   <li><b>删除必须有显式墓碑</b>：本轮 {@code _record} <b>没有</b>墓碑通道
 *       ⇒ 回填<b>永不删行</b>。「没出现」不等于「用户删了它」，可能只是这个视图本来就不查它。</li>
 * </ol>
 *
 * <h3>🚫 N+1 硬指标</h3>
 * SQL 条数 = 4（客户号 + 明细行 + 组件数据 + 绑定解析里的 3 条，共 6）
 * + 每张命中 sheet 的 (1 读基底 + 1 读 {@code _record} + 1 读 {@code _history} 最大版本)。
 * <b>与轴值数、表数（表数固定 13）、行数无关</b>。所有循环体内无任何查询。
 */
@ApplicationScoped
public class DsBackfillCollector {

    private static final Logger LOG = Logger.getLogger(DsBackfillCollector.class);

    static final String REASON_SAME_VERSION_ORIGIN_MISS = "SAME_VERSION_ORIGIN_MISS";
    static final String REASON_CROSS_VERSION_FP_MISS = "CROSS_VERSION_FINGERPRINT_MISS";
    static final String REASON_NO_ANCHOR = "NO_ANCHOR";

    /** C′（D-37）：该组存在撞键的「本该锚上却没锚上」的行 ⇒ 整组跳过回填。 */
    public static final String BLOCKED = "BLOCKED";
    /** 目前 {@link #BLOCKED} 的唯一原因；做成常量以便扩。 */
    public static final String BLOCKED_GRAIN_KEY_COLLISION = "GRAIN_KEY_COLLISION";

    @Inject EntityManager em;
    @Inject DsSheetBindingResolver bindingResolver;
    @Inject DsMainTableReader mainTableReader;
    /**
     * D-31：目标版本号<b>不自己算</b>，一律问 {@link com.cpq.dataset.versioning.VersionedGroupWriter
     * #predictNextVersion}。
     * <p>🚫 曾经在本类里写过一遍 {@code max(当前, _history 最大) + 1} —— 那是版本号规则的第二实现，
     * 规则一改预览就<b>静默显示错的目标版本号</b>，而财务正照着它做判断。
     */
    @Inject com.cpq.dataset.versioning.VersionedGroupWriter versionedGroupWriter;
    /** D-35：读「_record 快照过期」标记，预览必须显式报出来。 */
    @Inject DsRecordStaleService staleService;

    /** 只读、无副作用、幂等（与既有 preview 同语义，AC-7 / AC-14 靠它）。 */
    @Transactional(Transactional.TxType.SUPPORTS)
    public DsBackfillPlan collect(UUID quotationId) {
        DsBackfillPlan plan = new DsBackfillPlan();
        plan.quotationId = quotationId;
        plan.applicable = false;
        if (quotationId == null) return plan;

        // ── ① 客户号 + 本单涉及的组件（列作用域从组件绑定推，不从 _record 行推）──────────
        @SuppressWarnings("unchecked")
        List<Object> cust = em.createNativeQuery(
                        "SELECT c.code FROM quotation q JOIN customer c ON c.id = q.customer_id WHERE q.id = :id")
                .setParameter("id", quotationId).getResultList();
        plan.customerNo = cust.isEmpty() || cust.get(0) == null ? null : String.valueOf(cust.get(0));
        // D-35：先读标记 —— 🚨 必须在任何 early return **之前**，否则「本单没有可回填组件」
        //    这条路径会把过期警告一起吞掉，而那恰恰是最该报警的场景之一。
        plan.recordStale = staleService.find(quotationId);   // 1 条 SQL

        @SuppressWarnings("unchecked")
        List<UUID> lineIds = em.createNativeQuery(
                        "SELECT id FROM quotation_line_item WHERE quotation_id = :qid")
                .setParameter("qid", quotationId).getResultList();
        if (lineIds.isEmpty()) { plan.applicable = plan.recordStale != null; return plan; }

        @SuppressWarnings("unchecked")
        List<UUID> compIds = em.createNativeQuery(
                        "SELECT DISTINCT component_id FROM quotation_line_component_data " +
                        "WHERE line_item_id IN (:ids) AND component_id IS NOT NULL")
                .setParameter("ids", lineIds).getResultList();
        if (compIds.isEmpty()) { plan.applicable = plan.recordStale != null; return plan; }

        DsSheetBindingResolver.Resolution res = bindingResolver.resolveAll(compIds);   // 3 条 SQL
        Map<UUID, DsSheetBinding> bindings = res.bindings();
        plan.nonParticipating.addAll(res.nonParticipating());                    // D-33：🚫 不许静默丢弃
        if (bindings.isEmpty() && plan.nonParticipating.isEmpty() && plan.recordStale == null) {
            return plan;                                   // 真的没什么可说 → 不渲染该区
        }

        // 纯内存：sheet → 列作用域（多个页签共用同一张表时取并集）+ extendColumnOnly
        // （bindings 为空时这个循环不执行，plan.tables 保持空，但 nonParticipating 仍会呈现）
        Map<String, SheetDef> sheetByTable = new LinkedHashMap<>();
        Map<String, Set<String>> scopeByTable = new LinkedHashMap<>();
        Map<String, Set<String>> grainByTable = new LinkedHashMap<>();
        for (DsSheetBinding b : bindings.values()) {
            sheetByTable.putIfAbsent(b.sheet().tableName, b.sheet());
            scopeByTable.computeIfAbsent(b.sheet().tableName, k -> new LinkedHashSet<>())
                    .addAll(b.fieldToColumn().values());
            grainByTable.computeIfAbsent(b.sheet().tableName, k -> new LinkedHashSet<>())
                    .addAll(b.grainColumns());
            if (!b.extendFields().isEmpty()) {
                plan.extendColumnOnly.computeIfAbsent(b.sheet().sheetKey, k -> new LinkedHashSet<>())
                        .addAll(b.extendFields());
            }
        }
        // ⚠️ D-33：即使一个组件都绑不上（全是手写视图），只要有「不参与」清单要给财务看，
        //    本段也必须 applicable=true —— 否则前端整块不渲染，警告恰好在最该出现的场景里消失。
        plan.applicable = true;

        // ── ② 逐 sheet 组装（sheet 数固定 ≤13，与轴值数无关）───────────────────────
        for (Map.Entry<String, SheetDef> se : sheetByTable.entrySet()) {
            SheetDef sheet = se.getValue();
            Set<String> scope = scopeByTable.getOrDefault(se.getKey(), Set.of());
            Set<String> grain = grainByTable.getOrDefault(se.getKey(), Set.of());

            List<RecordRow> records = readRecords(sheet, quotationId, plan.customerNo);   // 1 条 SQL
            if (records.isEmpty()) continue;

            Map<String, List<RecordRow>> byAxis = new LinkedHashMap<>();
            for (RecordRow r : records) byAxis.computeIfAbsent(r.axisValue, k -> new ArrayList<>()).add(r);

            Map<String, DsMainTableReader.BaseGroup> base =
                    mainTableReader.readGroups(sheet, byAxis.keySet(), plan.customerNo);  // 1 条 SQL
            // D-31：目标版本号问 writer（只读、2 条 SQL、与轴值数无关），🚫 不在本类里再算一遍规则。
            // 🚨 2026-09-07 合并 customer_dim：轴已是复合键 ⇒ **必须带客户维度**。
            //    漏掉客户号 = 同一料号在客户 A 下显示出客户 B 的版本号，而财务正照着这个数字确认。
            //    🚫 绝不能退化成 currentVersionAnyCustomer 的跨客户 max（writer 的 javadoc 点名禁止）。
            //    AxisKey.of(sheet, ...) 自带 arity 判断：核价两套无客户维度时自动丢弃客户号。
            Map<String, com.cpq.dataset.versioning.AxisKey> axisKeys = new LinkedHashMap<>();
            for (String av : byAxis.keySet()) {
                axisKeys.put(av, com.cpq.dataset.versioning.AxisKey.of(sheet, plan.customerNo, av));
            }
            Map<com.cpq.dataset.versioning.AxisKey, Integer> predictedByKey =
                    versionedGroupWriter.predictNextVersion(sheet, axisKeys.values());
            Map<String, Integer> predicted = new LinkedHashMap<>();
            for (Map.Entry<String, com.cpq.dataset.versioning.AxisKey> e : axisKeys.entrySet()) {
                predicted.put(e.getKey(), predictedByKey.getOrDefault(e.getValue(), 1));
            }

            DsBackfillPlan.Table t = new DsBackfillPlan.Table();
            t.sheet = sheet;
            for (Map.Entry<String, List<RecordRow>> ae : byAxis.entrySet()) {
                // 纯内存（🚫 循环体内无查询）
                t.groups.add(buildGroup(sheet, scope, grain, ae.getKey(), ae.getValue(),
                        base.get(ae.getKey()), predicted.getOrDefault(ae.getKey(), 1), plan.customerNo));
            }
            if (!t.groups.isEmpty()) plan.tables.add(t);
        }
        LOG.debugf("[ds-backfill] quotation=%s 计划：tables=%d（sql 与轴值数无关）",
                quotationId, plan.tables.size());
        return plan;
    }

    // ==================================================================
    // 组装一个「表 × 轴值」组 —— 本段风险最高的一段，逐条对照 AP-60
    // ==================================================================

    /**
     * @param predictedVersionNo {@code VersionedGroupWriter.predictNextVersion} 给出的「真写会拿到的版本号」
     *                           （D-31）。🚫 本方法不许自己算版本号。
     */
    private DsBackfillPlan.Group buildGroup(SheetDef sheet, Set<String> scope, Set<String> grain, String axisValue,
                                            List<RecordRow> records, DsMainTableReader.BaseGroup base,
                                            int predictedVersionNo, String customerNo) {
        DsBackfillPlan.Group g = new DsBackfillPlan.Group();
        g.axisValue = axisValue;
        g.customerNo = customerNo;
        g.currentVersionNo = base == null ? 0 : base.versionNo;
        g.baseVersionNo = records.isEmpty() ? 0 : records.get(0).baseVersionNo;
        g.crossVersion = g.baseVersionNo != g.currentVersionNo;
        g.baseRowCount = base == null ? 0 : base.rows.size();

        List<ColumnDef> cols = sheet.persistedColumns();
        for (ColumnDef c : cols) {
            if (scope.contains(c.name)) g.patchedColumns.add(c.name);
            else g.preservedColumns.add(c.name);
        }

        // ── 双锚对位（A0-1 三支路）。全部纯内存。──────────────────────────────────
        Map<Long, RecordRow> byOrigin = new HashMap<>();
        Map<String, Deque<RecordRow>> byFingerprint = new LinkedHashMap<>();
        for (RecordRow r : records) {
            if (r.originId != null) byOrigin.putIfAbsent(r.originId, r);
            if (r.baseRowFingerprint != null && !r.baseRowFingerprint.isBlank()) {
                byFingerprint.computeIfAbsent(r.baseRowFingerprint.trim(), k -> new ArrayDeque<>()).add(r);
            }
        }
        Set<RecordRow> consumed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Map<Long, RecordRow> pairing = new java.util.HashMap<>();           // 基底行 id → 命中的 _record 行

        // ── 配对（三支路，全部纯内存）——🔑 必须先配完再建 resultRows，否则第 3 支路命中的行
        //    会因为基底行早已 emit 而补不上 patch。
        if (base != null) {
            for (DsMainTableReader.BaseRow br : base.rows) {
                RecordRow hit = null;
                if (!g.crossVersion) {
                    // ① 同版：origin_id 精确对位，零歧义
                    RecordRow cand = byOrigin.get(br.id);
                    if (cand != null && !consumed.contains(cand)) hit = cand;
                } else {
                    // ② 跨版：主表行 id 已全部失效（UPGRADED = 归档→整组 DELETE→重新 INSERT），
                    //    改用拍快照时的整行指纹在当前组内重锚。
                    Deque<RecordRow> q = br.rowFingerprint == null ? null
                            : byFingerprint.get(br.rowFingerprint.trim());
                    while (q != null && !q.isEmpty()) {
                        RecordRow cand = q.pollFirst();
                        if (!consumed.contains(cand)) { hit = cand; break; }
                    }
                }
                if (hit != null) { consumed.add(hit); pairing.put(br.id, hit); }
            }

            // ── ③ 粒度列兜底（与写入侧 DsRecordProjector.anchor 同一条纪律）───────────────
            // 🔑 为什么必须有：①② 都可能整体失效 —— 拍快照时主表还没有这一组（origin_id/指纹全 NULL），
            //    之后别的单把这一组建了出来。此时 _record 的每一行都锚不上 ⇒ 回填把它们当新增追加，
            //    而基底行原样保留 ⇒ **该组每回填一次就翻一倍**。实测：v1 的 2 行 → 回填后 4 行，
            //    而财务在界面上看到的是「本次覆盖 0 / 本次不动 2」——AP-60 的镜像形态（静默翻倍）。
            // 🚫 不放宽 ①② 的比对（那会写到错的行上，是 AP-60 的原始形态）；
            //    改用**独立于值的行身份** = 语义节点声明的粒度列。
            // ⚠️ 需求文档 §⑥ 实证这些键**不保证唯一** ⇒ 只在「两侧都恰好一条」时才认，
            //    有歧义一律不认，让它进 unanchoredRows 被财务看见。
            if (grain != null && !grain.isEmpty()) {
                Map<String, List<DsMainTableReader.BaseRow>> baseByGrain = new LinkedHashMap<>();
                for (DsMainTableReader.BaseRow br : base.rows) {
                    if (pairing.containsKey(br.id)) continue;
                    baseByGrain.computeIfAbsent(
                            DsRecordProjector.contentKey(br.values, colDefsOf(sheet), grain),
                            k -> new ArrayList<>()).add(br);
                }
                Map<String, List<RecordRow>> recByGrain = new LinkedHashMap<>();
                for (RecordRow r : records) {
                    if (consumed.contains(r)) continue;
                    if (!grainFallbackEligible(r)) continue;      // 🚨 见 grainFallbackEligible 的注释
                    recByGrain.computeIfAbsent(
                            DsRecordProjector.contentKey(r.values, colDefsOf(sheet), grain),
                            k -> new ArrayList<>()).add(r);
                }
                for (Map.Entry<String, List<RecordRow>> e : recByGrain.entrySet()) {
                    List<DsMainTableReader.BaseRow> cands = baseByGrain.get(e.getKey());
                    if (cands == null || cands.size() != 1 || e.getValue().size() != 1) continue;
                    DsMainTableReader.BaseRow br = cands.get(0);
                    if (pairing.containsKey(br.id)) continue;
                    RecordRow r = e.getValue().get(0);
                    consumed.add(r);
                    pairing.put(br.id, r);
                    LOG.debugf("[ds-backfill][anchor] 粒度列兜底命中 baseRowId=%d（origin_id/指纹都对不上）", br.id);
                }
            }
        }

        // 🚨 遍历主轴 = 基底行。禁止「遍历 _record 生成行」。
        if (base != null) {
            for (DsMainTableReader.BaseRow br : base.rows) {
                RecordRow hit = pairing.get(br.id);

                Map<String, Object> out = new LinkedHashMap<>(br.values);   // 基底整行，逐列保留
                if (hit != null) {
                    // 🚨 列级 patch：只覆盖该页签表征的列。
                    //    scope 之外的列一个字节不碰（AP-60 列维度共因：REBUILD 只写页签暴露的列，
                    //    其余物理列写 NULL ⇒ 每次通过静默抹一批列）。
                    for (String col : g.patchedColumns) {
                        if (!hit.values.containsKey(col)) continue;
                        out.put(col, hit.values.get(col));
                    }
                    out.put(sheet.axisColumn, axisValue);                   // 轴列恒为本组轴值
                    g.patchedRows++;
                } else {
                    g.untouchedRows++;   // 🔑 页签没表征这一行 ⇒ 原样保留。🚫 不是「删除」
                }
                g.resultRows.add(out);
            }
        }

        // ── 锚不上的 _record 行：显式上报 + 按新增追加（AC-20 第三支路③）──────────────
        List<RecordRow> unanchoredRecs = new ArrayList<>();
        for (RecordRow r : records) {
            if (consumed.contains(r)) continue;
            unanchoredRecs.add(r);
            DsBackfillPlan.Unanchored u = new DsBackfillPlan.Unanchored();
            u.recordId = r.recordId;
            u.originId = r.originId;
            u.baseRowFingerprint = r.baseRowFingerprint;
            u.reason = (r.originId == null && r.baseRowFingerprint == null) ? REASON_NO_ANCHOR
                    : (g.crossVersion ? REASON_CROSS_VERSION_FP_MISS : REASON_SAME_VERSION_ORIGIN_MISS);
            u.displayValues.put(sheet.axisColumn, axisValue);
            for (String grainCol : displayColumns(sheet)) {
                Object v = r.values.get(grainCol);
                if (v != null) u.displayValues.put(grainCol, v);
            }
            g.unanchoredRows.add(u);

            // 🚨 追加，不是替换：AC-20③ 断言「该行按新增写入且**主表原行未被静默删除**」。
            //    基底行已经全部在 resultRows 里了，这里只是往后加一行。
            Map<String, Object> add = new LinkedHashMap<>();
            for (ColumnDef c : sheet.persistedColumns()) {
                if (r.values.containsKey(c.name)) add.put(c.name, r.values.get(c.name));
            }
            add.put(sheet.axisColumn, axisValue);
            g.resultRows.add(add);
        }

        // ── 判定预测（与 writer 同一套指纹口径；权威判定仍由 VersionedGroupWriter 做）──────
        if (base == null || base.rows.isEmpty()) {
            g.result = com.cpq.dataset.versioning.VersionedGroupWriter.CREATED;
            g.targetVersionNo = predictedVersionNo;      // 库+历史都空时 writer 同样给 1
        } else {
            List<String> dbFps = new ArrayList<>(base.rows.size());
            for (DsMainTableReader.BaseRow br : base.rows) dbFps.add(br.rowFingerprint);
            List<String> newFps = com.cpq.dataset.fingerprint.DatasetFingerprints.computeAll(sheet, g.resultRows);
            if (com.cpq.dataset.fingerprint.RowFingerprints.sameMultiset(dbFps, newFps)) {
                g.result = com.cpq.dataset.versioning.VersionedGroupWriter.UNCHANGED;
                g.targetVersionNo = g.currentVersionNo;   // 一行不写，连 updated_at 都不动（AC-14）
                applyUnchangedContract(g, sheet);         // 🔑 四项计数必须回填成「什么都不写」
            } else {
                g.result = com.cpq.dataset.versioning.VersionedGroupWriter.UPGRADED;
                g.targetVersionNo = predictedVersionNo;  // D-31：规则只有一份，在 writer 里
            }
        }
        applyCollisionGuard(g, sheet, grain, base, unanchoredRecs);   // C′（D-37）
        return g;
    }

    /**
     * <b>C′ · 歧义膨胀拦截</b>（D-37，用户 2026-09-07 裁决本期落地）。
     *
     * <h3>判据（不变量，🚫 无任何数字阈值）</h3>
     * <pre>
     *   该组存在 r ∈ unanchoredRows，使得 grainKey(r) ∈ grainKeys(baseRows)
     *   ⇒ 整组判 BLOCKED，跳过回填
     * </pre>
     * <b>为什么这个式子就够</b>：真正的新增行，其粒度键在基底里<b>不存在</b>；
     * 粒度键已经在基底里、却没锚上，只有一种解释 —— <b>本该锚上却没锚上</b>
     * （多半是该键在组内重复、第③层按「两侧都恰好一条」不敢认）。再写下去就是
     * 「原行保留 + 追加一份」⇒ <b>组翻倍</b>，而预览显示「本次覆盖 0 / 本次不动 N」。
     * <p>🚫 <b>刻意不写「连续 N 次」「超过 K 倍」这类阈值</b>：本项目在「判据写具体数字」上
     * 栽过三次（{@code AC-113}/{@code AC-122} 同一条 AC 上三处数字全过期）。而且阈值式的本质是
     * 「允许坏事发生 N 次之后才拦」。
     *
     * <h3>三个边界（逐条对应主线的追问）</h3>
     * <ol>
     *   <li><b>粒度列为空 / 粒度键退化为空</b>：直接<b>不适用</b>。否则空键会与基底里任何一个
     *       空键行「相等」，把整组误判成 BLOCKED —— 那是把一个诊断不足的场景升级成阻断。
     *       判据是「grain 非空 <b>且</b> 该行至少有一个粒度列取值非空」。</li>
     *   <li><b>真新增行撞键不该拦</b>：区分点<b>不在粒度键，在它有没有过锚</b> ——
     *       复用 {@link #grainFallbackEligible}：{@code NO_ANCHOR}（两个锚都空）且
     *       {@code base_version_no > 0} = 组已存在、用户新加的一行 ⇒ <b>排除在 C′ 之外</b>。
     *       这与 D-36 的闸是<b>同一个谓词</b>，两处不会漂。</li>
     *   <li><b>UNCHANGED 不会进 BLOCKED</b>：本方法只在 result != UNCHANGED 时改判，
     *       且 UNCHANGED 意味着指纹多重集相等 ⇒ 一行都没追加 ⇒ unanchoredRecs 必空。
     *       两者任一成立即可，这里<b>两条都断言</b>（不一致时打 ERROR 而不是掩盖）。</li>
     * </ol>
     *
     * <h3>🚫 N+1</h3>
     * 纯内存：基底粒度键建一次 Map，未锚定行遍历一次。<b>循环体内零查询</b>。
     */
    private static void applyCollisionGuard(DsBackfillPlan.Group g, SheetDef sheet,
                                            Set<String> grain, DsMainTableReader.BaseGroup base,
                                            List<RecordRow> unanchoredRecs) {
        if (com.cpq.dataset.versioning.VersionedGroupWriter.UNCHANGED.equals(g.result)) {
            if (!unanchoredRecs.isEmpty()) {
                // 边界③ 的断言：理论上不可能（追加了行，指纹多重集就不可能相等）。
                LOG.errorf("[ds-backfill] 不变式违反：组 %s 判 UNCHANGED 却有 %d 行未锚定 —— "
                        + "判定逻辑或指纹口径出问题了，🚫 不要靠这里掩盖", g.axisValue, unanchoredRecs.size());
            }
            return;
        }
        if (grain == null || grain.isEmpty() || base == null || base.rows.isEmpty()) return;  // 边界①
        if (unanchoredRecs.isEmpty()) return;

        Map<String, ColumnDef> defs = colDefsOf(sheet);
        Map<String, Integer> baseCountByGrain = new LinkedHashMap<>();
        for (DsMainTableReader.BaseRow br : base.rows) {                       // 🚫 无查询
            String k = DsRecordProjector.contentKey(br.values, defs, grain);
            if (isDegenerate(br.values, grain)) continue;                      // 边界①
            baseCountByGrain.merge(k, 1, Integer::sum);
        }
        Map<String, Integer> recCountByGrain = new LinkedHashMap<>();
        Map<String, RecordRow> sampleByGrain = new LinkedHashMap<>();
        for (RecordRow r : unanchoredRecs) {                                   // 🚫 无查询
            if (!grainFallbackEligible(r)) continue;                           // 边界②：真新增行不参与
            if (isDegenerate(r.values, grain)) continue;                       // 边界①
            String k = DsRecordProjector.contentKey(r.values, defs, grain);
            recCountByGrain.merge(k, 1, Integer::sum);
            sampleByGrain.putIfAbsent(k, r);
        }
        for (Map.Entry<String, Integer> e : recCountByGrain.entrySet()) {      // 🚫 无查询
            Integer inBase = baseCountByGrain.get(e.getKey());
            if (inBase == null) continue;                                      // 键不在基底 ⇒ 真新增，放行
            DsBackfillPlan.Colliding c = new DsBackfillPlan.Colliding();
            for (String col : grain) c.grainKey.put(col, sampleByGrain.get(e.getKey()).values.get(col));
            c.baseRowCount = inBase;
            c.recordRowCount = e.getValue();
            g.collidingRows.add(c);
        }
        if (!g.collidingRows.isEmpty()) {
            g.result = BLOCKED;
            g.blockedReason = BLOCKED_GRAIN_KEY_COLLISION;
            g.targetVersionNo = g.currentVersionNo;      // 跳过回填 ⇒ 版本不动
            LOG.warnf("[ds-backfill] 组 %s/%s 判 BLOCKED（%s）：%d 个粒度键「本该锚上却没锚上、"
                            + "而该键在基底里确实存在」⇒ 再写下去就是组翻倍，本组跳过回填。"
                            + "🔑 核价通过本身不受影响。", sheet.tableName, g.axisValue,
                    BLOCKED_GRAIN_KEY_COLLISION, g.collidingRows.size());
        }
    }

    /** 粒度键是否退化（全部粒度列取值为空）—— 退化的键不参与 C′，见边界①。 */
    private static boolean isDegenerate(Map<String, Object> values, Set<String> grain) {
        for (String c : grain) {
            if (!com.cpq.dataset.fingerprint.ValueNormalizer.isBlank(values.get(c))) return false;
        }
        return true;
    }

    /**
     * 该 {@code _record} 行是否<b>有资格</b>走第③层（粒度列兜底）。—— D-36
     *
     * <h3>🚨 修的是什么</h3>
     * 原实现对**所有**未锚定的行一视同仁地做粒度兜底，包括「两个锚都为空」的行。
     * 而两个锚都为空<b>有两种完全相反的成因</b>：
     * <pre>
     *   甲｜拍快照时**整组还不存在**（base_version_no = 0），之后别的单把这组建了出来。
     *      ⇒ 这一行本来就是主表那一行的投影，**必须**兜底，否则组翻倍（AC-14 就栽在这）。
     *   乙｜拍快照时**组已存在**（base_version_no > 0），用户在组里**新加了一行**。
     *      ⇒ 这是真新增。若它的粒度键恰好与某条既有行相同、且基底中该键只有一条，
     *        兜底会把它认成那条既有行的 patch ⇒ **用户新增的行消失、既有行被覆盖**。
     *        方向与 AP-60 原始形态一致（写到错的行上）。
     * </pre>
     *
     * <h3>为什么判据是 {@code base_version_no}，而不是「reason == NO_ANCHOR 就不兜底」</h3>
     * 后者是最直觉的写法，但它**会把甲一起禁掉** —— 实测：AC-14 的决定性用例里，
     * tier③ 命中的那两行日志原文就是「{@code origin_id/指纹都对不上}」（即 NO_ANCHOR），
     * 一刀切禁掉之后 AC-14 立刻回到 {@code base=2 → result=4} 的翻倍态。
     * {@code base_version_no} 是唯一能把甲乙分开的信号，且它是本表已有的列，不需要新增字段。
     *
     * <h3>与 AC-20③ 的关系</h3>
     * AC-20③ 写的是「跨版 且 指纹已变但粒度列未变」。甲的 {@code base_version_no=0}
     * 与当前版本必然不等 ⇒ {@code crossVersion=true}，落在 AC 描述之内；
     * 乙是同版新增，本就不该由第③层处理 ⇒ 本方法让实现回到 AC 原意。
     *
     * @return true = 允许兜底（曾经有锚，或拍快照时整组不存在）
     */
    private static boolean grainFallbackEligible(RecordRow r) {
        if (r.originId != null || r.baseRowFingerprint != null) return true;   // 曾经有锚，只是丢了
        return r.baseVersionNo == 0;                                           // 甲：拍快照时整组不存在
    }

    /**
     * {@code UNCHANGED} 的组：把四项计数回填成「一个字节都不写」（AC-14③ / api.md §1 硬约束 3）。
     *
     * <h3>🚨 为什么必须在这里收口，而不是在行循环里分支</h3>
     * 行循环（上面 {@code patchedRows++} / {@code untouchedRows++} 那段）跑的时候
     * <b>还不知道 result</b> —— 判定要等整组 {@code resultRows} 拼完、与基底比完指纹多重集才出来。
     * 于是那四项描述的是「我<b>打算</b>怎么 patch」，而 {@code UNCHANGED} 的含义恰恰是
     * 「这些 patch 最终<b>一个字节都不会写</b>」。不回填 = <b>四项全部反向</b>：
     * <pre>
     *   实测（修复前）：result=UNCHANGED 却报 patchedRows=2 / untouchedRows=0
     *                  / columnScope.patched=[全部 12 列] / preserved=[]
     * </pre>
     *
     * <h3>🚨 这是 AP-60 判据四的反向形态</h3>
     * <ul>
     *   <li>{@code repair-0727}：预览显示「0 变更」，实际<b>删了 3 行</b>；</li>
     *   <li><b>本条</b>：预览显示「覆盖 2 行 / 12 列」，实际<b>一个字节不写</b>。</li>
     * </ul>
     * 两者都让财务<b>无法据预览判断后果</b>。我们为这条判据加了「本次不动」列、「回填后行数」列、
     * 「组会变小」告警 —— 而最基础的那一格自己在说谎。
     * <p>连带：财务点确认后去查库会发现什么都没变，界面却说改了 2 行
     * （{@code VersionedGroupWriter} 的 UNCHANGED 契约是「一行不写，连 {@code updated_at} 都不许动」）。
     *
     * <p>📌 <b>一处收口</b>：将来再加计数字段也在这里补，🚫 不要散回行循环里去分支 ——
     * 那里永远不知道 result，散一次就漏一个。
     */
    private static void applyUnchangedContract(DsBackfillPlan.Group g, SheetDef sheet) {
        g.patchedRows = 0;                    // 本次覆盖 0 行
        g.untouchedRows = g.baseRowCount;     // 整组原样保留
        g.patchedColumns.clear();             // 一列都不写
        g.preservedColumns.clear();
        for (ColumnDef c : sheet.persistedColumns()) g.preservedColumns.add(c.name);
        // ⚠️ unanchoredRows 在 UNCHANGED 下必然为空：锚不上的行会被追加进 resultRows，
        //    行数一变指纹多重集就不可能相等 ⇒ 判不出 UNCHANGED。这里不清空，
        //    真出现了就让它露出来（那意味着判定逻辑坏了，🚫 不许拿清空来掩盖）。
    }

    // ==================================================================
    // 读 _record / _history
    // ==================================================================

    /** {@code _record} 的一行（读侧视图）。 */
    static final class RecordRow {
        long recordId;
        String axisValue;
        Long originId;
        String baseRowFingerprint;
        int baseVersionNo;
        final Map<String, Object> values = new LinkedHashMap<>();
    }

    /** 读本单本客户在该 sheet 上的全部 {@code _record} 行。<b>1 条 SQL</b>。 */
    List<RecordRow> readRecords(SheetDef sheet, UUID quotationId, String customerNo) {
        List<ColumnDef> cols = sheet.persistedColumns();
        StringBuilder sql = new StringBuilder("SELECT id, origin_id, base_row_fingerprint, base_version_no");
        for (ColumnDef c : cols) sql.append(", ").append(SqlIdent.of(c.name));
        sql.append(" FROM ").append(SqlIdent.of(sheet.recordTable()))
           .append(" WHERE quotation_id = :qid");
        boolean withCust = customerNo != null && !customerNo.isBlank();
        if (withCust) sql.append(" AND ").append(SheetDef.RECORD_CUSTOMER_COLUMN).append(" = :cust");
        sql.append(" ORDER BY ").append(SqlIdent.of(sheet.axisColumn)).append(", id");

        var q = em.createNativeQuery(sql.toString()).setParameter("qid", quotationId);
        if (withCust) q.setParameter("cust", customerNo);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        List<RecordRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {                       // 纯内存映射
            int i = 0;
            RecordRow rr = new RecordRow();
            rr.recordId = ((Number) r[i++]).longValue();
            rr.originId = r[i] == null ? null : ((Number) r[i]).longValue(); i++;
            rr.baseRowFingerprint = r[i] == null ? null : String.valueOf(r[i]); i++;
            rr.baseVersionNo = r[i] == null ? 0 : ((Number) r[i]).intValue(); i++;
            for (ColumnDef c : cols) rr.values.put(c.name, r[i++]);
            Object axis = rr.values.get(sheet.axisColumn);
            rr.axisValue = axis == null ? null : String.valueOf(axis);
            if (rr.axisValue != null) out.add(rr);
        }
        return out;
    }


    private static Map<String, ColumnDef> colDefsOf(SheetDef sheet) {
        return com.cpq.quotation.service.dsrecord.DsQuoteRecordService.colDefsOf(sheet);
    }

    /** 给财务看的识别列：轴列之外，取 Registry 里前几个 SUBDIM/VALUE 列（纯展示，不参与任何判定）。 */
    private static List<String> displayColumns(SheetDef sheet) {
        List<String> out = new ArrayList<>();
        for (ColumnDef c : sheet.persistedColumns()) {
            if (c.name.equals(sheet.axisColumn)) continue;
            out.add(c.name);
            if (out.size() >= 3) break;
        }
        return out;
    }
}
