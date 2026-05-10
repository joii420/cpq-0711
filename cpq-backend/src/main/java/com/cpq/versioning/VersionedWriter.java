package com.cpq.versioning;

import com.cpq.importexcel.service.FieldMetaCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.Session;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * VersionedWriter — 客户级表版本化写入服务.
 * 当前覆盖: mat_process / mat_fee / mat_plating_fee / plating_fee (V125 弃用,只读保留).
 *
 * <p>算法（Phase 3 #12+#13 架构师设计）：
 * <ol>
 *   <li>SELECT current row WHERE 业务键 + customer_id + hf_part_no + is_current=true
 *   <li>无 → INSERT v1, is_current=true → return isFirstInsert=true, changeLogEntriesWritten=0
 *   <li>有 → diff currentRow vs newFieldValues
 *       <ul>
 *         <li>diff 空 → return noChange=true，不写任何东西
 *         <li>diff 非空 → UPDATE current SET is_current=false → INSERT 新行 version+1 → 批量 INSERT change_log
 *       </ul>
 * </ol>
 *
 * <p>事务：调用方（BasicDataImportServiceV5.writePhysicalTables）已有 @Transactional(REQUIRED)，
 * VersionedWriter 方法在同一事务内执行（无额外 @Transactional 注解，加入调用方事务）。
 */
@ApplicationScoped
public class VersionedWriter {

    private static final Logger LOG = Logger.getLogger(VersionedWriter.class);

    @Inject
    EntityManager em;

    @Inject
    FieldMetaCache fieldMetaCache;

    // ──────────────────────────────────────────────────────────────────────
    // 公开 API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 写入请求 record（不可变值对象）。
     *
     * @param tableName       目标表: mat_process / mat_fee / mat_plating_fee / plating_fee
     * @param customerId      客户 ID
     * @param hfPartNo        HF 料号
     * @param businessKey     业务键字段（不含 customer_id / hf_part_no）
     * @param newFieldValues  全量数据字段（不含 id / version / is_current / 审计字段）
     * @param userId          操作用户 ID
     * @param importRecordId  导入记录 ID（可 null）
     * @param changeSource    变更来源（V5_IMPORT / MANUAL_EDIT / SYSTEM_INIT / SYNC）
     * @param note            备注（透传）
     */
    public record WriteRequest(
            String tableName,
            UUID customerId,
            String hfPartNo,
            Map<String, Object> businessKey,
            Map<String, Object> newFieldValues,
            UUID userId,
            UUID importRecordId,
            String changeSource,
            String note
    ) {}

    /**
     * 写入结果 record。
     *
     * @param newRowId                新行 UUID（首次插入或版本升级后的新行）
     * @param newVersion              新版本号（首次插入=1）
     * @param isFirstInsert           true → 无历史行，首次新建，不写 change_log
     * @param noChange                true → 字段值未变，无写操作
     * @param changeLogEntriesWritten 写入 change_log 的行数（每字段一行）
     */
    public record WriteResult(
            UUID newRowId,
            int newVersion,
            boolean isFirstInsert,
            boolean noChange,
            int changeLogEntriesWritten
    ) {}

    // ──────────────────────────────────────────────────────────────────────
    // 表元数据（硬编码）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 表元数据 record。
     *
     * @param tableName          物理表名
     * @param businessKeyColumns 业务键列（不含 customer_id / hf_part_no）
     * @param dataColumns        数据列（不含 id / version / is_current / 审计列）
     * @param versionColumn      版本列名
     * @param currentFlagColumn  当前标记列名
     */
    public record TableMeta(
            String tableName,
            List<String> businessKeyColumns,
            List<String> dataColumns,
            String versionColumn,
            String currentFlagColumn
    ) {}

    private static final Map<String, TableMeta> TABLE_META_MAP;

