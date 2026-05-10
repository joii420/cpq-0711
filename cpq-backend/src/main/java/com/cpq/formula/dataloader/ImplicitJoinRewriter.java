package com.cpq.formula.dataloader;

import com.cpq.datapath.ast.CompoundPredicate;
import com.cpq.datapath.ast.EqPredicate;
import com.cpq.datapath.ast.InPredicate;
import com.cpq.datapath.ast.LikePredicate;
import com.cpq.datapath.ast.PathExpression;
import com.cpq.datapath.ast.PathSegment;
import com.cpq.datapath.ast.Predicate;
import com.cpq.datapath.cache.CachedPathParser;
import com.cpq.datapath.sql.SchemaContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Y1.5 隐式 JOIN 路径重写器。
 *
 * <p>给定一条字段路径 + driver 行(K-V Map),自动把 driver 行中"目标表也存在的列"
 * 作为 AND 谓词追加到字段路径的 [...] 中,实现"字段可跨 sheet"的隐式 JOIN。
 *
 * <h3>例子</h3>
 * <pre>
 *   字段路径:        mat_part.unit_weight
 *   driver 行:       { hf_part_no:'P1', input_material_no:'C001', loss_rate:0.05 }
 *   mat_part 列:     [hf_part_no, part_name, unit_weight, currency, ...]
 *
 *   重写结果:        mat_part[hf_part_no='P1'].unit_weight
 *                   ↑ input_material_no/loss_rate 不在 mat_part 列里 → 不注入
 * </pre>
 *
 * <h3>策略</h3>
 * <ol>
 *   <li>用 AST 解析字段路径,定位首段表名 + 已有谓词字段集合</li>
 *   <li>通过 SchemaContext.resolveTable 把首段名解析为物理表名</li>
 *   <li>查 information_schema.columns 拿物理表列(进程级缓存)</li>
 *   <li>过滤 driver 行: 列存在 AND 未被原谓词使用 AND 值非空 → 候选注入项</li>
 *   <li>字符串级追加 AND 子句(保留原谓词字面量,避免 toString 不等价回写)</li>
 * </ol>
 */
@ApplicationScoped
public class ImplicitJoinRewriter {

    private static final Logger LOG = Logger.getLogger(ImplicitJoinRewriter.class);

    // 系统/审计/状态列黑名单 - 这些列跟业务语义无关，不应作为隐式 JOIN 候选键。
    // 注入它们会引入三类问题:
    //   1. 类型不匹配 timestamptz/uuid 列与字符串字面量做等值比较 PG 解析不到运算符
    //   2. 语义错误 例如 mat_bom 应返回多行 注入 id/created_at 后被收窄成驱动行自己
    //   3. 多类型 IN 被吞 driver_path 与 basic_data_path 同表时 driver 整行被复制为
    //      WHERE 条件 结果总是自己等于自己 IN 多类型扩展被压扁
    // 三类: 审计追溯 / 导入溯源 / 生命周期状态
    private static final Set<String> SYSTEM_COLUMN_DENYLIST = Set.of(
            "id", "created_at", "updated_at", "created_by", "updated_by",
            "version", "deleted_at", "is_deleted",
            // 导入溯源
            "import_record_id", "imported_by",
            // 生命周期 / 状态 (不是业务键 会过度收窄)
            "status", "is_current"
    );

    private final Map<String, Set<String>> tableColumnsCache = new ConcurrentHashMap<>();

    @Inject
    CachedPathParser parser;

    @Inject
    DataSource dataSource;

    /**
     * 重写字段路径,把 driverRow 中适用的列以 AND 子句注入。
     *
     * @param fieldPath 原始字段路径(可含/不含花括号)
     * @param driverRow driver 行 K-V; null 或空 → 直接返回原路径
     * @param schema    Schema 上下文(用于逻辑表名 → 物理表名)
     * @return 重写后的路径(剥去花括号)
     */
    public String rewrite(String fieldPath, Map<String, Object> driverRow, SchemaContext schema) {
        return rewriteWithContext(fieldPath, driverRow, null, null, schema);
    }

    /**
     * Y1.5 增强: 重写字段路径,合并 partNo / customerId 作为"基础上下文 driver",
     * 与显式 driverRow 一起当作隐式 JOIN 候选。
     *
     * <p>合并规则: effective = {hf_part_no:partNo, customer_id:customerId} 与 driverRow 合并(driverRow 优先)。
     * 然后只注入"目标物理表存在 + 原谓词没有 + 值非 null"的项。
     *
     * @param fieldPath  原始字段路径
     * @param driverRow  显式 driver 行(可空)
     * @param partNo     当前料号(可空) — 自动作为 hf_part_no 候选
     * @param customerId 当前客户 UUID(可空) — 自动作为 customer_id 候选
     * @param schema     Schema 上下文
     */
    public String rewriteWithContext(String fieldPath,
                                      Map<String, Object> driverRow,
                                      String partNo,
                                      UUID customerId,
                                      SchemaContext schema) {
        if (fieldPath == null || fieldPath.isBlank()) return fieldPath;

        // 合并 driver 上下文:partNo / customerId + driverRow(后者优先)
        // 注意: partNo 同时作为 hf_part_no(mat_bom/mat_process/...)和 part_no(mat_part) 候选,
        //       由 getColumns 过滤掉目标表不存在的那一个 — 保证全套表都能受益
        Map<String, Object> effective = new LinkedHashMap<>();
        if (partNo != null && !partNo.isBlank()) {
            effective.put("hf_part_no", partNo);
            effective.put("part_no", partNo);
        }
        if (customerId != null) {
            effective.put("customer_id", customerId);
        }
        if (driverRow != null) effective.putAll(driverRow);

        if (effective.isEmpty()) return DataLoader.normalizePath(fieldPath);

        String normalized = DataLoader.normalizePath(fieldPath);

        PathExpression ast;
        try {
            ast = parser.parse(normalized);
        } catch (Exception e) {
            LOG.debugf("rewrite skip — parse failure for path='%s': %s", fieldPath, e.getMessage());
            return normalized;
        }

        PathSegment first = ast.getPrimarySegment();
        Optional<String> physical = (schema != null ? schema : SchemaContext.defaultContext())
                .resolveTable(first.getName());
        if (physical.isEmpty()) {
            return normalized;
        }
        Set<String> tableCols = getColumns(physical.get());
        if (tableCols.isEmpty()) {
            return normalized;
        }

        Set<String> existing = collectFields(first.getPredicate());

        // 保留 effective driver 顺序,选可注入的项
        LinkedHashMap<String, Object> toInject = new LinkedHashMap<>();
        for (var e : effective.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k == null || v == null) continue;
            if (SYSTEM_COLUMN_DENYLIST.contains(k)) continue;
            if (!tableCols.contains(k)) continue;
            if (existing.contains(k)) continue;
            toInject.put(k, v);
        }
        if (toInject.isEmpty()) {
            return normalized;
        }

