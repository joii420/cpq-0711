package com.cpq.datapath.sql;

import com.cpq.datapath.ast.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 {@link PathExpression} AST 编译为参数化 SQL（防注入）。
 *
 * <h2>X.2 已实现范围（最小可用版本）</h2>
 * <ul>
 *   <li>单表查询（含 WHERE 子句从 predicate 生成）</li>
 *   <li>EQ/NEQ/GT/LT/GTE/LTE 比较谓词 → WHERE col op ?</li>
 *   <li>IN 谓词 → WHERE col IN (?, ?, ...)</li>
 *   <li>LIKE 谓词 → WHERE col LIKE ?</li>
 *   <li>AND 复合谓词 → WHERE ... AND ...</li>
 *   <li>中文 Sheet 名通过 SchemaContext 映射到物理表名</li>
 * </ul>
 *
 * <h2>X.6 待完成（当前返回 UnsupportedOperationException）</h2>
 * <ul>
 *   <li>多段嵌套路径（多于 1 个 segment）→ 跨表 JOIN / 子查询</li>
 *   <li>嵌套 PathExpression 作为操作数的谓词（predicate value 为 PathExpression）</li>
 *   <li>跨表 JOIN 优化（由 X.6 DataLoader 负责合并）</li>
 * </ul>
 */
public class PathToSqlGenerator {

    /**
     * 客户级版本化物理表（mat_process / mat_fee / mat_plating_fee / plating_fee）。
     * 这些表都有 is_current 标志，路径查询时如果不过滤会同时拉回所有历史版本。
     * V69 修复：路径解析时自动注入 is_current = true。
     * V125: plating_fee 拆分为 mat_plating_fee (报价侧) + costing_part_plating_fee (核价侧),
     *       核价侧用 is_active 不在此集合; 旧 plating_fee 保留只读兼容.
     */
    private static final java.util.Set<String> VERSIONED_TABLES = java.util.Set.of(
            "mat_fee", "mat_process", "plating_fee", "mat_plating_fee"
    );

    /**
     * 将 AST 编译为参数化 SQL。
     *
     * @param ast 已解析的路径表达式
     * @param ctx Schema 上下文（表/字段名映射）
     * @return 编译后的 SQL 和参数列表
     * @throws UnsupportedOperationException 对 X.6 才支持的场景（多段嵌套路径）
     * @throws IllegalArgumentException      Schema 中找不到对应的物理表名
     */
    public SqlAndParams compile(PathExpression ast, SchemaContext ctx) {
        if (ast.getSegments().size() > 1) {
            // X.6 完成：多段嵌套路径需要 JOIN 或子查询，当前阶段不实现
            throw new UnsupportedOperationException(
                    "Multi-segment nested paths (JOIN/subquery) are not implemented in X.2. " +
                    "Will be completed in X.6 (DataLoader phase). Path: " + ast);
        }

        PathSegment primary = ast.getPrimarySegment();
        String physicalTable = ctx.resolveTable(primary.getName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot resolve table name for: '" + primary.getName() +
                        "'. Register it in SchemaContext or use an ASCII physical table name."));

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ");

