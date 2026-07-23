package com.cpq.formula.dataloader;

import com.cpq.component.dto.RuntimeContext;
import com.cpq.datapath.CpqPathParser;
import com.cpq.datapath.ast.PathExpression;
import com.cpq.datapath.cache.CachedPathParser;
import com.cpq.datapath.cache.CachedSqlCompiler;
import com.cpq.datapath.sql.SchemaContext;
import com.cpq.datapath.sql.SqlAndParams;
import com.cpq.datasource.sqlview.SqlViewExecutor;
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

    /** 阶段 2: 组件级数据源 SQL 视图执行通路（path 以 $ 开头时旁路 CachedPathParser/SqlCompiler 走此通路） */
    @Inject
    SqlViewExecutor sqlViewExecutor;

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

        // 阶段 2: $ 前缀走 SqlViewExecutor 旁路（无上下文版本）
        if (sqlViewExecutor.isSqlViewPath(normalizedPath)) {
            return resultCache.computeIfAbsent(normalizedPath, p -> {
                try {
                    RuntimeContext emptyCtx = new RuntimeContext();
                    return CompletableFuture.completedFuture(
                            sqlViewExecutor.execute(p, emptyCtx, null));
                } catch (Exception e) {
                    LOG.warnf("DataLoader sql-view failed for path='%s': %s", p, e.getMessage());
                    CompletableFuture<List<Map<String, Object>>> failed = new CompletableFuture<>();
                    failed.completeExceptionally(e);
                    return failed;
                }
            });
        }

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
        return loadByPath(path, driverRow, (String) null, (UUID) null);
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
        // V6: 自动从 ThreadLocal 拾取 partVersion，深层求值代码无需修改签名即可注入版本谓词。
        // 上游（ExcelViewService.buildRowData / regenerateAllSnapshots）在调用前 set(),
        // finally clear()。partVersion=null 时行为完全等同旧 4-arg 语义。
        Integer ctxVersion = PartVersionContext.get();
        return loadByPath(path, driverRow, partNo, customerId, ctxVersion);
    }

    /**
     * 料号版本管理 B3: 增加 partVersion 参数的重载.
     *
     * <p>partVersion 来自报价单上下文 (quotation_line_item.part_version_locked):
     * <ul>
     *   <li>非空 → 注入 AND part_version=N 到 14 张版本化表 (V153)</li>
     *   <li>null → 行为与旧 loadByPath(path, driverRow, partNo, customerId) 完全等价</li>
     * </ul>
     *
     * <p>resultCache 用 normalized path 作 key, part_version 注入后进入 path 字面,
     * 因此不同 partVersion 自动有独立 cache 项, 无串混风险.
     */
    public CompletableFuture<List<Map<String, Object>>> loadByPath(String path,
                                                                    Map<String, Object> driverRow,
                                                                    String partNo,
                                                                    UUID customerId,
                                                                    Integer partVersion) {
        if (path == null || path.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        // 阶段 2: $ 前缀走 SqlViewExecutor 旁路 — 在 ImplicitJoinRewriter 之前拦截，
        // 用完整 RuntimeContext + partNo batch 执行
        String normalizedPath = normalizePath(path);
        if (sqlViewExecutor.isSqlViewPath(normalizedPath)) {
            // per-quote 视图（如 selopt_line_processes 按报价行过滤）需要 :lineItemId。
            // 从 driverRow hint 取 quotation_line_item_id 注入 ctx.lineItem.id，
            // 并并入 cache key（否则同 partNo/customer 不同报价行会命中同一缓存 → 串数据）。
            Object _lineIdObj = (driverRow != null) ? driverRow.get("quotation_line_item_id") : null;
            final UUID viewLineItemId = (_lineIdObj instanceof UUID u) ? u
                    : (_lineIdObj != null ? UUID.fromString(_lineIdObj.toString()) : null);
            // 同一 `$<view>` 视图名可被多个组件各自定义（如 COMP-0020 的导入副本 d18ac7e4=报价元素 /
            // b3359f70=核价元素 都有名为 ys_view 的视图，但列不同：报价侧无单价/material_type）。
            // SqlViewExecutor.executeAllRows/execute 都按 owner.componentId 经 lookupForResolver 解析到
            // 各自的 ComponentSqlView，因此 resultCache key 必须含 owner.componentId，否则同一请求内
            // 报价卡 + 核价卡对同一 (partNo,customerId,lineItem) 查 $ys_view 会串号：先跑的组件视图行
            // 喂给后跑的 → 核价元素拿到报价侧旧视图行(无单价列) → 单价回退中文标量路径报错、material_type
            // 跨行数组 → 核价 Excel [A]/[B] 算成 0（QT-1557 实证）。templateId 一并入 key（模板快照路径维度）。
            com.cpq.datasource.sqlview.SqlViewRuntimeContext.Snapshot _own =
                    com.cpq.datasource.sqlview.SqlViewRuntimeContext.get();
            final String _ownerTag = (_own != null ? _own.componentId : null) + "/"
                    + (_own != null ? _own.templateId : null);
            // task-0722 B3：quotationId 维度补齐——本方法虽是 @RequestScoped，但 ComponentResource.batchExpand
            // 单次 HTTP 请求内可对不同 task 各自 QuotationIdContext.set(t.quotationId)（同请求处理多张报价单的
            // task），resultCache 是该请求内共享的单个 Map；key 若不含 quotationId，同 (path,partNo,customerId,
            // lineItem,owner) 但不同 quotationId 的两次调用会互相复用缓存值——:priceBaseDate 等按 quotationId
            // 求值的占位符会串号（PriceBaseDateCacheIsolationTest 实测复现）。此处提前读取一次，同时用于 key 与
            // 下方 lambda 内的 ctx 绑定，语义不变、只补维度。
            final UUID _quotIdForKey = QuotationIdContext.get();
            return resultCache.computeIfAbsent(
                    normalizedPath + "::" + partNo + "::" + customerId + "::" + viewLineItemId
                            + "::" + _ownerTag + "::" + _quotIdForKey, key -> {
                try {
                    RuntimeContext ctx = new RuntimeContext();
                    // 统一协议:从 ThreadLocal 拿 quotationId,绑到 ctx.quotation.id
                    // → RuntimeContext.toNamedParams() 自动暴露 :quotationId 给所有 mirror 视图使用
                    UUID quotIdSv = _quotIdForKey;
                    if (customerId != null || quotIdSv != null) {
                        ctx.quotation = new RuntimeContext.QuotationContext(quotIdSv, customerId);
                    }
                    if (viewLineItemId != null) {
                        ctx.lineItem = new RuntimeContext.LineItemContext(partNo, null, viewLineItemId);
                    }
                    List<String> partNos = (partNo != null && !partNo.isBlank())
                            ? List.of(partNo) : null;
                    // 区分两种 $ 路径形态:
                    //   $view[pred]?            → driver path 返整行 (component data_driver_path 用)
                    //   $view[pred]?.column     → 字段 BNF 返单列 (component fields[].basic_data_path 用)
                    List<Map<String, Object>> rows;
                    if (sqlViewExecutor.isDriverViewPath(normalizedPath)) {
                        rows = sqlViewExecutor.executeAllRows(normalizedPath, ctx, partNos);
                    } else {
                        rows = sqlViewExecutor.execute(normalizedPath, ctx, partNos);
                    }
                    return CompletableFuture.completedFuture(stableSort(rows));
                } catch (Exception e) {
                    LOG.warnf("DataLoader sql-view-ctx failed for path='%s': %s", normalizedPath, e.getMessage());
                    CompletableFuture<List<Map<String, Object>>> failed = new CompletableFuture<>();
                    failed.completeExceptionally(e);
                    return failed;
                }
            });
        }

        boolean noDriver = (driverRow == null || driverRow.isEmpty());
        boolean noPartNo = (partNo == null || partNo.isBlank());
        boolean noCustomer = (customerId == null);
        boolean noVersion = (partVersion == null);
        if (noDriver && noPartNo && noCustomer && noVersion) {
            return loadByPath(path);
        }
        String rewritten = implicitJoinRewriter.rewriteWithContext(path, driverRow, partNo, customerId, partVersion,
                com.cpq.datapath.sql.SchemaContext.defaultContext());
        return loadByPath(rewritten);
    }

    /**
     * 多值入口 — 批量合桶专用:一次执行 SQL 视图,返回 :hfPartNos = ANY(partNos) 命中的所有行。
     *
     * <p>用途:`ComponentResource.batchExpand` 的"产品卡片维度合桶"使用。多个 task(同 componentId/
     * customerId/partVersion/driverPath/fields,且 driverPath 不含 :lineItemId)合成一次查询,
     * 由调用方按返回行的 {@code hf_part_no} 分发回各 task 结果。
     *
     * <p>调用方需保证:① partNos 互不重复(同 partNo 多张卡片不能合,分不开);② driverPath 不含
     * {@code :lineItemId}(否则视图按 lineItemId 过滤,合桶语义错乱);③ snapshot 命中的 task
     * 已经在合桶前直返,不进本方法。
     *
     * <p>本方法不进 {@code resultCache}(批量合桶单次性,跨请求复用价值低,避免 cache key 设计复杂化);
     * 不在 {@code ctx.lineItem.partNo} 上写单值(多值场景没有"当前 partNo"语义,视图只靠外层
     * {@code hf_part_no = ANY(:hfPartNos)} 过滤)。
     */
    public CompletableFuture<List<Map<String, Object>>> loadByPath(String path,
                                                                    Map<String, Object> driverRow,
                                                                    List<String> partNos,
                                                                    UUID customerId) {
        if (path == null || path.isBlank()) return CompletableFuture.completedFuture(List.of());
        String normalizedPath = normalizePath(path);
        if (!sqlViewExecutor.isSqlViewPath(normalizedPath)) {
            throw new IllegalArgumentException(
                    "DataLoader 多值入口仅支持 $view 路径(批量合桶);非视图路径请用单值入口。path=" + path);
        }
        Object lineIdObj = driverRow != null ? driverRow.get("quotation_line_item_id") : null;
        final UUID viewLineItemId = (lineIdObj instanceof UUID u) ? u
                : (lineIdObj != null ? UUID.fromString(lineIdObj.toString()) : null);
        try {
            RuntimeContext ctx = new RuntimeContext();
            if (customerId != null) {
                ctx.quotation = new RuntimeContext.QuotationContext(null, customerId);
            }
            if (viewLineItemId != null) {
                // 防御性:正常 bucket-merge 不会带 lineItemId(否则视图按 lineItemId 过滤合不了桶),
                // 此处保留以兼容调用方主动传 hint 的边缘情形。
                ctx.lineItem = new RuntimeContext.LineItemContext(null, null, viewLineItemId);
            }
            List<Map<String, Object>> rows = sqlViewExecutor.isDriverViewPath(normalizedPath)
                    ? sqlViewExecutor.executeAllRows(normalizedPath, ctx, partNos)
                    : sqlViewExecutor.execute(normalizedPath, ctx, partNos);
            return CompletableFuture.completedFuture(stableSort(rows));
        } catch (Exception e) {
            LOG.warnf("DataLoader multi-value sql-view failed path='%s' partNosSize=%d: %s",
                    normalizedPath, partNos == null ? 0 : partNos.size(), e.getMessage());
            CompletableFuture<List<Map<String, Object>>> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    /**
     * 根治视图无 ORDER BY 的行序非确定性(2026-06-23):对取回行按"列名升序拼 col=value"稳定键排序。
     * 保证 expand(单 partNo) 与 expandMulti(ANY 多 partNo) 对同一 partNo 子集返回 <b>逐位相同的行序</b>
     * (全集稳定排序 → 任一 partNo 子集仍稳定),使 batch-expand 合桶与逐 task 等价、C4 union 与逐行等价、
     * 渲染行序跨运行确定。仅改顺序不改行/值(AP-51 行数不变)。同内容行 key 相同(相对序无所谓)。
     */
    static List<Map<String, Object>> stableSort(List<Map<String, Object>> rows) {
        if (rows == null || rows.size() < 2) return rows;
        List<Map<String, Object>> out = new ArrayList<>(rows);
        out.sort(java.util.Comparator.comparing(DataLoader::stableKey));
        return out;
    }

    private static String stableKey(Map<String, Object> r) {
        if (r == null || r.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : new java.util.TreeMap<>(r).entrySet()) {
            sb.append(e.getKey()).append('=').append(String.valueOf(e.getValue())).append('');
        }
        return sb.toString();
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