        // 字符串级注入 — 不重写已有谓词,只追加
        return injectIntoFirstSegment(normalized, first.getName(), first.hasPredicate(), toInject);
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private static Set<String> collectFields(Predicate p) {
        Set<String> out = new HashSet<>();
        if (p == null) return out;
        if (p instanceof EqPredicate eq) {
            out.add(eq.getField());
        } else if (p instanceof InPredicate in) {
            out.add(in.getField());
        } else if (p instanceof LikePredicate lk) {
            out.add(lk.getField());
        } else if (p instanceof CompoundPredicate cp) {
            for (Predicate t : cp.getTerms()) out.addAll(collectFields(t));
        }
        return out;
    }

    /**
     * 在首段位置追加 AND 子句。
     *  - 已有 [...]: 在 ] 前插入 ` AND k=v AND k=v`
     *  - 无 [...]:   在首段名后插入 `[k=v AND k=v]`
     */
    private static String injectIntoFirstSegment(String path, String segName,
                                                  boolean hasPredicate,
                                                  Map<String, Object> toInject) {
        // 找首段名结束位置
        int segStart = path.indexOf(segName);
        if (segStart < 0) {
            return path;
        }
        int afterSeg = segStart + segName.length();

        if (hasPredicate) {
            int bracketStart = path.indexOf('[', afterSeg);
            if (bracketStart < 0) return path;
            int closeIdx = findMatchingBracket(path, bracketStart);
            if (closeIdx < 0) return path;
            StringBuilder sb = new StringBuilder();
            sb.append(path, 0, closeIdx);
            for (var e : toInject.entrySet()) {
                sb.append(" AND ").append(e.getKey()).append(" = ").append(literal(e.getValue()));
            }
            sb.append(path.substring(closeIdx));
            return sb.toString();
        } else {
            StringBuilder pred = new StringBuilder();
            boolean firstTerm = true;
            for (var e : toInject.entrySet()) {
                if (!firstTerm) pred.append(" AND ");
                pred.append(e.getKey()).append(" = ").append(literal(e.getValue()));
                firstTerm = false;
            }
            return path.substring(0, afterSeg) + "[" + pred + "]" + path.substring(afterSeg);
        }
    }

    /** 找 path[start] 处 '[' 对应的匹配 ']' 位置(-1 表示不平衡)。 */
    private static int findMatchingBracket(String path, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * 转 SQL 字面量（注意：这里产出的"字面量"会再被 path parser 解析成 EqPredicate.value，
     * 所以不能用 PG 专属语法如 ::uuid——交给 JDBC 端按列类型动态 cast 处理）。
     *  - Number/Boolean 原样
     *  - 其余（含 UUID）作为字符串单引号包裹 + 单引号转义；UUID 形态的字符串会在
     *    DataLoader 绑定时识别并转 UUID 类型，避免 PG `uuid = varchar` 类型冲突。
     */
    private static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        String s = String.valueOf(v).replace("'", "''");
        return "'" + s + "'";
    }

    /**
     * 拿物理表列名集合, 带进程级缓存。
     *
     * V112 修复: 之前用 computeIfAbsent 一次性写入. 但当视图在迁移期间临时被 DROP
     * (例如 V109 用 CASCADE 删 v_costing_element_price 期间, 下游视图也被瞬时删除)
     * 而恰好有请求过来 → 缓存了空 Set → 永久残留 → 之后所有请求查这个视图都判"列不存在"
     * → 不注入隐式 JOIN 谓词 → SQL 返全表多行 → UI "共N项" 错乱.
     *
     * 修法: 不缓存空集. 空时每次重查 information_schema (PG 这查询代价极小);
     *      非空才写缓存. 视图重建后下次请求自动恢复.
     */
    private Set<String> getColumns(String physicalTable) {
        Set<String> cached = tableColumnsCache.get(physicalTable);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        Set<String> cols = new HashSet<>();
        String sql = "SELECT column_name FROM information_schema.columns "
                   + "WHERE table_schema = 'public' AND table_name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, physicalTable);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
            }
        } catch (Exception e) {
            LOG.warnf("ImplicitJoinRewriter — column lookup failed for table=%s: %s",
                    physicalTable, e.getMessage());
        }
        // ★ 只有非空才写缓存; 空集表示"视图当前不可见", 不缓存让下次重查
        if (!cols.isEmpty()) {
            tableColumnsCache.put(physicalTable, cols);
        }
        return cols;
    }

    /** 测试 / DDL 后清缓存(列结构变化时)。 */
    public void invalidateColumnCache() {
        tableColumnsCache.clear();
    }
}
