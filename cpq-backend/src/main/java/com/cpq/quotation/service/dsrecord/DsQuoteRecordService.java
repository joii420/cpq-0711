package com.cpq.quotation.service.dsrecord;

import com.cpq.dataset.registry.ColumnDef;
import com.cpq.dataset.registry.SheetDef;
import com.cpq.dataset.support.DatasetValues;
import com.cpq.dataset.support.SqlIdent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 报价单 → {@code ds_quote_*_record} 快照写入（task-260907 第二段 · B-5 / B-6 / B-7）。
 *
 * <h3>服务的 AC</h3>
 * AC-2（保存时按变更产品增量写）· AC-3（{@code extend_column} 只留痕）· AC-4（{@code element_price}）
 * · AC-11（覆盖式，最终只有一份）
 *
 * <h3>增量语义（AC-2 的核心）</h3>
 * 只重写<b>本次变更产品对应的轴值组</b>；未变更产品的 {@code _record} 行一个字节不动
 * （AC-2② 断言它们的 {@code updated_at} 逐字未变）。
 * 🚫 <b>不许整单重写</b> —— autoSave 频率下会写放大，{@code task-260901} 已为 {@code row_data}
 * 那条路付过 43 秒保存的账。
 *
 * <p>⚠️ 组的粒度是 <b>(quotation_id, 轴值)</b> 而不是 (line_item, 轴值)：同一张单里两个产品卡片
 * 用同一个销售料号时，它们表征的是<b>同一个主数据组</b>。所以一旦某个轴值被本次改动命中，
 * 该轴值的 {@code _record} 由<b>本单全部持有该轴值的卡片</b>一起重算 ——
 * 否则第二张卡片的投影会在第一张卡片保存时被整组删掉。
 *
 * <h3>🚫 N+1 硬指标</h3>
 * SQL 条数 = 4（单头 + 明细行 + 组件数据 + 客户号）+ 3（绑定解析）
 * + 每张命中 sheet 的 (1 读基底 + 1 整组删 + ceil(行数/500) 批量插)。
 * <b>与产品数、轴值数、行数（除分批外）无关</b>。
 * 全部循环体内<b>没有</b> repository 调用、没有 {@code em.createNativeQuery}、没有懒加载 getter。
 */
@ApplicationScoped
public class DsQuoteRecordService {

    private static final Logger LOG = Logger.getLogger(DsQuoteRecordService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code _record.source}。⚠️ 受 {@code source varchar(16)} 约束（实测），11 字符。 */
    public static final String SOURCE_QUOTE_DRAFT = "QUOTE_DRAFT";

    /** 多行 INSERT 分批行数，与 {@code VersionedGroupWriter.INSERT_CHUNK} 同口径。 */
    private static final int INSERT_CHUNK = 500;

    @Inject EntityManager em;
    @Inject DsSheetBindingResolver bindingResolver;
    @Inject DsMainTableReader mainTableReader;
    /** D-35：写成功时清「快照过期」标记。 */
    @Inject DsRecordStaleService staleService;

    /** 一次写入的结果摘要（日志与自测用，不进契约）。 */
    public record Summary(int sheets, int axes, int rows, int unanchoredRows) {
        public static Summary empty() { return new Summary(0, 0, 0, 0); }
    }

