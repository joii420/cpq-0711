package com.cpq.semanticgraph.service;

import com.cpq.builder.compiler.PhysicalColumnCatalog;
import com.cpq.semanticgraph.entity.SemanticEdge;
import com.cpq.semanticgraph.entity.SemanticNode;
import com.cpq.semanticgraph.exception.SemanticValidationException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.*;

/**
 * 语义图四道保存期校验（task-260819 B-3，api.md §1.3 固定次序 + 阻断性）。
 *
 * <p>① {@code PHYSICAL_EXISTENCE} → ② {@code EDGE_CARDINALITY} → ③ {@code PATH_UNIQUENESS} 阻断；
 * ④ {@code HANDLER_RECONCILE} 告警不阻断（另一侧是代码，CI 兜底见 B-17）。
 *
 * <p>🚨 ②的固有盲区（D-32 实测）：目标表在当前收窄条件下行数 &lt; 30 时，无论声明是否正确，
 * 基数断言都"必然通过"——此时返回 {@code THIN} 而非 {@code PASS}（不阻断，200 + warnings）。
 *
 * <p>N+1 自检：本类每个 check 方法对单条候选边/节点执行固定数量（1~2 条）的
 * {@code information_schema} / 目标表探测 SQL，不随图中已有节点/边数量增长而增长。
 */
@ApplicationScoped
public class SemanticGraphValidator {

    private static final int THIN_THRESHOLD = 30;

    @Inject
    EntityManager em;

    @Inject
    PhysicalColumnCatalog catalog;

    public static final class CheckResult {
        public final String check;
        public final String status; // PASS | FAIL | THIN | SKIPPED | WARN
        public final String message;
        public final Map<String, Object> detail;

        public CheckResult(String check, String status, String message, Map<String, Object> detail) {
            this.check = check;
            this.status = status;
            this.message = message;
            this.detail = detail;
        }

        public boolean blocks() {
            return "FAIL".equals(status);
        }
    }

    // ---------------- ① PHYSICAL_EXISTENCE ----------------

