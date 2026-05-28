package com.cpq.datasource.sqlview;

import com.cpq.component.entity.ComponentSqlView;
import com.cpq.component.service.ComponentSqlViewService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BNF path $ / $$ 前缀重写器（方案 §5.1 / §5.2）。
 *
 * <p>把 {@code $sql_view_name[谓词].col} 重写成等价的物理 path，方法是：
 * 把 sheet 名（$name）替换成一段 inline subquery + 视图别名，最终被
 * {@code DataLoader.executeQuery} 当成"虚拟物理表"走原解析链路（不动 CachedPathParser /
 * CachedSqlCompiler / ImplicitJoinRewriter 底层逻辑）。
 *
 * <p>限制：本阶段不在 path 中拼参数化的占位符（防止破坏 PathExpression 解析）。
 * 用户 SQL 内的 :customerId / :partVersion 等命名占位符由 DataLoader 在执行时绑定。
 * 由于 CachedSqlCompiler 当前无 NamedParameter 通路，**本阶段对 SQL 中的 :xxx 占位符
 * 仅做原样保留**，由上层在调 loadByPath 之前自行替换或预绑定。
 *
 * <p>已实现：
 * <ul>
 *   <li>识别 path 开头 $name 与 $$code.name 命名空间</li>
 *   <li>查 ComponentSqlViewService.lookupForResolver 拿 SQL 模板</li>
 *   <li>把 sheet 部分替换成 {@code (SELECT ...) <alias>} 形态</li>
 *   <li>非 $ 前缀的物理 path 100% 原样返回（向后兼容）</li>
 * </ul>
 *
 * <p>未实现（标 TODO）：
 * <ul>
 *   <li>占位符运行时绑定（依赖 RequestContext，留待阶段 2）</li>
 *   <li>双层冻结快照查询（依赖 quotation/template snapshot，留待阶段 2）</li>
 * </ul>
 */
@ApplicationScoped
public class SqlViewPathRewriter {

    private static final Logger LOG = Logger.getLogger(SqlViewPathRewriter.class);

    /**
     * 匹配 path 开头的命名空间前缀：
     * <ul>
     *   <li>组 1：$$&lt;componentCode&gt;.&lt;sql_view_name&gt; （跨组件）</li>
     *   <li>组 2：$&lt;sql_view_name&gt; （本组件）</li>
     * </ul>
     */
    private static final Pattern PREFIX = Pattern.compile(
            "^(?:\\$\\$([A-Za-z][A-Za-z0-9_-]*)\\.([a-z_][a-z0-9_]*)|\\$([a-z_][a-z0-9_]*))"
    );

    @Inject
    ComponentSqlViewService sqlViewService;

    /**
     * 判断 path 是否需要 SQL 视图重写。
     */
    public boolean isSqlViewPath(String path) {
        if (path == null || path.isBlank()) return false;
        return path.startsWith("$");
    }

    /**
     * 把含 $ 前缀的 path 重写成等价的物理 path（inline subquery 形态）。
     *
     * @param path             原始 BNF path（如 {@code $element_view[bom_type='ELEMENT'].composition_pct}）
     * @param currentComponentId 当前组件 ID（本组件引用 $name 时必需）
     * @return 重写后的物理 path；若不含 $ 前缀，原样返回；若 SQL 视图未找到，抛 IllegalArgumentException
     */
    public String rewrite(String path, UUID currentComponentId) {
        if (path == null || path.isBlank()) return path;
        String trimmed = path.trim();
        if (!trimmed.startsWith("$")) return path;

        Matcher m = PREFIX.matcher(trimmed);
        if (!m.find()) {
            throw new IllegalArgumentException("非法的 SQL 视图引用语法：" + path);
        }

        boolean isCross;
        String componentCode;
        String viewName;
        if (m.group(1) != null) {
            isCross = true;
            componentCode = m.group(1);
            viewName = m.group(2);
        } else {
            isCross = false;
            componentCode = null;
            viewName = m.group(3);
        }

        Optional<ComponentSqlView> viewOpt = sqlViewService.lookupForResolver(
                currentComponentId, viewName, isCross, componentCode);
        if (viewOpt.isEmpty()) {
            String hint = isCross
                    ? "跨组件 GLOBAL SQL 视图未找到或非 GLOBAL scope: $$" + componentCode + "." + viewName
                    : "本组件 SQL 视图未找到: $" + viewName;
            throw new IllegalArgumentException(hint);
        }
        ComponentSqlView view = viewOpt.get();

        // 替换 sheet 部分：$xxx 或 $$code.xxx → (SELECT ...) <alias>
        String alias = "sqlview_" + view.sqlViewName;
        String inlineSubquery = "(" + view.sqlTemplate + ") " + alias;

        // path 后续部分（[谓词].col 等）原样保留
        String tail = trimmed.substring(m.end());
        String rewritten = inlineSubquery + tail;

        LOG.debugf("[SqlViewPathRewriter] rewrite path=%s → %s",
                trimmed, rewritten.replaceAll("\\s+", " "));
        return rewritten;
    }
}