    /**
     * 增量同步 {@code _record}。
     *
     * @param quotationId         报价单
     * @param changedLineItemIds  本次变更的明细行 id；<b>null = 整单同步</b>（建单物化后首次落
     *                            {@code _record} 用），空集合 = 什么都不做
     */
    // 🚨 <b>刻意不加 @Transactional</b>（实测倒逼，🚫 不要「顺手补上」）：
    //    本方法只在 saveDraft 的事务里被调用，EntityManager 自然加入环境事务，功能上不需要注解。
    //    但加上 @Transactional(MANDATORY) 会有一个**致命副作用**：
    //    异常一旦逸出本方法，拦截器就把调用方的事务标记成 rollback-only ——
    //    调用方 catch 住也没用，saveDraft 照样整单回滚，**却仍然返回 200**。
    //    ⇒ 用户以为草稿存上了，实际一行都没落库。
    //    【实测】注入 `throw new IllegalStateException(...)`：
    //        加注解  → saveDraft=200，但 quotation_line_item = **0 行**（草稿静默丢失）
    //        去注解  → saveDraft=200，quotation_line_item = 1 行（草稿保住，_record 缺失可见）
    //    ⚠️ 这比 D-35 要消灭的静默更严重：那条丢的是派生数据，这条丢的是**用户刚写的正文**。
    //    ⚠️ 对比：{@link #syncElementPrice} 保留 MANDATORY 是**故意**的 ——
    //       D-29 要求它与 snapshot_rows「同写点同事务」，那里失败就该一起回滚。
    /**
     * <b>建单流程末尾的挂点</b>（D-40 · 用户 2026-09-07 改裁「本期补写入」）。
     *
     * <h3>🚨 它与直接调 {@link #syncRecords} 的区别，以及为什么两侧挂法必须不同</h3>
     * <ul>
     *   <li><b>导入侧</b>（{@code ImportExecutionService.executeImport / confirmImport}）本身带
     *       {@code @Transactional} ⇒ 明细行还在<b>未提交</b>的外层事务里。
     *       🚫 <b>绝不能用 {@code REQUIRES_NEW}</b> —— 新事务走另一条连接，<b>看不见未提交的行</b>，
     *       结果是「调了、没报错、就是没数据」的空实现。⇒ 那侧<b>直接调无注解的
     *       {@link #syncRecords}</b>，加入外层事务。
     *       <p>🔑 无注解正是关键：没有拦截器 ⇒ 异常<b>不会</b>把调用方事务标 rollback-only
     *       ⇒ 调用点 catch 住就真的不阻断（这正是早前 {@code saveDraft} 那次
     *       「返 200 而整单回滚、草稿静默丢失」事故的修法）。</li>
     *   <li><b>选配侧</b>挂在 {@code ConfigureProductResource}（Resource 层<b>没有事务</b>，
     *       且此刻 service 的写入<b>已提交</b>）⇒ 必须由本方法开一个事务，否则原生
     *       {@code INSERT} 无事务可用直接抛。</li>
     * </ul>
     * <p>🚫 <b>不是第二套投影</b>：本方法只是事务外壳，投影一律走 {@link #syncRecords}（D-31）。
     * <p>🚫 <b>N+1</b>：整条流程<b>只许调一次</b>，{@code changedLineItemIds} 传 {@code null}
     * = 本单全部明细行一次算完。🚫 不许按行/按明细调。
     */
    @Transactional
    public Summary syncRecordsForFlow(UUID quotationId) {
        return syncRecords(quotationId, null);
    }

