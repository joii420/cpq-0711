package com.cpq.quotation.service;

import com.cpq.datasource.sqlview.QuotePendingScope;
import com.cpq.datasource.sqlview.SqlViewRuntimeContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * task-0725 根因 2 —— {@link BomTreeRenderService} 核价树递归裸 JDBC 站点（backtask T1 站点 6
 * {@code TREE_PARAM}）的注释屏蔽自测。
 *
 * <p>用反射直接调用 private {@code queryRecursive(String, List, Map)}，贴近真实实现路径
 * （而不是复制一份等价的 mask+regex 逻辑到测试里自证自话）。
 *
 * <p>关键不变量（backtask T1 明确强调「别顺手优化」）：屏蔽/匹配必须作用在 {@code withPending}
 * （pending 改写<b>之后</b>），不是 {@code expanded}。本测试用一张白名单表（{@code unit_price}）
 * 触发 {@code QuotePendingRewriter} 真实生成 {@code :pq} token，验证：
 * <ol>
 *   <li>注释里的假 {@code :production_part_nos} / {@code :pq} 不参与绑定；</li>
 *   <li>正文里真实的 {@code :production_part_nos}（1 处）+ pending 改写生成的多处真实 {@code :pq}
 *       都能正确按出现顺序绑定，SQL 能成功执行（不抛 "column index out of range" 之类的错位异常）。</li>
 * </ol>
 * 若把匹配挪到 {@code expanded}（改写前）而非 {@code withPending}，pending 改写新生成的 {@code :pq}
 * 会因为定位阶段看不到而绑不上，本测试会失败（抛异常或结果与预期不符）。
 *
 * <p><b>task-0725 T2 更新</b>：本测试原用 {@code SqlViewRuntimeContext.set(...)} 驱动
 * {@code resolvePendingOwner()}（T1 落地时 T2 尚未接线，彼时 {@code resolvePendingOwner()} 直接读
 * {@code SqlViewRuntimeContext}）。T2 把 {@code resolvePendingOwner()} 改为读
 * {@link QuotePendingScope#pendingOwner()} 后，继续用 {@code SqlViewRuntimeContext.set} 驱动会让本测试
 * 的"DRAFT 态生成真实 {@code :pq} token"分支静默退化为空转（{@code resolvePendingOwner()} 恒返回
 * null，{@code assertDoesNotThrow} 仍会通过，但不再验证任何 pending 改写逻辑）——已改用
 * {@link QuotePendingScope#open}/{@link QuotePendingScope#restore} 驱动，恢复本测试的真实覆盖面。
 */
@QuarkusTest
class BomTreeRenderServiceTreeParamMaskingTest {

    @Inject
    BomTreeRenderService svc;

    @AfterEach
    void cleanup() {
        SqlViewRuntimeContext.clear();
        QuotePendingScope.restore(null);
    }

    /**
     * 递归 SQL 模板：注释里混入假 {@code :production_part_nos}/{@code :pq}；正文对白名单表
     * {@code unit_price} 做单表查询（真实触发 {@code QuotePendingRewriter} 表替换，生成真实
     * {@code :pq} token），并对 {@code :production_part_nos} 做一次真实数组过滤。
     */
    private static final String RECURSIVE_SQL_WITH_COMMENT_DECOYS =
        "-- 注释测试：:production_part_nos 和 :pq 这两个假 token 只应出现在这行注释里，不应被绑定\n" +
        "/* 块注释也测一遍：:production_part_nos / :pq */\n" +
        "SELECT\n" +
        "  up.finished_material_no AS root_no,\n" +
        "  up.finished_material_no AS material_no,\n" +
        "  NULL::text AS bom_version,\n" +
        "  NULL::text AS parent_no,\n" +
        "  up.finished_material_no AS node_path\n" +
        "FROM unit_price up\n" +
        "WHERE up.finished_material_no = ANY(:production_part_nos)\n";

    @Test
    void draftQuotation_pendingRewriteGeneratesRealPqTokens_commentDecoysExcluded_executesSuccessfully() throws Exception {
        // task-0725 T2：DRAFT + 非空 quotationId → QuotePendingScope.open() 打开作用域 →
        // resolvePendingOwner() 非 null → 触发 QuotePendingRewriter，真实在 unit_price 的替换子查询里
        // 生成若干 :pq token（1 处 is_current 列 + 2 处 WHERE 谓词）。
        UUID prev = QuotePendingScope.open(UUID.randomUUID(), "DRAFT");
        try {
            assertDoesNotThrow(() -> invokeQueryRecursive(
                    RECURSIVE_SQL_WITH_COMMENT_DECOYS, List.of("NONEXISTENT-PART-NO"), null),
                "注释里的假 :production_part_nos/:pq 若被误当占位符统计，会导致 order.size() 与 pgjdbc "
                + "实际占位符数错位，setArray/setObject 抛异常（复现 task-0725 根因2 的报错模式）");
        } finally {
            QuotePendingScope.restore(prev);
        }
    }

    @Test
    void noQuotationContext_noRewrite_commentDecoysStillExcluded_executesSuccessfully() throws Exception {
        // QuotePendingScope 未打开（未调 open）→ resolvePendingOwner() 返回 null → withPending ==
        // expanded，不会生成额外 :pq；但注释里的假 :production_part_nos 仍不应被误绑（本站点原本就该
        // 处理的场景）。

        assertDoesNotThrow(() -> invokeQueryRecursive(
                RECURSIVE_SQL_WITH_COMMENT_DECOYS, List.of("NONEXISTENT-PART-NO"), null),
            "无 pending 上下文时注释里的假 token 同样不应被误绑");
    }

    @SuppressWarnings("unchecked")
    private Object invokeQueryRecursive(String sqlTemplate, List<String> seed,
                                         java.util.Map<String, String> treeOverrides) throws Exception {
        Method m = BomTreeRenderService.class.getDeclaredMethod(
                "queryRecursive", String.class, List.class, java.util.Map.class);
        m.setAccessible(true);
        // @Inject 拿到的是 ArC 客户端代理（子类字节码代理），private 方法不属于代理的可覆写契约，
        // 反射直接 invoke(proxy, ...) 会落在代理自身未初始化的字段上（dataSource==null）。
        // 用 ClientProxy.unwrap 拿到真正被 CDI 容器管理、字段已注入的上下文实例。
        Object target = (svc instanceof io.quarkus.arc.ClientProxy) ? io.quarkus.arc.ClientProxy.unwrap(svc) : svc;
        return m.invoke(target, sqlTemplate, seed, treeOverrides);
    }
}
