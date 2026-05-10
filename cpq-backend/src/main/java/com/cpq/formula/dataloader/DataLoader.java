package com.cpq.formula.dataloader;

import com.cpq.datapath.CpqPathParser;
import com.cpq.datapath.ast.PathExpression;
import com.cpq.datapath.cache.CachedPathParser;
import com.cpq.datapath.cache.CachedSqlCompiler;
import com.cpq.datapath.sql.SchemaContext;
import com.cpq.datapath.sql.SqlAndParams;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求级 DataLoader：对路径查询做 per-request dedupe。
 *
 * <p>v5.1 §3.6 DataLoader 模式实现（同步降级版本）：
 * <ul>
 *   <li>{@code @RequestScoped}：每个 HTTP 请求一个实例</li>
 *   <li>相同 path 的多次调用合并为一次 SQL（按 path 字符串 dedupe）</li>
 *   <li>接口签名保持 {@code CompletableFuture&lt;List&lt;Map&gt;&gt;}，便于未来升级为真正异步</li>
 *   <li>当前实现：同步执行（第一次 load 时执行 SQL，后续同 path 直接复用缓存）</li>
 * </ul>
 *
 * <p>降级理由：Quarkus RESTEasy Reactive 的 Worker Thread 模型下，
 * 真正异步 DataLoader 需要 MutinyUni/Multi 或 CompletionStage 与框架线程池整合，
 * 复杂度超出 X.6 范围。v1 同步 dedupe 已满足"同请求同 path 只查一次"的核心目标，
 * 未来可无缝升级为真正异步而不改接口。
 */
@RequestScoped
public class DataLoader {

    private static final Logger LOG = Logger.getLogger(DataLoader.class);

    /** UUID 字符串识别：8-4-4-4-12 hex 形态。命中即按 uuid 类型绑定到 PreparedStatement。 */
    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    /** 已完成的查询结果缓存（同请求 dedupe 核心）。 */
    private final Map<String, CompletableFuture<List<Map<String, Object>>>> resultCache =
            new ConcurrentHashMap<>();

    @Inject
    CachedPathParser pathParser;

    @Inject
    CachedSqlCompiler sqlCompiler;

    @Inject
    DataSource dataSource;

    @Inject
    ImplicitJoinRewriter implicitJoinRewriter;

    /**
     * 按路径查询，同请求内相同 path 只执行一次 SQL。
     *
     * @param path 路径字符串，格式：{tableName[conditions].fieldName} 或 tableName[conditions].fieldName
     * @return CompletableFuture，已完成（同步实现）
     */
    public CompletableFuture<List<Map<String, Object>>> loadByPath(String path) {
        if (path == null || path.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        // 规范化 path（剥去花括号）
        String normalizedPath = normalizePath(path);

        return resultCache.computeIfAbsent(normalizedPath, this::executeQuery);
    }

    /**
     * Y1.5: 按路径查询(带 driver 行隐式 JOIN)。
     *
     * <p>当 driverRow 非空时,会把 driver 行中"目标表也存在的列"作为 AND 谓词追加到字段路径,
     * 然后走与 {@link #loadByPath(String)} 相同的执行链路(每个重写后的路径仍享受 dedupe 缓存)。
     *
     * @param path      原始路径
     * @param driverRow 当前 driver 行 K-V(可空); 空 → 等价于 {@link #loadByPath(String)}
     */
    public CompletableFuture<List<Map<String, Object>>> loadByPath(String path,
                                                                    Map<String, Object> driverRow) {
        return loadByPath(path, driverRow, null, null);
    }

    /**
     * Y1.5 增强: 按路径查询(同时把 partNo / customerId 作为基础上下文隐式 JOIN)。
     *
     * <p>合并语义: effective driver = {hf_part_no: partNo, customer_id: customerId} ∪ driverRow,
     * 仅注入目标物理表存在的列(input/explicit driverRow 优先级更高)。
     *
     * <p>无任何上下文 → 等价于 {@link #loadByPath(String)}(纯查路径)。
     */
    public CompletableFuture<List<Map<String, Object>>> loadByPath(String path,
                                                                    Map<String, Object> driverRow,
                                                                    String partNo,
                                                                    UUID customerId) {
        if (path == null || path.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        boolean noDriver = (driverRow == null || driverRow.isEmpty());
        boolean noPartNo = (partNo == null || partNo.isBlank());
        boolean noCustomer = (customerId == null);
        if (noDriver && noPartNo && noCustomer) {
            return loadByPath(path);
        }
        String rewritten = implicitJoinRewriter.rewriteWithContext(path, driverRow, partNo, customerId,
                com.cpq.datapath.sql.SchemaContext.defaultContext());
        return loadByPath(rewritten);
    }

    /**
     * 执行单次路径 SQL 查询。
     * 使用 X.2 CachedPathParser + CachedSqlCompiler，SchemaContext.defaultContext()。
     */
    private CompletableFuture<List<Map<String, Object>>> executeQuery(String path) {
        try {
            PathExpression ast = pathParser.parse(path);
            SchemaContext schemaCtx = SchemaContext.defaultContext();
            SqlAndParams compiled = sqlCompiler.compile(ast, schemaCtx);

            List<Map<String, Object>> rows = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(compiled.sql())) {

                List<Object> params = compiled.params();
                for (int i = 0; i < params.size(); i++) {
                    Object v = params.get(i);
                    // PG 不接受 `uuid 列 = varchar 字面量` 比较；ImplicitJoinRewriter
                    // 注入 customer_id 等 UUID 列时只能写成字符串字面量，
                    // 这里识别 UUID 形态字符串并转 java.util.UUID，
                    // 让 PG JDBC 驱动绑成 uuid 类型，避免运算符错配。
                    if (v instanceof String s && UUID_PATTERN.matcher(s).matches()) {
                        try { v = java.util.UUID.fromString(s); } catch (IllegalArgumentException ignore) {}
                    }
                    ps.setObject(i + 1, v);
                }

                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>(colCount);
                        for (int c = 1; c <= colCount; c++) {
                            row.put(meta.getColumnName(c), rs.getObject(c));
                        }
                        rows.add(row);
                    }
                }
            }
            LOG.debugf("DataLoader executed path='%s', rows=%d", path, rows.size());
            return CompletableFuture.completedFuture(rows);

        } catch (Exception e) {
            LOG.warnf("DataLoader failed for path='%s': %s", path, e.getMessage());
            CompletableFuture<List<Map<String, Object>>> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    /**
     * 返回当前请求内已缓存的 path 数量（供调试/测试）。
     */
    public int cachedPathCount() {
        return resultCache.size();
    }

    /**
     * 清空本请求缓存（仅供测试使用）。
     */
    public void clearCache() {
        resultCache.clear();
    }

    @PreDestroy
    public void onDestroy() {
        resultCache.clear();
    }

    /** 规范化路径：剥去花括号（{...} → ...）。 */
    public static String normalizePath(String path) {
        String trimmed = path.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