    public Summary syncRecords(UUID quotationId, Collection<UUID> changedLineItemIds) {
        if (quotationId == null) return Summary.empty();
        if (changedLineItemIds != null && changedLineItemIds.isEmpty()) return Summary.empty();

        // ── ① 客户号（_record.customer_no NOT NULL，取值口径 = customer.code）────────────
        String customerNo = resolveCustomerNo(quotationId);
        if (customerNo == null || customerNo.isBlank()) {
            LOG.warnf("[ds-record] quotation=%s 解析不出客户编号（customer.code），跳过 _record 写入", quotationId);
            return Summary.empty();
        }

        // ── ② 明细行 → 轴值（1 条 SQL）──────────────────────────────────────────────
        @SuppressWarnings("unchecked")
        List<Object[]> lines = em.createNativeQuery(
                        "SELECT id, product_part_no_snapshot FROM quotation_line_item WHERE quotation_id = :qid")
                .setParameter("qid", quotationId).getResultList();
        if (lines.isEmpty()) return Summary.empty();

        Map<UUID, String> axisByLine = new LinkedHashMap<>();
        for (Object[] r : lines) {
            axisByLine.put((UUID) r[0], r[1] == null ? null : String.valueOf(r[1]));
        }
        Set<UUID> changed = changedLineItemIds == null ? axisByLine.keySet() : new LinkedHashSet<>(changedLineItemIds);
        Set<String> touchedAxes = new LinkedHashSet<>();
        for (UUID lid : changed) {
            String a = axisByLine.get(lid);
            if (a != null && !a.isBlank()) touchedAxes.add(a);
        }
        if (touchedAxes.isEmpty()) return Summary.empty();
        // 同轴值的其它卡片一并纳入重算（见类注释「组的粒度」）
        List<UUID> scopeLines = new ArrayList<>();
        for (Map.Entry<UUID, String> e : axisByLine.entrySet()) {
            if (e.getValue() != null && touchedAxes.contains(e.getValue())) scopeLines.add(e.getKey());
        }

        // ── ③ 组件数据（1 条 SQL）───────────────────────────────────────────────────
        @SuppressWarnings("unchecked")
        List<Object[]> compData = em.createNativeQuery(
                        "SELECT line_item_id, component_id, snapshot_rows::text, row_data::text, " +
                        "       deleted_row_keys::text, sort_order " +
                        "FROM quotation_line_component_data WHERE line_item_id IN (:ids)")
                .setParameter("ids", scopeLines).getResultList();
        if (compData.isEmpty()) {
            LOG.debugf("[ds-record] quotation=%s 命中 %d 个轴值但无组件数据，跳过", quotationId, touchedAxes.size());
            return Summary.empty();
        }

        Set<UUID> componentIds = new LinkedHashSet<>();
        for (Object[] r : compData) if (r[1] != null) componentIds.add((UUID) r[1]);

        // ── ④ 绑定解析（3 条 SQL，与组件数无关）─────────────────────────────────────
        Map<UUID, DsSheetBinding> bindings = bindingResolver.resolve(componentIds);
        if (bindings.isEmpty()) {
            LOG.debugf("[ds-record] quotation=%s 无任何组件绑定到 ds_quote_* 带版本表（存量手写视图 / 非报价方言），跳过",
                    quotationId);
            return Summary.empty();
        }

        // ── ⑤ 纯内存投影：sheet → 轴值 → 行（🚫 循环体内无查询）────────────────────
        Map<String, SheetDef> sheetByTable = new LinkedHashMap<>();
        Map<String, Map<String, List<DsRecordRow>>> bySheet = new LinkedHashMap<>();
        Map<String, Set<String>> scopeColumnsBySheet = new LinkedHashMap<>();
        Map<String, Set<String>> grainColumnsBySheet = new LinkedHashMap<>();
        for (Object[] r : compData) {
            UUID lineId = (UUID) r[0];
            UUID compId = (UUID) r[1];
            DsSheetBinding b = compId == null ? null : bindings.get(compId);
            if (b == null) continue;
            int sortOrder = r[5] == null ? 0 : ((Number) r[5]).intValue();
            List<DsRecordRow> rows = DsRecordProjector.project(
                    b, b.sheet().axisColumn, (String) r[2], (String) r[3], (String) r[4],
                    axisByLine.get(lineId), sortOrder);
            if (rows.isEmpty()) continue;
            String table = b.sheet().tableName;
            sheetByTable.putIfAbsent(table, b.sheet());
            scopeColumnsBySheet.computeIfAbsent(table, k -> new LinkedHashSet<>()).addAll(b.fieldToColumn().values());
            grainColumnsBySheet.computeIfAbsent(table, k -> new LinkedHashSet<>()).addAll(b.grainColumns());
            Map<String, List<DsRecordRow>> byAxis = bySheet.computeIfAbsent(table, k -> new LinkedHashMap<>());
            for (DsRecordRow row : rows) {
                if (!touchedAxes.contains(row.axisValue)) continue;   // 别的组的行（BOM 树跨组）不在本次范围
                byAxis.computeIfAbsent(row.axisValue, k -> new ArrayList<>()).add(row);
            }
        }

        // ── ⑥ 逐 sheet：读基底 → 锚定 → 整组删 → 批量插 ────────────────────────────
        int totalRows = 0, unanchored = 0, axisCount = 0;
        for (Map.Entry<String, Map<String, List<DsRecordRow>>> se : bySheet.entrySet()) {
            SheetDef sheet = sheetByTable.get(se.getKey());
            Map<String, List<DsRecordRow>> byAxis = se.getValue();
            if (byAxis.isEmpty()) continue;

            Map<String, DsMainTableReader.BaseGroup> base =
                    mainTableReader.readGroups(sheet, byAxis.keySet(), customerNo);   // 1 条 SQL
            Map<String, ColumnDef> colDefs = colDefsOf(sheet);
            Set<String> matchCols = scopeColumnsBySheet.getOrDefault(se.getKey(), Set.of());
            Set<String> grainCols = grainColumnsBySheet.getOrDefault(se.getKey(), Set.of());

            for (Map.Entry<String, List<DsRecordRow>> ae : byAxis.entrySet()) {
                // 纯内存锚定（🚫 无查询）
                DsRecordProjector.anchor(ae.getValue(), base.get(ae.getKey()), colDefs, matchCols, grainCols);
                for (DsRecordRow row : ae.getValue()) if (row.originId == null) unanchored++;
                totalRows += ae.getValue().size();
                axisCount++;
            }
            deleteGroups(sheet, quotationId, customerNo, byAxis.keySet());            // 1 条 SQL
            insertRows(sheet, quotationId, customerNo, byAxis);                       // ceil(行数/500) 条
        }

        // D-35：本次写成功 ⇒ 清掉该单未清除的「_record 快照过期」标记（不删行，只置 cleared_at）。
        //    🔒 与写入同事务：写成功与清标记必须同生共死，否则会留下「其实已经补上了、
        //    但预览还在报过期」的假警报 —— 假警报久了就没人看，等于把 D-35 作废。
        staleService.clearStale(quotationId);

        Summary s = new Summary(bySheet.size(), axisCount, totalRows, unanchored);
        LOG.infof("[ds-record] quotation=%s 写入 _record：sheets=%d axes=%d rows=%d unanchored=%d "
                        + "(sql 与产品数/轴值数无关)", quotationId, s.sheets(), s.axes(), s.rows(), s.unanchoredRows());
        return s;
    }