    static {
        Map<String, TableMeta> m = new HashMap<>();

        // mat_process 业务键：seq_no + sub_seq_no（+ customer_id + hf_part_no 隐含）
        // dataColumns = mat_process 全部字段去掉 id/version/is_current/customer_id/hf_part_no/imported_by/import_record_id/created_at/updated_at/created_by/updated_by
        m.put("mat_process", new TableMeta(
                "mat_process",
                List.of("seq_no", "sub_seq_no"),
                List.of("process_code", "assembly_process", "component_part_no", "component_name",
                        "supplier_code", "supplier_name", "quantity", "quantity_unit",
                        "unit_price", "freight", "currency", "price_unit", "status"),
                "version",
                "is_current"
        ));

        // mat_fee 业务键：fee_type + seq_no + 全部 dim_* 维度（对齐 V70 uq_mat_fee_current）。
        // 演进历史：
        //   V52 旧版 bk = [fee_type] -> 多 seq_no 行互相覆盖（AP-13 V69 修）。
        //   V69 bk = [fee_type, seq_no] -> 同 seq_no 下多 dim_* 行互相覆盖（AP-15 V70 修）。
        //   V70 bk = [fee_type, seq_no, 5 个 dim_*]，与 schema unique 三方对齐。
        //   Excel 业务允许同 (fee_type, seq_no) 下多行：典型如「来料 H85 + 包装费 / 材料管理费 /
        //   回收费」三行同 seq_no=2，必须按 dim_element_name 区分独立 current。
        m.put("mat_fee", new TableMeta(
                "mat_fee",
                List.of("fee_type", "seq_no",
                        "dim_input_material_no", "dim_input_material_name",
                        "dim_element_name", "dim_assembly_process", "dim_sub_seq_no"),
                List.of("fee_value", "fee_ratio", "currency", "price_unit",
                        "price_floating", "settlement_rise_ratio", "fixed_rise_value",
                        "rise_currency", "rise_unit", "reject_rate", "status"),
                "version",
                "is_current"
        ));

        // plating_fee 业务键：plating_plan_code + plan_version
        // V125: plating_fee 已弃用,新写入路由到 mat_plating_fee (报价侧).
        // 旧表 TableMeta 保留,只读路径仍可走它直到 V128+ 标 ARCHIVED.
        m.put("plating_fee", new TableMeta(
                "plating_fee",
                List.of("plating_plan_code", "plan_version"),
                List.of("plating_process_fee", "plating_material_fee", "currency", "price_unit",
                        "defect_rate", "status"),
                "version",
                "is_current"
        ));

        // V125: mat_plating_fee 报价侧电镀费用 — schema 与 plating_fee 一致.
        m.put("mat_plating_fee", new TableMeta(
                "mat_plating_fee",
                List.of("plating_plan_code", "plan_version"),
                List.of("plating_process_fee", "plating_material_fee", "currency", "price_unit",
                        "defect_rate", "status"),
                "version",
                "is_current"
        ));

        TABLE_META_MAP = Collections.unmodifiableMap(m);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 主方法
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 版本化写入。必须在调用方已有事务中执行（CDI 代理 REQUIRED 默认行为）。
     */
    public WriteResult writeWithVersioning(WriteRequest req) {
        TableMeta meta = TABLE_META_MAP.get(req.tableName());
        if (meta == null) {
            throw new IllegalArgumentException("VersionedWriter: unsupported table: " + req.tableName());
        }

        OffsetDateTime now = OffsetDateTime.now();

        // 1. 查找当前行
        Map<String, Object> currentRow = findCurrentRow(meta, req.customerId(), req.hfPartNo(), req.businessKey());

        if (currentRow == null) {
            // 2. 首次插入 v1
            UUID newId = UUID.randomUUID();
            insertNewRow(meta, newId, 1, req.customerId(), req.hfPartNo(),
                    req.businessKey(), req.newFieldValues(), req.userId(), req.importRecordId(), now);
            LOG.debugf("VersionedWriter: first insert %s cid=%s pn=%s bk=%s → v1",
                    req.tableName(), req.customerId(), req.hfPartNo(), req.businessKey());
            return new WriteResult(newId, 1, true, false, 0);
        }

        // 3a. diff
        List<FieldDiff> diffs = computeDiff(meta, req.tableName(), currentRow, req.newFieldValues());

        if (diffs.isEmpty()) {
            // 3b. 无变化 — 字段全相同，不创建新版本，但仍需把 import_record_id / imported_by / updated_at
            // 更新到本次 import，让 listCustomerPartCandidates(customerId, importRecordId) 能命中此行。
            // 否则用户重导相同 Excel 后，新 importRecordId 在物理表里没任何 row → Step2 候选为空。
            UUID existingId = toUUID(currentRow.get("id"));
            int existingVersion = toInt(currentRow.get("version"));
            if (req.importRecordId() != null) {
                touchCurrentRow(meta, existingId, req.importRecordId(), req.userId(), now);
            }
            LOG.debugf("VersionedWriter: noChange %s cid=%s pn=%s bk=%s (touched import_record_id=%s)",
                    req.tableName(), req.customerId(), req.hfPartNo(), req.businessKey(), req.importRecordId());
            return new WriteResult(existingId, existingVersion, false, true, 0);
        }

        // 3c. 版本升级：UPDATE current + INSERT new + batch change_log
        int currentVersion = toInt(currentRow.get("version"));
        UUID currentId = toUUID(currentRow.get("id"));
        int newVersion = currentVersion + 1;
        UUID newId = UUID.randomUUID();

        // UPDATE 旧行 is_current=false
        markNotCurrent(meta, currentId, now);

        // INSERT 新行
        insertNewRow(meta, newId, newVersion, req.customerId(), req.hfPartNo(),
                req.businessKey(), req.newFieldValues(), req.userId(), req.importRecordId(), now);

        // 批量写 change_log
        int logCount = batchInsertChangeLogs(req, meta, currentId, newId, currentVersion, newVersion, diffs, now);

        LOG.debugf("VersionedWriter: versioned %s cid=%s pn=%s bk=%s v%d→v%d, %d log entries",
                req.tableName(), req.customerId(), req.hfPartNo(), req.businessKey(),
                currentVersion, newVersion, logCount);

        return new WriteResult(newId, newVersion, false, false, logCount);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部方法：查询
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 查找当前版本行（is_current=true）。
     * 用 PreparedStatement 防 SQL 注入（列名白名单来自 TableMeta，值通过 ? 绑定）。
     * 返回列名→值 Map；无结果返回 null。
     */
    private Map<String, Object> findCurrentRow(TableMeta meta,
                                                UUID customerId, String hfPartNo,
                                                Map<String, Object> businessKey) {
        // 构建 SQL：WHERE customer_id=? AND hf_part_no=? AND is_current=true AND bk1=? ...
        // 列名来自白名单，直接内插安全
        StringBuilder sb = new StringBuilder("SELECT * FROM ");
        sb.append(meta.tableName())
          .append(" WHERE customer_id = ? AND hf_part_no = ? AND is_current = true");

        for (String bkCol : meta.businessKeyColumns()) {
            Object val = businessKey.get(bkCol);
            if (val == null) {
                sb.append(" AND ").append(bkCol).append(" IS NULL");
            } else {
                sb.append(" AND COALESCE(").append(bkCol).append("::text,'') = ?");
            }
        }
        sb.append(" LIMIT 1");

        final String sql = sb.toString();

        // 按顺序收集参数值（对应 ? 位置）
        final List<Object> params = new ArrayList<>();
        params.add(customerId);
        params.add(hfPartNo);
        for (String bkCol : meta.businessKeyColumns()) {
            Object val = businessKey.get(bkCol);
            // COALESCE(col::text,'') = ? 需要 text 类型参数
            if (val != null) params.add(val.toString());
        }

        final Map<String, Object> result = new LinkedHashMap<>();

        em.unwrap(Session.class).doWork(conn -> {
            try (var ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    Object p = params.get(i);
                    if (p instanceof UUID u) {
                        ps.setObject(i + 1, u, java.sql.Types.OTHER);
                    } else {
                        ps.setString(i + 1, p.toString());
                    }
                }
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        var rsMeta = rs.getMetaData();
                        int cols = rsMeta.getColumnCount();
                        for (int i = 1; i <= cols; i++) {
                            result.put(rsMeta.getColumnName(i).toLowerCase(), rs.getObject(i));
                        }
                    }
                }
            }
        });