    public CheckResult checkTableExists(String physicalTable) {
        if (physicalTable == null || physicalTable.isBlank()) {
            return new CheckResult("PHYSICAL_EXISTENCE", "PASS", "无物理表（FUNCTION 节点）", null);
        }
        long count = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM information_schema.tables " +
                "WHERE table_schema='public' AND table_name=:tbl")
                .setParameter("tbl", physicalTable)
                .getSingleResult()).longValue();
        if (count == 0) {
            return new CheckResult("PHYSICAL_EXISTENCE", "FAIL",
                    "表不存在：" + physicalTable, Map.of("table", physicalTable));
        }
        return new CheckResult("PHYSICAL_EXISTENCE", "PASS", null, null);
    }

    public CheckResult checkColumnExists(String physicalTable, String dbColumn) {
        @SuppressWarnings("unchecked")
        List<String> cols = em.createNativeQuery(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema='public' AND table_name=:tbl")
                .setParameter("tbl", physicalTable)
                .getResultList();
        if (!cols.contains(dbColumn)) {
            return new CheckResult("PHYSICAL_EXISTENCE", "FAIL",
                    "列不存在，该表实有列为：" + String.join(",", cols),
                    Map.of("table", physicalTable, "column", dbColumn, "actualColumns", cols));
        }
        return new CheckResult("PHYSICAL_EXISTENCE", "PASS", null, null);
    }

    // ---------------- ② EDGE_CARDINALITY ----------------

    /** 不带目标节点自身判别式的重载（standalone 场景，如 CI 反证测试直接喂表名+列名）。 */
    public CheckResult checkEdgeCardinality(String targetTable, List<String> rightColumns) {
        return checkEdgeCardinality(targetTable, rightColumns, null);
    }

    /**
     * @param targetTable 目标表物理名
     * @param rightColumns 右侧连接键列（&ge;1，多列取组合唯一性）
     * @param targetDiscriminator 目标节点自身的判别式（{@code semantic_node.discriminator}，如
     *   {@code price_type = 'COMPONENT_OTHER'}），非 null 时并入收窄条件。
     *   2026-08-21 实测发现：SUB 边指向的 {@code unit_price} 系节点若不带这条，同一右键组合会
     *   跨 price_type 撞出"重复"——那是不同判别式分支的行混在一起比对，不是真基数违反，
     *   会把 3 条本来合法的 SUB 边误判成 FAIL。这条判别式是节点自身已声明的结构性收窄
     *   （不因客户而变），与 is_current/system_type 同一类，不是新引入的客户维度。
     */
    public CheckResult checkEdgeCardinality(String targetTable, List<String> rightColumns, String targetDiscriminator) {
        String colsCsv = String.join(",", rightColumns);
        List<String> where = new ArrayList<>();
        String narrowWhere = narrowingWhere(targetTable);
        if (!narrowWhere.isEmpty()) where.add(narrowWhere);
        if (targetDiscriminator != null && !targetDiscriminator.isBlank()) where.add(targetDiscriminator);
        String whereClause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);

        long total = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM " + quoteIdent(targetTable) + whereClause).getSingleResult()).longValue();

        @SuppressWarnings("unchecked")
        List<Object[]> dups = em.createNativeQuery(
                "SELECT " + colsCsv + ", count(*) c FROM " + quoteIdent(targetTable) + whereClause +
                " GROUP BY " + colsCsv + " HAVING count(*) > 1 ORDER BY c DESC LIMIT 5")
                .getResultList();

        if (!dups.isEmpty()) {
            List<Map<String, Object>> duplicates = new ArrayList<>();
            for (Object[] row : dups) {
                duplicates.add(Map.of("value", arraysToString(row), "count", row[row.length - 1]));
            }
            return new CheckResult("EDGE_CARDINALITY", "FAIL",
                    "边基数断言未通过：" + targetTable + "." + colsCsv + " 有重复值",
                    Map.of("targetTable", targetTable, "rightColumns", rightColumns,
                            "duplicates", duplicates,
                            "assertionSql", "SELECT " + colsCsv + ", count(*) FROM " + targetTable + whereClause +
                                    " GROUP BY " + colsCsv + " HAVING count(*) > 1",
                            "suggestion", "改成 ONE_TO_MANY，或补一组连接键把粒度收窄到唯一"));
        }
        if (total < THIN_THRESHOLD) {
            return new CheckResult("EDGE_CARDINALITY", "THIN",
                    "证据不足：样本仅 " + total + " 行，该断言不构成保证",
                    Map.of("targetTable", targetTable, "sampleRows", total));
        }
        return new CheckResult("EDGE_CARDINALITY", "PASS", null, Map.of("sampleRows", total));
    }

    /**
     * 断言 SQL 的收窄条件（2026-08-21 主线裁决，D-44）：只带 {@code is_current}/{@code system_type}
     * （表若存在这两列），**不带 customer_no**——边基数是图级别的结构声明，对全体客户都成立，
     * 掺进某个客户会把"结构性唯一"降级成"该客户下唯一"，语义变了。
     *
     * <p>实测教训：不带收窄时 {@code unit_price.code} 在全表范围重复 40 组（历史失效版本行都在内），
     * 带上 is_current + system_type 后只剩 5 组——版本化表不带收窄的"重复"是误报，不是真违反。
     */
    private String narrowingWhere(String table) {
        Set<String> cols = catalog.columnsOf(List.of(table)).getOrDefault(table, Set.of());
        List<String> parts = new ArrayList<>();
        if (cols.contains("is_current")) parts.add("is_current");
        if (cols.contains("system_type")) parts.add("system_type = 'QUOTE'");
        return String.join(" AND ", parts);
    }

    private static String arraysToString(Object[] row) {
        return Arrays.toString(Arrays.copyOf(row, row.length - 1));
    }

    private static String quoteIdent(String ident) {
        // 白名单来自语义图节点的 physical_table（DDL 里已限定），此处仅做基本转义防御。
        if (!ident.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("非法表名: " + ident);
        }
        return ident;
    }

    // ---------------- ③ PATH_UNIQUENESS ----------------

    public CheckResult checkPathUniqueness(SemanticGraphSnapshot snap, UUID anchorNodeId, UUID targetNodeId) {
        List<GraphPathResolver.Path> paths = GraphPathResolver.findAllPaths(snap, anchorNodeId, targetNodeId);
        if (paths.size() >= 2) {
            List<List<String>> candidatePaths = new ArrayList<>();
            for (GraphPathResolver.Path p : paths) {
                List<String> names = new ArrayList<>();
                for (UUID nid : p.nodeIds) {
                    SemanticNode n = snap.nodeById.get(nid);
                    names.add(n != null ? n.displayName : nid.toString());
                }
                candidatePaths.add(names);
            }
            return new CheckResult("PATH_UNIQUENESS", "FAIL",
                    "存在多条路径，需显式指定 preferredPath",
                    Map.of("paths", candidatePaths));
        }
        if (paths.isEmpty()) {
            return new CheckResult("PATH_UNIQUENESS", "PASS", "该节点非锚点直接可达路径（可能经独立声明的边直接引用）", null);
        }
        return new CheckResult("PATH_UNIQUENESS", "PASS", null, null);
    }

    // ---------------- ④ HANDLER_RECONCILE（告警不阻断，权威实现见 B-17 CI 测试） ----------------

    public CheckResult checkHandlerReconcile(SemanticNode node) {
        if (node.sourceHandler == null && "SHEET".equals(node.nodeKind)) {
            return new CheckResult("HANDLER_RECONCILE", "WARN",
                    "该 SHEET 节点未登记 sourceHandler，无法与导入 handler 对账", null);
        }
        return new CheckResult("HANDLER_RECONCILE", "PASS", null, null);
    }

    /** 汇总跑四道检查，任一阻断项 FAIL 即抛异常；THIN/WARN 不阻断但要透出。 */
    public List<CheckResult> requireAllOrThrow(List<CheckResult> ordered) {
        for (CheckResult r : ordered) {
            if (r.blocks()) {
                List<Map<String, String>> checksSummary = new ArrayList<>();
                for (CheckResult c : ordered) {
                    checksSummary.add(Map.of("check", c.check, "status",
                            checksSummaryStatus(ordered, c)));
                }
                throw new SemanticValidationException(r.check, r.message,
                        r.detail == null ? Map.of() : r.detail, checksSummary);
            }
        }
        return ordered;
    }

    private String checksSummaryStatus(List<CheckResult> ordered, CheckResult c) {
        return c.status;
    }
}