    // ==================================================================
    // B-8 · S-3：价格调整改价时同步 _record.element_price（AC-12）
    // ==================================================================

    /**
     * 把本次价格调整算出的元素价同步进 {@code _record.element_price}。
     *
     * <h3>🔑 为什么必须挂在 {@code MaterialVersionUpgradeService} 写 {@code snapshot_rows} 的
     * <b>同一处、同一事务</b>（D-29）</h3>
     * 那是唯一能防「{@code snapshot_rows} 变了而 {@code _record} 没变」这种分叉的口径：
     * <ul>
     *   <li>{@code snapshot_rows} 写了 ⇒ 本方法必然被调用（同事务，失败一起回滚）；</li>
     *   <li>被 S0 L3 守卫拦下、或状态不在 {@code ACTIVE_STATUSES}（实测
     *       {@code {DRAFT, SUBMITTED, APPROVED, REJECTED, COSTING_REJECTED}}）而 {@code SKIPPED} 的单
     *       ⇒ 根本走不到那个写点 ⇒ 两者<b>同时不写</b>（AC-12②）。</li>
     * </ul>
     * ⚠️ 🚫 <b>不许另起一个「扫全表同步」的定时任务</b> —— 那会制造第二套口径，必然漂移。
     *
     * <h3>🚫 N+1</h3>
     * 本方法对「升级一个产品行的一个价格组件」这个业务操作是 <b>3 + 1 + 1 = 5 条常数 SQL</b>
     * （绑定解析 3 + 明细行 1 + 批量 UPDATE 1），与元素个数、{@code _record} 行数<b>无关</b> ——
     * 元素价用一条 {@code UPDATE … FROM (VALUES …)} 一次性打完，🚫 不是「每个元素一条 UPDATE」。
     *
     * @param elementCodeField 组件的元素编码字段名（{@code component.element_code_field}）
     * @param priceByCode      元素编码 → 本版价（{@code null} 价的元素请<b>不要</b>放进来，
     *                         语义是「本版无价」，与 S3a 的删键分支对应）
     * @return 实际更新的 {@code _record} 行数
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public int syncElementPrice(UUID lineItemId, UUID componentId, String elementCodeField,
                                Map<String, java.math.BigDecimal> priceByCode) {
        if (lineItemId == null || componentId == null || elementCodeField == null
                || elementCodeField.isBlank() || priceByCode == null || priceByCode.isEmpty()) {
            return 0;
        }
        DsSheetBinding b = bindingResolver.resolve(List.of(componentId)).get(componentId);
        if (b == null) return 0;                                  // 存量手写视图 / 非报价方言 → 无 _record
        if (!elementPriceEnabled(b.sheet())) return 0;            // 只有两张 BOM 表的 _record 有这一列
        String elemCol = b.fieldToColumn().get(elementCodeField);
        if (elemCol == null) {
            // 元素编码列没落在主表上（例如手填形态的元素键，task-0729 形态 B）⇒ _record 里无从匹配。
            LOG.debugf("[ds-record] component=%s 的元素编码字段「%s」未映射到 %s 的物理列，跳过 element_price 同步",
                    componentId, elementCodeField, b.sheet().tableName);
            return 0;
        }

        @SuppressWarnings("unchecked")
        List<Object[]> line = em.createNativeQuery(
                        "SELECT quotation_id, product_part_no_snapshot FROM quotation_line_item WHERE id = :lid")
                .setParameter("lid", lineItemId).getResultList();
        if (line.isEmpty()) return 0;
        UUID quotationId = (UUID) line.get(0)[0];
        Object axis = line.get(0)[1];
        if (quotationId == null || axis == null) return 0;

        List<String> codes = new ArrayList<>(priceByCode.keySet());
        StringBuilder sql = new StringBuilder("UPDATE ").append(SqlIdent.of(b.sheet().recordTable()))
                .append(" r SET element_price = v.price, updated_at = :now FROM (VALUES ");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("(CAST(:c").append(i).append(" AS varchar), CAST(:p").append(i).append(" AS numeric))");
        }
        sql.append(") AS v(code, price) WHERE r.quotation_id = :qid AND r.")
           .append(SqlIdent.of(b.sheet().axisColumn)).append(" = :axis AND r.")
           .append(SqlIdent.of(elemCol)).append(" = v.code");

        Query q = em.createNativeQuery(sql.toString())
                .setParameter("now", java.time.OffsetDateTime.now())
                .setParameter("qid", quotationId)
                .setParameter("axis", String.valueOf(axis));
        for (int i = 0; i < codes.size(); i++) {                  // 🚫 纯绑参，无查询
            q.setParameter("c" + i, codes.get(i));
            q.setParameter("p" + i, priceByCode.get(codes.get(i)));
        }
        int n = q.executeUpdate();
        LOG.debugf("[ds-record] element_price 同步：line=%s component=%s 元素 %d 个 → %d 行（sql=1）",
                lineItemId, componentId, codes.size(), n);
        return n;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private String resolveCustomerNo(UUID quotationId) {
        @SuppressWarnings("unchecked")
        List<Object> r = em.createNativeQuery(
                        "SELECT c.code FROM quotation q JOIN customer c ON c.id = q.customer_id WHERE q.id = :id")
                .setParameter("id", quotationId).getResultList();
        return r.isEmpty() || r.get(0) == null ? null : String.valueOf(r.get(0));
    }

    static Map<String, ColumnDef> colDefsOf(SheetDef sheet) {
        Map<String, ColumnDef> m = new LinkedHashMap<>();
        for (ColumnDef c : sheet.persistedColumns()) m.put(c.name, c);
        return m;
    }

    /**
     * 覆盖式写入的第一步：整组删（AC-11①「{@code _record} 最终只有一份」）。
     *
     * <p>⚠️ <b>带 {@code quotation_id} 与 {@code customer_no} 双重收窄</b> ——
     * 只删本单本客户的快照行，🚫 绝不碰别的单。{@code WHERE} 必须命中面明确（{@code CLAUDE.md} §3.2）。
     */
    private void deleteGroups(SheetDef sheet, UUID quotationId, String customerNo, Collection<String> axes) {
        em.createNativeQuery("DELETE FROM " + SqlIdent.of(sheet.recordTable())
                        + " WHERE quotation_id = :qid AND " + SheetDef.RECORD_CUSTOMER_COLUMN + " = :cust"
                        + " AND " + SqlIdent.of(sheet.axisColumn) + " IN (:axes)")
                .setParameter("qid", quotationId)
                .setParameter("cust", customerNo)
                .setParameter("axes", new ArrayList<>(axes))
                .executeUpdate();
    }

