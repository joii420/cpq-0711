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
import java.sql.Connection;
import java.util.*;

/**
 * task-0721 报价数据版本升级 · B5.1/B5.2 —— 回填有效行集收集器（只读，preview 与 execute 共用）。
 *
 * <p>算法概览（对齐 backtask B5.1/B5.2 + 需求说明 §4.3 规则四）：
 * <ol>
 *   <li>整单一次 IN 查 {@code quotation_line_component_data}（禁逐行）+ 一次 IN 查涉及的 {@code component}。</li>
 *   <li>逐（行×页签）解析 {@code snapshot_rows}/{@code row_data}/{@code deleted_row_keys}/
 *       {@code deleted_tree_nodes}，按 §规则四 分类为 CHANGE（有 {@code __v6_id}）/ ADD（手工新增，
 *       两套标记都认）/ DELETE（墓碑命中）/ 排除（spine 骨架空行）。</li>
 *   <li>按 {@link QuoteBackfillColumnMapper} 把 jsonb 列值映射回 V6 物理列，仅对可回写页签生效。</li>
 *   <li>CHANGE/DELETE 行按 {@code __v6_id} 批量回查 DB 当前行（拿轴值 + 旧值），分桶到
 *       （表 → 组轴）；ADD 行按上下文合成轴值。</li>
 *   <li>额外扫描 7 张受管表里"本单 pending 但没有任何页签渲染表征"的组（路径②）。</li>
 *   <li>产出 {@link QuoteBackfillPlan}：每组按有效行集是否为空判定路径①(REBUILD)/③(OFFLINE)，
 *       未被任何页签触达的纯 pending 组判定路径②(FLIP)。</li>
 * </ol>
 */
@ApplicationScoped
public class QuoteBackfillCollector {

