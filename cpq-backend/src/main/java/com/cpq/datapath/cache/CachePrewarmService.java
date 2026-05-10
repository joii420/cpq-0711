package com.cpq.datapath.cache;

import com.cpq.datapath.ast.PathExpression;
import com.cpq.datapath.sql.SchemaContext;
import com.cpq.datapath.sql.SqlAndParams;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板发布预热钩子（X.3 — 仅提供 API，不订阅事件）。
 *
 * <p>调用 {@link #prewarm(List)} 后，AST 缓存和 SQL 缓存均被填充，
 * 后续实际查询可直接命中缓存（零解析开销）。
 *
 * <p>事件订阅集成留 X.6（Template 服务集成阶段）完成。
 *
 * <p>预热策略：
 * <ol>
 *   <li>调用 {@link CachedPathParser#parse(String)} — 填充 AST 缓存</li>
 *   <li>调用 {@link CachedSqlCompiler#compile(PathExpression, SchemaContext)} — 填充 SQL 缓存</li>
 * </ol>
 * 若某条路径解析或编译失败（例如语法错误、schema 中无对应映射），记录警告并跳过，不中断其余路径的预热。
 */
@ApplicationScoped
public class CachePrewarmService {

    private static final Logger LOG = Logger.getLogger(CachePrewarmService.class);

    @Inject
    CachedPathParser cachedPathParser;

    @Inject
    CachedSqlCompiler cachedSqlCompiler;

    @Inject
    CachedSchemaContextProvider schemaContextProvider;

    /**
     * 预热指定路径列表的 AST + SQL 缓存。
     *
     * @param paths 路径字符串列表（与 {@link CachedPathParser#parse} 相同语义）
     * @return 预热结果摘要
     */
    public PrewarmResult prewarm(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return new PrewarmResult(0, 0, List.of());
        }

        SchemaContext ctx = schemaContextProvider.getDefaultContext();
        int succeeded = 0;
        List<String> failed = new ArrayList<>();

        for (String path : paths) {
            try {
                PathExpression ast = cachedPathParser.parse(path);
                try {
                    SqlAndParams sql = cachedSqlCompiler.compile(ast, ctx);
                    LOG.debugf("[prewarm] path='%s' → SQL='%s'", path, sql.sql());
                } catch (UnsupportedOperationException e) {
                    // X.6 待实现的多段嵌套路径 — 记录但不算失败（AST 已预热）
                    LOG.debugf("[prewarm] path='%s' — SQL compilation deferred (X.6): %s", path, e.getMessage());
                } catch (Exception e) {
                    LOG.warnf("[prewarm] path='%s' — SQL compilation failed: %s", path, e.getMessage());
                    failed.add(path + " [sql: " + e.getMessage() + "]");
                    continue;
                }
                succeeded++;
            } catch (Exception e) {
                LOG.warnf("[prewarm] path='%s' — parse failed: %s", path, e.getMessage());
                failed.add(path + " [parse: " + e.getMessage() + "]");
            }
        }

        LOG.infof("[prewarm] completed: %d/%d paths warmed, %d failed", succeeded, paths.size(), failed.size());
        return new PrewarmResult(paths.size(), succeeded, failed);
    }

    /**
     * 预热结果摘要。
     *
     * @param total     输入路径总数
     * @param succeeded 成功预热数（AST 至少成功）
     * @param failed    失败路径列表（含错误描述）
     */
    public record PrewarmResult(int total, int succeeded, List<String> failed) {}
}
