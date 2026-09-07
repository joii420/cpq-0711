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
        for (DsSheetBinding b : bindings.values()) {
            sheetByTable.putIfAbsent(b.sheet().tableName, b.sheet());
            scopeByTable.computeIfAbsent(b.sheet().tableName, k -> new LinkedHashSet<>())
                    .addAll(b.fieldToColumn().values());
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

            List<RecordRow> records = readRecords(sheet, quotationId, plan.customerNo);   // 1 条 SQL
            if (records.isEmpty()) continue;

            Map<String, List<RecordRow>> byAxis = new LinkedHashMap<>();
            for (RecordRow r : records) byAxis.computeIfAbsent(r.axisValue, k -> new ArrayList<>()).add(r);

            Map<String, DsMainTableReader.BaseGroup> base =
                    mainTableReader.readGroups(sheet, byAxis.keySet(), plan.customerNo);  // 1 条 SQL
            // D-31：目标版本号问 writer（只读、2 条 SQL、与轴值数无关），🚫 不在本类里再算一遍规则
            Map<String, Integer> predicted = versionedGroupWriter.predictNextVersion(sheet, byAxis.keySet());

            DsBackfillPlan.Table t = new DsBackfillPlan.Table();
            t.sheet = sheet;
            for (Map.Entry<String, List<RecordRow>> ae : byAxis.entrySet()) {
                // 纯内存（🚫 循环体内无查询）
                t.groups.add(buildGroup(sheet, scope, ae.getKey(), ae.getValue(),
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
    private DsBackfillPlan.Group buildGroup(SheetDef sheet, Set<String> scope, String axisValue,
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

        // 🚨 遍历主轴 = 基底行。禁止「遍历 _record 生成行」。
        if (base != null) {
            for (DsMainTableReader.BaseRow br : base.rows) {
                RecordRow hit = null;
                if (!g.crossVersion) {
                    // 同版：origin_id 精确对位，零歧义
                    RecordRow cand = byOrigin.get(br.id);
                    if (cand != null && !consumed.contains(cand)) hit = cand;
                } else {
                    // 跨版：主表行 id 已全部失效（UPGRADED = 归档→整组 DELETE→重新 INSERT），
                    // 改用拍快照时的整行指纹在当前组内重锚。
                    Deque<RecordRow> q = br.rowFingerprint == null ? null
                            : byFingerprint.get(br.rowFingerprint.trim());
                    while (q != null && !q.isEmpty()) {
                        RecordRow cand = q.pollFirst();
                        if (!consumed.contains(cand)) { hit = cand; break; }
                    }
                }

                Map<String, Object> out = new LinkedHashMap<>(br.values);   // 基底整行，逐列保留
                if (hit != null) {
                    consumed.add(hit);
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
        for (RecordRow r : records) {
            if (consumed.contains(r)) continue;
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
            } else {
                g.result = com.cpq.dataset.versioning.VersionedGroupWriter.UPGRADED;
                g.targetVersionNo = predictedVersionNo;  // D-31：规则只有一份，在 writer 里
            }
        }
        return g;
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
