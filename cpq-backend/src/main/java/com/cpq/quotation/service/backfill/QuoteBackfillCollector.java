package com.cpq.quotation.service.backfill;

import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import com.cpq.common.exception.BusinessException;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.cpq.quotation.entity.Quotation;
import com.cpq.quotation.entity.QuotationLineItem;
import com.cpq.quotation.rowkey.DeletedRowKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;

/**
 * repair-0727 · 回填改 patch 语义（修 D1 行丢失 + D2 列丢失）+ task-0721 B5.1/B5.2 —— 回填有效行集
 * 收集器（只读，preview 与 execute 共用）。
 *
 * <p><b>patch 语义</b>（取代原「页签行 = 全部有效行」的丙·全量对齐语义，见需求说明 §3.1）：
 * <ol>
 *   <li><b>基底行集</b>（权威来源是 DB，不是页签）：本单在该组有 pending 行 → 基底 = 本单全部 pending
 *       行；否则 → 基底 = 该组 {@code is_current=true AND pending_quotation_id IS NULL} 的正式行；
 *       两者皆无 → 基底为空（纯新增组）。</li>
 *   <li><b>列级 patch</b>：某基底行被页签以 {@code __v6_id} 表征时，把该页签映射出的<b>非空</b>列值
 *       覆盖到该行；未映射列保持基底值——{@code effectiveNewRows} 永远以基底行为主轴遍历，
 *       <b>不允许遍历候选(candidate)直接生成行</b>（那正是 D1 的形状：一个页签只表征组内部分行，
 *       却把投影当成整组权威）。</li>
 *   <li><b>删除</b>：被墓碑命中且能定位 {@code __v6_id} 的基底行，从有效集剔除。</li>
 *   <li><b>新增</b>：手工新增行（树叶子 {@code __manual} / 平铺 {@code _origin:'manual'}）合成后追加。</li>
 *   <li><b>路径判定</b>（repair-0727 技术总监裁决①，新增 NOOP 第四路径）：
 *       <pre>
 *       effectiveNewRows 为空                          → OFFLINE
 *       无 CHANGE/ADD/DELETE && baseSource == PENDING  → FLIP（零成本转正）
 *       无 CHANGE/ADD/DELETE && baseSource == CURRENT  → NOOP（整组跳过，不写库不计入摘要不进预览）
 *       其余                                            → REBUILD
 *       </pre>
 *       不变式（结构性保证，非仅注释）：{@code route == REBUILD ⇒ rowChanges 非空}——三种"零变更"
 *       情形已分别被 OFFLINE/FLIP/NOOP 吃掉，REBUILD 分支若仍出现空 {@code rowChanges} 判定为收集器
 *       自身 bug，见 {@link #determineRoute}。</li>
 * </ol>
 *
 * <p><b>多页签冲突</b>：同一 {@code (v6Id, 列)} 被两个组件的页签同时 patch 且值不同 → 按组件
 * {@code sort_order}（{@code quotation_line_component_data.sort_order}）先到先得，无则按
 * {@code componentId} 稳定序；记 WARN 日志，并在对应 {@link QuoteBackfillPlan.RowChange} 上打
 * {@code conflict=true} 供预览标注（repair-0727 backtask B3.2）。
 *
 * <p><b>已知限制</b>（需求说明 §3.1）：页签把某格清空为 NULL 时，{@code mapColumns} 系列方法的
 * {@code isNull → continue} 使其等同「未提供」，清空不传导——区分「未提供」与「清空」需要额外协议，
 * 本次不做，登记 BACKLOG。
 */
@ApplicationScoped
public class QuoteBackfillCollector {

