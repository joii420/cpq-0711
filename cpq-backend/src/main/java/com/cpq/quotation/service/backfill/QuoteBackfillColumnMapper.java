package com.cpq.quotation.service.backfill;

import com.cpq.datasource.sqlview.QuotePendingRewriter;
import com.cpq.datasource.sqlview.VersionFilterMacro;
import org.postgresql.jdbc.PgResultSetMetaData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * task-0721 报价数据版本升级 · B5 —— 组件 {@code $view} 输出列 → V6 物理列（表.列）映射解析。
 *
 * <p>backtask B3.1 设想 {@code QuotePendingRewriter.rewrite} 顺带产出这份映射（"colToBase"），
 * 但 B3 实际交付的 {@code Result} 只含 {@code primaryTable/primaryAlias/anchorInjected}（供锚点
 * 校验用），没有逐列映射。本类补齐这一环，复用与 {@code QuoteViewValidationService.checkOne}
 * <b>完全相同的技术</b>（pgjdbc {@code PgResultSetMetaData#getBaseTableName/getBaseColumnName}
 * 对改写后 SQL 的 {@code LIMIT 0} 执行结果做元数据反查），只是把"只校验 __v6_id 一列"扩展成
 * "对每个输出列都反查基表.基列"。
 *
 * <p>安全降级：计算列 / {@code COALESCE}/表达式 / 常量列 → {@code getBaseTableName} 返回空 →
 * 该列不出现在返回的 map 里（B5 据此跳过，不猜测、不错写，与 B3 POC 结论一致）。
 *
 * <p>进程级缓存：按 {@code sqlTemplate} 文本缓存（同一视图模板结构不变则映射不变，
 * DDL/模板变更需重启——与 {@code ImplicitJoinRewriter}/{@code QuotePendingRewriter} 的既有缓存
 * 约定一致）。
 */
public final class QuoteBackfillColumnMapper {

    private QuoteBackfillColumnMapper() {}

    /** 一个输出列的基表归属。 */
    public record ColumnRef(String table, String column) {}

    /** 单个视图模板的解析结果。 */
    public static final class Resolved {
        /** 输出列别名 → 基表.基列（仅含落在 7 张白名单表内的列；计算列/非白名单基表已被过滤）。 */
        public final Map<String, ColumnRef> colToBase;
        /** 主位表（= 该视图 driver 行的 {@code __v6_id} 归属表）；不可回填时为 null。 */
        public final String primaryTable;
        public final boolean backfillable;

        Resolved(Map<String, ColumnRef> colToBase, String primaryTable, boolean backfillable) {
            this.colToBase = colToBase; this.primaryTable = primaryTable; this.backfillable = backfillable;
        }
    }

    private static final Resolved NOT_BACKFILLABLE = new Resolved(Map.of(), null, false);

    private static final ConcurrentHashMap<String, Resolved> CACHE = new ConcurrentHashMap<>();

    /**
     * 解析一个组件 {@code $view} 的 sql_template。
     *
     * @param sqlTemplate 组件 {@code component_sql_view.sql_template} 原文（未展开 versionFilter 宏）
     * @param conn        取元数据用的连接（不改数据）
     */
    public static Resolved resolve(String sqlTemplate, Connection conn) {
        if (sqlTemplate == null || sqlTemplate.isBlank()) return NOT_BACKFILLABLE;
        Resolved cached = CACHE.get(sqlTemplate);
        if (cached != null) return cached;
        Resolved r = resolveUncached(sqlTemplate, conn);
        CACHE.put(sqlTemplate, r);
        return r;
    }

    private static Resolved resolveUncached(String sqlTemplate, Connection conn) {
        try {
            String withVersionFilter = VersionFilterMacro.containsMacro(sqlTemplate)
                ? VersionFilterMacro.expandForExecution(sqlTemplate) : sqlTemplate;
            QuotePendingRewriter.Result rw = QuotePendingRewriter.rewrite(withVersionFilter, conn);
            if (!rw.anchorInjected) return NOT_BACKFILLABLE;

            // LIMIT 0 探测：:pq 绑随机 uuid（占位，不影响列元数据），其余 :xxx 命名占位符 → NULL
            // （与 QuoteViewValidationService.checkOne / SqlViewExecutor.rewriteNamedParams 的
            // "未绑定占位符安全降级"约定一致，仅用于元数据探测，不依赖具体业务值）。
            String bound = ("SELECT * FROM (" + rw.sql + ") _outer LIMIT 0")
                .replaceAll("(?<!:):pq\\b", "'" + UUID.randomUUID() + "'::uuid")
                .replaceAll("(?<!:):[A-Za-z_][A-Za-z0-9_]*\\b", "NULL");

            Map<String, ColumnRef> colToBase = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(bound);
                 ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                if (!(meta instanceof PgResultSetMetaData pgMeta)) return NOT_BACKFILLABLE;
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String label = meta.getColumnLabel(i);
                    String baseTable = pgMeta.getBaseTableName(i);
                    String baseColumn = pgMeta.getBaseColumnName(i);
                    if (baseTable == null || baseTable.isBlank() || baseColumn == null || baseColumn.isBlank()) {
                        continue; // 计算列/表达式/常量 → 安全降级，跳过
                    }
                    if (!QuotePendingRewriter.WHITELIST_TABLES.contains(baseTable)) {
                        continue; // 非白名单基表（如 material_master 等维表 JOIN 出的列）不参与回填
                    }
                    colToBase.put(label, new ColumnRef(baseTable, baseColumn));
                }
            } catch (SQLException e) {
                return NOT_BACKFILLABLE;
            }
            return new Resolved(colToBase, rw.primaryTable, true);
        } catch (Exception e) {
            return NOT_BACKFILLABLE;
        }
    }
}
