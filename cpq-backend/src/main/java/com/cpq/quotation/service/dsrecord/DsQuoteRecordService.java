package com.cpq.quotation.service.dsrecord;

import com.cpq.dataset.registry.ColumnDef;
import com.cpq.dataset.registry.DatasetRegistries;
import com.cpq.dataset.registry.DatasetRegistry;
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
 * <p>🔴 <b>D-43（2026-09-08 主线亲验抓到的 P0）</b>：「一起重算」<b>不等于</b>「把 N 张卡片的
 * 投影拼起来」。它们表征的是<b>同一批主表行</b> ⇒ 直接拼接会让每条主表行出现 N 份，
 * 而 {@link DsRecordProjector#anchor} 的 {@code usedBase} 只让第一份认领到 {@code origin_id}，
 * 其余 N-1 份 {@code origin_id=NULL} ⇒ 回填按新增追加 ⇒ <b>整组 ×N</b>
 * （实测：合计 {@code base=43 → result=71}，两个产品行的料号 12 个组全部翻倍）。
 * ⇒ 组装完 {@code byAxis} 后、{@code anchor()} 之前必须过一遍
 * {@link DsRecordCardDeduper}（见 §⑤-b）。
 *
 * <h3>🔴 D-46：写入面 = 产品轴值 ∪ <b>树闭包派生轴</b>（2026-09-08 主线亲验抓到的第三个洞）</h3>
 * 原来 §⑤ 里那句 {@code if (!touchedAxes.contains(row.axisValue)) continue;}（注释写着
 * 「别的组的行（BOM 树跨组）不在本次范围」）是<b>故意</b>丢树行的。配上
 * {@link DsRecordProjector#resolveAxis} 反了的优先级，后果是
 * <b>{@code ds_quote_material_bom_record} 实测 64 行 100% 是合成根行，一条真实 BOM 边行都没有</b>
 * ⇒ 物料BOM 从来没有被报价单回填过（涉 30 张单）。
 * <p>新语义：<b>本单卡片投影出来的轴值都是本单表征的</b>（它们来自本单的 {@code snapshot_rows}），
 * 🚫 不因为「不是产品料号」被丢。树页签行主轴是 spine 节点、轴值 = <b>父件料号</b>，
 * 深度 ≥3 的行父件是中间件 ⇒ 那些组（{@code treeDerivedAxes}）本次一并重写。
 *
 * <h4>唯一护栏：轴值恰好是本单<b>另一个产品</b>的销售料号 ⇒ 跳过</h4>
 * 那个组归它自己的卡片管，而那张卡片<b>不在本次 {@code scopeLines} 里</b>
 * ⇒ 放行会让 {@code deleteGroups} 整组删掉它的投影、只写回本卡片这一份 ⇒ 静默丢行
 * （正是 {@code scopeLines} 当初要防的形态）。⇒ 等那个产品自己被保存时再写，日志
 * {@code crossProductAxisSkipped=%d}。
 *
 * <h4>⚠️ 已知残留（本轮<b>刻意不修</b>，交主线裁决）</h4>
 * 中间件被<b>两个产品共用</b>时（实测数据里 {@code RM01} 就同时挂在 {@code FG01} 与 {@code FG02} 下），
 * 只保存 {@code FG01} 会让 {@code RM01} 组只由 {@code FG01} 的卡片写回。
 * 实践中无害 —— 两张卡片投的是<b>同一批 BOM 边</b>（同一个 {@code RM01} 的 BOM），内容相同；
 * 且真有分歧时 {@link DsRecordCardDeduper} 本来就按 {@code sort_order} 仲裁成一份。
 * <p>要彻底闭合得把 {@code scopeLines} 从「共享产品轴值的卡片」扩成「<b>贡献到任一 effective 轴值</b>
 * 的卡片」—— 那需要先投影完才知道是哪些卡片，即<b>两轮投影</b>（第二轮多 1 条常数 SQL 取剩余卡片的
 * 组件数据）。🚦 那是增量重算语义的扩张，<b>未经用户裁决不许自行做</b>。
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
    /** D-48：删单清 {@code _record} 时，表清单从 Registry 派生（🚫 不许硬编表名）。 */
    @Inject DatasetRegistries registries;
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

    /**
     * D-42（甲-1）：带<b>增量名单</b>的流程末尾挂点 —— 供 {@code saveDraft} 那条路使用。
     *
     * <p>🔑 {@code changedLineItemIds} 必须是本次真正触碰的行（新增/修改 + 删除），
     * 🚫 <b>不许图省事传 {@code null}</b>：那是整单重写，会直接打破
     * {@code AC-2②}「未变更产品的 {@code _record.updated_at} 逐字未变」，
     * 且大单量下写放大（{@code task-260825} 栽过建单 N+1 超时）。
     * <p>名单由 {@code QuotationService.saveDraft} 算出、经
     * {@code SaveDraftResponse#touchedLineItemIds}（{@code @JsonIgnore}，不上线）带出来。
     */
    @Transactional
    public Summary syncRecordsForFlow(UUID quotationId, Collection<UUID> changedLineItemIds) {
        return syncRecords(quotationId, changedLineItemIds);
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
        //    🔑 D-43：顺带取 sort_order —— 跨卡片归一的仲裁依据。它就在这条本来就要发的 SQL 里，
        //    🚫 不许为它另发一条（N+1 硬指标：SQL 条数与产品数无关）。
        @SuppressWarnings("unchecked")
        List<Object[]> lines = em.createNativeQuery(
                        "SELECT id, product_part_no_snapshot, sort_order FROM quotation_line_item "
                        + "WHERE quotation_id = :qid")
                .setParameter("qid", quotationId).getResultList();
        if (lines.isEmpty()) return Summary.empty();

        Map<UUID, String> axisByLine = new LinkedHashMap<>();
        Map<UUID, Integer> sortOrderByLine = new LinkedHashMap<>();
        for (Object[] r : lines) {
            axisByLine.put((UUID) r[0], r[1] == null ? null : String.valueOf(r[1]));
            sortOrderByLine.put((UUID) r[0], r[2] == null ? null : ((Number) r[2]).intValue());
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
        // 🔴 D-46：本单**其它产品**的销售料号 —— 派生轴的护栏，见 §⑤ 的 acceptAxis 注释。
        //    数据就在上面那条已发的 SQL 里（axisByLine 覆盖本单**全部**明细行），🚫 不新增查询。
        Set<String> otherProductAxes = new LinkedHashSet<>();
        for (String a : axisByLine.values()) {
            if (a != null && !a.isBlank() && !touchedAxes.contains(a)) otherProductAxes.add(a);
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
        // D-46 留痕：派生轴（树闭包）与被护栏挡下的轴。🚫 不许静默 —— 这两个数字就是本次
        //           写入面比「产品轴值」多出/少掉多少的唯一可观测量。
        Set<String> derivedAxes = new LinkedHashSet<>();
        Set<String> crossProductSkippedAxes = new LinkedHashSet<>();
        int crossProductSkippedRows = 0;
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
                if (!acceptAxis(row.axisValue, touchedAxes, otherProductAxes)) {
                    crossProductSkippedRows++;
                    crossProductSkippedAxes.add(row.axisValue);
                    continue;
                }
                if (!touchedAxes.contains(row.axisValue)) derivedAxes.add(row.axisValue);
                row.lineItemId = lineId;                              // D-43：记住是哪张卡片投的
                byAxis.computeIfAbsent(row.axisValue, k -> new ArrayList<>()).add(row);
            }
        }

        // ── ⑤-b 🔴 D-43 跨卡片归一（🚫 必须在 anchor() 之前）───────────────────────
        //    上面这段是**逐张卡片直接拼接**：同一销售料号有 N 个产品行时（一号多客户产品编号，
        //    导入建单的常态），同一条主表行会被投影 N 份。
        //    ⚠️ 在 anchor() **之后**去重就晚了：anchor 的 usedBase 只让第一份拿到 origin_id，
        //    其余 N-1 份变成 origin_id=NULL —— 那时它们与「真·用户新增行」已经分不开，
        //    而后者必须保留（D-36）。⇒ 归一只能挂在这里。
        //    🚫 只跨卡片归一，绝不收敛同一张卡片内部的行（那是 AP-60 的原始形态）。
        int dedupedRows = 0;
        for (Map.Entry<String, Map<String, List<DsRecordRow>>> se : bySheet.entrySet()) {
            Map<String, ColumnDef> dedupColDefs = colDefsOf(sheetByTable.get(se.getKey()));
            Set<String> dedupMatchCols = scopeColumnsBySheet.getOrDefault(se.getKey(), Set.of());
            Set<String> dedupGrainCols = grainColumnsBySheet.getOrDefault(se.getKey(), Set.of());
            for (Map.Entry<String, List<DsRecordRow>> ae : se.getValue().entrySet()) {
                dedupedRows += DsRecordCardDeduper.dedupe(ae.getValue(), sortOrderByLine,
                        se.getKey(), ae.getKey(), dedupColDefs, dedupMatchCols, dedupGrainCols);
            }
        }

        // ── ⑥ 逐 sheet：读基底 → 锚定 → 去重影 → 整组删 → 批量插 ────────────────────
        int totalRows = 0, unanchored = 0, axisCount = 0, shadowRows = 0;
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
                // 🆕 task-260910 · B-17（AC-16 / AC-17）：同卡片渲染重影 —— 必须在 anchor **之后**，
                //    判据就是「一条锚上了、另一条逐列相同却锚不上」。见 dropAnchorShadows 的注释。
                shadowRows += DsRecordCardDeduper.dropAnchorShadows(ae.getValue(), se.getKey(), ae.getKey(),
                        colDefs, matchCols);
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
                        + "crossCardDeduped=%d sameCardShadowDropped=" + shadowRows + " treeDerivedAxes=%d%s"
                        + " crossProductAxisSkipped=%d%s (sql 与产品数/轴值数无关)",
                quotationId, s.sheets(), s.axes(), s.rows(), s.unanchoredRows(), dedupedRows,
                derivedAxes.size(), derivedAxes.isEmpty() ? "" : " " + derivedAxes,
                crossProductSkippedRows,
                crossProductSkippedAxes.isEmpty() ? "" : " " + crossProductSkippedAxes);
        // D-46：派生轴 = 本次写入面比「产品销售料号」多出来的组。财务在回填预览里会看到它们
        //       （预览的组直接来自 _record 的轴值，见 DsBackfillCollector#readRecords）
        //       ⇒ 🚫 不是静默扩大写入面。这条单独打是为了让「多出来的到底是哪几个」可查。
        if (!derivedAxes.isEmpty()) {
            LOG.infof("[ds-record] quotation=%s treeDerivedAxes=%d %s —— BOM 树闭包的**中间件**轴值组"
                            + "（树页签行主轴是 spine 节点、轴值=父件料号）。这些组本次一并重写，"
                            + "核价通过时会随本单一起回填升版。",
                    quotationId, derivedAxes.size(), derivedAxes);
        }
        if (crossProductSkippedRows > 0) {
            LOG.infof("[ds-record] quotation=%s crossProductAxisSkipped=%d 行 axes=%s —— 轴值是本单"
                            + "**另一个产品**的销售料号，那个组归它自己的卡片管（本次不在 scopeLines 里）"
                            + "⇒ 本次不写，等那张卡片自己保存时写。🚫 这不是数据丢失。",
                    quotationId, crossProductSkippedRows, crossProductSkippedAxes);
        }
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

    /**
     * <b>这个轴值组，本次要不要重写</b>（D-46 · 本次写入面的唯一判据）。
     *
     * <h3>三条规则，按序</h3>
     * <ol>
     *   <li>{@code axis ∈ touchedAxes} —— 本次变更产品（及同料号同伴卡片）的组。<b>必写</b>；</li>
     *   <li>{@code axis ∈ otherProductAxes} —— 轴值恰好是本单<b>另一个产品</b>的销售料号
     *       （该产品既是成品、又是本产品的下阶件）。<b>不写</b>：那个组归它自己的卡片管，
     *       而那张卡片不在本次 {@code scopeLines} 里 ⇒ 放行会让 {@code deleteGroups}
     *       整组删掉它的投影、只写回本卡片这一份 ⇒ <b>静默丢它的行</b>
     *       （正是 {@code scopeLines} 当初要防的形态）。等那个产品自己被保存时再写；</li>
     *   <li>其余 —— <b>树闭包派生轴</b>（中间件料号）。<b>必写</b>：这些行是本单卡片从本单
     *       {@code snapshot_rows} 投出来的，🚫 不该因为「不是产品料号」被丢 ——
     *       丢了就是 D-46（物料BOM 从来没被回填过）。</li>
     * </ol>
     *
     * <h3>🚨 ②③ 的先后不许调换</h3>
     * 一个轴值可以同时是「另一个产品的销售料号」和「本产品的树闭包派生轴」——
     * 组合产品单里这恰恰是<b>常态</b>。此时必须走 ②（不写），因为该组的<b>权威贡献者</b>
     * 是那张不在 scope 里的卡片；按 ③ 放行等于用局部投影覆盖整组。
     *
     * @param axis             投影行算出的轴值（{@code DsRecordProjector#resolveAxis}）
     * @param touchedAxes      本次变更明细行的产品销售料号
     * @param otherProductAxes 本单<b>其余</b>明细行的产品销售料号（已剔除 touchedAxes）
     */
    static boolean acceptAxis(String axis, Set<String> touchedAxes, Set<String> otherProductAxes) {
        if (axis == null || axis.isBlank()) return false;
        if (touchedAxes.contains(axis)) return true;
        return !otherProductAxes.contains(axis);
    }

    static Map<String, ColumnDef> colDefsOf(SheetDef sheet) {
        Map<String, ColumnDef> m = new LinkedHashMap<>();
        for (ColumnDef c : sheet.persistedColumns()) m.put(c.name, c);
        return m;
    }

    /**
     * <b>D-48（2026-09-08 主线亲验抓到）</b>：删报价单时，清掉该单在<b>全部</b>
     * {@code ds_quote_*_record} 上的行。
     *
     * <h3>为什么必须显式删（而不是靠数据库）</h3>
     * {@code ds_quote_*_record} 的<b>外键数 = 0</b>（两个库实测）⇒ 数据库不会级联。
     * 🚫 <b>不许为了「修」它去加 FK</b>：{@code _record} 是<b>投影</b>不是从属实体，
     * 加 FK 等于把投影的生命周期绑死在 {@code quotation} 上（回填 / 归档等场景会被 FK 反噬），
     * 零外键是设计不是遗漏。
     *
     * <p>在此之前，全工程唯一的 {@code _record} DELETE 是 {@link #deleteGroups}，它按
     * {@code (quotation_id, customer_no, axis IN (...))} 三重收窄、<b>只在重算时跑</b>
     * ⇒ 够不到「报价单已经没了」这个场景。后果是<b>孤儿行按删单次数累积</b>
     * （实测 {@code cpq_db_0724}：{@code ds_quote_element_bom_record} 孤儿 27 / 总 206 = 13%）。
     * ⚠️ 危害是数据累积而<b>不是正确性</b>：{@code DsBackfillCollector.readRecords} 按
     * {@code quotation_id} 收窄，孤儿行不会被任何活单读到。但这是本段自己交付的
     * {@code _record} 生命周期缺口 ——「谁交付谁收口」。
     *
     * <h3>表清单从 Registry 派生（🚫 不许硬编 13 张表名）</h3>
     * {@code registries.all()} → {@link DatasetRegistry#quoteRecordEnabled()}（只有报价侧 true）
     * → {@link DatasetRegistry#versionedSheets()} → {@link SheetDef#recordTable()}
     * （免版本三表返回 {@code null}，天然被跳过）。
     * <p>⇒ <b>以后加一张带版本表，它的 {@code _record} 自动被清到。</b>
     * 本项目在「判据/清单写死具体数字或名字」上栽过多次（{@code BL-0225}：
     * {@code ts01_tableCounts} 钉死 84 张表被合法建表推翻）。
     *
     * <h3>🔒 事务：{@code MANDATORY}，与删单同生共死</h3>
     * 半清半删会留下比现在更难查的中间态（单没了、投影还在，且再也没有 {@code quotation_id}
     * 能把它们找回来）。
     * <p>⚠️ 这与 {@link #syncRecords} <b>刻意不加注解</b>的理由不冲突，两者调用形态相反：
     * 那条路的调用方（{@code saveDraft}）会 {@code catch} 住并继续返回 200，
     * 加拦截器会把外层事务标成 rollback-only ⇒ 用户正文静默丢失；
     * 而本方法的调用方 {@code QuotationService.delete} <b>不 catch、直接向上抛</b>，
     * 删不干净就该整个删单失败。
     *
     * <h3>🚫 N+1</h3>
     * SQL 条数 = 带版本 sheet 数（13）条 DELETE，<b>常数级、与孤儿行数/轴值数/产品数无关</b>。
     * 🚫 不许按行、按轴值、按客户号循环发 SQL。
     *
     * @return 实际删除的总行数
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public int deleteByQuotation(UUID quotationId) {
        if (quotationId == null) return 0;
        int tables = 0, rows = 0;
        List<String> hits = new ArrayList<>();
        for (DatasetRegistry reg : registries.all()) {
            if (!reg.quoteRecordEnabled()) continue;          // 核价两套没有 _record
            for (SheetDef sheet : reg.versionedSheets()) {
                String table = sheet.recordTable();
                if (table == null) continue;                   // 免版本表不建 _record
                tables++;
                int n = em.createNativeQuery(
                                "DELETE FROM " + SqlIdent.of(table) + " WHERE quotation_id = :qid")
                        .setParameter("qid", quotationId)
                        .executeUpdate();
                rows += n;
                if (n > 0) hits.add(table + "=" + n);
            }
        }
        // 🚫 不许静默 —— 「静默」正是它累积到 13% 才被发现的原因（D-48）。
        LOG.infof("[ds-record] quotation=%s 删单清理 _record：扫描 %d 张表，命中 %d 行%s",
                quotationId, tables, rows, hits.isEmpty() ? "（本单无快照行）" : " " + hits);
        return rows;
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