    private static final Logger LOG = Logger.getLogger(QuoteBackfillCollector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject EntityManager em;
    @Inject DataSource dataSource;
    @Inject MaterialMasterRepository materialMasterRepo;

    /** 一条待归组的行变更候选（CHANGE/DELETE 有 v6Id；ADD 无）。 */
    private static final class Candidate {
        String op; // CHANGE / ADD / DELETE
        UUID v6Id;
        Map<String, Object> content = new LinkedHashMap<>();  // 物理列 -> 新值（映射过的内容列）
        Map<String, Object> axisHint = new LinkedHashMap<>();  // ADD 专用：合成的轴值（可能部分为 null）
        String tabName;
    }

    public QuoteBackfillPlan collect(UUID quotationId) {
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
                    "SELECT line_item_id, component_id, tab_name, snapshot_rows, row_data, deleted_row_keys " +
                    "FROM quotation_line_component_data WHERE line_item_id IN (:ids)")
                    .setParameter("ids", lineIds)
                    .getResultList();

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
                        tabName, customerNo, out);
                } else {
                    JsonNode rowData = parseArray((String) r[4]);
                    collectFlatRows(snapshotRows, rowData, resolved, rowKeyFieldNames, delFps,
                        comp, tabName, customerNo, li, out);
                }
            }
        }

        // ── Phase B：按表批量回查 DB 当前值（拿轴值 + 旧值），把 CHANGE/DELETE 分桶到组 ──
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

            for (Candidate c : cands) {
                Map<String, Object> axis;
                Map<String, Object> oldValues = Map.of();
                if (c.v6Id != null) {
                    Map<String, Object> dbRow = dbRowById.get(c.v6Id);
                    if (dbRow == null) continue; // 行已不在 DB（并发/已被其它路径处理），跳过防炸
                    axis = new LinkedHashMap<>();
                    for (String col : spec.axisColumns) axis.put(col, dbRow.get(col));
                    oldValues = dbRow;
                } else {
                    axis = c.axisHint;
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

                QuoteBackfillPlan.RowChange rc = new QuoteBackfillPlan.RowChange();
                rc.op = c.op;
                rc.v6Id = c.v6Id;
                if (!"DELETE".equals(c.op)) {
                    rc.newValues = c.content;
                    gc.effectiveNewRows.add(c.content);
                }
                rc.oldValues = oldValues;
                gc.rowChanges.add(rc);
                if (gc.versionFrom == null && oldValues.get(spec.versionColumn) != null) {
                    gc.versionFrom = String.valueOf(oldValues.get(spec.versionColumn));
                }
            }
        }

        // ── Phase C：扫描"本单 pending 但无任何页签渲染表征"的组（路径②） ──
        for (String table : QuoteTableAxis.SCAN_TABLES) {
            QuoteTableAxis.Spec spec = QuoteTableAxis.of(table);
            List<Map<String, Object>> pendingGroups = distinctPendingAxis(table, spec.axisColumns, quotationId);
            Set<Map<String, Object>> visited = visitedAxis.getOrDefault(table, Set.of());
            Map<Map<String, Object>, QuoteBackfillPlan.GroupChange> groups =
                groupsByTable.computeIfAbsent(table, k -> new LinkedHashMap<>());
            for (Map<String, Object> axis : pendingGroups) {
                if (visited.contains(axis)) continue; // 已被页签渲染表征，路径①/③处理，跳过
                QuoteBackfillPlan.GroupChange gc = new QuoteBackfillPlan.GroupChange();
                gc.table = table;
                gc.tabName = null;
                gc.groupKeyAxis = axis;
                gc.isGlobalShared = "plating_scheme".equals(table);
                gc.route = QuoteBackfillPlan.Route.FLIP;
                gc.contentColumns = spec.contentColumns;
                gc.masterDetail = spec.master != null;
                if (spec.master != null) gc.masterTable = spec.master.masterTable;
                groups.put(axis, gc);
            }
        }

        // ── 定路径：REBUILD(有效行非空) / OFFLINE(曾有表征但删空) / FLIP(已在上面标好) ──
        for (Map.Entry<String, Map<Map<String, Object>, QuoteBackfillPlan.GroupChange>> e : groupsByTable.entrySet()) {
            for (QuoteBackfillPlan.GroupChange gc : e.getValue().values()) {
                if (gc.route == QuoteBackfillPlan.Route.FLIP) { plan.groups.add(gc); continue; }
                gc.route = gc.effectiveNewRows.isEmpty()
                    ? QuoteBackfillPlan.Route.OFFLINE : QuoteBackfillPlan.Route.REBUILD;
                if (gc.masterDetail) {
                    gc.masterFixedColumns = deriveMasterFixedColumns(gc.table, gc.effectiveNewRows);
                }
                plan.groups.add(gc);
            }
        }

        plan.materialMasterStaging = materialMasterRepo.listStaging(quotationId);
        collectNewMaterialStubs(plan);
        return plan;
    }

    // ======================================================================
    // 树页签（tabType=BOM）行分类
    // ======================================================================

    private void collectTreeRows(JsonNode snapshotRows, QuoteBackfillColumnMapper.Resolved resolved,
                                 List<String> rowKeyFieldNames, Set<String> delFps, List<String> prunedNodeIds,
                                 String tabName, String customerNo, List<Candidate> out) {
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
                    Candidate c = new Candidate();
                    c.op = "DELETE";
                    c.v6Id = asUuid(driverRow.get("__v6_id"));
                    c.tabName = tabName;
                    out.add(c);
                }
                continue; // manual 且被剪 → 从未落库，静默丢弃
            }

            if (hasV6Id) {
                Candidate c = new Candidate();
                c.op = "CHANGE";
                c.v6Id = asUuid(driverRow.get("__v6_id"));
                c.content = mapColumns(resolved.colToBase, driverRow, basicDataValues, null);
                c.tabName = tabName;
                out.add(c);
            } else {
                // 手工新增树叶子（__manual=true）：material_bom_item 语义特化——
                // 轴 material_no = 宿主父件（__parentNo），content.component_no = 叶子自身料号，
                // content.characteristic = __nodeType（addLeaf 已用 BomNodeTypeResolver 算好，
                // 直接复用，不再按 tabType 重新猜测）。
                Candidate c = new Candidate();
                c.op = "ADD";
                c.tabName = tabName;
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
                                 QuotationLineItem li, List<Candidate> out) {
        List<JsonNode> survivors = new ArrayList<>();
        if (snapshotRows != null) {
            for (JsonNode row : snapshotRows) {
                JsonNode driverRow = row.path("driverRow");
                String fp = DeletedRowKeys.rowFingerprint(rowKeyFieldNames, driverRow);
                if (delFps.contains(fp)) {
                    if (driverRow.hasNonNull("__v6_id")) {
                        Candidate c = new Candidate();
                        c.op = "DELETE";
                        c.v6Id = asUuid(driverRow.get("__v6_id"));
                        c.tabName = tabName;
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
            Candidate c = new Candidate();
            c.op = "CHANGE";
            c.v6Id = asUuid(driverRow.get("__v6_id"));
            c.content = mapColumnsFlat(resolved.colToBase, driverRow, override);
            c.tabName = tabName;
            out.add(c);
        }
        for (JsonNode rd : rdManual) {
            Candidate c = new Candidate();
            c.op = "ADD";
            c.tabName = tabName;
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
        }
        return out;
    }

    /** 该表本单 pending 行按轴列去重后的组清单（DISTINCT，1 条 SQL，无 N+1）。 */
    private List<Map<String, Object>> distinctPendingAxis(String table, List<String> axisColumns, UUID quotationId) {
        List<Map<String, Object>> out = new ArrayList<>();
        String cols = String.join(", ", axisColumns);
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                 "SELECT DISTINCT " + cols + " FROM " + table + " WHERE pending_quotation_id = ?")) {
            ps.setObject(1, quotationId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> axis = new LinkedHashMap<>();
                    for (int i = 0; i < axisColumns.size(); i++) axis.put(axisColumns.get(i), rs.getObject(i + 1));
                    out.add(axis);
                }
            }
        } catch (Exception e) {
            LOG.warnf("[quote-backfill] distinctPendingAxis(%s) 失败: %s", table, e.getMessage());
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
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
    }

    private static UUID asUuid(Object v) {
        if (v instanceof UUID u) return u;
        // task-0721 Bug A 修复：driverRow.get("__v6_id") 返回的是 JsonNode（通常 TextNode）。
        // String.valueOf(JsonNode) 等价 JsonNode.toString()，对 TextNode 会带外层引号
        // （如 "\"6a3d...\""，38 字符），UUID.fromString 解析报 "UUID string too large"。
        // 必须先用 .asText() 取裸字符串值，再解析为 UUID。覆盖全部 4 处调用点
        // （253/263/314/341 行 asUuid(driverRow.get("__v6_id"))）。
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
