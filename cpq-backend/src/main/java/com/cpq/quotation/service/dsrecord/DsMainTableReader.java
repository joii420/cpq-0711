package com.cpq.quotation.service.dsrecord;

import com.cpq.dataset.registry.ColumnDef;
import com.cpq.dataset.registry.SheetDef;
import com.cpq.dataset.support.SqlIdent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读 {@code ds_quote_*} 主表的「某几个轴值的整组真实行」（task-260907 第二段 · B-5 / B-9）。
 *
 * <h3>🚨 这是 AP-60 的地基</h3>
 * 回填的基底<b>必须</b>是主表该组的真实整组行，<b>不是</b> {@code _record} 的行集 ——
 * {@code _record} 是页签投影，一个页签往往只表征组内部分行、部分列。
 * 把投影当全集正是 {@code repair-0727} 的事故形状（4 行被「对齐」成 1 行、多列置 NULL，
 * 而预览显示 0 变更）。
 *
 * <h3>🚫 N+1 硬指标</h3>
 * {@link #readGroups} <b>每张表恒 1 条 SQL</b>（{@code 轴列 IN (:axes)}），与轴值数无关。
 *
 * <h3>过渡期的 {@code customer_no}</h3>
 * 上游 {@code task-260907-报价侧加客户维度} 会把轴模型从单列改成 {@code (customer_no, 轴列)}。
 * 该 DDL 落库前主表没有这一列 ⇒ 本类<b>按 schema 自适应</b>：
 * 探测到主表有 {@code customer_no} 就带上等值谓词，没有就只按轴列查。
 * <p>🚨 <b>不自适应的后果是跨客户误伤</b>：上游落库后若仍只按轴列取基底，
 * 会把别的客户同料号的行当成本组基底 —— 那是静默的数据串号，不是报错。
 * <p>探测结果按表名进程级缓存；DDL 变更需重启（与本项目 {@code ImplicitJoinRewriter.tableColumnsCache}
 * 等既有缓存同约定，见 {@code docs/rules/backend.md §3}）。
 */
@ApplicationScoped
public class DsMainTableReader {

    /** 主表某一行的整行状态（基底行）。 */
    public static final class BaseRow {
        public final long id;
        public final int versionNo;
        public final String rowFingerprint;
        /** 全部业务列（{@code persistedColumns()}）的当前值，key = 物理列名。 */
        public final Map<String, Object> values;
        /** 该行的来源报价单 id（{@code source_quotation_id}）；上游列未落库或从未回填过时为 null。 */
        public final Object sourceQuotationId;

        BaseRow(long id, int versionNo, String rowFingerprint,
                Map<String, Object> values, Object sourceQuotationId) {
            this.id = id; this.versionNo = versionNo; this.rowFingerprint = rowFingerprint;
            this.values = values; this.sourceQuotationId = sourceQuotationId;
        }
    }

    /** 一个轴值组的现状。 */
    public static final class BaseGroup {
        public final String axisValue;
        public final List<BaseRow> rows = new ArrayList<>();
        /** 该组当前版本号；组不存在时 0。 */
        public int versionNo;

        BaseGroup(String axisValue) { this.axisValue = axisValue; }
    }

    @Inject EntityManager em;

    private final ConcurrentHashMap<String, Boolean> hasCustomerNoCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> hasSourceQuotationCache = new ConcurrentHashMap<>();

    /** 主表是否已有 {@code customer_no}（上游 DDL 落库后为 true）。进程级缓存。 */
    public boolean hasCustomerNo(SheetDef sheet) {
        return hasCustomerNoCache.computeIfAbsent(sheet.tableName,
                t -> columnExists(t, SheetDef.RECORD_CUSTOMER_COLUMN));
    }

    /** 主表是否已有 {@code source_quotation_id}（B-3 迁移落库后为 true）。进程级缓存。 */
    public boolean hasSourceQuotationId(String tableName) {
        return hasSourceQuotationCache.computeIfAbsent(tableName,
                t -> columnExists(t, SheetDef.SOURCE_QUOTATION_COLUMN));
    }

    private boolean columnExists(String table, String column) {
        Object n = em.createNativeQuery(
                        "SELECT count(*) FROM information_schema.columns " +
                        "WHERE table_schema = 'public' AND table_name = :t AND column_name = :c")
                .setParameter("t", table).setParameter("c", column)
                .getSingleResult();
        return n != null && ((Number) n).intValue() > 0;
    }

    /**
     * 读一张 sheet 的多个轴值组现状。<b>1 条 SQL</b>。
     *
     * @param customerNo 客户编号；主表尚无 {@code customer_no} 列时忽略
     * @return 轴值 → 组（入参里库中不存在的轴值也会有一个空组，{@code versionNo=0}，
     *         便于调用方区分「组不存在（CREATED）」与「组存在但没被表征」）
     */
    public Map<String, BaseGroup> readGroups(SheetDef sheet, Collection<String> axisValues, String customerNo) {
        Map<String, BaseGroup> out = new LinkedHashMap<>();
        if (axisValues == null || axisValues.isEmpty()) return out;
        List<String> axes = new ArrayList<>(new LinkedHashSet<>(axisValues));
        for (String a : axes) out.put(a, new BaseGroup(a));

        String table = SqlIdent.of(sheet.tableName);
        String axisCol = SqlIdent.of(sheet.axisColumn);
        List<ColumnDef> cols = sheet.persistedColumns();

        StringBuilder sql = new StringBuilder("SELECT id, version_no, row_fingerprint");
        for (ColumnDef c : cols) sql.append(", ").append(SqlIdent.of(c.name));
        boolean withSrcQuote = hasSourceQuotationId(sheet.tableName);
        if (withSrcQuote) sql.append(", ").append(SheetDef.SOURCE_QUOTATION_COLUMN);
        sql.append(" FROM ").append(table).append(" WHERE ").append(axisCol).append(" IN (:axes)");
        boolean withCustomer = hasCustomerNo(sheet) && customerNo != null && !customerNo.isBlank();
        if (withCustomer) sql.append(" AND ").append(SheetDef.RECORD_CUSTOMER_COLUMN).append(" = :cust");
        // ⚠️ PG 没有 ORDER BY 的行序是未定义的。这里按 (轴, id) 排只是让日志/预览稳定可复核，
        //    🚫 行身份**不靠行序**（AP-54 / A0-1「为什么不用组内行序」）——对位一律走 origin_id / 指纹。
        sql.append(" ORDER BY ").append(axisCol).append(", id");

        var q = em.createNativeQuery(sql.toString()).setParameter("axes", axes);
        if (withCustomer) q.setParameter("cust", customerNo);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        for (Object[] r : rows) {                     // 纯内存分桶（🚫 循环体内无查询）
            int i = 0;
            long id = ((Number) r[i++]).longValue();
            int ver = r[i] == null ? 0 : ((Number) r[i]).intValue(); i++;
            String fp = r[i] == null ? null : String.valueOf(r[i]); i++;
            Map<String, Object> values = new LinkedHashMap<>();
            for (ColumnDef c : cols) values.put(c.name, r[i++]);
            Object srcQ = withSrcQuote ? r[i] : null;
            Object axisVal = values.get(sheet.axisColumn);
            BaseGroup g = out.get(axisVal == null ? null : String.valueOf(axisVal));
            if (g == null) continue;
            g.rows.add(new BaseRow(id, ver, fp, values, srcQ));
            if (ver > g.versionNo) g.versionNo = ver;
        }
        return out;
    }
}