    /** 多行 VALUES 合批插入；分批只按<b>总行数</b>切，与轴值数无关。 */
    private void insertRows(SheetDef sheet, UUID quotationId, String customerNo,
                            Map<String, List<DsRecordRow>> byAxis) {
        List<ColumnDef> bizCols = sheet.persistedColumns();
        boolean withElementPrice = elementPriceEnabled(sheet);

        List<String> cols = new ArrayList<>();
        cols.add("quotation_id");
        cols.add("origin_id");
        cols.add("base_row_fingerprint");
        cols.add("base_version_no");
        for (ColumnDef c : bizCols) cols.add(SqlIdent.of(c.name));
        cols.add("extend_column");
        if (withElementPrice) cols.add("element_price");
        cols.add(SheetDef.RECORD_CUSTOMER_COLUMN);
        cols.add("source");
        cols.add("updated_at");

        List<DsRecordRow> flat = new ArrayList<>();
        for (Map.Entry<String, List<DsRecordRow>> e : byAxis.entrySet()) flat.addAll(e.getValue());
        if (flat.isEmpty()) return;

        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        String table = SqlIdent.of(sheet.recordTable());
        for (int start = 0; start < flat.size(); start += INSERT_CHUNK) {
            int end = Math.min(start + INSERT_CHUNK, flat.size());
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(table)
                    .append(" (").append(String.join(", ", cols)).append(") VALUES ");
            for (int i = start; i < end; i++) {
                if (i > start) sql.append(", ");
                sql.append('(');
                for (int c = 0; c < cols.size(); c++) {
                    if (c > 0) sql.append(", ");
                    // extend_column 是 jsonb —— 参数按 text 绑，显式 CAST（与既有写点同款）
                    if ("extend_column".equals(cols.get(c))) sql.append("CAST(:p").append(i).append('_').append(c).append(" AS jsonb)");
                    else sql.append(":p").append(i).append('_').append(c);
                }
                sql.append(')');
            }
            Query q = em.createNativeQuery(sql.toString());
            for (int i = start; i < end; i++) {
                DsRecordRow row = flat.get(i);
                int c = 0;
                q.setParameter("p" + i + "_" + c++, quotationId);
                q.setParameter("p" + i + "_" + c++, row.originId);
                q.setParameter("p" + i + "_" + c++, row.baseRowFingerprint);
                q.setParameter("p" + i + "_" + c++, row.baseVersionNo);
                for (ColumnDef col : bizCols) {
                    Object v = sheet.axisColumn.equals(col.name) ? row.axisValue : row.columnValues.get(col.name);
                    q.setParameter("p" + i + "_" + c++, DatasetValues.coerce(col, v));
                }
                q.setParameter("p" + i + "_" + c++, writeJson(row.extendValues));
                if (withElementPrice) q.setParameter("p" + i + "_" + c++, row.elementPrice);
                q.setParameter("p" + i + "_" + c++, customerNo);
                q.setParameter("p" + i + "_" + c++, SOURCE_QUOTE_DRAFT);
                q.setParameter("p" + i + "_" + c, now);
            }
            q.executeUpdate();
        }
    }

    /** 只有物料BOM / 物料与元素BOM 两张的 {@code _record} 有 {@code element_price}（S-3 / AC-4）。 */
    static boolean elementPriceEnabled(SheetDef sheet) {
        return com.cpq.dataset.registry.QuoteRegistry.ELEMENT_PRICE_RECORD_SHEETS.contains(sheet.sheetKey);
    }

    private static String writeJson(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return "{}";
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }
}