        // ── SELECT 子句 ──────────────────────────────────────────────────
        List<String> selectedCols = new ArrayList<>();
        if (ast.getLeafField() != null) {
            String physCol = ctx.resolveColumn(primary.getName(), ast.getLeafField().getName())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Cannot resolve column '" + ast.getLeafField().getName() +
                            "' in table '" + primary.getName() + "'"));
            selectedCols.add(physCol);
            sql.append(quoteIdentifier(physCol));
        } else {
            selectedCols.add("*");
            sql.append("*");
        }

        sql.append(" FROM ").append(quoteIdentifier(physicalTable));

        // ── WHERE 子句（来自 predicate）──────────────────────────────────
        boolean hasUserPredicate = primary.hasPredicate();
        if (hasUserPredicate) {
            sql.append(" WHERE ");
            appendPredicate(sql, params, primary.getPredicate(), primary.getName(), physicalTable, ctx);
        }

        // 客户级版本表自动注入 is_current = true，避免历史版本污染查询结果
        if (VERSIONED_TABLES.contains(physicalTable)) {
            sql.append(hasUserPredicate ? " AND " : " WHERE ");
            sql.append(quoteIdentifier("is_current")).append(" = true");
        }

        return new SqlAndParams(sql.toString(), params, selectedCols);
    }

    // ── Predicate 转 WHERE ────────────────────────────────────────────────

    private void appendPredicate(StringBuilder sql, List<Object> params,
                                  Predicate predicate,
                                  String logicalTable, String physicalTable,
                                  SchemaContext ctx) {
        if (predicate instanceof EqPredicate eq) {
            appendEqPredicate(sql, params, eq, logicalTable, physicalTable, ctx);
        } else if (predicate instanceof InPredicate in) {
            appendInPredicate(sql, params, in, logicalTable, physicalTable, ctx);
        } else if (predicate instanceof LikePredicate like) {
            appendLikePredicate(sql, params, like, logicalTable, physicalTable, ctx);
        } else if (predicate instanceof CompoundPredicate compound) {
            appendCompoundPredicate(sql, params, compound, logicalTable, physicalTable, ctx);
        } else {
            throw new UnsupportedOperationException("Unknown predicate type: " + predicate.getClass());
        }
    }

    private void appendEqPredicate(StringBuilder sql, List<Object> params,
                                    EqPredicate eq,
                                    String logicalTable, String physicalTable,
                                    SchemaContext ctx) {
        String physCol = resolveColumnRequired(ctx, logicalTable, physicalTable, eq.getField());
        sql.append(quoteIdentifier(physCol))
           .append(" ").append(eq.getOp().getSymbol()).append(" ");

        Object value = eq.getValue();
        if (value instanceof PathExpression) {
            // X.6 完成：嵌套路径作为 predicate 的 value
            throw new UnsupportedOperationException(
                    "Nested PathExpression as predicate value is not implemented in X.2. " +
                    "Will be completed in X.6. Field: " + eq.getField());
        }
        sql.append("?");
        params.add(value);
    }

    private void appendInPredicate(StringBuilder sql, List<Object> params,
                                    InPredicate in,
                                    String logicalTable, String physicalTable,
                                    SchemaContext ctx) {
        String physCol = resolveColumnRequired(ctx, logicalTable, physicalTable, in.getField());
        sql.append(quoteIdentifier(physCol)).append(" IN (");
        List<Object> values = in.getValues();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?");
            params.add(values.get(i));
        }
        sql.append(")");
    }

    private void appendLikePredicate(StringBuilder sql, List<Object> params,
                                      LikePredicate like,
                                      String logicalTable, String physicalTable,
                                      SchemaContext ctx) {
        String physCol = resolveColumnRequired(ctx, logicalTable, physicalTable, like.getField());
        sql.append(quoteIdentifier(physCol)).append(" LIKE ?");
        params.add(like.getPattern());
    }

    private void appendCompoundPredicate(StringBuilder sql, List<Object> params,
                                          CompoundPredicate compound,
                                          String logicalTable, String physicalTable,
                                          SchemaContext ctx) {
        List<Predicate> terms = compound.getTerms();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) sql.append(" AND ");
            sql.append("(");
            appendPredicate(sql, params, terms.get(i), logicalTable, physicalTable, ctx);
            sql.append(")");
        }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────

    private String resolveColumnRequired(SchemaContext ctx,
                                          String logicalTable, String physicalTable,
                                          String logicalField) {
        return ctx.resolveColumn(logicalTable, logicalField)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot resolve column '" + logicalField +
                        "' in table '" + logicalTable + "' (physical: " + physicalTable + ")"));
    }

    /**
     * 对 SQL 标识符加双引号（PostgreSQL 风格，保留大小写）。
     * 仅对含特殊字符的标识符必要；纯英文小写可省略，但统一加更安全。
     */
    private String quoteIdentifier(String identifier) {
        // 如果是 *，不加引号
        if ("*".equals(identifier)) return identifier;
        return identifier;  // PostgreSQL 不强制引号，但 schema 中的名称都是小写英文，无需引号
    }
}
