import com.cpq.dataset.registry.ColumnDef;
import com.cpq.dataset.registry.QuoteRegistry;
import com.cpq.dataset.registry.SheetDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只读导出 Registry 的「期望列 + 期望类型」，供 P0-2 成对性证明比对。
 *
 * ⚠️ 类型侧逐字转写自 DatasetSchemaSelfCheck#typesOf + check() 里 rec 分支的 put，
 *    转写点在下面每一行注释里标了出处。它是**校验脚本**，不是产品第二实现；
 *    运行期真正的强制仍在 DatasetSchemaSelfCheck 本身。
 */
public class DumpExpectedTyped {

    // 转写自 DatasetSchemaSelfCheck#typesOf
    static Map<String, String> typesOf(SheetDef s) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "bigint");
        for (ColumnDef c : s.persistedColumns()) m.put(c.name, c.pgType);
        if (s.versioned) {
            m.put("version_no", "integer");
            m.put("row_fingerprint", "char(64)");
        }
        m.put("source", "varchar(16)");
        m.put("created_by", "varchar(64)");
        m.put("updated_by", "varchar(64)");
        return m;
    }

    public static void main(String[] a) {
        QuoteRegistry r = new QuoteRegistry();
        boolean rec = r.quoteRecordEnabled();          // 实测恒 true
        for (SheetDef s : r.sheets()) {
            emit("MAIN", s.tableName, s.expectedTableColumns(rec), mainTypes(s, rec));
            if (!s.versioned) continue;
            Map<String, String> ht = typesOf(s);
            ht.put("origin_id", "bigint");             // check(): _history 分支
            if (rec) ht.put(SheetDef.SOURCE_QUOTATION_COLUMN, "uuid");
            emit("HIST", s.historyTable(), s.expectedHistoryColumns(rec), ht);
            if (rec) {
                Map<String, String> extra = r.recordExtraColumns(s);
                emit("RECORD", s.recordTable(), s.expectedRecordColumns(extra.keySet()),
                        recordTypes(s, extra));
            }
        }
    }

    static Map<String, String> mainTypes(SheetDef s, boolean rec) {
        Map<String, String> m = typesOf(s);
        if (rec && s.versioned) m.put(SheetDef.SOURCE_QUOTATION_COLUMN, "uuid");
        return m;
    }

    // 转写自 check() 的 _record 分支
    static Map<String, String> recordTypes(SheetDef s, Map<String, String> extra) {
        Map<String, String> m = typesOf(s);
        m.remove("version_no");
        m.remove("row_fingerprint");
        m.put("quotation_id", "uuid");
        m.put("origin_id", "bigint");
        m.put("base_row_fingerprint", "char(64)");
        m.put("base_version_no", "integer");
        m.put("extend_column", "jsonb");
        m.put(SheetDef.RECORD_CUSTOMER_COLUMN, "varchar(20)");
        m.putAll(extra);
        return m;
    }

    static void emit(String kind, String table, java.util.List<String> cols, Map<String, String> types) {
        StringBuilder sb = new StringBuilder();
        for (String c : cols) {
            if (sb.length() > 0) sb.append(';');
            sb.append(c).append(':').append(types.getOrDefault(c, "-"));
        }
        System.out.println(kind + "\t" + table + "\t" + sb);
    }
}