    private static final Logger LOG = Logger.getLogger(QuoteBackfillCollector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject EntityManager em;
    @Inject DataSource dataSource;
    @Inject MaterialMasterRepository materialMasterRepo;

    // ======================================================================
    // repair-0727 AC-R8：DB 往返计数（技术总监裁决③——照抄 VersionedV6Writer.Profile 范式，
    // ThreadLocal 计数器，纯计数不改行为；测试直接 reset() → 跑 collect()/preview() → 读计数断言）。
    // ======================================================================

    /** 回填收集/预览路径的 DB 往返分类计数器。 */
    public static final class Profile {
        /** Phase A：整单一次性查 quotation_line_component_data / component。 */
        public int compDataQueries;
        /** Phase B：每表 1 次按 __v6_id 批量回查当前行（loadRowsByIds）。 */
        public int rowByIdQueries;
        /** B3.1 基底行集装载：每表最多 2 次（pending 一次 + 补查 current 一次）。 */
        public int baseRowQueries;
        /** Phase C：每张 SCAN_TABLES 表 1 次 distinct pending 轴扫描。 */
        public int pendingAxisScanQueries;
        /** B4 预览标签解析：品名/客户名各一次批量查询。 */
        public int labelResolveQueries;
        /** 其它（resolveCustomerNo / newMaterialStubs 存在性检查等）。 */
        public int misc;

        public void reset() {
            compDataQueries = rowByIdQueries = baseRowQueries = pendingAxisScanQueries
                = labelResolveQueries = misc = 0;
        }
        public int total() {
            return compDataQueries + rowByIdQueries + baseRowQueries + pendingAxisScanQueries
                + labelResolveQueries + misc;
        }
        public String summary() {
            return String.format(
                "total=%d | compData=%d rowById=%d baseRow=%d pendingScan=%d labelResolve=%d misc=%d",
                total(), compDataQueries, rowByIdQueries, baseRowQueries, pendingAxisScanQueries,
                labelResolveQueries, misc);
        }
    }
    private static final ThreadLocal<Profile> PROFILE = ThreadLocal.withInitial(Profile::new);
    /** 取当前线程的回填 DB 往返计数器（AC-R8：测试在调用前 {@code reset()}，调用后读计数断言）。 */
    public static Profile profile() { return PROFILE.get(); }

    /** 一条待归组的行变更候选（CHANGE/DELETE 有 v6Id；ADD 无）。 */
    private static final class Candidate {
        String op; // CHANGE / ADD / DELETE
        UUID v6Id;
        Map<String, Object> content = new LinkedHashMap<>();  // 物理列 -> 值（CHANGE=patch片段；ADD=完整新增内容）
        Map<String, Object> axisHint = new LinkedHashMap<>();  // ADD 专用：合成的轴值（可能部分为 null）
        String tabName;
        UUID componentId;   // repair-0727 B3.2：patch 冲突仲裁用
        int sortOrder;      // 同上，来自 quotation_line_component_data.sort_order
        /** repair-0727 B4：产出本候选的组件视图列映射（alias -> 基表.基列），供 GroupChange.columnAliases
         *  反查中文标签；null 或空即降级到静态字典（BackfillLabelResolver 二级）。 */
        Map<String, QuoteBackfillColumnMapper.ColumnRef> colToBase = Map.of();
    }

    public QuoteBackfillPlan collect(UUID quotationId) {
        PROFILE.get().reset();
        QuoteBackfillPlan plan = new QuoteBackfillPlan(quotationId);
        Quotation q = Quotation.findById(quotationId);
        if (q == null) throw new BusinessException(404, "报价单不存在: " + quotationId);
        String customerNo = resolveCustomerNo(q.customerId);

        List<QuotationLineItem> lineItems = QuotationLineItem.list("quotationId", quotationId);
        Map<String, List<Candidate>> byTable = new LinkedHashMap<>();

        if (!lineItems.isEmpty()) {
            List<UUID> lineIds = new ArrayList<>();
            Map<UUID, QuotationLineItem> liById = new HashMap<>();
            for (QuotationLineItem li : lineItems) { lineIds.add(li.id); liById.put(li.id, li); }

            @SuppressWarnings("unchecked")
            List<Object[]> compDataRows = em.createNativeQuery(
                    "SELECT line_item_id, component_id, tab_name, snapshot_rows, row_data, deleted_row_keys, sort_order " +
                    "FROM quotation_line_component_data WHERE line_item_id IN (:ids)")
                    .setParameter("ids", lineIds)
                    .getResultList();
            PROFILE.get().compDataQueries++;

            Set<UUID> compIds = new LinkedHashSet<>();
            for (Object[] r : compDataRows) if (r[1] != null) compIds.add(asUuid(r[1]));
            Map<UUID, Component> compById = new HashMap<>();
            if (!compIds.isEmpty()) {
                for (Component c : Component.<Component>list("id in ?1", new ArrayList<>(compIds))) {
                    compById.put(c.id, c);
                }
            }

            Map<UUID, QuoteBackfillColumnMapper.Resolved> resolvedByComp = new HashMap<>();
            try (Connection conn = dataSource.getConnection()) {
                for (Component c : compById.values()) {
                    resolvedByComp.put(c.id, resolveComponentView(c, conn));
                }
            } catch (Exception e) {
                LOG.warnf("[quote-backfill] 打开元数据连接失败，本次回填收集降级为仅 DB 扫描: %s", e.getMessage());
            }

            for (Object[] r : compDataRows) {
                if (r[0] == null || r[1] == null) continue;
                UUID lineItemId = asUuid(r[0]);
                UUID componentId = asUuid(r[1]);
                String tabName = r[2] == null ? null : r[2].toString();
                int sortOrder = r[6] == null ? 0 : ((Number) r[6]).intValue();
                Component comp = compById.get(componentId);
                if (comp == null) continue;
                QuoteBackfillColumnMapper.Resolved resolved = resolvedByComp.get(componentId);
                if (resolved == null || !resolved.backfillable) continue;
                QuoteTableAxis.Spec axisSpec = QuoteTableAxis.of(resolved.primaryTable);
                if (axisSpec == null) continue;

                QuotationLineItem li = liById.get(lineItemId);
                boolean tree = "BOM".equals(comp.tabType);
                List<String> rowKeyFieldNames = parseStringArray(comp.rowKeyFields);
                JsonNode snapshotRows = parseArray((String) r[3]);
                List<DeletedRowKeys.Tombstone> tombstones = DeletedRowKeys.parse((String) r[5]);
                Set<String> delFps = new HashSet<>();
                for (DeletedRowKeys.Tombstone t : tombstones) if (t.fp() != null) delFps.add(t.fp());
                List<String> prunedNodeIds = tree ? parseStringArray(li != null ? li.deletedTreeNodes : null) : List.of();

                List<Candidate> out = byTable.computeIfAbsent(resolved.primaryTable, k -> new ArrayList<>());

                if (tree) {
                    collectTreeRows(snapshotRows, resolved, rowKeyFieldNames, delFps, prunedNodeIds,
                        tabName, customerNo, componentId, sortOrder, out);
                } else {
                    JsonNode rowData = parseArray((String) r[4]);
                    collectFlatRows(snapshotRows, rowData, resolved, rowKeyFieldNames, delFps,
                        comp, tabName, customerNo, li, componentId, sortOrder, out);
                }
            }
        }

        // ── Phase B + repair-0727 B3.1/B3.2：按表批量回查 DB → 分桶到组 → 装载基底行集 → patch 合成 ──
        Map<String, Map<Map<String, Object>, QuoteBackfillPlan.GroupChange>> groupsByTable = new LinkedHashMap<>();
        Map<String, Set<Map<String, Object>>> visitedAxis = new LinkedHashMap<>();

        for (Map.Entry<String, List<Candidate>> e : byTable.entrySet()) {
            String table = e.getKey();
            QuoteTableAxis.Spec spec = QuoteTableAxis.of(table);
            List<Candidate> cands = e.getValue();

            Set<UUID> ids = new LinkedHashSet<>();
            for (Candidate c : cands) if (c.v6Id != null) ids.add(c.v6Id);
            Map<UUID, Map<String, Object>> dbRowById = ids.isEmpty() ? Map.of() : loadRowsByIds(table, ids);

            Map<Map<String, Object>, QuoteBackfillPlan.GroupChange> groups =
                groupsByTable.computeIfAbsent(table, k -> new LinkedHashMap<>());
            Set<Map<String, Object>> visited = visitedAxis.computeIfAbsent(table, k -> new LinkedHashSet<>());

            // ── B3.2 第一步：CHANGE 候选合成 patch（同 v6Id 多候选按 sortOrder/componentId 先到先得）──
            List<Candidate> changeCands = new ArrayList<>();
            for (Candidate c : cands) if ("CHANGE".equals(c.op) && c.v6Id != null) changeCands.add(c);
            changeCands.sort(Comparator.comparingInt((Candidate c) -> c.sortOrder)
                .thenComparing(c -> c.componentId == null ? "" : c.componentId.toString()));

            Map<UUID, Map<String, Object>> patchByRow = new LinkedHashMap<>();
            Set<UUID> conflictRows = new HashSet<>();
            for (Candidate c : changeCands) {
                Map<String, Object> patch = patchByRow.computeIfAbsent(c.v6Id, k -> new LinkedHashMap<>());
                for (Map.Entry<String, Object> en : c.content.entrySet()) {
                    String col = en.getKey();
                    if (spec.axisColumns.contains(col) || col.equals(spec.versionColumn)) {
                        LOG.warnf("[quote-backfill] table=%s v6Id=%s 页签映射列命中轴列/版本列(%s)，按规则忽略该列 patch",
                            table, c.v6Id, col);
                        continue;
                    }
                    if (patch.containsKey(col)) {
                        if (!Objects.equals(norm(patch.get(col)), norm(en.getValue()))) {
                            LOG.warnf("[quote-backfill] table=%s v6Id=%s 列=%s 被多页签同时 patch 且值不同，" +
                                "按 sortOrder 先到先得，丢弃后到值（该行预览将标注 conflict）", table, c.v6Id, col);
                            conflictRows.add(c.v6Id);
                        }
                        continue; // 先到先得：已有值不覆盖
                    }
                    patch.put(col, en.getValue());
                }
            }

            // ── B3.2 第二步：CHANGE/DELETE 按 dbRow 轴分桶；ADD 按 axisHint 分桶 ──
            Set<UUID> tombstones = new HashSet<>();
            for (Candidate c : cands) {
                Map<String, Object> axis;
                Map<String, Object> dbRow = null;
                if ("ADD".equals(c.op)) {
                    axis = c.axisHint;
                } else {
                    dbRow = dbRowById.get(c.v6Id);
                    if (dbRow == null) continue; // 行已不在 DB（并发/已被其它路径处理），跳过防炸
                    axis = new LinkedHashMap<>();
                    for (String col : spec.axisColumns) axis.put(col, dbRow.get(col));
                }
                visited.add(axis);
                QuoteBackfillPlan.GroupChange gc = groups.computeIfAbsent(axis, k -> {
                    QuoteBackfillPlan.GroupChange g = new QuoteBackfillPlan.GroupChange();
                    g.table = table;
                    g.tabName = c.tabName;
                    g.groupKeyAxis = axis;
                    g.isGlobalShared = "plating_scheme".equals(table);
                    g.contentColumns = spec.contentColumns;
                    g.masterDetail = spec.master != null;
                    if (spec.master != null) g.masterTable = spec.master.masterTable;
                    return g;
                });
                if ("DELETE".equals(c.op)) tombstones.add(c.v6Id);
                // repair-0727 B4：把本候选视图的 alias 反查合进组的 columnAliases（先到先得，同物理列
                // 多个页签重复贡献时保留第一个——够用即可，不追求"哪个页签的措辞更好"这种主观判断）。
                for (Map.Entry<String, QuoteBackfillColumnMapper.ColumnRef> ce : c.colToBase.entrySet()) {
                    String alias = ce.getKey();
                    if ("__v6_id".equals(alias)) continue;
                    String physicalCol = ce.getValue().column();
                    gc.columnAliases.putIfAbsent(physicalCol, alias.startsWith("_") ? alias.substring(1) : alias);
                }
                if (dbRow != null && gc.versionFrom == null && dbRow.get(spec.versionColumn) != null) {
                    gc.versionFrom = String.valueOf(dbRow.get(spec.versionColumn));
                }
            }

            // ── B3.1：装载基底行集（本表本次触达的全部轴，pending 优先，否则补查 current）──
            List<Map<String, Object>> axisList = new ArrayList<>(visited);
            Map<Map<String, Object>, List<Map<String, Object>>> pendingBase = groupRowsByAxis(
                loadBaseRowsByAxis(table, spec.axisColumns, axisList, true, quotationId), spec.axisColumns);
            List<Map<String, Object>> needCurrent = new ArrayList<>();
            for (Map<String, Object> ax : axisList) {
                if (!pendingBase.getOrDefault(ax, List.of()).isEmpty()) continue;
                needCurrent.add(ax);
            }
            Map<Map<String, Object>, List<Map<String, Object>>> currentBase = needCurrent.isEmpty() ? Map.of()
                : groupRowsByAxis(loadBaseRowsByAxis(table, spec.axisColumns, needCurrent, false, null), spec.axisColumns);

            // ── B3.2 第三步：有效行集 = 基底 ⊕ patch ⊖ 墓碑 ⊕ 新增（永远以基底行为主轴遍历）──
            for (Map<String, Object> axis : axisList) {
                QuoteBackfillPlan.GroupChange gc = groups.get(axis);
                List<Map<String, Object>> baseRows = pendingBase.getOrDefault(axis, List.of());
                String baseSource;
                if (!baseRows.isEmpty()) {
                    baseSource = "PENDING";
                } else {
                    baseRows = currentBase.getOrDefault(axis, List.of());
                    baseSource = baseRows.isEmpty() ? "NONE" : "CURRENT";
                }
                gc.baseRows = baseRows;
                gc.baseSource = baseSource;

                for (Map<String, Object> r : baseRows) {
                    UUID rid = asUuid(r.get("id"));
                    if (tombstones.contains(rid)) {
                        QuoteBackfillPlan.RowChange dc = new QuoteBackfillPlan.RowChange();
                        dc.op = "DELETE";
                        dc.v6Id = rid;
                        dc.oldValues = r;
                        gc.rowChanges.add(dc);
                        continue; // 剔除出有效集
                    }
                    Map<String, Object> newRow = new LinkedHashMap<>();
                    for (String col : spec.contentColumns) newRow.put(col, r.get(col));
                    Map<String, Object> patch = patchByRow.get(rid);
                    if (patch != null && !patch.isEmpty()) {
                        List<String> changedCols = new ArrayList<>();
                        for (Map.Entry<String, Object> pe : patch.entrySet()) {
                            newRow.put(pe.getKey(), pe.getValue());
                            if (!Objects.equals(norm(r.get(pe.getKey())), norm(pe.getValue()))) {
                                changedCols.add(pe.getKey());
                            }
                        }
                        if (!changedCols.isEmpty()) {
                            QuoteBackfillPlan.RowChange cc = new QuoteBackfillPlan.RowChange();
                            cc.op = "CHANGE";
                            cc.v6Id = rid;
                            cc.oldValues = r;
                            for (String col : changedCols) cc.newValues.put(col, newRow.get(col));
                            cc.conflict = conflictRows.contains(rid);
                            gc.rowChanges.add(cc);
                        }
                    }
                    gc.effectiveNewRows.add(newRow);
                }

                for (Candidate c : cands) {
                    if (!"ADD".equals(c.op) || !axis.equals(c.axisHint)) continue;
                    gc.effectiveNewRows.add(c.content);
                    QuoteBackfillPlan.RowChange ac = new QuoteBackfillPlan.RowChange();
                    ac.op = "ADD";
                    ac.newValues = c.content;
                    gc.rowChanges.add(ac);
                }
            }
        }

        // ── Phase C：扫描"本单 pending 但无任何页签渲染表征"的组（路径②，零成本 FLIP）──
        // repair-0727 验收 Bug-1 修复：executeFlip 按轴直改确实不需要 effectiveNewRows，但预览侧
        // 需要真实行数（financial 看到"0 行→0 行"会误以为整组不动，实际会转正 1~N 行——与 D4 同类
        // 失信，只是方向相反）。pendingAxisCounts 用 GROUP BY 一次性拿到"轴→行数"，仍是每表 1 条
        // SQL（不比原先的 DISTINCT 查询多一条），不逐组/逐行查，禁 N+1。行内容本身对 FLIP 预览无
        // 意义（只展示数量，不展示逐行 diff），用不可变空 Map 占位即可，不做无谓的整行拉取。
        for (String table : QuoteTableAxis.SCAN_TABLES) {
            QuoteTableAxis.Spec spec = QuoteTableAxis.of(table);
            Map<Map<String, Object>, Long> pendingCounts = pendingAxisCounts(table, spec.axisColumns, quotationId);
            Set<Map<String, Object>> visited = visitedAxis.getOrDefault(table, Set.of());
            Map<Map<String, Object>, QuoteBackfillPlan.GroupChange> groups =
                groupsByTable.computeIfAbsent(table, k -> new LinkedHashMap<>());
            for (Map.Entry<Map<String, Object>, Long> e : pendingCounts.entrySet()) {
                Map<String, Object> axis = e.getKey();
                if (visited.contains(axis)) continue; // 已被页签渲染表征，走上面 B3.1/B3.2，跳过
                int count = e.getValue() == null ? 0 : e.getValue().intValue();
                QuoteBackfillPlan.GroupChange gc = new QuoteBackfillPlan.GroupChange();
                gc.table = table;
                gc.tabName = null;
                gc.groupKeyAxis = axis;
                gc.isGlobalShared = "plating_scheme".equals(table);
                gc.route = QuoteBackfillPlan.Route.FLIP;
                gc.baseSource = "PENDING"; // Phase C 定义即"纯 pending 组"
                gc.contentColumns = spec.contentColumns;
                gc.masterDetail = spec.master != null;
                if (spec.master != null) gc.masterTable = spec.master.masterTable;
                // 占位行（内容为空 Map，仅供 .size() 反映真实行数）：executeFlip 不读这两个字段，
                // 只有 QuoteBackfillPreviewService 的 baseRowCount/resultRowCount 消费它们。
                List<Map<String, Object>> placeholders = Collections.nCopies(count, Map.of());
                gc.baseRows = placeholders;
                gc.effectiveNewRows.addAll(placeholders);
                groups.put(axis, gc);
            }
        }

        // ── 定路径：REBUILD / OFFLINE / FLIP(新) / NOOP(新，跳过不进 plan)；Phase C 已预设 FLIP ──
        for (Map.Entry<String, Map<Map<String, Object>, QuoteBackfillPlan.GroupChange>> e : groupsByTable.entrySet()) {
            for (QuoteBackfillPlan.GroupChange gc : e.getValue().values()) {
                if (gc.route == QuoteBackfillPlan.Route.FLIP) { plan.groups.add(gc); continue; }
                determineRoute(gc);
                if (gc.route == QuoteBackfillPlan.Route.NOOP) continue; // 技术总监裁决①：整组跳过
                plan.groups.add(gc);
            }
        }

        plan.materialMasterPending = materialMasterRepo.listPending(quotationId);
        PROFILE.get().misc++;
        collectNewMaterialStubs(plan);
        return plan;
    }

    /**
     * repair-0727 技术总监裁决①：四路径判定（含新增 NOOP）。
     *
     * <p>不变式：{@code route == REBUILD ⇒ rowChanges 非空}（写成断言，非仅注释）——三种"零变更"
     * 情形已分别被 OFFLINE（有效集空）/FLIP（基底来自 pending 且零变更）/NOOP（基底来自 current
     * 且零变更）吃掉，若此处仍判定 REBUILD 却 rowChanges 为空，说明收集器自身有 bug，直接抛异常
     * 而非带病放行（{@code QuoteBackfillPreviewService} 的 ERROR 日志是 defense-in-depth 的第二道，
     * 不是唯一防线）。
     */
    private void determineRoute(QuoteBackfillPlan.GroupChange gc) {
        if (gc.effectiveNewRows.isEmpty()) {
            gc.route = QuoteBackfillPlan.Route.OFFLINE;
            return;
        }
        boolean anyChange = !gc.rowChanges.isEmpty(); // CHANGE(真实差异)/ADD/DELETE 任一存在则非空
        if (!anyChange) {
            gc.route = "PENDING".equals(gc.baseSource) ? QuoteBackfillPlan.Route.FLIP : QuoteBackfillPlan.Route.NOOP;
            return;
        }
        gc.route = QuoteBackfillPlan.Route.REBUILD;
        if (gc.route == QuoteBackfillPlan.Route.REBUILD && gc.rowChanges.isEmpty()) {
            throw new IllegalStateException("[quote-backfill] 不变式违反(repair-0727 裁决①)：route=REBUILD 但 " +
                "rowChanges 为空，table=" + gc.table + " axis=" + gc.groupKeyAxis);
        }
        if (gc.masterDetail) {
            gc.masterFixedColumns = deriveMasterFixedColumns(gc.table, gc.effectiveNewRows);
        }
    }

    // ======================================================================
    // 树页签（tabType=BOM）行分类
    // ======================================================================

    private void collectTreeRows(JsonNode snapshotRows, QuoteBackfillColumnMapper.Resolved resolved,
                                 List<String> rowKeyFieldNames, Set<String> delFps, List<String> prunedNodeIds,
                                 String tabName, String customerNo, UUID componentId, int sortOrder,
                                 List<Candidate> out) {
        if (snapshotRows == null) return;
        for (JsonNode row : snapshotRows) {
            JsonNode driverRow = row.path("driverRow");
            JsonNode basicDataValues = row.path("basicDataValues");
            boolean hasV6Id = driverRow.hasNonNull("__v6_id");
            boolean manual = row.path("__manual").asBoolean(false);
            if (!hasV6Id && !manual) continue; // spine 骨架空行：排除，不计入有效集

            String nodeId = row.path("__nodeId").isMissingNode() ? null : row.path("__nodeId").asText(null);
            boolean prunedByNode = nodeId != null && prunedNodeIds.stream()
                .anyMatch(p -> nodeId.equals(p) || nodeId.startsWith(p + "/"));
            String fp = DeletedRowKeys.rowFingerprint(rowKeyFieldNames, driverRow);
            boolean prunedByRowKey = delFps.contains(fp);
            boolean removed = prunedByNode || prunedByRowKey;

            if (removed) {
                if (hasV6Id) {
                    Candidate c = newCandidate("DELETE", tabName, componentId, sortOrder, resolved.colToBase);
                    c.v6Id = asUuid(driverRow.get("__v6_id"));
                    out.add(c);
                }
                continue; // manual 且被剪 → 从未落库，静默丢弃
            }

            if (hasV6Id) {
                Candidate c = newCandidate("CHANGE", tabName, componentId, sortOrder, resolved.colToBase);
                c.v6Id = asUuid(driverRow.get("__v6_id"));
                c.content = mapColumns(resolved.colToBase, driverRow, basicDataValues, null);
                out.add(c);
            } else {
                // 手工新增树叶子（__manual=true）：material_bom_item 语义特化——
                // 轴 material_no = 宿主父件（__parentNo），content.component_no = 叶子自身料号，
                // content.characteristic = __nodeType（addLeaf 已用 BomNodeTypeResolver 算好，
                // 直接复用，不再按 tabType 重新猜测）。
                Candidate c = newCandidate("ADD", tabName, componentId, sortOrder, resolved.colToBase);
                String leafNo = driverRow.path("material_no").asText(null);
                if (leafNo == null) leafNo = row.path("__hfPartNo").isNull() ? null : row.path("__hfPartNo").asText(null);
                String hostNo = row.path("__parentNo").isNull() ? null : row.path("__parentNo").asText(null);
                String nodeType = row.path("__nodeType").isMissingNode() ? null : row.path("__nodeType").asText(null);
                if ("material_bom_item".equals(resolved.primaryTable)) {
                    c.axisHint.put("system_type", "QUOTE");
                    c.axisHint.put("customer_no", customerNo);
                    c.axisHint.put("material_no", hostNo);
                    c.content.put("component_no", leafNo);
                    if (nodeType != null) c.content.put("characteristic", nodeType);
                } else {
                    // 非 material_bom_item 的树页签手工叶子：按通用列映射兜底（无法确定专属轴语义时
                    // 交由通用 mapColumns 处理，已知限制——见交付说明）。
                    c.content = mapColumns(resolved.colToBase, driverRow, basicDataValues, null);
                    c.axisHint.put("system_type", "QUOTE");
                    c.axisHint.put("customer_no", customerNo);
                }
                out.add(c);
            }
        }
    }

    // ======================================================================
    // 平铺页签（非 BOM）行分类
    // ======================================================================

    private void collectFlatRows(JsonNode snapshotRows, JsonNode rowData,
                                 QuoteBackfillColumnMapper.Resolved resolved, List<String> rowKeyFieldNames,
                                 Set<String> delFps, Component comp, String tabName, String customerNo,
                                 QuotationLineItem li, UUID componentId, int sortOrder, List<Candidate> out) {
        List<JsonNode> survivors = new ArrayList<>();
        if (snapshotRows != null) {
            for (JsonNode row : snapshotRows) {
                JsonNode driverRow = row.path("driverRow");
                String fp = DeletedRowKeys.rowFingerprint(rowKeyFieldNames, driverRow);
                if (delFps.contains(fp)) {
                    if (driverRow.hasNonNull("__v6_id")) {
                        Candidate c = newCandidate("DELETE", tabName, componentId, sortOrder, resolved.colToBase);
                        c.v6Id = asUuid(driverRow.get("__v6_id"));
                        out.add(c);
                    }
                    continue;
                }
                survivors.add(row);
            }
        }

        List<JsonNode> rdDriverPortion = new ArrayList<>();
        List<JsonNode> rdManual = new ArrayList<>();
        if (rowData != null) {
            for (JsonNode rd : rowData) {
                if ("manual".equals(rd.path("_origin").asText(""))) rdManual.add(rd); else rdDriverPortion.add(rd);
            }
        }
        if (!survivors.isEmpty() && survivors.size() != rdDriverPortion.size()) {
            LOG.warnf("[quote-backfill] component=%s tab=%s: snapshot_rows 存活行数(%d) != row_data 非手工行数(%d)，"
                    + "按较短者对齐（已知限制，见交付说明）", comp.id, tabName, survivors.size(), rdDriverPortion.size());
        }
        for (int i = 0; i < survivors.size(); i++) {
            JsonNode driverRow = survivors.get(i).path("driverRow");
            if (!driverRow.hasNonNull("__v6_id")) continue;
            JsonNode override = i < rdDriverPortion.size() ? rdDriverPortion.get(i) : null;
            Candidate c = newCandidate("CHANGE", tabName, componentId, sortOrder, resolved.colToBase);
            c.v6Id = asUuid(driverRow.get("__v6_id"));
            c.content = mapColumnsFlat(resolved.colToBase, driverRow, override);
            out.add(c);
        }
        for (JsonNode rd : rdManual) {
            Candidate c = newCandidate("ADD", tabName, componentId, sortOrder, resolved.colToBase);
            c.content = mapColumnsFromFlatOnly(resolved.colToBase, rd);
            c.axisHint.put("system_type", "QUOTE");
            c.axisHint.put("customer_no", customerNo);
            if ("material_bom_item".equals(resolved.primaryTable) || "element_bom_item".equals(resolved.primaryTable)) {
                // 平铺（非树）材质元素/零件/外购件页签手工新增行：轴 material_no = 本行报价产品根料号
                // （该页签本就是"这个产品自己的单层 BOM/元素清单"，material_part_no 等其余轴列
                // 已知限制——无法从通用列映射确定，留 null，见交付说明）。
                c.axisHint.put("material_no", li != null ? li.productPartNoSnapshot : null);
            } else if (comp.partNoField != null) {
                Object identity = rd.path(comp.partNoField).isMissingNode() ? null : rd.path(comp.partNoField).asText(null);
                if (identity != null && QuoteTableAxis.of(resolved.primaryTable) != null
                        && QuoteTableAxis.of(resolved.primaryTable).axisColumns.contains("code")) {
                    c.axisHint.put("code", identity);
                }
            }
            out.add(c);
        }
    }

    private static Candidate newCandidate(String op, String tabName, UUID componentId, int sortOrder,
                                           Map<String, QuoteBackfillColumnMapper.ColumnRef> colToBase) {
        Candidate c = new Candidate();
        c.op = op;
        c.tabName = tabName;
        c.componentId = componentId;
        c.sortOrder = sortOrder;
        c.colToBase = colToBase == null ? Map.of() : colToBase;
        return c;
    }

    // ======================================================================
    // 列映射工具
    // ======================================================================

    /** 树/无 row_data 覆盖场景：driverRow(+basicDataValues) 直接按 colToBase 取值。 */
    private Map<String, Object> mapColumns(Map<String, QuoteBackfillColumnMapper.ColumnRef> colToBase,
                                           JsonNode driverRow, JsonNode basicDataValues, JsonNode override) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, QuoteBackfillColumnMapper.ColumnRef> e : colToBase.entrySet()) {
            String alias = e.getKey();
            if ("__v6_id".equals(alias)) continue;
            JsonNode v = (override != null && !override.path(alias).isMissingNode()) ? override.path(alias)
                : driverRow.path(alias);
            if (v.isMissingNode() || v.isNull()) continue;
            out.put(e.getValue().column(), nodeToJava(v));
        }
        return out;
    }

    /** 平铺 CHANGE 行：row_data（field 名=alias 约定）覆盖优先，driverRow 兜底。 */
    private Map<String, Object> mapColumnsFlat(Map<String, QuoteBackfillColumnMapper.ColumnRef> colToBase,
                                               JsonNode driverRow, JsonNode rowDataEntry) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, QuoteBackfillColumnMapper.ColumnRef> e : colToBase.entrySet()) {
            String alias = e.getKey();
            if ("__v6_id".equals(alias)) continue;
            JsonNode v = null;
            if (rowDataEntry != null && !rowDataEntry.path(alias).isMissingNode()) v = rowDataEntry.path(alias);
            else if (!driverRow.path(alias).isMissingNode()) v = driverRow.path(alias);
            if (v == null || v.isNull()) continue;
            out.put(e.getValue().column(), nodeToJava(v));
        }
        return out;
    }

    /** 平铺 ADD（手工新增行）：只有 row_data 扁平值可用，无 driverRow。 */
    private Map<String, Object> mapColumnsFromFlatOnly(Map<String, QuoteBackfillColumnMapper.ColumnRef> colToBase,
                                                        JsonNode rowDataEntry) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, QuoteBackfillColumnMapper.ColumnRef> e : colToBase.entrySet()) {
            String alias = e.getKey();
            if ("__v6_id".equals(alias)) continue;
            JsonNode v = rowDataEntry.path(alias);
            if (v.isMissingNode() || v.isNull()) continue;
            out.put(e.getValue().column(), nodeToJava(v));
        }
        return out;
    }

    private static Object nodeToJava(JsonNode v) {
        if (v.isTextual()) return v.textValue();
        if (v.isBoolean()) return v.booleanValue();
        if (v.isBigDecimal() || v.isFloatingPointNumber()) return v.decimalValue();
        if (v.isIntegralNumber()) return v.bigIntegerValue();
        return v.asText();
    }

    /** 与 {@code VersionedV6Writer#norm}/{@code QuoteBackfillPreviewService#norm} 同口径：
     *  数字 stripTrailingZeros 后比较；null→"∅"（repair-0727 B3.2：判定 patch 是否造成真实差异）。 */
    private static String norm(Object v) {
        if (v == null) return "∅";
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return v.toString();
    }

    // ======================================================================
    // DB 批量读取工具
    // ======================================================================

    /**
     * 按 id 批量取整行（列名 → 值），用裸 JDBC + {@code ResultSetMetaData} 自解析列名
     * （与 {@code SqlViewExecutor.executeJdbc} 通用做法一致；Hibernate native "SELECT *" 只返回
     * {@code Object[]}，不带列名，改走 JDBC 更直接）。
     */
    private Map<UUID, Map<String, Object>> loadRowsByIds(String table, Set<UUID> ids) {
        Map<UUID, Map<String, Object>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM " + table + " WHERE id = ANY(?)")) {
            ps.setArray(1, conn.createArrayOf("uuid", ids.toArray()));
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                int n = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) row.put(meta.getColumnLabel(i), rs.getObject(i));
                    Object idObj = row.get("id");
                    if (idObj != null) out.put(asUuid(idObj), row);
                }
            }
        } catch (Exception e) {
            LOG.warnf("[quote-backfill] loadRowsByIds(%s) 失败: %s", table, e.getMessage());
        } finally {
            PROFILE.get().rowByIdQueries++;
        }
        return out;
    }

    /**
     * repair-0727 B3.1：按轴批量取该表的基底行集（pending 优先，否则取正式 current）。
     * 每次调用 1 条 SQL；同一表最多调用 2 次（pending 一次 + 对 pending 为空的轴补查 current 一次），
     * 满足「禁 N+1」硬性约束。
     *
     * <p><b>NULL 安全轴匹配</b>：轴列可能含 NULL（如 {@code unit_price.supplier_no}），标准 SQL
     * 元组 {@code IN (...)} 对 NULL 走 {@code =} 语义，NULL 永远不等于任何值（含 NULL 自己），会
     * 漏掉该轴的行。本方法改用 {@code (ax1 IS NOT DISTINCT FROM ? AND ax2 IS NOT DISTINCT FROM ? ...)
     * OR (...) OR ...} 逐轴 OR 展开——backtask B3.1 二选一：选择"OR 展开"而非"先按非空轴收窄再内存
     * 过滤"，因为收窄需要额外一次全表扫描才能确定"哪些轴列在本批次全非空"，反而更复杂；OR 展开对
     * 本场景常见的个位数~十位数轴元组数量代价可忽略，且与 {@code QuoteBackfillService.axisWhere}
     * 既有的单组 {@code IS NOT DISTINCT FROM} 写法同口径，不新立规范。
     */
    private List<Map<String, Object>> loadBaseRowsByAxis(String table, List<String> axisColumns,
                                                           List<Map<String, Object>> axisList,
                                                           boolean pendingOnly, UUID quotationId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (axisList.isEmpty()) return out;
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < axisList.size(); i++) {
            if (i > 0) where.append(" OR ");
            where.append('(');
            Map<String, Object> axis = axisList.get(i);
            for (int j = 0; j < axisColumns.size(); j++) {
                if (j > 0) where.append(" AND ");
                String col = axisColumns.get(j);
                where.append(col).append(" IS NOT DISTINCT FROM ?");
                params.add(axis.get(col));
            }
            where.append(')');
        }
        String sql = pendingOnly
            ? "SELECT * FROM " + table + " WHERE pending_quotation_id = ? AND (" + where + ")"
            : "SELECT * FROM " + table + " WHERE is_current = true AND pending_quotation_id IS NULL AND (" + where + ")";
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (pendingOnly) ps.setObject(idx++, quotationId);
            for (Object p : params) ps.setObject(idx++, p);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                java.sql.ResultSetMetaData meta = rs.getMetaData();
                int n = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) row.put(meta.getColumnLabel(i), rs.getObject(i));
                    out.add(row);
                }
            }
        } catch (Exception ex) {
            LOG.warnf("[quote-backfill] loadBaseRowsByAxis(%s, pendingOnly=%s) 失败: %s", table, pendingOnly, ex.getMessage());
        } finally {
            PROFILE.get().baseRowQueries++;
        }
        return out;
    }

    /** 把一批基底行按轴列重新分桶（内存操作，非 DB 调用）。 */
    private static Map<Map<String, Object>, List<Map<String, Object>>> groupRowsByAxis(
            List<Map<String, Object>> rows, List<String> axisColumns) {
        Map<Map<String, Object>, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> axis = new LinkedHashMap<>();
            for (String col : axisColumns) axis.put(col, r.get(col));
            out.computeIfAbsent(axis, k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    /**
     * repair-0727 验收 Bug-1 修复：该表本单 pending 行按轴列分组的「轴 → 行数」（GROUP BY + COUNT，
     * 1 条 SQL，无 N+1）。取代原 {@code distinctPendingAxis}（只 DISTINCT 不计数）——Phase C 的
     * FLIP 组预览需要真实行数，不能永远显示 0（financial 会误以为整组不动，实为将转正 1~N 行）。
     */
    private Map<Map<String, Object>, Long> pendingAxisCounts(String table, List<String> axisColumns, UUID quotationId) {
        Map<Map<String, Object>, Long> out = new LinkedHashMap<>();
        String cols = String.join(", ", axisColumns);
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                 "SELECT " + cols + ", COUNT(*) FROM " + table + " WHERE pending_quotation_id = ? GROUP BY " + cols)) {
            ps.setObject(1, quotationId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> axis = new LinkedHashMap<>();
                    for (int i = 0; i < axisColumns.size(); i++) axis.put(axisColumns.get(i), rs.getObject(i + 1));
                    out.put(axis, rs.getLong(axisColumns.size() + 1));
                }
            }
        } catch (Exception e) {
            LOG.warnf("[quote-backfill] pendingAxisCounts(%s) 失败: %s", table, e.getMessage());
        } finally {
            PROFILE.get().pendingAxisScanQueries++;
        }
        return out;
    }

    /** material_bom/element_bom 主表固定列：从重建后的子行集合派生（与 MaterialBomMergeHandler/Q04 同口径）。 */
    private Map<String, Object> deriveMasterFixedColumns(String childTable, List<Map<String, Object>> childRows) {
        Map<String, Object> out = new LinkedHashMap<>();
        if ("material_bom_item".equals(childTable)) {
            boolean isAssembly = childRows.stream().anyMatch(r ->
                "ASSEMBLY".equals(r.get("characteristic")) || "OUTSOURCED".equals(r.get("characteristic")));
            out.put("bom_type", isAssembly ? "ASSEMBLY" : "MATERIAL");
            out.put("characteristic", isAssembly ? "ASSEMBLY" : null);
        } else if ("element_bom_item".equals(childTable)) {
            out.put("bom_type", "MATERIAL");
        }
        return out;
    }

    /** Q6：ADD 行引入的全新料号（material_bom_item.component_no / unit_price.code）补 material_master stub。 */
    private void collectNewMaterialStubs(QuoteBackfillPlan plan) {
        Map<String, String> candidates = new LinkedHashMap<>(); // materialNo -> 兜底名称
        for (QuoteBackfillPlan.GroupChange g : plan.groups) {
            for (QuoteBackfillPlan.RowChange rc : g.rowChanges) {
                if (!"ADD".equals(rc.op)) continue;
                String no = null;
                if ("material_bom_item".equals(g.table)) no = (String) rc.newValues.get("component_no");
                else if ("unit_price".equals(g.table)) no = (String) g.groupKeyAxis.get("code");
                if (no != null && !no.isBlank()) candidates.putIfAbsent(no, no);
            }
        }
        if (candidates.isEmpty()) return;
        List<String> nos = new ArrayList<>(candidates.keySet());
        @SuppressWarnings("unchecked")
        List<Object> existing = em.createNativeQuery(
                "SELECT material_no FROM material_master WHERE material_no IN (:nos)")
                .setParameter("nos", nos)
                .getResultList();
        PROFILE.get().misc++;
        Set<String> existingSet = new HashSet<>();
        for (Object o : existing) existingSet.add(String.valueOf(o));
        for (Map.Entry<String, String> e : candidates.entrySet()) {
            if (!existingSet.contains(e.getKey())) plan.newMaterialStubs.put(e.getKey(), e.getValue());
        }
    }

    // ======================================================================
    // 组件级 $view 列映射解析
    // ======================================================================

    private static final java.util.regex.Pattern SIMPLE_DRIVER_PATH =
        java.util.regex.Pattern.compile("^\\$([a-z_][a-z0-9_]*)(?:\\[[^\\]]*])?$");

    private QuoteBackfillColumnMapper.Resolved resolveComponentView(Component comp, Connection conn) {
        if (comp.dataDriverPath == null || comp.dataDriverPath.isBlank()) {
            return new QuoteBackfillColumnMapper.Resolved(Map.of(), null, false);
        }
        java.util.regex.Matcher m = SIMPLE_DRIVER_PATH.matcher(comp.dataDriverPath.trim());
        if (!m.matches()) {
            // $$ 跨组件引用或非常规形态：本波不处理（已知限制，见交付说明）。
            return new QuoteBackfillColumnMapper.Resolved(Map.of(), null, false);
        }
        String viewName = m.group(1);
        ComponentSqlView view = ComponentSqlView.find(
            "componentId = ?1 and sqlViewName = ?2", comp.id, viewName).firstResult();
        if (view == null) return new QuoteBackfillColumnMapper.Resolved(Map.of(), null, false);
        try {
            return QuoteBackfillColumnMapper.resolve(view.sqlTemplate, conn);
        } catch (Exception e) {
            LOG.warnf("[quote-backfill] 组件=%s 视图=%s 列映射解析失败: %s", comp.id, viewName, e.getMessage());
            return new QuoteBackfillColumnMapper.Resolved(Map.of(), null, false);
        }
    }

    // ======================================================================
    // 杂项
    // ======================================================================

    private String resolveCustomerNo(UUID customerId) {
        List<Object> rows = em.createNativeQuery("SELECT code FROM customer WHERE id = :cid")
            .setParameter("cid", customerId).getResultList();
        PROFILE.get().misc++;
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
    }

    private static UUID asUuid(Object v) {
        if (v instanceof UUID u) return u;
        // task-0721 Bug A 修复：driverRow.get("__v6_id") 返回的是 JsonNode（通常 TextNode）。
        // String.valueOf(JsonNode) 等价 JsonNode.toString()，对 TextNode 会带外层引号
        // （如 "\"6a3d...\""，38 字符），UUID.fromString 解析报 "UUID string too large"。
        // 必须先用 .asText() 取裸字符串值，再解析为 UUID。
        if (v instanceof JsonNode n) return UUID.fromString(n.asText());
        return UUID.fromString(String.valueOf(v));
    }

    private static JsonNode parseArray(String json) {
        if (json == null || json.isBlank()) return MAPPER.createArrayNode();
        try {
            JsonNode n = MAPPER.readTree(json);
            return n.isArray() ? n : MAPPER.createArrayNode();
        } catch (Exception e) {
            return MAPPER.createArrayNode();
        }
    }

    private static List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode n = MAPPER.readTree(json);
            if (!n.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode e : n) { String s = e.asText(""); if (!s.isBlank()) out.add(s); }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
