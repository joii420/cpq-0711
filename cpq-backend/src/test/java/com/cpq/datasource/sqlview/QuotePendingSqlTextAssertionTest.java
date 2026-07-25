package com.cpq.datasource.sqlview;

import com.cpq.component.service.ComponentDriverService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0725 T4 · AC-17 门禁 7.1 / 7.2 / 7.3 —— 核对<b>实际发出的 SQL</b>（环境无关，0 行也能失败）。
 *
 * <p>需求说明.md §8-7 决策：原「4 个等价性测试全绿」门禁作废（锚单硬编码、不在当前库、全 skip）。
 * 新门禁改为直接核对 {@link SqlViewExecutor} 最终发给 JDBC 的 SQL 文本（经
 * {@link SqlDebugContext} 捕获），不依赖库里现成有什么业务数据。
 *
 * <p><b>为什么用自建夹具而不是发现现有的 mc_view/wg_view/jg_view 三个组件</b>：那三个组件是
 * 本次任务导入报价单时顺带产生的业务数据，不保证在其它环境（尤其是重建后的空白 baseline 库）
 * 里存在——用它们等于重犯"测试硬编码库内数据"的同一个坑（backtask T2 已指出的第 3/4/5 个案例）。
 * 本测试改为在 {@code @BeforeEach} 内用原生 SQL 直接插入一个<b>只属于本测试</b>的组件 +
 * {@code component_sql_view}（sql_template 引用真实白名单表 {@code unit_price}，不含
 * {@code UNION}/{@code GROUP BY}，保证锚点注入必然成功），与 {@code PriceBaseDateCacheIsolationTest}
 * （task-0722）已验证可行的自建夹具模式一致。
 *
 * <p><b>为什么在 {@code ComponentDriverService.expand()} 这一层断言，而不是直接调用
 * {@code CardSnapshotService.precomputeCostingDriverUnion}/{@code buildCostingCardValues}</b>：
 * 本环境唯一样本单 {@code costing_card_template_id} 为 NULL（见需求说明.md §8-7 验收范围限制），
 * 无法在集成层触发核价卡构建。但 {@code CardSnapshotService} 的全部核价侧方法（已由
 * {@link QuotePendingScopeOpenWhitelistTest} 结构性证明"不调用 open()"）与全部报价侧方法
 * （P1~P4）最终都收敛到同一个choke point——{@link ComponentDriverService#expand}：它只读
 * {@link QuotePendingScope#pendingOwner()}（一个 ThreadLocal 读操作），不关心调用方是谁。
 * 因此"scope 关闭态下 expand() 产出的 SQL"逐字等价于"任意核价侧调用点此刻会产出的 SQL"——
 * 这是一个忠实的代理（proxy），而不是绕过验证目标的捷径。
 */
@QuarkusTest
class QuotePendingSqlTextAssertionTest {

    private static final String TEST_COMPONENT_CODE = "TEST-AC17-SQLTEXT-VIEW";
    private static final String TEST_VIEW_NAME = "test_ac17_sqltext_view";

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    @Inject
    ComponentDriverService componentDriverService;

    private UUID componentId;

    @BeforeEach
    void seed() throws Exception {
        utx.begin();
        em.joinTransaction();

        em.createNativeQuery("DELETE FROM component_sql_view WHERE sql_view_name = :n")
                .setParameter("n", TEST_VIEW_NAME).executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE code = :c")
                .setParameter("c", TEST_COMPONENT_CODE).executeUpdate();

        componentId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO component (id, name, code, fields, formulas, status, component_type, " +
                "  data_driver_path, excel_columns, column_count, bom_recursive_expand, created_at, updated_at) " +
                "VALUES (:id, :name, :code, '[]'::jsonb, '[]'::jsonb, 'ACTIVE', 'NORMAL', " +
                "  :dp, '[]'::jsonb, 0, false, NOW(), NOW())")
                .setParameter("id", componentId)
                .setParameter("name", "AC17 SQL 文本断言测试夹具")
                .setParameter("code", TEST_COMPONENT_CODE)
                .setParameter("dp", "$" + TEST_VIEW_NAME)
                .executeUpdate();

        em.createNativeQuery(
                "INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, scope, status, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), :cid, :vn, :tpl, 'COMPONENT', 'ACTIVE', NOW(), NOW())")
                .setParameter("cid", componentId)
                .setParameter("vn", TEST_VIEW_NAME)
                .setParameter("tpl",
                        "-- task-0725 T4 AC17 fixture: 单一白名单表(unit_price), 无 UNION, 无 GROUP BY," +
                        " 锚点注入必然成功\n" +
                        "SELECT up.id, up.code AS _code, up.is_current AS _is_current\n" +
                        "FROM unit_price up\n" +
                        "WHERE up.system_type = 'QUOTE' AND up.customer_no = :customerCode")
                .executeUpdate();

        utx.commit();
    }

    @AfterEach
    void cleanupThreadLocals() {
        QuotePendingScope.restore(null);
        SqlViewRuntimeContext.clear();
        // task-0725 技术总监补充：夹具组件/视图原先只在 @BeforeEach 清，导致最后一次跑完会在
        // 共享库 cpq_db 里留下孤儿 component + component_sql_view（不挂任何模板故不影响渲染，
        // 但会污染组件列表、干扰后续人工核对）。改为跑完即清。
        try {
            utx.begin();
            em.joinTransaction();
            em.createNativeQuery("DELETE FROM component_sql_view WHERE sql_view_name = :n")
                .setParameter("n", TEST_VIEW_NAME).executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE code = :c")
                .setParameter("c", TEST_COMPONENT_CODE).executeUpdate();
            utx.commit();
        } catch (Exception ignore) {
            // 最佳努力清理
        }
        SqlDebugContext.drain(); // 兜底清空，防止某条测试异常提前退出留下 BUFFER 污染同线程下一个测试
    }

    // ═══════════════════════ 7.1 负向（核价侧代理）═══════════════════════

    @Test
    void scopeClosed_costingSideProxy_sqlContainsNoPendingMarkers() {
        // scope 关闭是核价侧的天然状态（核价侧任何入口从不调用 open()，见白名单测试）。
        QuotePendingScope.restore(null);

        SqlDebugContext.begin();
        componentDriverService.expand(componentId, null, null, null);
        List<String> capturedSql = SqlDebugContext.drain();

        // 7.3 前置非空断言：先证明真的抓到了 SQL，排除"因为什么都没跑所以通过"的空转缺陷
        // （SqlViewExecutorPendingHookTest 的断言全在 for(row:rows) 里，0 行时空转通过——本测试不重蹈）。
        assertFalse(capturedSql.isEmpty(),
                "未捕获到任何 SQL——说明 expand() 没有真正触发查询，下面的「不含 xxx」断言会因空转而恒真，" +
                        "必须先在此失败，而不是让后面的检查悄悄地什么都没验证就通过");

        for (String sql : capturedSql) {
            assertFalse(sql.contains("pending_quotation_id"),
                    "核价侧（scope 关闭）不应改写出 pending_quotation_id，实际 SQL：\n" + sql);
            assertFalse(sql.contains("AS __v6_id"),
                    "核价侧（scope 关闭）不应注入 __v6_id 锚点，实际 SQL：\n" + sql);
        }
    }

    // ═══════════════════════ 7.2 正向对照（报价侧）═══════════════════════

    @Test
    void scopeOpen_quoteSide_sqlContainsPendingMarkers() {
        // 没有这条正向对照，7.1 的负向断言就是"因为什么都没跑所以通过"——本条不可省（需求说明.md §8-7 明确要求）。
        UUID prev = QuotePendingScope.open(UUID.randomUUID(), "DRAFT");
        try {
            SqlDebugContext.begin();
            componentDriverService.expand(componentId, null, null, null);
            List<String> capturedSql = SqlDebugContext.drain();

            assertFalse(capturedSql.isEmpty(),
                    "未捕获到任何 SQL——scope 打开态下 expand() 也应该照常执行查询");

            boolean hasPendingPredicate = capturedSql.stream()
                    .anyMatch(sql -> sql.contains("(t.is_current OR t.pending_quotation_id = ?)"));
            boolean hasAnchor = capturedSql.stream()
                    .anyMatch(sql -> sql.contains("AS __v6_id"));

            assertTrue(hasPendingPredicate,
                    "报价侧（scope 打开）应改写出 \"(t.is_current OR t.pending_quotation_id = ?)\"，" +
                            "实际抓到的 SQL：\n" + String.join("\n--------\n", capturedSql));
            assertTrue(hasAnchor,
                    "报价侧（scope 打开）应注入 \"AS __v6_id\" 锚点，实际抓到的 SQL：\n"
                            + String.join("\n--------\n", capturedSql));
        } finally {
            QuotePendingScope.restore(prev);
        }
    }

    // ═══════════════ 附加：冻结态与 AC-10 交叉验证（open() 内建冻结判定）═══════════════

    @Test
    void scopeOpen_frozenStatus_stillNoPendingMarkers() {
        // 即便显式调用了 open()，冻结态（SUBMITTED/APPROVED/PUBLISHED）下 open() 内建判定已存 null，
        // 表现必须与"根本没调用 open()"（核价侧）逐字相同——这是 AC-10 与 AC-17 共享的同一份保障。
        UUID prev = QuotePendingScope.open(UUID.randomUUID(), "SUBMITTED");
        try {
            SqlDebugContext.begin();
            componentDriverService.expand(componentId, null, null, null);
            List<String> capturedSql = SqlDebugContext.drain();

            assertFalse(capturedSql.isEmpty(), "未捕获到任何 SQL");
            for (String sql : capturedSql) {
                assertFalse(sql.contains("pending_quotation_id"),
                        "冻结态（SUBMITTED）即便调用了 open() 也不应改写出 pending_quotation_id，实际 SQL：\n" + sql);
                assertFalse(sql.contains("AS __v6_id"),
                        "冻结态（SUBMITTED）即便调用了 open() 也不应注入 __v6_id 锚点，实际 SQL：\n" + sql);
            }
        } finally {
            QuotePendingScope.restore(prev);
        }
    }
}
