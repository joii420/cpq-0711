package com.cpq.dataset.versioning;

import com.cpq.common.exception.BusinessException;
import com.cpq.dataset.registry.ColumnDef;
import com.cpq.dataset.registry.SheetDef;
import com.cpq.dataset.support.DatasetValues;
import com.cpq.dataset.support.SqlIdent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 免版本表写入器（task-260902 · B-7 · 需求文档 R-2 · AC-21/22/23/47）。
 *
 * <p>6 张免版本表（各套物料 / 报价客户料号 / 报价与详细核价的电镀方案）<b>按主键覆盖更新</b>：
 * 已存在则整行覆盖，不存在则新增。无版本号、无 {@code _history}。
 * 主键取 {@link SheetDef#primaryKeyColumns}（R-2 的唯一真源，与迁移里的唯一约束同源）。
 *
 * <p>实现：单条 {@code INSERT ... VALUES (...),(...) ON CONFLICT (主键) DO UPDATE ... RETURNING (xmax = 0)}。
 * {@code xmax = 0} 是 PG 判「本行是新插入还是被更新」的标准手法，一次往返同时拿到 inserted / updated 计数。
 *
 * <p>🚫 N+1（B-12）：语句条数 = ceil(行数/500)，与料号数无关。
 */
@ApplicationScoped
public class PlainTableWriter {

    private static final int CHUNK = 500;
    /** 去重键分隔符 0x1F，与行指纹同一约定（业务值不可能包含）。 */
    private static final char KEY_SEP = (char) 0x1F;

    private static final List<String> SYS_COLUMNS =
            List.of("source", "created_by", "updated_at", "updated_by");

    @Inject
    EntityManager em;

    /** @param inserted 新增行数 @param updated 覆盖行数 */
    public record UpsertResult(int inserted, int updated) {}

    public UpsertResult upsert(SheetDef sheet, List<Map<String, Object>> rows,
                               String source, String operator) {
        if (rows == null || rows.isEmpty()) return new UpsertResult(0, 0);
        if (sheet.versioned) {
            throw new IllegalArgumentException("带版本表不得走覆盖写入器: " + sheet.tableName);
        }
        String table = SqlIdent.of(sheet.tableName);

        List<String> pk = sheet.primaryKeyColumns;
        if (pk.isEmpty()) {
            // 明确报错好过静默重复插入：免版本表没主键 = Registry 漏了 R-2
            throw new BusinessException(500,
                    "免版本表 " + table + " 未声明业务主键，无法按主键覆盖更新（需求文档 R-2）");
        }
        for (String c : pk) SqlIdent.of(c);

        // 同一份 Excel 里主键重复 -> PG 报「ON CONFLICT DO UPDATE command cannot affect row a second time」。
        // 按主键去重，后出现的行胜出（与「按主键覆盖更新」语义一致）。
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            StringBuilder key = new StringBuilder();
            for (String c : pk) key.append(KEY_SEP).append(String.valueOf(r.get(c)));
            deduped.put(key.toString(), r);
        }
        List<Map<String, Object>> flat = new ArrayList<>(deduped.values());

        List<ColumnDef> dbCols = sheet.persistedColumns();
        List<String> allCols = new ArrayList<>();
        for (ColumnDef c : dbCols) allCols.add(SqlIdent.of(c.name));
        allCols.addAll(SYS_COLUMNS);

        List<String> setClauses = new ArrayList<>();
        for (ColumnDef c : dbCols) {
            if (pk.contains(c.name)) continue;                  // 主键列不更新
            setClauses.add(c.name + " = EXCLUDED." + c.name);
        }
        setClauses.add("source = EXCLUDED.source");
        setClauses.add("updated_at = now()");
        setClauses.add("updated_by = EXCLUDED.updated_by");

        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        int inserted = 0, updated = 0;
        for (int start = 0; start < flat.size(); start += CHUNK) {
            int end = Math.min(start + CHUNK, flat.size());
            StringBuilder sql = new StringBuilder("INSERT INTO ").append(table)
                    .append(" (").append(String.join(", ", allCols)).append(") VALUES ");
            for (int i = start; i < end; i++) {
                if (i > start) sql.append(", ");
                sql.append('(');
                for (int c = 0; c < allCols.size(); c++) {
                    if (c > 0) sql.append(", ");
                    sql.append(":p").append(i).append('_').append(c);
                }
                sql.append(')');
            }
            sql.append(" ON CONFLICT (").append(String.join(", ", pk)).append(") DO UPDATE SET ")
               .append(String.join(", ", setClauses))
               .append(" RETURNING (xmax = 0)");

            Query q = em.createNativeQuery(sql.toString());
            for (int i = start; i < end; i++) {
                Map<String, Object> row = flat.get(i);
                int c = 0;
                for (ColumnDef col : dbCols) {
                    q.setParameter("p" + i + "_" + c, DatasetValues.coerce(col, row.get(col.name)));
                    c++;
                }
                q.setParameter("p" + i + "_" + c++, source);
                q.setParameter("p" + i + "_" + c++, operator);
                q.setParameter("p" + i + "_" + c++, now);
                q.setParameter("p" + i + "_" + c, operator);
            }
            @SuppressWarnings("unchecked")
            List<Object> flags = q.getResultList();
            for (Object f : flags) {
                if (Boolean.TRUE.equals(f)) inserted++; else updated++;
            }
        }
        return new UpsertResult(inserted, updated);
    }
}
