package com.cpq.semanticgraph.service;

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

    /**
     * @param targetTable 目标表物理名
     * @param rightColumns 右侧连接键列（&ge;1，多列取组合唯一性）
     */
    public CheckResult checkEdgeCardinality(String targetTable, List<String> rightColumns) {
        String colsCsv = String.join(",", rightColumns);
        long total = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM " + quoteIdent(targetTable)).getSingleResult()).longValue();

        @SuppressWarnings("unchecked")
        List<Object[]> dups = em.createNativeQuery(
                "SELECT " + colsCsv + ", count(*) c FROM " + quoteIdent(targetTable) +
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
                            "assertionSql", "SELECT " + colsCsv + ", count(*) FROM " + targetTable +
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