        return result.isEmpty() ? null : result;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部方法：diff 计算
    // ──────────────────────────────────────────────────────────────────────

    record FieldDiff(String fieldName, Object oldVal, Object newVal,
                     String importance, boolean affectsCalculation) {}

    private List<FieldDiff> computeDiff(TableMeta meta, String tableName,
                                         Map<String, Object> currentRow,
                                         Map<String, Object> newValues) {
        List<FieldDiff> diffs = new ArrayList<>();

        for (String col : meta.dataColumns()) {
            Object oldVal = currentRow.get(col);
            Object newVal = newValues.get(col);

            if (!fieldsEqual(tableName, col, oldVal, newVal)) {
                FieldMetaCache.FieldMeta fm = fieldMetaCache.get(tableName, col);
                diffs.add(new FieldDiff(col, oldVal, newVal,
                        fm.importance(), fm.affectsCalculation()));
            }
        }

        return diffs;
    }

    /**
     * 字段值比较（使用 FieldMetaCache 比较器）：
     * NUM → BigDecimal.compareTo==0；STR → trim equals；DATE → Instant.equals；其他 → equals。
     */
    private boolean fieldsEqual(String tableName, String col, Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;

        FieldMetaCache.FieldMeta fm = fieldMetaCache.get(tableName, col);
        String comparator = fm.comparator();

        try {
            switch (comparator) {
                case "NUM": {
                    BigDecimal ba = toBigDecimal(a);
                    BigDecimal bb = toBigDecimal(b);
                    if (ba == null && bb == null) return true;
                    if (ba == null || bb == null) return false;
                    return ba.compareTo(bb) == 0;
                }
                case "DATE": {
                    Instant ia = toInstant(a);
                    Instant ib = toInstant(b);
                    if (ia == null && ib == null) return true;
                    if (ia == null || ib == null) return false;
                    return ia.equals(ib);
                }
                case "BOOL": {
                    Boolean ba = toBool(a);
                    Boolean bb = toBool(b);
                    if (ba == null && bb == null) return true;
                    if (ba == null || bb == null) return false;
                    return ba.equals(bb);
                }
                default: { // STR
                    return a.toString().trim().equals(b.toString().trim());
                }
            }
        } catch (Exception e) {
            LOG.warnf("VersionedWriter: fieldsEqual fallback for %s.%s: %s", tableName, col, e.getMessage());
            return a.toString().equals(b.toString());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 内部方法：写入
    // ──────────────────────────────────────────────────────────────────────

    private void markNotCurrent(TableMeta meta, UUID rowId, OffsetDateTime now) {
        em.createNativeQuery(
                "UPDATE " + meta.tableName() +
                " SET is_current = false, updated_at = :now" +
                " WHERE id = :id")
                .setParameter("now", now)
                .setParameter("id", rowId)
                .executeUpdate();
    }

    /**
     * noChange 分支专用：业务字段未变但仍要把 import_record_id / imported_by / updated_at /
     * updated_by 刷成本次 import，让按 import_record_id 过滤的查询（如
     * listCustomerPartCandidates）在重导相同 Excel 后仍能命中此行。
     * 不动业务键/业务字段/version/is_current。
     */
    private void touchCurrentRow(TableMeta meta, UUID rowId, UUID importRecordId,
                                  UUID userId, OffsetDateTime now) {
        em.createNativeQuery(
                "UPDATE " + meta.tableName() +
                " SET import_record_id = :irid, imported_by = :uid," +
                "     updated_at = :now, updated_by = :uid" +
                " WHERE id = :id")
                .setParameter("irid", importRecordId)
                .setParameter("uid", userId)
                .setParameter("now", now)
                .setParameter("id", rowId)
                .executeUpdate();
    }

    private void insertNewRow(TableMeta meta, UUID newId, int version,
                               UUID customerId, String hfPartNo,
                               Map<String, Object> businessKey,
                               Map<String, Object> fieldValues,
                               UUID userId, UUID importRecordId,
                               OffsetDateTime now) {
        // 构建有序列列表
        List<String> cols = new ArrayList<>();
        cols.add("id");
        cols.add("customer_id");
        cols.add("hf_part_no");
        cols.add("version");
        cols.add("is_current");
        cols.addAll(meta.businessKeyColumns());
        cols.addAll(meta.dataColumns());
        cols.add("imported_by");
        cols.add("import_record_id");
        cols.add("created_at");
        cols.add("updated_at");
        cols.add("created_by");
        cols.add("updated_by");

        String colList = String.join(", ", cols);
        String placeholders = cols.stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + meta.tableName() + "(" + colList + ") VALUES (" + placeholders + ")";

        // 构建按列顺序的参数值列表
        List<Object> params = new ArrayList<>();
        params.add(newId);
        params.add(customerId);
        params.add(hfPartNo);
        params.add(version);
        params.add(true);
        for (String bkCol : meta.businessKeyColumns()) {
            params.add(businessKey.get(bkCol));
        }
        for (String dataCol : meta.dataColumns()) {
            params.add(fieldValues.get(dataCol));
        }
        params.add(userId);
        params.add(importRecordId);
        params.add(java.sql.Timestamp.from(now.toInstant()));
        params.add(java.sql.Timestamp.from(now.toInstant()));
        params.add(userId);
        params.add(userId);

        em.unwrap(Session.class).doWork(conn -> {
            try (var ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    Object val = params.get(i);
                    if (val == null) {
                        ps.setNull(i + 1, java.sql.Types.NULL);
                    } else if (val instanceof UUID u) {
                        ps.setObject(i + 1, u, java.sql.Types.OTHER);
                    } else if (val instanceof Boolean b) {
                        ps.setBoolean(i + 1, b);
                    } else if (val instanceof Integer iv) {
                        ps.setInt(i + 1, iv);
                    } else if (val instanceof Long lv) {
                        ps.setLong(i + 1, lv);
                    } else if (val instanceof BigDecimal bd) {
                        ps.setBigDecimal(i + 1, bd);
                    } else if (val instanceof java.sql.Timestamp ts) {
                        ps.setTimestamp(i + 1, ts);
                    } else {
                        ps.setString(i + 1, val.toString());
                    }
                }
                ps.executeUpdate();
            }
        });
    }

    /**
     * 批量 INSERT change_log（每字段一行），使用多行 VALUES。
     * 受限于 JDBC 参数数量，一次插入所有 diff 行（通常 ≤20 字段）。
     */
    private int batchInsertChangeLogs(WriteRequest req, TableMeta meta,
                                       UUID oldRowId, UUID newRowId,
                                       int oldVersion, int newVersion,
                                       List<FieldDiff> diffs,
                                       OffsetDateTime now) {
        if (diffs.isEmpty()) return 0;

        // 为每个 diff 构建一个 VALUES 行，参数名用索引区分
        StringBuilder sqlSb = new StringBuilder();
        sqlSb.append("INSERT INTO basic_data_change_log(" +
                "id, table_name, record_id, business_key, change_type, " +
                "field_name, old_value, new_value, " +
                "customer_id, hf_part_no, importance, affects_calculation, " +
                "change_source, note, " +
                "version_before, version_after, " +
                "import_record_id, changed_by, changed_at, " +
                "created_at, updated_at, created_by, updated_by) VALUES ");

        List<String> valueClauses = new ArrayList<>();
        for (int i = 0; i < diffs.size(); i++) {
            valueClauses.add(
                "(:id" + i + ", :tn" + i + ", :rid" + i + ", CAST(:bk" + i + " AS jsonb), NULL, " +
                ":fn" + i + ", :ov" + i + ", :nv" + i + ", " +
                ":cid" + i + ", :pn" + i + ", :imp" + i + ", :ac" + i + ", " +
                ":src" + i + ", :note" + i + ", " +
                ":vb" + i + ", :va" + i + ", " +
                ":irid" + i + ", :uid" + i + ", :cat" + i + ", " +
                ":crat" + i + ", :upat" + i + ", :crby" + i + ", :upby" + i + ")"
            );
        }
        sqlSb.append(String.join(", ", valueClauses));

        String businessKeyJson = buildBusinessKeyJson(req.customerId(), req.hfPartNo(), req.businessKey());

        var q = em.createNativeQuery(sqlSb.toString());

        for (int i = 0; i < diffs.size(); i++) {
            FieldDiff d = diffs.get(i);
            q.setParameter("id" + i, UUID.randomUUID());
            q.setParameter("tn" + i, req.tableName());
            q.setParameter("rid" + i, newRowId);
            q.setParameter("bk" + i, businessKeyJson);
            q.setParameter("fn" + i, d.fieldName());
            q.setParameter("ov" + i, d.oldVal() == null ? null : d.oldVal().toString());
            q.setParameter("nv" + i, d.newVal() == null ? null : d.newVal().toString());
            q.setParameter("cid" + i, req.customerId());
            q.setParameter("pn" + i, req.hfPartNo());
            q.setParameter("imp" + i, d.importance());
            q.setParameter("ac" + i, d.affectsCalculation());
            q.setParameter("src" + i, req.changeSource());
            q.setParameter("note" + i, req.note());
            q.setParameter("vb" + i, oldVersion);
            q.setParameter("va" + i, newVersion);
            q.setParameter("irid" + i, req.importRecordId());
            q.setParameter("uid" + i, req.userId());
            q.setParameter("cat" + i, now);
            q.setParameter("crat" + i, now);
            q.setParameter("upat" + i, now);
            q.setParameter("crby" + i, req.userId());
            q.setParameter("upby" + i, req.userId());
        }

        q.executeUpdate();
        return diffs.size();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────────────────────────────

    private String buildBusinessKeyJson(UUID customerId, String hfPartNo, Map<String, Object> businessKey) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"customer_id\":\"").append(customerId).append("\",");
        sb.append("\"hf_part_no\":\"").append(escapeSql(hfPartNo)).append("\"");
        for (Map.Entry<String, Object> e : businessKey.entrySet()) {
            sb.append(",\"").append(e.getKey()).append("\":\"");
            sb.append(e.getValue() == null ? "" : escapeSql(e.getValue().toString())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeSql(String s) {
        if (s == null) return "";
        return s.replace("'", "''").replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private UUID toUUID(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }

    private int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return null; }
    }

    private Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        try { return Instant.parse(o.toString()); } catch (Exception e) { return null; }
    }

    private Boolean toBool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(o.toString());
    }
}
