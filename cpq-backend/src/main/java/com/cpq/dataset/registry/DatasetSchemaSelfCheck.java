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
 * <p><b>为什么必须有</b>：历史上核价维护端的声明式镜像，其类注释已自陈
 * 「改 handler 的 groupKey/content 必须同步改那里，否则维护保存的升版口径与导入不一致
 * （虚假升版 / 匹配错组）」—— 这是典型的<b>双写漂移</b>：两份声明必须一致，却没有任何机制强制，
 * 漂了也<b>完全静默</b>。本类把这条口头纪律变成硬约束。
 * （那份维护端实现已于 2026-09-07 随 task-260907 移除，但它留下的教训正是本类存在的理由，
 * 🚫 不要因为「那个类没了」就认为本自检可以删。）
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

    /**
     * task-260907 第二段 · B-4：单独关掉「报价侧 {@code _record} + {@code source_quotation_id}」这一段自检。
     *
     * <p><b>唯一合法场景</b>：代码已合入、而 B-1/B-3 两条迁移<b>还没落到目标库</b>的那个窗口
     * （本段的迁移刻意压在上游 {@code 报价侧加客户维度} 的 DDL 之后，见
     * {@code db/migration-pending-260907/README.md}）。此时自检会报「表不存在: ds_quote_xxx_record」
     * 并<b>让服务起不来</b> —— 那是设计如此，不是 bug。
     *
     * <p>🚫 迁移落库后必须改回 {@code true}（默认值）。刻意做成<b>比
     * {@code cpq.dataset.schema-check.enabled} 更窄</b>的开关：关掉这一个只放过新增的 26+13 张表，
     * 而关掉那个会把 45 张主表 + 39 张 {@code _history} 的漂移一起放回静默状态。
     */
    @ConfigProperty(name = "cpq.dataset.record-check.enabled", defaultValue = "true")
    boolean recordCheckEnabled;

    public void onStartup(@Observes StartupEvent ev) {
        // ── D-47：类型自洽先查，且**不受 enabled 开关管**──────────────────────────
        // 这一段是纯内存的（只读 Registry，不碰库），不可能因为「迁移还没落到目标库」而误报 ——
        // 而那正是 enabled=false 的唯一合法场景。放在开关外面，才不会被排障用的开关顺手关掉。
        List<String> typeProblems = checkTypeCoherence();
        if (!typeProblems.isEmpty()) {
            throw new IllegalStateException(
                    "[dataset] Registry 声明类型(type) 与建表类型(pgType) 不自洽，共 " + typeProblems.size()
                    + " 处（D-47：错配会让该列绕过对应的指纹规范化，造成「值没变也升版」且完全静默）：\n  - "
                    + String.join("\n  - ", typeProblems));
        }
        if (!enabled) {
            LOG.warn("[dataset] Registry↔DDL 启动自检已被 cpq.dataset.schema-check.enabled=false 关闭 —— 双写漂移不再被拦截");
            return;
        }
        List<String> problems = check();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "[dataset] Registry 与数据库 schema 不一致，共 " + problems.size() + " 处（V401~V404 / "
                    + "task-260907 第二段的 `_record` 与 source_quotation_id 迁移 与 "
                    + "com.cpq.dataset.registry.* 必须同源）：\n  - " + String.join("\n  - ", problems));
        }
    }

    /**
     * D-47 · 声明类型 ↔ 建表类型 自洽（<b>纯内存，不碰库</b>）。
     *
     * <h3>为什么单独立一条</h3>
     * {@link #check()} 比的是「Registry 说有这一列 / 库里也有这一列，且 {@code pgType} 一致」——
     * 它<b>完全看不到</b> {@link ColumnDef#type} 这个字段，因为那一列在库里根本没有对应物。
     * 而 {@code type} 决定的是<b>指纹怎么规范化</b>（{@link com.cpq.dataset.fingerprint.ValueNormalizer}），
     * 声明错了 DDL 一样对得上、启动一样成功、导入一样不报错，只是<b>值没变也升版</b>。
     *
     * <h3>实际事故（D-47）</h3>
     * {@code ds_quote_incoming_fixed_fee.follow_material_price} 物理类型是 {@code boolean}，
     * 却声明成 {@code type="STRING"} ⇒ 绕过 {@code normalizeBoolean} 的「宽进严出」：
     * 导入侧指纹存 Excel 原文「是」，读回侧 JDBC 返 {@code Boolean} → {@code "true"}，
     * 两边<b>永不相等</b> ⇒ 12 个业务列逐字节相同（md5 一致）却每次核价通过都空转升一版。
     *
     * <h3>规则（按 pgType 的物理族反查合法的 type 族）</h3>
     * <ul>
     *   <li>{@code boolean}                          ⟺ {@code BOOLEAN}</li>
     *   <li>{@code numeric/integer/bigint/smallint}  ⟺ {@code ValueNormalizer.isDecimalType}（NUMBER / DECIMAL / …）</li>
     *   <li>{@code varchar/char/text}                ⟹ 既不是布尔也不是数值（STRING / ENUM）</li>
     *   <li>其它物理类型                              ⟹ <b>直接报</b>。新加一种物理类型（date / uuid / jsonb …）
     *       必须先想清楚它的指纹规范化走哪条分支，🚫 不许靠「默认落文本」蒙混过去。</li>
     * </ul>
     *
     * @return 全部不自洽的描述；空列表 = 自洽。可被测试直接调用（无需起 Quarkus）。
     */
    public List<String> checkTypeCoherence() {
        List<String> problems = new ArrayList<>();
        // 🚫 N+1 自检：三层循环全是内存遍历 Registry 声明，无任何查询 / 懒加载。
        for (DatasetRegistry reg : registries.all()) {
            for (SheetDef s : reg.sheets()) {
                for (ColumnDef c : s.persistedColumns()) {
                    String problem = typeCoherenceProblem(c);
                    if (problem != null) problems.add(s.tableName + "." + c.name + " " + problem);
                }
            }
        }
        return problems;
    }

    /** @return null = 自洽；否则为差异描述（不含表名列名前缀）。 */
    static String typeCoherenceProblem(ColumnDef c) {
        String pg = c.pgType == null ? "" : c.pgType.trim().toLowerCase();
        String declared = c.type == null ? "(null)" : c.type;
        String suffix = "：pgType=" + c.pgType + " type=" + declared;
        boolean isBool = com.cpq.dataset.fingerprint.ValueNormalizer.isBooleanType(c.type);
        boolean isNum = com.cpq.dataset.fingerprint.ValueNormalizer.isDecimalType(c.type);

        if (pg.startsWith("boolean")) {
            return isBool ? null : "物理列是 boolean，type 必须声明为 BOOLEAN" + suffix;
        }
        if (pg.startsWith("numeric") || pg.startsWith("integer")
                || pg.startsWith("bigint") || pg.startsWith("smallint")) {
            return isNum ? null : "物理列是数值，type 必须是数值族（NUMBER / DECIMAL / INTEGER）" + suffix;
        }
        if (pg.startsWith("varchar") || pg.startsWith("char") || pg.startsWith("text")) {
            if (isBool) return "物理列是字符型，却声明成 BOOLEAN（会把非 true 字面量一律归一成 false）" + suffix;
            if (isNum) return "物理列是字符型，却声明成数值族（会对文本做 BigDecimal 归一）" + suffix;
            return null;
        }
        return "未识别的建表类型 —— 请先确定它的指纹规范化分支，再把它加进 typeCoherenceProblem" + suffix;
    }

    /** @return 全部差异描述；空列表 = 一致。可被测试直接调用。 */
    public List<String> check() {
        List<String> problems = new ArrayList<>();
        Map<String, List<String>> expectCols = new LinkedHashMap<>();   // 表 → 期望列名（有序）
        Map<String, Map<String, String>> expectTypes = new LinkedHashMap<>(); // 表 → 列 → 期望类型
        Map<String, Set<String>> forbidden = new LinkedHashMap<>();     // 表 → 不得存在的 NAME 列

        for (DatasetRegistry reg : registries.all()) {
            // task-260907 第二段 · B-2/B-4：只有报价侧要求「来源报价单 id」+ `_record`
            // （核价两套跟着要，它们的库里没这些东西 ⇒ 当场起不来）。
            boolean rec = reg.quoteRecordEnabled() && recordCheckEnabled;
            for (SheetDef s : reg.sheets()) {
                expectCols.put(s.tableName, s.expectedTableColumns(rec));
                Map<String, String> mt = typesOf(s);
                if (rec && s.versioned) mt.put(SheetDef.SOURCE_QUOTATION_COLUMN, "uuid");
                expectTypes.put(s.tableName, mt);
                Set<String> nameCols = new LinkedHashSet<>();
                for (ColumnDef c : s.nameColumns()) nameCols.add(c.name);
                if (!nameCols.isEmpty()) forbidden.put(s.tableName, nameCols);
                if (s.versioned) {
                    expectCols.put(s.historyTable(), s.expectedHistoryColumns(rec));
                    Map<String, String> ht = typesOf(s);
                    ht.put("origin_id", "bigint");
                    if (rec) ht.put(SheetDef.SOURCE_QUOTATION_COLUMN, "uuid");
                    expectTypes.put(s.historyTable(), ht);
                }
                // ── task-260907 第二段 · B-4：`_record` 与 `_history` 同等对待（AC-1⑤）──
                // 不纳入 = 给自己开后门：本类存在的全部意义就是硬拦「Registry 声明了、DDL 没建」
                // 这类完全静默的双写漂移。
                if (rec && s.versioned) {
                    Map<String, String> extra = reg.recordExtraColumns(s);
                    expectCols.put(s.recordTable(), s.expectedRecordColumns(extra.keySet()));
                    Map<String, String> rt = typesOf(s);
                    rt.remove("version_no");            // `_record` 不带版本列（AC-9：升版归主表）
                    rt.remove("row_fingerprint");
                    rt.put("quotation_id", "uuid");
                    // task-260911 · B-1：批次归属列（V442）。🚨 Registry 不声明它，
                    // 上面那句「多出未声明的列」会把 13 张 _record 全判红 ⇒ 后端起不来。
                    rt.put(SheetDef.RECORD_IMPORT_BATCH_COLUMN, "uuid");
                    rt.put("origin_id", "bigint");
                    rt.put("base_row_fingerprint", "char(64)");
                    rt.put("base_version_no", "integer");
                    rt.put("extend_column", "jsonb");
                    rt.put(SheetDef.RECORD_CUSTOMER_COLUMN, "varchar(20)");
                    rt.putAll(extra);
                    expectTypes.put(s.recordTable(), rt);
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
        // task-260907 · B-1：报价侧的 customer_no 静态系统列。口径照抄 ds_quote_customer_part
        // 那一列的实查结果（varchar(20) NOT NULL 无默认值），🚫 不要自己另定类型。
        if (s.customerScoped()) m.put(SheetDef.CUSTOMER_COLUMN, "varchar(20)");
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
