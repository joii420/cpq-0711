package com.cpq.dataset.registry;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动期 Registry ↔ DDL 同源自检（task-260902 · backtask B-3）。
 *
 * <p><b>为什么必须有</b>：现有 {@code PricingSheetRegistry} 的类注释已自陈
 * 「改 handler 的 groupKey/content 必须同步改这里，否则维护保存的升版口径与导入不一致
 * （虚假升版 / 匹配错组）」—— 这是典型的<b>双写漂移</b>：两份声明必须一致，却没有任何机制强制，
 * 漂了也<b>完全静默</b>。本类把这条口头纪律变成硬约束。
 *
 * <p><b>检查什么</b>：45 张主表 + 39 张 {@code _history} 表，逐表比对
 * <ol>
 *   <li>列名集合：Registry 声明（业务列 + id + 版本列 + 系统列，{@code _history} 再加归档三列）
 *       与 {@code information_schema.columns} 必须<b>完全相等</b>（多一列少一列都算漂移）；</li>
 *   <li>列类型：{@code ColumnDef.pgType} 与库中实际类型必须一致
 *       （{@code varchar(n)} / {@code numeric(p,s)} / {@code integer}）；</li>
 *   <li>白底 NAME 列<b>不得</b>出现在库里（AC-3：这些是主数据 JOIN 展示列，规则要求不建字段）。</li>
 * </ol>
 *
 * <p><b>不一致 = 直接启动失败</b>，异常里列出全部差异（不是遇到第一条就停）。
 *
 * <p>⚠️ 依赖 Flyway 的 {@code migrate-at-start} 已完成 —— Quarkus 在 runtime-init 阶段跑迁移，
 * 早于 {@link StartupEvent} 观察者，顺序是安全的。
 */
@ApplicationScoped
public class DatasetSchemaSelfCheck {

    private static final Logger LOG = Logger.getLogger(DatasetSchemaSelfCheck.class);

    private static final String COLS_SQL =
            "SELECT table_name, column_name, data_type, character_maximum_length, " +
            "       numeric_precision, numeric_scale " +
            "FROM information_schema.columns " +
            "WHERE table_schema = 'public' AND table_name = ANY (?)";

    @Inject DataSource dataSource;
    @Inject DatasetRegistries registries;

    /**
     * 关掉自检的唯一合法场景：迁移尚未落到目标库的一次性排障。
     * 🚫 dev / 生产不得关闭 —— 关掉就等于把双写漂移放回静默状态。
     */
    @ConfigProperty(name = "cpq.dataset.schema-check.enabled", defaultValue = "true")
    boolean enabled;

    public void onStartup(@Observes StartupEvent ev) {
        if (!enabled) {
            LOG.warn("[dataset] Registry↔DDL 启动自检已被 cpq.dataset.schema-check.enabled=false 关闭 —— 双写漂移不再被拦截");
            return;
        }
        List<String> problems = check();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "[dataset] Registry 与数据库 schema 不一致，共 " + problems.size() + " 处（V401~V404 与 "
                    + "com.cpq.dataset.registry.* 必须同源）：\n  - " + String.join("\n  - ", problems));
        }
    }

    /** @return 全部差异描述；空列表 = 一致。可被测试直接调用。 */
    public List<String> check() {
        List<String> problems = new ArrayList<>();
        Map<String, List<String>> expectCols = new LinkedHashMap<>();   // 表 → 期望列名（有序）
        Map<String, Map<String, String>> expectTypes = new LinkedHashMap<>(); // 表 → 列 → 期望类型
        Map<String, Set<String>> forbidden = new LinkedHashMap<>();     // 表 → 不得存在的 NAME 列

        for (DatasetRegistry reg : registries.all()) {
            for (SheetDef s : reg.sheets()) {
                expectCols.put(s.tableName, s.expectedTableColumns());
                expectTypes.put(s.tableName, typesOf(s));
                Set<String> nameCols = new LinkedHashSet<>();
                for (ColumnDef c : s.nameColumns()) nameCols.add(c.name);
                if (!nameCols.isEmpty()) forbidden.put(s.tableName, nameCols);
                if (s.versioned) {
                    expectCols.put(s.historyTable(), s.expectedHistoryColumns());
                    Map<String, String> ht = typesOf(s);
                    ht.put("origin_id", "bigint");
                    expectTypes.put(s.historyTable(), ht);
                }
            }
        }

        Map<String, Map<String, String>> actual = loadActual(expectCols.keySet());

        for (Map.Entry<String, List<String>> e : expectCols.entrySet()) {
            String table = e.getKey();
            Map<String, String> act = actual.get(table);
            if (act == null || act.isEmpty()) {
                problems.add("表不存在: " + table);
                continue;
            }
            Set<String> exp = new LinkedHashSet<>(e.getValue());
            for (String col : exp) {
                if (!act.containsKey(col)) problems.add(table + " 缺列: " + col);
            }
            for (String col : act.keySet()) {
                if (!exp.contains(col)) problems.add(table + " 多出未声明的列: " + col);
            }
            for (Map.Entry<String, String> t : expectTypes.get(table).entrySet()) {
                String a = act.get(t.getKey());
                if (a != null && !a.equals(t.getValue())) {
                    problems.add(table + "." + t.getKey() + " 类型不一致: Registry=" + t.getValue() + " DB=" + a);
                }
            }
            Set<String> forb = forbidden.get(table);
            if (forb != null) {
                for (String col : forb) {
                    if (act.containsKey(col)) {
                        problems.add(table + " 不应建白底 NAME 列（AC-3）: " + col);
                    }
                }
            }
        }
        LOG.infof("[dataset] Registry↔DDL 自检通过：%d 张表 / %d 列（%d 套数据集）",
                expectCols.size(),
                expectCols.values().stream().mapToInt(List::size).sum(),
                registries.all().size());
        return problems;
    }

    private static Map<String, String> typesOf(SheetDef s) {
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

    private Map<String, Map<String, String>> loadActual(Set<String> tables) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        try (Connection cn = dataSource.getConnection();
             PreparedStatement ps = cn.prepareStatement(COLS_SQL)) {
            ps.setArray(1, cn.createArrayOf("text", tables.toArray(new String[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>())
                       .put(rs.getString(2), normalize(rs));
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("[dataset] 读取 information_schema.columns 失败", ex);
        }
        return out;
    }

    /** 把 information_schema 的类型描述归一成迁移里的写法，便于逐字比对。 */
    private static String normalize(ResultSet rs) throws java.sql.SQLException {
        String type = rs.getString("data_type");
        Integer len = (Integer) rs.getObject("character_maximum_length");
        Integer precision = (Integer) rs.getObject("numeric_precision");
        Integer scale = (Integer) rs.getObject("numeric_scale");
        return switch (type) {
            case "character varying" -> len == null ? "varchar" : "varchar(" + len + ")";
            case "character" -> len == null ? "char" : "char(" + len + ")";
            case "numeric" -> (precision == null || scale == null) ? "numeric" : "numeric(" + precision + "," + scale + ")";
            default -> type;
        };
    }
}
